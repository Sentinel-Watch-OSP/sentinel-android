@file:Suppress("BlockingMethodInNonBlockingContext")

package com.samourai.sentinel.api

import com.samourai.sentinel.BuildConfig
import com.samourai.sentinel.api.okHttp.await
import com.samourai.sentinel.core.SentinelState
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.tor.EnumTorState
import com.samourai.sentinel.tor.SentinelTorManager
import com.samourai.sentinel.ui.dojo.DojoUtility
import com.samourai.sentinel.ui.utils.PrefsUtil
import com.samourai.sentinel.util.apiScope
import com.samourai.wallet.api.backend.beans.UnspentOutput
import com.samourai.wallet.api.backend.beans.WalletResponse
import com.samourai.wallet.util.XPUB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.util.Arrays
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


/**
 * sentinel-android
 *
 * @author Sarath
 */

open class ApiService {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java);
    private val dojoUtility: DojoUtility by inject(DojoUtility::class.java);
    private var ACCESS_TOKEN: String? = null
    private val ACCESS_TOKEN_REFRESH = 300L
    lateinit var client: OkHttpClient
    private val  JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val HARDENED = 2147483648


    init {
        try {
            buildClient()
        } catch (_: Exception) {
        } catch (e: ApiNotConfigured) {
            Timber.e(e)
        }
    }


    private fun buildClient(excludeApiKey: Boolean = false, excludeAuthenticator: Boolean = false) {
        client = buildClient(
            excludeApiKey,
            getAPIUrl(),
            this,
            prefsUtil.authorization,
            excludeAuthenticator
        )
    }


    fun authenticateDojo(): Job {
        return apiScope.launch {
            if (dojoUtility.getApiKey() != null) {
                try {
                    val response = authenticateDojo(dojoUtility.getApiKey()!!)
                    if (response.isSuccessful) {
                        val string = response.body?.string()
                        string?.let { dojoUtility.setAuthToken(it) }
                    }
                } catch (e: Exception) {
                    throw  Throwable(e.message)
                }
            }
        }
    }

    /**
     * Synchronously obtains a fresh Dojo access token. Returns true on success.
     *
     * Dojo access tokens are valid for only 15 minutes, so this is the routine
     * path for a long-running session, not an edge case.
     *
     * Tries `/auth/refresh` with the stored refresh token first, then falls back
     * to `/auth/login` with the API key. Both are attempted because the refresh
     * token has its own (longer) expiry, after which only the API key works.
     *
     * Deliberately blocking: [TokenAuthenticator] runs on an OkHttp thread and
     * must have a valid token before it can return the retried request.
     */
    suspend fun refreshDojoAuth(): Boolean = withContext(Dispatchers.IO) {
        // 1. Preferred: exchange the refresh token.
        val refreshToken = prefsUtil.refreshToken
        if (!refreshToken.isNullOrEmpty()) {
            try {
                val response = refreshDojoToken(refreshToken)
                val body = response.body?.string()
                if (response.isSuccessful && body != null && body.contains("authorizations")) {
                    dojoUtility.setAuthToken(body)
                    Timber.i("Dojo token refreshed via /auth/refresh")
                    return@withContext true
                }
                Timber.w("Dojo /auth/refresh rejected (code ${response.code}); trying API key")
            } catch (e: Exception) {
                Timber.w(e, "Dojo /auth/refresh failed; trying API key")
            }
        }

        // 2. Fallback: full login with the API key.
        val apiKey = dojoUtility.getApiKey()
        if (apiKey.isNullOrEmpty()) {
            Timber.e("Cannot re-authenticate with Dojo: no refresh token and no API key")
            return@withContext false
        }

        try {
            val response = authenticateDojo(apiKey)
            val body = response.body?.string()
            if (response.isSuccessful && body != null && body.contains("authorizations")) {
                dojoUtility.setAuthToken(body)
                Timber.i("Dojo token refreshed via /auth/login")
                return@withContext true
            }
            Timber.e("Dojo /auth/login rejected (code ${response.code})")
            return@withContext false
        } catch (e: Exception) {
            Timber.e(e, "Dojo /auth/login failed")
            return@withContext false
        }
    }

    /**
     * POST /auth/refresh - exchanges a refresh token for a new access token.
     *
     * excludeAuthenticator avoids recursing into [TokenAuthenticator] if this
     * call itself returns 401.
     */
    private suspend fun refreshDojoToken(refreshToken: String): Response {
        buildClient(excludeApiKey = true, excludeAuthenticator = true)
        val formBody = FormBody.Builder()
            .add("rt", refreshToken)
            .build()
        val request = Request.Builder()
            .post(formBody)
            .url("${getAPIUrl()}/auth/refresh")
            .build()
        return client.newCall(request).await()
    }

    suspend fun checkImportStatus(pubKey: String) = withContext(Dispatchers.IO) {
        buildClient(excludeAuthenticator = true)
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub/${pubKey}/import/status")
            .build()
        val response = client.newCall(request).await()
        val status = response.body?.string()
        if (!status.isNullOrEmpty()) {
            val json = JSONObject(status)
            if (json["status"] == "ok") {
                return@withContext true
            } else {
                try {
                    return@withContext json.getJSONObject("data").getBoolean("import_in_progress")
                } catch (e: Exception) {
                    return@withContext false
                }
            }
        } else {
            return@withContext false
        }
    }

    suspend fun importXpub(pubKey: String, segwit: String): Response {
        val xpub = XPUB(pubKey)
        xpub.decode()
        var segwitValue = segwit

        if (segwit == "44" || segwit == "bip44")
            segwitValue = ""

        if ((xpub.child + HARDENED).toString().equals("2147483646"))
            segwitValue = "bip84"

        buildClient(excludeAuthenticator = true)
        client.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)

        val formBody = FormBody.Builder()
            .add("xpub", pubKey)
            .add("segwit", segwitValue)
            .add("type", "restore")
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub")
            .post(formBody)
            .build()

        return client.newCall(request).await()
    }

    suspend fun fetchAddressForSweep(address: String): MutableList<UnspentOutput> {
        val response = getWallet(address)
        val items: WalletResponse = fromJSON<WalletResponse>(response.body!!.string())!!
        return Arrays.asList(*items.unspent_outputs)
    }

    suspend fun importAddress(pubKey: String): Response {

        buildClient(excludeAuthenticator = true)
        client.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)

        val formBody = FormBody.Builder()
            .add("xpub", pubKey)
            .add("type", "restore")
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/xpub")
            .post(formBody)
            .build()

        return client.newCall(request).await()
    }


    suspend fun getTxHex(utxoHash: String): Response {
        buildClient(excludeAuthenticator = true)
        client.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
        val request = Request.Builder()
            .get()
            .url("${getAPIUrl()}/tx/$utxoHash/HEX")
            .build()
        return client.newCall(request).await()
    }


    suspend fun authenticateDojo(apiKey: String): Response {
        buildClient()
        val formBody = FormBody.Builder()
            .add("apikey", apiKey)
            .build()
        val request = Request.Builder()
            .post(formBody)
            .url("${getAPIUrl()}/auth/login")
            .build()
        return client.newCall(request).await()
    }


    suspend fun getTx(txId: String): Response {
        buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/tx/${txId}?fees=1")
            .build()
        return client.newCall(request).await()
    }


    suspend fun getFees(): Response {
        buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/fees")
            .build()
        return client.newCall(request).await()
    }


    suspend fun getWallet(pubKey: String): Response {
        buildClient()
        val request = Request.Builder()
            .url("${getAPIUrl()}/wallet?active=${pubKey}")
            .build()
        return client.newCall(request).await()
    }


    suspend fun request(request: Request, excludeApiKey: Boolean = true): Response {
        buildClient(excludeApiKey=excludeApiKey)
        return client.newCall(request).await()
    }


    public fun getAPIUrl(): String? {
        return if (SentinelState.isTorRequired()) {
            if (prefsUtil.apiEndPointTor == null) {
                throw  ApiNotConfigured()
            }
            prefsUtil.apiEndPointTor
        } else {
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                return prefsUtil.apiEndPointTor
            }
            if (prefsUtil.apiEndPoint == null) {
                throw  ApiNotConfigured()
            }
            prefsUtil.apiEndPoint
        }
    }


    fun setAccessToken(accessToken: String?) {
        this.ACCESS_TOKEN = accessToken
    }

    suspend fun broadcast(hex: String): String {
        buildClient()
        val formBody: RequestBody = FormBody.Builder()
            .add("tx", hex.trim())
            .build()
        val request = Request.Builder()
            .url("${getAPIUrl()}/pushtx/")
            .post(formBody)
            .build()
        val response = client.newCall(request).await()
        val string = response.body?.string() ?: "{}"
        val json = JSONObject(string)
        return if (response.isSuccessful) {
            if (json.has("status") && json.getString("status").equals("ok")) {
                json.getString("data")
            } else {
                "TX_ID_NOT_FOUND"
            }
        } else {
            if (json.has("status") && json.getString("status").equals("error")) {
                throw  Exception(json.getJSONObject("error").getString("message"))
            } else {
                throw InvalidResponse()
            }
        }
    }

    class ApiNotConfigured : Throwable(message = "Api endpoint is not configured")
    class InvalidResponse : Throwable(message = "Invalid response")

    companion object {

        /**
         * The current Dojo access token, read fresh from prefs.
         *
         * [buildClient] is static and has no injected PrefsUtil, so the token is
         * resolved through Koin at call time. Returns null if prefs aren't
         * available yet, in which case the caller falls back to the token passed
         * into [buildClient].
         */
        private fun currentAuthToken(): String? = try {
            val prefs: PrefsUtil by inject(PrefsUtil::class.java)
            prefs.authorization
        } catch (e: Exception) {
            null
        }

        fun buildClient(
            excludeApiKey: Boolean = false, url: String?,
            apiService: ApiService?,
            authToken: String?,
            excludeAuthenticator: Boolean = false,
        ): OkHttpClient {
            val builder = OkHttpClient.Builder()
            if (BuildConfig.DEBUG) {
                builder.addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            }
            builder.callTimeout(60, TimeUnit.SECONDS)
            builder.readTimeout(90, TimeUnit.SECONDS)
            builder.connectTimeout(120, TimeUnit.SECONDS)
            if (url != null && apiService != null) {
                if (!excludeAuthenticator)
                    builder.authenticator(TokenAuthenticator(apiService))
            }
            if (SentinelTorManager.getTorState().state == EnumTorState.ON) {
                // Over Tor, building a circuit to an onion service can take several
                // minutes on a poor connection. A 90s callTimeout was aborting
                // healthy requests, which surfaced to the user as a sync failure.
                // callTimeout(0) disables the overall ceiling; the read/connect
                // timeouts below still catch a genuinely dead connection.
                builder.callTimeout(0, TimeUnit.MILLISECONDS)
                builder.readTimeout(180, TimeUnit.SECONDS)
                builder.writeTimeout(180, TimeUnit.SECONDS)
                builder.connectTimeout(180, TimeUnit.SECONDS)
                getHostNameVerifier(builder)
                builder.proxy(SentinelTorManager.getProxy())
            }

            /**
             * Intercept current request and add the Dojo access token if needed.
             * See https://code.samourai.io/dojo/samourai-dojo/-/blob/master/doc/POST_auth_login.md#authentication
             *
             * IMPORTANT: the token is resolved PER REQUEST via [currentAuthToken]
             * rather than captured from the [authToken] parameter.
             *
             * Dojo access tokens expire after 15 minutes. The previous version
             * baked the token into this closure when the client was built, so after
             * a refresh every request still carried the old expired token and all
             * balances silently stopped loading.
             */
            if (!excludeApiKey) {
                try {
                    builder.addInterceptor(Interceptor { chain ->
                        val original = chain.request()
                        // Re-read on every call so a refreshed token takes effect.
                        val token = currentAuthToken() ?: authToken
                        if (!token.isNullOrEmpty() && SentinelState.isDojoEnabled()) {
                            val url = original.url.newBuilder()
                                // Drop any stale token before adding the fresh one,
                                // otherwise a retried request carries both.
                                .removeAllQueryParameters("at")
                                .addQueryParameter("at", token)
                                .build()
                            chain.proceed(original.newBuilder().url(url).build())
                        } else {
                            chain.proceed(original)
                        }
                    })
                } catch (_:Exception) {}
            }
            return builder.build()
        }

        @Throws(Exception::class)
        protected fun getHostNameVerifier(clientBuilder: OkHttpClient.Builder) {

            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<java.security.cert.X509Certificate>,
                    authType: String
                ) {
                }

                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                    return arrayOf()
                }
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory


            clientBuilder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            clientBuilder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
    }


}
