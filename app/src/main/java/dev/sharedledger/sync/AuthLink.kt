package dev.sharedledger.sync

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

enum class EmailPurpose(val path: String, internal val storageKey: String) {
    CONFIRMATION("/confirm", "confirmation"),
    RECOVERY("/recovery", "recovery");

    val redirect: String get() = "sharedledger://auth$path"
}

enum class AuthLinkProblem { INVALID, EXPIRED_OR_USED, FAILED }

sealed interface AuthLink {
    val purpose: EmailPurpose

    class Code(override val purpose: EmailPurpose, internal val value: String) : AuthLink {
        internal val fingerprint = sha256(value)
    }

    data class Invalid(override val purpose: EmailPurpose, val problem: AuthLinkProblem) : AuthLink
}

/** Only accept a PKCE code or a sanitized error on the two exact Android callback routes. */
fun parseAuthLink(raw: String): AuthLink? {
    val endpoint = raw.substringBefore('?').substringBefore('#')
    val purpose = EmailPurpose.entries.find { it.redirect == endpoint } ?: return null
    val invalid = AuthLink.Invalid(purpose, AuthLinkProblem.INVALID)
    if (raw.length > 8192) return invalid
    val uri = runCatching { URI(raw) }.getOrNull() ?: return invalid
    val query = parseParameters(uri.rawQuery) ?: return invalid
    val fragment = parseParameters(uri.rawFragment) ?: return invalid
    val error = query["error_code"] ?: fragment["error_code"] ?: query["error"] ?: fragment["error"]
    if (error != null) return AuthLink.Invalid(purpose, when (error) {
        "otp_expired", "flow_state_expired", "flow_state_not_found" -> AuthLinkProblem.EXPIRED_OR_USED
        else -> AuthLinkProblem.FAILED
    })
    if (listOf("access_token", "refresh_token", "token_hash").any { it in query || it in fragment }) return invalid
    val expectedType = if (purpose == EmailPurpose.RECOVERY) setOf("recovery") else setOf("email", "signup")
    if (listOfNotNull(query["type"], fragment["type"]).any { it !in expectedType }) return invalid
    val code = query["code"]?.takeIf { "code" !in fragment && validAuthCode.matches(it) } ?: return invalid
    return AuthLink.Code(purpose, code)
}

private val validAuthCode = Regex("[a-zA-Z0-9_-]{8,512}")

private fun parseParameters(raw: String?): Map<String, String>? = runCatching {
    buildMap {
        raw.orEmpty().split('&').filter(String::isNotEmpty).forEach { field ->
            val name = URLDecoder.decode(field.substringBefore('='), "UTF-8")
            require(name !in this)
            put(name, URLDecoder.decode(field.substringAfter('=', ""), "UTF-8"))
        }
    }
}.getOrNull()

internal fun sha256(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()))
