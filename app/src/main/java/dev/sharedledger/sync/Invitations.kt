package dev.sharedledger.sync

import dev.sharedledger.domain.Role
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

private const val INVITATION_LINK = "sharedledger://invite/"
private val hexCode = Regex("[0-9a-fA-F]{64}")
private val embeddedLink = Regex("""(?i)(?<![\w:/=])sharedledger://invite/([^\s<>"]+)""")
private val labeledCode = Regex("""(?im)^[\t ]*Invitation code:[\t ]*([^\r\n]+)$""")

internal fun normalizeInvitation(input: String): String? {
    if (input.length > 4096) return null
    val text = input.trim()
    canonicalCode(text)?.let { return it }
    if (text.startsWith(INVITATION_LINK, ignoreCase = true)) {
        canonicalCode(text.substring(INVITATION_LINK.length))?.let { return it }
    }

    // Accept the full text sent by older Share sheets. Reject conflicting codes.
    val links = embeddedLink.findAll(text).map { it.groupValues[1].trimEnd('.', ',', ';', '!', ')', ']', '}', '\'') }
    val codes = labeledCode.findAll(text).map { it.groupValues[1] }
    val candidates = (links + codes).map(::canonicalCode).toList()
    return if (null in candidates) null else candidates.distinct().singleOrNull()
}

private fun canonicalCode(value: String): String? =
    value.filterNot { it.isWhitespace() || it == '-' }.takeIf(hexCode::matches)?.lowercase()

internal enum class InvitationError(val message: String) {
    INVITATION_NOT_FOUND("Invalid invitation code."),
    INVITATION_EXPIRED("This invitation has expired."),
    INVITATION_ALREADY_USED("This invitation has already been used."),
    INVITATION_REVOKED("This invitation is no longer active."),
    AUTH_REQUIRED("Please sign in before accepting an invitation."),
    EMAIL_NOT_CONFIRMED("Please confirm your email address."),
    PERMISSION_DENIED("You do not have permission to perform this action."),
    NETWORK_ERROR("No internet connection. Please try again."),
    SERVER_ERROR("Unable to verify the invitation right now."),
    RATE_LIMITED("Too many attempts. Please try again later."),
    BUDGET_NOT_SYNCED("Sync the shared budget before generating an invitation.");
}

internal class InvitationFailure(val error: InvitationError) : IllegalArgumentException(error.message)

internal data class RedeemedInvitation(val budgetId: String, val userId: String, val alreadyMember: Boolean)

data class JoinedBudget(val alreadyMember: Boolean, val downloaded: Boolean)

internal class InvitationService(
    private val auth: CloudAuth,
    private val rpc: suspend (String, String, JsonObject) -> JsonElement,
    private val log: (String) -> Unit
) {
    suspend fun create(budgetId: String, role: Role, prepare: suspend (Session) -> Unit): String =
        operation("ledger_invite") { request ->
            val session = request.session()
            prepare(session)
            val result = request.call(session, buildJsonObject { put("p_budget", budgetId); put("p_role", role.name) })
            val token = result.string("token")?.takeIf(hexCode::matches)
            val invitationId = result.string("id")?.takeIf(::isUuid)
            val returnedBudget = result.string("budgetId")
            val code = result.string("code")
            if (token == null || invitationId == null || (code != null && (code != "CREATED" || returnedBudget != budgetId))) {
                throw InvitationFailure(InvitationError.SERVER_ERROR)
            }
            log("rpc=ledger_invite outcome=CREATED")
            token
        }

    suspend fun redeem(input: String): RedeemedInvitation = operation("ledger_redeem") { request ->
        val session = request.session()
        val token = normalizeInvitation(input) ?: throw InvitationFailure(InvitationError.INVITATION_NOT_FOUND)
        val result = request.call(session, buildJsonObject { put("p_token", token) })
        val budget = result.string("budgetId")?.takeIf(::isUuid) ?: throw InvitationFailure(InvitationError.SERVER_ERROR)
        val code = result.string("code")
        if (code != null && code != "JOINED" && code != "ALREADY_MEMBER") throw InvitationFailure(InvitationError.SERVER_ERROR)
        log("rpc=ledger_redeem outcome=${if (code == "ALREADY_MEMBER") "ALREADY_MEMBER" else "JOINED"}")
        RedeemedInvitation(budget, session.userId, code == "ALREADY_MEMBER")
    }

    private inner class Request(private val name: String) {
        suspend fun session(expectedUser: String? = null, refresh: Boolean = false): Session {
            val active = try {
                auth.current(forceRefresh = refresh, expectedUserId = expectedUser)
            } catch (e: CloudError) {
                if (e.status in listOf(400, 401, 403) && e.code != "email_not_confirmed") {
                    throw InvitationFailure(InvitationError.AUTH_REQUIRED)
                }
                throw e
            }
            return (active ?: throw InvitationFailure(InvitationError.AUTH_REQUIRED)).also {
                if (expectedUser == null) log("rpc=$name auth=signed_in")
            }
        }

        suspend fun call(session: Session, body: JsonObject): JsonObject {
            // Preparation may have triggered a token refresh while uploading the budget.
            val active = session(expectedUser = session.userId)
            val reply = try {
                rpc(name, active.access, body)
            } catch (e: CloudError) {
                if (e.status != 401) throw e
                log("rpc=$name auth=refresh_after_401")
                val fresh = session(expectedUser = session.userId, refresh = true)
                rpc(name, fresh.access, body)
            }
            val result = reply as? JsonObject ?: throw InvitationFailure(InvitationError.SERVER_ERROR)
            val code = result.string("code")
            val failure = InvitationError.entries.find { it.name == code }
            if (failure != null) throw InvitationFailure(failure)
            if ("error" in result) throw InvitationFailure(InvitationError.SERVER_ERROR)
            return result
        }
    }

    private suspend fun <T> operation(name: String, perform: suspend (Request) -> T): T {
        log("rpc=$name outcome=attempt")
        try {
            return perform(Request(name))
        } catch (e: InvitationFailure) {
            log("rpc=$name outcome=${e.error.name}")
            throw e
        } catch (e: CloudError) {
            val error = when {
                e.code == "email_not_confirmed" -> InvitationError.EMAIL_NOT_CONFIRMED
                e.status == 0 -> InvitationError.NETWORK_ERROR
                e.status == 401 -> InvitationError.AUTH_REQUIRED
                e.status == 403 -> InvitationError.PERMISSION_DENIED
                e.status == 429 -> InvitationError.RATE_LIMITED
                else -> InvitationError.SERVER_ERROR
            }
            val diagnostic = e.code?.takeIf { it.matches(Regex("PGRST[A-Z0-9]{3}|[A-Z0-9]{5}")) } ?: "none"
            log("rpc=$name outcome=${error.name} http=${e.status} rpc_code=$diagnostic")
            throw InvitationFailure(error)
        }
    }
}

private fun isUuid(value: String): Boolean =
    value.length == 36 && runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)
