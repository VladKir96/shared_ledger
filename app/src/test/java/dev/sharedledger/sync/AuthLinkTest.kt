package dev.sharedledger.sync

import org.junit.Assert.*
import org.junit.Test

class AuthLinkTest {
    @Test fun acceptsExactRoutesAndKeepsTheirPurposesSeparate() {
        val confirm = parseAuthLink("sharedledger://auth/confirm?code=confirmation-code") as AuthLink.Code
        val recover = parseAuthLink("sharedledger://auth/recovery?code=recovery-code&type=recovery") as AuthLink.Code
        assertEquals(EmailPurpose.CONFIRMATION, confirm.purpose)
        assertEquals("confirmation-code", confirm.value)
        assertEquals(EmailPurpose.RECOVERY, recover.purpose)
        assertNotEquals(confirm.fingerprint, recover.fingerprint)
        assertNull(parseAuthLink("sharedledger://invite/invitation"))
        assertNull(parseAuthLink("sharedledger://auth/confirmation?code=confirmation-code"))
        assertNull(parseAuthLink("sharedledger://anything@auth/confirm?code=confirmation-code"))
    }

    @Test fun classifiesErrorsInQueryAndFragmentWithoutKeepingDescriptions() {
        val description = "a%20private%20value"
        assertEquals(AuthLink.Invalid(EmailPurpose.CONFIRMATION, AuthLinkProblem.EXPIRED_OR_USED),
            parseAuthLink("sharedledger://auth/confirm?error_code=otp_expired#error_code=otp_expired&error_description=$description"))
        assertEquals(AuthLink.Invalid(EmailPurpose.RECOVERY, AuthLinkProblem.EXPIRED_OR_USED),
            parseAuthLink("sharedledger://auth/recovery#error=access_denied&error_code=otp_expired"))
        assertEquals(AuthLink.Invalid(EmailPurpose.CONFIRMATION, AuthLinkProblem.FAILED),
            parseAuthLink("sharedledger://auth/confirm?error=access_denied&error_description=$description"))
    }

    @Test fun rejectsMissingMalformedAmbiguousAndBearerPayloads() {
        val invalid = AuthLink.Invalid(EmailPurpose.CONFIRMATION, AuthLinkProblem.INVALID)
        listOf("", "?code=%XX", "?code=short", "?code=confirmation-code&code=another-code",
            "?code=confirmation-code#code=another-code", "#code=confirmation-code",
            "?code=confirmation-code&type=recovery", "?code=confirmation-code#access_token=example",
            "?token_hash=unexpected", "?refresh_token=unexpected").forEach { suffix ->
            assertEquals(invalid, parseAuthLink("sharedledger://auth/confirm$suffix"))
        }
    }
}
