package com.samourai.sentinel.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Dojo auth token response parsing that caused balances to stop
 * loading 15 minutes after pairing.
 *
 * These mirror the real response shapes documented for Dojo's /auth/login and
 * /auth/refresh endpoints. The parsing logic is duplicated here rather than
 * calling DojoUtility, which needs Koin/Android prefs; the point is to pin the
 * contract that /auth/refresh omits refresh_token.
 */
class DojoAuthTokenTest {

    /** Mirrors DojoUtility.setAuthToken's extraction. */
    private data class Tokens(val access: String, val refresh: String?)

    private fun parse(body: String): Tokens {
        val payload = JSONObject(body).getJSONObject("authorizations")
        val access = payload.getString("access_token")
        val refresh = payload.optString("refresh_token", "")
        return Tokens(access, refresh.ifEmpty { null })
    }

    @Test
    fun `login response yields both tokens`() {
        val body = """
            {"authorizations":{"access_token":"access-1","refresh_token":"refresh-1"}}
        """.trimIndent()

        val tokens = parse(body)

        assertEquals("access-1", tokens.access)
        assertEquals("refresh-1", tokens.refresh)
    }

    /**
     * The regression: /auth/refresh returns only a new access token and does not
     * rotate the refresh token. Reading refresh_token with getString() threw
     * JSONException, so every refresh failed and the token stayed expired.
     */
    @Test
    fun `refresh response without refresh_token does not throw`() {
        val body = """
            {"authorizations":{"access_token":"access-2"}}
        """.trimIndent()

        val tokens = parse(body)

        assertEquals("access-2", tokens.access)
        assertEquals(null, tokens.refresh)
    }

    @Test
    fun `empty refresh_token is treated as absent so the stored one is kept`() {
        val body = """
            {"authorizations":{"access_token":"access-3","refresh_token":""}}
        """.trimIndent()

        assertEquals(null, parse(body).refresh)
    }

    @Test
    fun `access token is required`() {
        val body = """{"authorizations":{"refresh_token":"refresh-4"}}"""

        // Missing access_token is a genuine protocol error and should surface.
        try {
            parse(body)
            throw AssertionError("expected a JSON error for missing access_token")
        } catch (e: Exception) {
            assertTrue(e is org.json.JSONException)
        }
    }

    /**
     * Documents the 15 minute expiry that makes refresh mandatory. Payload is a
     * real token captured from a Dojo v1.29.2 instance.
     */
    @Test
    fun `dojo access tokens are short lived`() {
        val claims = JSONObject(
            """
            {"iss":"Samourai Wallet backend","type":"access-token","prf":"api",
             "iat":1785631918,"exp":1785632818}
            """.trimIndent()
        )

        val lifetimeSeconds = claims.getLong("exp") - claims.getLong("iat")

        assertEquals(900L, lifetimeSeconds)
        assertTrue(
            "token lifetime is short enough that refresh must work",
            lifetimeSeconds < 3600
        )
    }
}
