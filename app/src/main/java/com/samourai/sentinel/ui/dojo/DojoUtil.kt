package com.samourai.sentinel.ui.dojo

import com.samourai.sentinel.api.ApiService
import com.samourai.sentinel.data.db.SentinelCollectionStore
import com.samourai.sentinel.data.db.PayloadRecord
import com.samourai.sentinel.helpers.fromJSON
import com.samourai.sentinel.helpers.toJSON
import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.*
import okhttp3.Response
import org.json.JSONObject
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * sentinel-android
 *
 **/

class DojoUtility {

    private var dojoPayload: DojoPairing? = null
    private var apiKey: String? = null
    private var isAuthenticated = false;
    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)
    private val dbHandler: SentinelCollectionStore by inject(SentinelCollectionStore::class.java)
    private val apiService: ApiService by inject(ApiService::class.java)

    private var dojoStore: PayloadRecord

    init {
        dojoStore = dbHandler.getDojoStore()
        read()
    }

    suspend fun setDojo(pairing: String): Response {
        val payload = fromJSON<DojoPairing>(pairing)
                ?: throw  Exception("Invalid payload")
        this.dojoPayload = payload
        // See setDojoPayload: apiKey must track dojoPayload.
        this.apiKey = payload.pairing?.apikey
        prefsUtil.apiEndPoint = this.dojoPayload!!.pairing?.url
        prefsUtil.apiEndPointTor = this.dojoPayload!!.pairing?.url
        writePayload(dojoPayload!!);
        return apiService.authenticateDojo(dojoPayload!!.pairing!!.apikey!!);
    }


    fun validate(payloadString: String): Boolean {
        try {
            val payload = fromJSON<DojoPairing>(payloadString)
                    ?: throw  Exception("Invalid payload")
            if (payload.pairing == null) {
                return false
            }
            if (payload.pairing.type != "dojo.api") {
                return false
            }
            if (payload.pairing.apikey == null) {
                return false
            }
            if (payload.pairing.url == null) {
                return false
            }
            return true
        } catch (ex: Exception) {
            return false
        }
    }

    fun isAuthenticated(): Boolean {
        return isAuthenticated;
    }

    fun isDojoEnabled(): Boolean {
        return dojoPayload != null
    }

    suspend fun writePayload(dojoPairing: DojoPairing) = withContext(Dispatchers.IO) {
        dbHandler.getDojoStore().write(dojoPairing, true)
    }

    private fun readPayload(): DojoPairing? {
        return if (dojoStore.file.exists()) {
            Timber.d("${dbHandler.getDojoStore().read<DojoPairing>()}")
            return dbHandler.getDojoStore().read<DojoPairing>()
        } else {
            null
        }
    }

    fun clearDojo() {
        CoroutineScope(Dispatchers.IO).launch {
            if (dojoStore.file.exists())
                dojoStore.file.delete()
        }
        dojoPayload = null
        // Clear the credentials too, so a later re-pair cannot accidentally
        // authenticate against the new Dojo with the old key.
        apiKey = null
        isAuthenticated = false
        prefsUtil.authorization = ""
        prefsUtil.refreshToken = ""
        prefsUtil.apiEndPointTor = null
        prefsUtil.apiEndPoint = null
    }

    /**
     * Stores the tokens from an /auth/login or /auth/refresh response.
     *
     * Only access_token is guaranteed to be present: /auth/login returns both
     * tokens, but /auth/refresh returns just a new access_token and does not
     * rotate the refresh token. getString() throws on a missing key, so reading
     * refresh_token unconditionally made every /auth/refresh response fail.
     * The existing refresh token is preserved when the response omits it.
     */
    fun setAuthToken(body: String) {
        val payload = JSONObject(body).getJSONObject("authorizations")
        prefsUtil.authorization = payload.getString("access_token")
        val refreshToken = payload.optString("refresh_token", "")
        if (refreshToken.isNotEmpty()) {
            prefsUtil.refreshToken = refreshToken
        }
        isAuthenticated = true
    }

    fun getApiKey(): String? {
        return apiKey;
    }

    /**
     * The pairing details for the currently connected Dojo, or null if none.
     *
     * Read-only accessor for display purposes.
     *
     * NOTE: [Pairing.apikey] grants access to the node - treat any UI that shows
     * it accordingly (see DojoCredentialsBottomSheet, which sets FLAG_SECURE).
     */
    fun getPairing(): Pairing? {
        return dojoPayload?.pairing
    }

    fun read() {
        CoroutineScope(Dispatchers.Default).launch {
            dojoPayload = readPayload()
            if (dojoPayload != null) {
                prefsUtil.apiEndPointTor = dojoPayload?.pairing?.url
                prefsUtil.apiEndPoint = dojoPayload?.pairing?.url
                apiKey = dojoPayload?.pairing?.apikey
                apiService.setAccessToken(prefsUtil.refreshToken)
            }
        }
    }

    fun store() {
        CoroutineScope(Dispatchers.Default).launch {
            dojoPayload?.let { writePayload(it) }
        }
    }

    fun exportDojoPayload(): String? {
        return dojoPayload?.toJSON()
    }

    fun setDojoPayload(payloadString: String) {
        val payload = fromJSON<DojoPairing>(payloadString)
            ?: throw  Exception("Invalid payload")
        this.dojoPayload = payload
        // Must be kept in sync with dojoPayload. apiKey is the only credential
        // that can re-authenticate once the refresh token has also expired; if
        // it is left null here, token refresh silently no-ops and balances stop
        // loading 15 minutes after pairing.
        this.apiKey = payload.pairing?.apikey
    }

    fun import(dojoPairing: JSONObject) {
        dojoPayload = fromJSON(dojoPairing.toString())
        // See setDojoPayload: apiKey must track dojoPayload.
        apiKey = dojoPayload?.pairing?.apikey
    }
}
