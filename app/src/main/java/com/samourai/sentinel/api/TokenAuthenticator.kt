package com.samourai.sentinel.api

import com.samourai.sentinel.ui.utils.PrefsUtil
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

/**
 * Re-authenticates with the Dojo when a request comes back 401.
 *
 * Dojo access tokens expire after 15 minutes, so this runs routinely, not just
 * in error cases.
 *
 * The previous implementation was broken in three ways, which together meant
 * balances stopped loading permanently 15 minutes after pairing:
 *
 *  1. It returned null, which tells OkHttp "authentication is impossible" and
 *     fails the call. The original request was never retried even when the
 *     refresh succeeded.
 *  2. It called `authenticateDojo()` (which returns a Job on another scope)
 *     without awaiting it, then slept a fixed 100ms. Over Tor a login round trip
 *     takes seconds, so the refresh was still in flight.
 *  3. Even on retry, the token was injected from a value captured when the
 *     OkHttp client was built, so the stale token was resent.
 */
class TokenAuthenticator(private val apiService: ApiService) : Authenticator {

    private val prefsUtil: PrefsUtil by inject(PrefsUtil::class.java)

    companion object {
        /** Max consecutive re-auth attempts for one logical request. */
        private const val MAX_RETRIES = 2
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Give up rather than loop forever if the server keeps rejecting us.
        if (responseCount(response) >= MAX_RETRIES) {
            Timber.w("Dojo re-auth failed after $MAX_RETRIES attempts; giving up")
            return null
        }

        val refreshed = runBlocking {
            try {
                // Blocking and awaited, unlike the old fire-and-forget Job.
                apiService.refreshDojoAuth()
            } catch (e: Exception) {
                Timber.e(e, "Dojo re-authentication failed")
                false
            }
        }

        if (!refreshed) {
            // Returning null fails the call, which is correct here: we genuinely
            // could not obtain a token.
            return null
        }

        val token = prefsUtil.authorization
        if (token.isNullOrEmpty()) {
            Timber.e("Dojo re-auth reported success but no token was stored")
            return null
        }

        // Rebuild the request with the NEW token so OkHttp retries it.
        val url = response.request.url.newBuilder()
            .removeAllQueryParameters("at")
            .addQueryParameter("at", token)
            .build()

        return response.request.newBuilder()
            .url(url)
            .build()
    }

    /** Number of times this request has already been retried. */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
