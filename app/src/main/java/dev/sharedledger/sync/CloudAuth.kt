package dev.sharedledger.sync

import dev.sharedledger.data.LedgerRepository
import dev.sharedledger.data.ledgerJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val EMAIL_COOLDOWN_MS = 60_000L
private const val RECOVERY_WINDOW_SECONDS = 600L
private const val RECOVERY_GRANT = "recovery_grant"

@Serializable
private data class PendingEmail(val email: String?, val verifier: String)

@Serializable
private data class EmailCooldown(val email: String, val retryAt: Long)

@Serializable
private data class RecoveryGrant(val fingerprint: String, val userId: String, val access: String, val expiresAt: Long)

enum class ConfirmationResult { SIGNED_IN, OTHER_ACCOUNT_ACTIVE }

@Singleton
class CloudAuth internal constructor(
    private val api: AuthTransport,
    private val vault: AuthVault,
    private val setPrincipal: (String?) -> Unit,
    private val purgePrincipal: suspend (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject constructor(api: CloudApi, vault: SessionVault, repository: LedgerRepository) :
        this(api, vault, { repository.principal.value = it }, repository::purgePrincipal)

    private val mutex = Mutex()
    val session = MutableStateFlow<Session?>(null)

    suspend fun restore() = mutex.withLock { if (session.value == null) publish(vault.read()) }

    suspend fun signIn(email: String, password: String, name: String?) = mutex.withLock {
        val address = emailAddress(email)
        requirePassword(password)
        val result = if (name == null) {
            authRequest("/auth/v1/token?grant_type=password", "Unable to sign in. Check your email and password.", buildJsonObject {
                put("email", address)
                put("password", password)
            })
        } else {
            require(name.isNotBlank()) { "Enter a display name." }
            sendEmail(EmailPurpose.CONFIRMATION, address, "signup") {
                put("password", password)
                put("data", buildJsonObject { put("display_name", name.trim().take(80)) })
            }
        }
        val authenticated = sessionFrom(result)
        if (authenticated != null) {
            if (name != null) vault.clearValue(EmailPurpose.CONFIRMATION.storageKey)
            publish(authenticated)
        } else if (name == null || result.isEmpty()) throw invalidResponse("Unable to sign in. Please try again.")
        authenticated != null
    }

    suspend fun resendConfirmation(email: String) = mutex.withLock {
        sendEmail(EmailPurpose.CONFIRMATION, emailAddress(email), "resend") { put("type", "signup") }
        Unit
    }

    suspend fun requestRecovery(email: String) = mutex.withLock {
        sendEmail(EmailPurpose.RECOVERY, emailAddress(email), "recover")
        vault.clearValue(RECOVERY_GRANT)
    }

    suspend fun confirmEmail(link: AuthLink.Code): ConfirmationResult = mutex.withLock {
        val verified = exchange(link, EmailPurpose.CONFIRMATION)
        val active = session.value ?: vault.read()
        if (active != null && active.userId != verified.userId) {
            endSession(verified.access)
            ConfirmationResult.OTHER_ACCOUNT_ACTIVE
        } else {
            publish(verified)
            ConfirmationResult.SIGNED_IN
        }
    }

    suspend fun authorizeRecovery(link: AuthLink.Code): Unit = mutex.withLock {
        require(link.purpose == EmailPurpose.RECOVERY) { "This is an invalid password reset link." }
        val previous = readStored<RecoveryGrant>(RECOVERY_GRANT)
        if (previous?.fingerprint == link.fingerprint && previous.expiresAt > now() / 1000) return@withLock
        val verified = exchange(link, EmailPurpose.RECOVERY)
        val grant = RecoveryGrant(link.fingerprint, verified.userId, verified.access,
            minOf(verified.expiresAt, now() / 1000 + RECOVERY_WINDOW_SECONDS))
        vault.writeValue(RECOVERY_GRANT, ledgerJson.encodeToString(RecoveryGrant.serializer(), grant))
    }

    suspend fun finishRecovery(link: AuthLink.Code, password: String): Unit = mutex.withLock {
        requirePassword(password)
        val grant = readStored<RecoveryGrant>(RECOVERY_GRANT)
        if (link.purpose != EmailPurpose.RECOVERY || grant == null || grant.fingerprint != link.fingerprint ||
            grant.expiresAt <= now() / 1000) throw expiredRecovery()
        try {
            authRequest("/auth/v1/user", "Unable to update your password. Please try again.", buildJsonObject {
                put("password", password)
            }, grant.access, "PUT")
        } catch (e: CloudError) {
            if (e.status == 401 || e.code == "session_not_found" || e.code == "session_expired") {
                vault.clearValue(RECOVERY_GRANT)
                throw expiredRecovery()
            }
            throw e
        }
        vault.clearValue(RECOVERY_GRANT)
        if ((session.value ?: vault.read())?.userId == grant.userId) publish(null)
        endSession(grant.access)
    }

    suspend fun current(forceRefresh: Boolean = false, expectedUserId: String? = null): Session? = mutex.withLock {
        val old = session.value ?: vault.read() ?: return@withLock null
        if (expectedUserId != null && old.userId != expectedUserId) return@withLock null
        if (session.value == null) publish(old)
        if (!forceRefresh && old.expiresAt > now() / 1000 + 45) return@withLock old
        try {
            val value = authRequest("/auth/v1/token?grant_type=refresh_token", "Sign in again to resume sync.", buildJsonObject {
                put("refresh_token", old.refresh)
            })
            val updated = sessionFrom(value) ?: throw CloudError(401, "Sign in again to resume sync.")
            require(updated.userId == old.userId) { "The session identity changed. Sign in again." }
            publish(updated)
            updated
        } catch (e: CloudError) {
            if (e.status in listOf(400, 401, 403)) publish(null)
            throw e
        }
    }

    suspend fun signOut() = mutex.withLock {
        val old = session.value ?: vault.read()
        publish(null)
        vault.clearValue(EmailPurpose.RECOVERY.storageKey)
        vault.clearValue(RECOVERY_GRANT)
        if (old != null) endSession(old.access)
    }

    suspend fun deleteAccount() {
        val active = current() ?: throw CloudError(401, "Sign in first.")
        api.call("/functions/v1/delete-account", buildJsonObject { }, active.access)
        purgePrincipal(active.userId)
        signOut()
    }

    private suspend fun sendEmail(
        purpose: EmailPurpose, email: String, endpoint: String, extra: JsonObjectBuilder.() -> Unit = {}
    ): JsonObject {
        val cooldownKey = "${purpose.storageKey}_cooldown"
        val cooldown = readStored<EmailCooldown>(cooldownKey)
        if (cooldown != null && cooldown.email.equals(email, ignoreCase = true) && cooldown.retryAt > now()) {
            throw CloudError(429, "Please wait before requesting another email.")
        }
        val previous = vault.readValue(purpose.storageKey)
        val verifier = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val pending = PendingEmail(email, verifier)
        vault.writeValue(purpose.storageKey, ledgerJson.encodeToString(PendingEmail.serializer(), pending))
        writeCooldown(cooldownKey, email, EMAIL_COOLDOWN_MS)
        val redirect = URLEncoder.encode(purpose.redirect, "UTF-8")
        return try {
            authRequest("/auth/v1/$endpoint?redirect_to=$redirect", "Unable to request the email. Please try again.", buildJsonObject {
                put("email", email)
                put("code_challenge", sha256(verifier))
                put("code_challenge_method", "s256")
                extra()
            })
        } catch (e: CloudError) {
            // A definite server rejection leaves the previous link usable. A timeout may still have sent the new link.
            if (e.status in 400..499) {
                if (previous == null) vault.clearValue(purpose.storageKey) else vault.writeValue(purpose.storageKey, previous)
                if (e.status == 429) writeCooldown(cooldownKey, email, (e.retryAfterSeconds ?: 60L).coerceIn(60, 3600) * 1000)
                else vault.clearValue(cooldownKey)
            }
            throw e
        }
    }

    private suspend fun exchange(link: AuthLink.Code, purpose: EmailPurpose): Session {
        require(link.purpose == purpose) { "This email link is invalid." }
        val consumedKey = "${purpose.storageKey}_used"
        if (vault.readValue(consumedKey) == link.fingerprint) {
            throw CloudError(400, "This email link has already been used. Sign in or request a new link.")
        }
        val stored = vault.readValue(purpose.storageKey)
        val pending = readStored<PendingEmail>(purpose.storageKey)
            // A reset requested in an older build stored only the verifier.
            ?: stored?.takeIf { purpose == EmailPurpose.RECOVERY && it.matches(Regex("[a-zA-Z0-9_-]{43,128}")) }
                ?.let { PendingEmail(null, it) }
            ?: throw CloudError(400, "Request a new email from this device and open the newest link here.")
        val response = authRequest("/auth/v1/token?grant_type=pkce", "Unable to verify the email link. Please try again.", buildJsonObject {
            put("auth_code", link.value)
            put("code_verifier", pending.verifier)
        })
        val verified = sessionFrom(response) ?: throw invalidResponse("Supabase did not return a verified session. Request a new email.")
        if ((pending.email != null && !verified.email.equals(pending.email, ignoreCase = true)) ||
            (response["user"] as? JsonObject)?.string("email_confirmed_at").isNullOrBlank()) {
            endSession(verified.access)
            throw CloudError(403, "Supabase could not verify the email for this request. Request a new email.")
        }
        vault.writeValue(consumedKey, link.fingerprint)
        vault.clearValue(purpose.storageKey)
        return verified
    }

    private suspend fun authRequest(
        path: String, fallback: String, body: JsonObject, token: String? = null, method: String? = null
    ): JsonObject = try {
        api.call(path, body, token, method) as? JsonObject ?: throw invalidResponse(fallback)
    } catch (e: CloudError) {
        val message = when {
            e.status == 0 -> "The server is unreachable. Check your connection and try again."
            e.status == 429 -> "Too many email or sign-in requests. Wait and try again."
            e.code in setOf("otp_expired", "flow_state_expired", "flow_state_not_found") ->
                "This email link has expired or has already been used. Request a new one."
            e.code == "bad_code_verifier" -> "Open the newest link on the device that requested the email."
            e.code == "email_not_confirmed" -> "Confirm your email before signing in, or request a new confirmation email."
            e.code == "weak_password" || e.code == "same_password" -> "Choose a different password that meets the password requirements."
            e.code == "email_address_not_authorized" -> "Supabase cannot deliver email to this address. Check your project's email settings."
            else -> fallback
        }
        throw CloudError(e.status, message, e.code, e.retryAfterSeconds)
    }

    private suspend fun endSession(access: String) {
        try {
            api.call("/auth/v1/logout?scope=local", buildJsonObject {}, access)
        } catch (_: CloudError) {
            // A failed server sign-out does not restore a local session.
        }
    }

    private fun publish(value: Session?) {
        if (value == null) vault.clear() else vault.write(value)
        setPrincipal(value?.userId)
        session.value = value
    }

    private fun sessionFrom(json: JsonObject): Session? {
        val access = json.string("access_token")?.takeIf(String::isNotBlank) ?: return null
        val refresh = json.string("refresh_token")?.takeIf(String::isNotBlank) ?: return null
        val user = json["user"] as? JsonObject ?: return null
        val id = user.string("id")?.takeIf(String::isNotBlank) ?: return null
        val expires = (json["expires_at"] as? JsonPrimitive)?.longOrNull
            ?: (now() / 1000 + ((json["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600))
        return Session(id, access, refresh, expires, user.string("email").orEmpty())
    }

    private inline fun <reified T> readStored(key: String): T? =
        vault.readValue(key)?.let { runCatching { ledgerJson.decodeFromString<T>(it) }.getOrNull() }

    private fun writeCooldown(key: String, email: String, delay: Long) =
        vault.writeValue(key, ledgerJson.encodeToString(EmailCooldown.serializer(), EmailCooldown(email, now() + delay)))

    private fun emailAddress(email: String) =
        email.trim().also { require(it.length in 3..254 && '@' in it) { "Enter a valid email address." } }

    private fun requirePassword(password: String) {
        require(password.length in 8..128) { "Enter a password between eight and 128 characters." }
    }

    private fun invalidResponse(message: String) = CloudError(502, message)

    private fun expiredRecovery() =
        CloudError(410, "The password reset link has expired or has already been used. Request a new one.")
}
