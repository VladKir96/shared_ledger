package dev.sharedledger.sync

import dev.sharedledger.data.PendingChange
import dev.sharedledger.data.Entry
import dev.sharedledger.data.ledgerJson
import dev.sharedledger.domain.EntryType
import org.junit.Assert.*
import org.junit.Test

class SyncOutboxTest {
    @Test fun legacyServerKeyCasingStillDecodesIntoTheFourRateMetadataFields() {
        val original = Entry("entry", "budget", "card", "food", EntryType.EXPENSE, "2500", "RSD", "USD", "RSD", "2026-11-20",
            accountAmount = "21.50", accountRate = "0.0086", accountRateDate = "2026-11-20", accountRateSource = "API",
            budgetAmount = "2500", budgetRate = "1", budgetRateDate = "2026-11-20", budgetRateSource = "Same currency")
        val canonical = ledgerJson.encodeToString(Entry.serializer(), original)
        val legacy = canonical.replace("RateDate\"", "Ratedate\"").replace("RateSource\"", "Ratesource\"")
        assertEquals(original, ledgerJson.decodeFromString(Entry.serializer(), legacy))
        assertTrue(canonical.contains("accountRateDate"))
        assertFalse(canonical.contains("accountRatedate"))
    }

    @Test fun synchronizedDeletionSendsOnlyItsTimestampAndStillKeepsTheLocalIntent() {
        val original = """{"deletedAt":1795220000000,"accountAmount":"21.5","accountId":"card","ruleId":"monthly"}"""
        val queued = PendingChange("entry", "purchase", "household", "operation", original, 4)
        val payload = checkNotNull(queued.payload())
        assertEquals(setOf("deletedAt"), queued.pushPayload(payload).keys)
        assertEquals(listOf("budget" to "household"), queued.dependencies(payload))
        assertEquals(original, queued.body)
        val unsentOccurrence = queued.copy(baseVersion = 0)
        assertEquals(payload, unsentOccurrence.pushPayload(payload))
        assertEquals(listOf("budget" to "household", "account" to "card", "rule" to "monthly"),
            unsentOccurrence.dependencies(payload))
    }

    @Test fun classifiesRetryableErrorsAndExtractsOnlyTheConstraintIdentifier() {
        val violation = CloudError(400, "new row for relation violates check constraint \"transactions_check2\"", "23514")
        assertEquals("transactions_check2", violation.constraintName())
        assertEquals(SyncFailure.CONSTRAINT, SyncFailure.from(violation))
        assertFalse(violation.isTransient)
        assertFalse(CloudError(500, "Invalid payload", "22023").isTransient)
        assertEquals(SyncFailure.PERMISSION, SyncFailure.from(CloudError(403, "Permission denied", "42501")))
        assertEquals(SyncFailure.CONFLICT, SyncFailure.from(CloudError(409, "Conflict")))
        assertTrue(listOf(0, 401, 408, 429, 503).all { CloudError(it, "Retry").isTransient })
        assertFalse(violation.syncMessage().contains("transactions_check2"))
    }
}
