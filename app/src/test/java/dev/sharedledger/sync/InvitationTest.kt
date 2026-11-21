package dev.sharedledger.sync

import dev.sharedledger.domain.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InvitationTest {
    @Test fun extractsTheTokenFromOldShareMessagesAndFormattedInput() {
        val shared = "Join my SharedLedger budget\nsharedledger://invite/$TOKEN\nInvitation code: $TOKEN"
        assertNotEquals(TOKEN, shared.trim().substringAfterLast('/').lowercase())
        assertEquals(TOKEN, normalizeInvitation(shared))
        assertEquals(TOKEN, normalizeInvitation(" sharedledger://invite/${TOKEN.uppercase()}\r\n"))
        assertEquals(TOKEN, normalizeInvitation(TOKEN.uppercase().chunked(8).joinToString(" -\n")))
        assertEquals(TOKEN, normalizeInvitation("Open (sharedledger://invite/$TOKEN)."))
        assertNull(normalizeInvitation("sharedledger://invite/$TOKEN\nInvitation code: ${"f".repeat(64)}"))
        assertNull(normalizeInvitation("https://example.test/$TOKEN"))
        assertNull(normalizeInvitation("sharedledger://invite/$TOKEN?unknown=true"))
        assertNull(normalizeInvitation("sharedledger://invite/unexpected/$TOKEN"))
        assertNull(normalizeInvitation("Xg".repeat(32)))
    }

    @Test fun signedOutAndUnconfirmedUsersGetSeparateErrorsWithoutLeakingInput() = runBlocking {
        val fixture = Fixture()
        fixture.vault.identity = null
        fixture.expect(InvitationError.AUTH_REQUIRED) { fixture.invites.redeem("not a token") }
        assertTrue(fixture.requests.isEmpty())
        fixture.vault.identity = session()
        fixture.reply = { buildJsonObject { put("code", "EMAIL_NOT_CONFIRMED") } }
        fixture.expect(InvitationError.EMAIL_NOT_CONFIRMED) { fixture.invites.redeem(TOKEN) }
        assertEquals("access-old", fixture.requests.single().access)
        assertTrue(fixture.logs.none { it.contains(TOKEN) || it.contains("access-old") })
    }

    @Test fun expiredSessionIsRefreshedBeforeRedeemingAndRejectedRefreshRequiresSignIn() = runBlocking {
        val fixture = Fixture()
        fixture.vault.identity = session(expires = 100)
        fixture.reply = { reply("JOINED") }
        assertEquals(BUDGET, fixture.invites.redeem(TOKEN).budgetId)
        assertEquals(1, fixture.refreshes)
        assertEquals("access-fresh", fixture.requests.single().access)

        val expired = Fixture()
        expired.vault.identity = session(expires = 100)
        expired.refreshResponse = { throw CloudError(400, "private data", "refresh_token_not_found") }
        expired.expect(InvitationError.AUTH_REQUIRED) { expired.invites.redeem(TOKEN) }
        assertTrue(expired.requests.isEmpty())
        assertNull(expired.vault.identity)
    }

    @Test fun invalidAccessTokenIsRefreshedAndRetryRecognizesExistingMembership() = runBlocking {
        val fixture = Fixture()
        fixture.reply = { request ->
            if (request.access == "access-old") throw CloudError(401, "private data", "PGRST301")
            reply("ALREADY_MEMBER")
        }
        assertTrue(fixture.invites.redeem(TOKEN).alreadyMember)
        assertEquals(listOf("access-old", "access-fresh"), fixture.requests.map { it.access })
        assertEquals(listOf(TOKEN, TOKEN), fixture.requests.map { it.body.string("p_token") })
    }

    @Test fun creationNeedsServerReceiptForTheRequestedBudget() = runBlocking {
        val fixture = Fixture()
        fixture.expect(InvitationError.BUDGET_NOT_SYNCED) {
            fixture.invites.create(BUDGET, Role.EDITOR) { throw InvitationFailure(InvitationError.BUDGET_NOT_SYNCED) }
        }
        assertTrue(fixture.requests.isEmpty())
        fixture.reply = { buildJsonObject { put("code", "PERMISSION_DENIED") } }
        fixture.expect(InvitationError.PERMISSION_DENIED) { fixture.invites.create(BUDGET, Role.EDITOR) {} }
        fixture.reply = { buildJsonObject { put("code", "CREATED"); put("token", TOKEN) } }
        fixture.expect(InvitationError.SERVER_ERROR) { fixture.invites.create(BUDGET, Role.EDITOR) {} }
        fixture.reply = { buildJsonObject { put("code", "CREATED"); put("token", TOKEN); put("id", OTHER); put("budgetId", OTHER) } }
        fixture.expect(InvitationError.SERVER_ERROR) { fixture.invites.create(BUDGET, Role.EDITOR) {} }
        fixture.reply = { buildJsonObject { put("code", "CREATED"); put("token", TOKEN); put("id", OTHER); put("budgetId", BUDGET) } }
        assertEquals(TOKEN, fixture.invites.create(BUDGET, Role.EDITOR) {})
        assertEquals(BUDGET, fixture.requests.last().body.string("p_budget"))
        assertEquals("EDITOR", fixture.requests.last().body.string("p_role"))
    }

    @Test fun structuredErrorsAndTransportFailuresNeverBecomeInvalidCode() = runBlocking {
        val fixture = Fixture()
        val outcomes = listOf(InvitationError.INVITATION_EXPIRED, InvitationError.INVITATION_ALREADY_USED,
            InvitationError.INVITATION_REVOKED, InvitationError.INVITATION_NOT_FOUND, InvitationError.RATE_LIMITED)
        for (outcome in outcomes) {
            fixture.reply = { buildJsonObject { put("code", outcome.name); put("error", TOKEN) } }
            fixture.expect(outcome) { fixture.invites.redeem(TOKEN) }
        }
        val transport = mapOf(0 to InvitationError.NETWORK_ERROR, 403 to InvitationError.PERMISSION_DENIED,
            429 to InvitationError.RATE_LIMITED, 500 to InvitationError.SERVER_ERROR)
        for ((status, outcome) in transport) {
            fixture.reply = { throw CloudError(status, TOKEN, "42501") }
            fixture.expect(outcome) { fixture.invites.redeem(TOKEN) }
        }
        assertTrue(fixture.logs.any { "rpc_code=42501" in it })
        assertTrue(fixture.logs.none { TOKEN in it })
    }

    private class Fixture {
        val vault = MemoryVault()
        val logs = mutableListOf<String>()
        val requests = mutableListOf<Rpc>()
        var refreshes = 0
        var refreshResponse: () -> JsonObject = {
            buildJsonObject {
                put("access_token", "access-fresh")
                put("refresh_token", "refresh-fresh")
                put("expires_at", 5000)
                put("user", buildJsonObject { put("id", "editor"); put("email", "editor@example.test") })
            }
        }
        private val auth = CloudAuth(object : AuthTransport {
            override suspend fun call(path: String, body: JsonObject?, token: String?, method: String?): JsonElement {
                assertEquals("/auth/v1/token?grant_type=refresh_token", path)
                refreshes++
                return refreshResponse()
            }
        }, vault, {}, {}, { 100_000L })
        var reply: (Rpc) -> JsonObject = { error("Unexpected RPC") }
        val invites = InvitationService(auth, { name, access, body ->
            val request = Rpc(name, access, body)
            requests += request
            reply(request)
        }, logs::add)

        suspend fun expect(expected: InvitationError, operation: suspend () -> Unit) {
            try {
                operation()
                fail("Expected ${expected.name}")
            } catch (e: InvitationFailure) {
                assertEquals(expected, e.error)
                assertFalse(e.message.orEmpty().contains(TOKEN))
            }
        }
    }

    private data class Rpc(val name: String, val access: String, val body: JsonObject)

    private class MemoryVault : AuthVault {
        var identity: Session? = session()
        override fun read() = identity
        override fun write(session: Session) { identity = session }
        override fun clear() { identity = null }
        override fun readValue(name: String): String? = null
        override fun writeValue(name: String, value: String) { error("Unexpected write") }
        override fun clearValue(name: String) { error("Unexpected clear") }
    }

    private companion object {
        val TOKEN = "abcdef0123456789".repeat(4)
        const val BUDGET = "10000000-0000-0000-0000-000000000001"
        const val OTHER = "10000000-0000-0000-0000-000000000002"

        fun session(expires: Long = 5000) = Session("editor", "access-old", "refresh-old", expires, "editor@example.test")
        fun reply(outcome: String) = buildJsonObject { put("code", outcome); put("budgetId", BUDGET) }
    }
}
