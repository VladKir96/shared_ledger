package dev.sharedledger.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class CloudAuthTest {
    @Test fun signupAndResendUseConfirmationPkceAndPublishVerifiedSession() = runBlocking {
        val fixture = Fixture()
        fixture.api.response = { request ->
            when {
                request.path.startsWith("/auth/v1/signup?") -> buildJsonObject { put("id", "new-user") }
                request.path.startsWith("/auth/v1/resend?") -> buildJsonObject {}
                request.path == PKCE_PATH -> {
                    assertEquals("confirmation-code", request.body?.string("auth_code"))
                    assertEquals(fixture.verifier("confirmation"), request.body?.string("code_verifier"))
                    authResponse()
                }
                request.path == PASSWORD_PATH -> authResponse()
                else -> error("Unexpected request")
            }
        }
        assertFalse(fixture.auth.signIn(ADDRESS, OLD_PASSWORD, "New user"))
        val first = fixture.verifier("confirmation")
        fixture.api.requests.last().assertEmailRequest("signup", EmailPurpose.CONFIRMATION, first)
        assertEquals("New user", (fixture.api.requests.last().body?.get("data") as JsonObject).string("display_name"))
        val cooldown = fixture.expectCloudError(429) { fixture.auth.resendConfirmation(ADDRESS) }
        assertTrue(cooldown.message.orEmpty().contains("wait"))
        assertEquals(1, fixture.api.requests.size)

        fixture.time += 60_000
        fixture.auth.resendConfirmation(ADDRESS)
        val latest = fixture.verifier("confirmation")
        assertNotEquals(first, latest)
        fixture.api.requests.last().assertEmailRequest("resend", EmailPurpose.CONFIRMATION, latest)
        assertEquals("signup", fixture.api.requests.last().body?.string("type"))

        val callback = link(EmailPurpose.CONFIRMATION, "confirmation-code")
        val recoveredAuth = fixture.newAuth()
        assertEquals(ConfirmationResult.SIGNED_IN, recoveredAuth.confirmEmail(callback))
        assertEquals("new-user", fixture.principal)
        assertEquals(ADDRESS, fixture.vault.identity?.email)
        val calls = fixture.api.requests.size
        fixture.expectCloudError(400) { recoveredAuth.confirmEmail(callback) }
        assertEquals(calls, fixture.api.requests.size)
        assertTrue(recoveredAuth.signIn(ADDRESS, OLD_PASSWORD, null))
        assertEquals(PASSWORD_PATH, fixture.api.requests.last().path)
    }

    @Test fun rejectedResendRetainsPendingVerifierAndHonorsServerRetryAfter() = runBlocking {
        val fixture = Fixture()
        fixture.auth.resendConfirmation(ADDRESS)
        val original = fixture.verifier("confirmation")
        fixture.time += 60_000
        fixture.api.response = { throw CloudError(429, "private payload", "over_email_send_rate_limit", 120) }
        val failure = fixture.expectCloudError(429) { fixture.auth.resendConfirmation(ADDRESS) }
        assertFalse(failure.message.orEmpty().contains("private payload"))
        assertEquals(original, fixture.verifier("confirmation"))
        fixture.time += 119_999
        fixture.expectCloudError(429) { fixture.auth.resendConfirmation(ADDRESS) }
        assertEquals(2, fixture.api.requests.size)
        fixture.time++
        fixture.api.response = { buildJsonObject {} }
        fixture.auth.resendConfirmation(ADDRESS)
        assertNotEquals(original, fixture.verifier("confirmation"))
    }

    @Test fun confirmationRequiresAConfirmedMatchingIdentityAndPreservesAnExistingOtherAccount() = runBlocking {
        val fixture = Fixture()
        val existing = Session("existing", "existing-access", "existing-refresh", 5000, "other@example.test")
        fixture.vault.identity = existing
        fixture.auth.restore()
        fixture.auth.resendConfirmation(ADDRESS)
        fixture.api.response = { request ->
            if (request.path == PKCE_PATH) authResponse(confirmed = false) else buildJsonObject {}
        }
        fixture.expectCloudError(403) { fixture.auth.confirmEmail(link(EmailPurpose.CONFIRMATION, "unconfirmed-code")) }
        assertEquals(existing, fixture.vault.identity)

        fixture.api.response = { request ->
            if (request.path == PKCE_PATH) authResponse(email = "wrong@example.test") else buildJsonObject {}
        }
        fixture.expectCloudError(403) { fixture.auth.confirmEmail(link(EmailPurpose.CONFIRMATION, "different-address-code")) }
        fixture.api.response = { request ->
            if (request.path == PKCE_PATH) authResponse() else buildJsonObject {}
        }
        assertEquals(ConfirmationResult.OTHER_ACCOUNT_ACTIVE,
            fixture.auth.confirmEmail(link(EmailPurpose.CONFIRMATION, "confirmation-code")))
        assertEquals(existing, fixture.auth.session.value)
        assertEquals(existing.userId, fixture.principal)
        assertEquals("new-access", fixture.api.requests.last().token)
        assertEquals("/auth/v1/logout?scope=local", fixture.api.requests.last().path)
    }

    @Test fun recoveryUsesItsOwnVerifierAndCanRetryPasswordUpdateThenSignIn() = runBlocking {
        val fixture = Fixture()
        fixture.vault.identity = Session("new-user", "old-access", "old-refresh", 5000, ADDRESS)
        fixture.auth.restore()
        fixture.auth.resendConfirmation(ADDRESS)
        fixture.auth.requestRecovery(ADDRESS)
        val verifier = fixture.verifier("recovery")
        assertNotEquals(fixture.verifier("confirmation"), verifier)
        fixture.api.requests.last().assertEmailRequest("recover", EmailPurpose.RECOVERY, verifier)
        val wrongType = link(EmailPurpose.CONFIRMATION, "not-recovery-code")
        val originalCalls = fixture.api.requests.size
        try {
            fixture.auth.authorizeRecovery(wrongType)
            fail("Accepted a confirmation callback for recovery")
        } catch (_: IllegalArgumentException) {
            assertEquals(originalCalls, fixture.api.requests.size)
        }

        var pass = OLD_PASSWORD
        fixture.api.response = { request ->
            when (request.path) {
                PKCE_PATH -> {
                    assertEquals(verifier, request.body?.string("code_verifier"))
                    authResponse()
                }
                "/auth/v1/user" -> {
                    assertEquals("new-access", request.token)
                    assertEquals("PUT", request.method)
                    val next = request.body?.string("password")
                    if (next == OLD_PASSWORD) throw CloudError(422, "private payload", "same_password")
                    assertEquals(NEW_PASSWORD, next)
                    pass = NEW_PASSWORD
                    buildJsonObject {}
                }
                "/auth/v1/logout?scope=local" -> buildJsonObject {}
                PASSWORD_PATH -> {
                    if (request.body?.string("password") != pass) throw CloudError(400, "bad password")
                    authResponse()
                }
                else -> error("Unexpected request")
            }
        }
        val callback = link(EmailPurpose.RECOVERY, "recovery-code")
        fixture.auth.authorizeRecovery(callback)
        assertEquals("old-access", fixture.vault.identity?.access)
        fixture.expectCloudError(422) { fixture.auth.finishRecovery(callback, OLD_PASSWORD) }
        fixture.newAuth().authorizeRecovery(callback)
        assertEquals(1, fixture.api.requests.count { it.path == PKCE_PATH })
        fixture.auth.finishRecovery(callback, NEW_PASSWORD)
        assertNull(fixture.vault.identity)
        assertNull(fixture.principal)
        assertNotNull(fixture.vault.readValue("confirmation"))
        fixture.expectCloudError(400) { fixture.auth.authorizeRecovery(callback) }
        fixture.expectCloudError(410) { fixture.auth.finishRecovery(callback, NEW_PASSWORD) }
        fixture.expectCloudError(400) { fixture.auth.signIn(ADDRESS, OLD_PASSWORD, null) }
        assertTrue(fixture.auth.signIn(ADDRESS, NEW_PASSWORD, null))
    }

    @Test fun expiredLinkAndMissingVerifierDoNotExposeServerPayloadOrAuthenticate() = runBlocking {
        val fixture = Fixture()
        val callback = link(EmailPurpose.RECOVERY, "expired-recovery-code")
        val missing = fixture.expectCloudError(400) { fixture.auth.authorizeRecovery(callback) }
        assertTrue(missing.message.orEmpty().contains("this device"))
        assertTrue(fixture.api.requests.isEmpty())
        fixture.auth.requestRecovery(ADDRESS)
        fixture.api.response = { throw CloudError(400, "private payload", "flow_state_expired") }
        val expired = fixture.expectCloudError(400) { fixture.auth.authorizeRecovery(callback) }
        assertTrue(expired.message.orEmpty().contains("expired"))
        assertFalse(expired.message.orEmpty().contains("private payload"))
        assertNull(fixture.vault.identity)
    }

    @Test fun existingRecoveryVerifierWorksAndResetOfAnotherAccountPreservesActiveIdentity() = runBlocking {
        val fixture = Fixture()
        val existing = Session("existing", "existing-access", "existing-refresh", 5000, "other@example.test")
        fixture.vault.identity = existing
        fixture.auth.restore()
        val oldVerifier = "v".repeat(43)
        fixture.vault.writeValue("recovery", oldVerifier)
        fixture.api.response = { request ->
            if (request.path == PKCE_PATH) {
                assertEquals(oldVerifier, request.body?.string("code_verifier"))
                authResponse()
            } else buildJsonObject {}
        }
        val callback = link(EmailPurpose.RECOVERY, "legacy-recovery-code")
        fixture.auth.authorizeRecovery(callback)
        fixture.auth.finishRecovery(callback, NEW_PASSWORD)
        assertEquals(existing, fixture.vault.identity)
        assertEquals(existing.userId, fixture.principal)
    }

    private class Fixture {
        val vault = MemoryVault()
        val api = FakeApi()
        var time = 1_000_000L
        var principal: String? = null
        val auth = newAuth()

        fun newAuth() = CloudAuth(api, vault, { principal = it }, { error("Unexpected purge") }, { time })

        fun verifier(flow: String) = Json.parseToJsonElement(checkNotNull(vault.readValue(flow)))
            .jsonObject.getValue("verifier").jsonPrimitive.content

        suspend fun expectCloudError(status: Int, action: suspend () -> Unit): CloudError {
            try {
                action()
            } catch (error: CloudError) {
                assertEquals(status, error.status)
                return error
            }
            error("Expected HTTP $status")
        }
    }

    private class MemoryVault : AuthVault {
        var identity: Session? = null
        private val values = mutableMapOf<String, String>()
        override fun read() = identity
        override fun write(session: Session) { identity = session }
        override fun clear() { identity = null }
        override fun readValue(name: String) = values[name]
        override fun writeValue(name: String, value: String) { values[name] = value }
        override fun clearValue(name: String) { values.remove(name) }
    }

    private data class Request(val path: String, val body: JsonObject?, val token: String?, val method: String?) {
        fun assertEmailRequest(endpoint: String, purpose: EmailPurpose, verifier: String) {
            assertEquals("/auth/v1/$endpoint?redirect_to=${purpose.redirect}", URLDecoder.decode(path, "UTF-8"))
            assertEquals(ADDRESS, body?.string("email"))
            assertEquals(sha256(verifier), body?.string("code_challenge"))
            assertEquals("s256", body?.string("code_challenge_method"))
        }
    }

    private class FakeApi : AuthTransport {
        val requests = mutableListOf<Request>()
        var response: (Request) -> JsonObject = { buildJsonObject {} }

        override suspend fun call(path: String, body: JsonObject?, token: String?, method: String?): JsonObject {
            val request = Request(path, body, token, method)
            requests += request
            return response(request)
        }
    }

    private companion object {
        const val ADDRESS = "new@example.test"
        const val OLD_PASSWORD = "old-password"
        const val NEW_PASSWORD = "new-password"
        const val PKCE_PATH = "/auth/v1/token?grant_type=pkce"
        const val PASSWORD_PATH = "/auth/v1/token?grant_type=password"

        fun link(purpose: EmailPurpose, code: String) = parseAuthLink("${purpose.redirect}?code=$code") as AuthLink.Code

        fun authResponse(email: String = ADDRESS, confirmed: Boolean = true) = buildJsonObject {
            put("access_token", "new-access")
            put("refresh_token", "new-refresh")
            put("expires_in", 3600)
            put("user", buildJsonObject {
                put("id", "new-user")
                put("email", email)
                if (confirmed) put("email_confirmed_at", "2026-01-01T00:00:00Z")
            })
        }
    }
}
