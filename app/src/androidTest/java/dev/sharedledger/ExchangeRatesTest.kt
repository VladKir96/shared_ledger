package dev.sharedledger

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class ExchangeRatesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: LedgerDatabase
    private lateinit var rates: Rates
    private lateinit var repo: LedgerRepository
    private lateinit var refresh: RateRefresh
    private lateinit var budget: Budget
    private lateinit var cash: Account
    private lateinit var category: Category
    private lateinit var provider: TestProvider
    private lateinit var fallback: TestProvider
    private val today = LocalDate.parse("2026-11-20")
    private val sunday = LocalDate.parse("2025-01-19")

    @get:org.junit.Rule val migrations = MigrationTestHelper(instrumentation, LedgerDatabase::class.java)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, LedgerDatabase::class.java).build()
        provider = TestProvider("Frankfurter")
        fallback = TestProvider("Fallback").apply { response = { _, _, _ -> emptyList() } }
        rates = Rates(db, listOf(provider, fallback))
        repo = LedgerRepository(db, rates)
        refresh = RateRefresh(repo, rates)
        repo.initialize()
        budget = requireNotNull(repo.dao.localBudget())
        cash = repo.dao.accounts(budget.id).single()
        category = repo.dao.categories(budget.id).first { it.type == EntryType.EXPENSE }
    }

    @After fun close() = db.close()

    @Test fun dailyStartupRefreshBatchesCurrenciesAndReusesCompletedCache() = runBlocking {
        assertFalse(refresh.run(online = true, today = today))
        assertEquals(6, provider.requests.size)
        assertEquals(30, provider.requests.sumOf { it.targets.size })
        assertNotNull(rates.cached("RSD", "RUB", today))
        val sync = requireNotNull(repo.dao.rateSync())
        assertEquals(today.toString(), sync.successDate)
        assertFalse(refresh.run(online = true, today = today))
        assertEquals(sync.lastSuccess, repo.dao.rateSync()?.lastSuccess)
        assertEquals(6, provider.requests.size)

        assertFalse(refresh.run(online = true, today = today.plusDays(1)))
        assertEquals(12, provider.requests.size)
        repo.preferences { it.copy(automaticRates = false) }
        assertFalse(refresh.run(online = true, today = today.plusDays(2)))
        assertEquals(12, provider.requests.size)
        assertFalse(refresh.run(online = true, force = true, today = today.plusDays(2)))
        assertEquals(18, provider.requests.size)
    }

    @Test fun historicalBatchesPickLatestEarlierDateAndFallbackOnlyForMissingTargets() = runBlocking {
        provider.response = { base, _, day -> listOf(
            observation(base, "RSD", day, day.minusDays(3), "116"),
            observation(base, "RSD", day, day.minusDays(2), "117.16"),
            observation(base, "RSD", day, day.plusDays(1), "999")
        ) }
        fallback.response = { base, targets, day ->
            targets.map { observation(base, it, day, day.minusDays(1), "1.25", fallback.name) }
        }
        coroutineScope {
            List(3) { async { rates.ensure("EUR", setOf("RSD", "USD"), sunday, today) } }.awaitAll()
        }
        assertEquals(1, provider.requests.size)
        assertEquals(setOf("USD"), fallback.requests.single().targets)
        val wallet = cash.copy(id = id(), name = "USD", currency = "USD")
        repo.saveAccount(wallet)
        repo.saveEntry(budget.id, draft(sunday, wallet.id))
        val entry = repo.dao.entries(budget.id).single()
        assertEquals("2343.2", entry.budgetAmount)
        assertEquals("25", entry.accountAmount)
        assertEquals("2025-01-17", entry.budgetRateDate)
        assertEquals("2025-01-18", entry.accountRateDate)
        assertEquals("Fallback", entry.accountRateSource)
        assertEquals("Frankfurter", entry.budgetRateSource)
    }

    @Test fun offlineSaveBackfillsOnReconnectAndPreservesManualOrRecordedConversions() = runBlocking {
        repo.saveEntry(budget.id, draft(sunday))
        val pending = repo.dao.entries(budget.id).single()
        assertNull(pending.budgetAmount)
        assertTrue(refresh.run(online = false, today = today))
        assertTrue(provider.requests.isEmpty())
        val manual = draft(sunday).copy(note = "Manual", accountRate = BigDecimal("118"))
        repo.saveEntry(budget.id, manual)
        assertFalse(refresh.run(online = true, today = today))
        val filled = requireNotNull(repo.dao.entry(pending.id))
        assertEquals("20", filled.amount)
        assertEquals("2340", filled.budgetAmount)
        assertEquals("2340", filled.accountAmount)
        assertEquals("Frankfurter", filled.budgetRateSource)
        provider.response = { base, targets, date -> targets.map { observation(base, it, date, date, "140") } }
        assertFalse(refresh.run(online = true, force = true, today = today))
        repo.saveEntry(budget.id, draft(sunday).copy(note = "Edited"), filled.id)
        val edited = requireNotNull(repo.dao.entry(filled.id))
        assertEquals("2340", edited.budgetAmount)
        assertEquals("Edited", edited.note)
        val manualEntry = repo.dao.entries(budget.id).single { it.note == "Manual" }
        assertEquals("2360", manualEntry.budgetAmount)
        assertEquals("Manual", manualEntry.budgetRateSource)
    }

    @Test fun uncertainCachedOlderQuoteWorksOfflineAndFutureQuotesAreExcluded() = runBlocking {
        val earlier = sunday.minusDays(2)
        repo.dao.put(observation("EUR", "RSD", earlier, earlier, "117.16"))
        repo.dao.put(observation("EUR", "RSD", today, today, "120"))
        assertNull(rates.cached("EUR", "RSD", sunday))
        repo.saveEntry(budget.id, draft(sunday), allowOlder = true)
        val cached = repo.dao.entries(budget.id).single()
        assertEquals("2343.2", cached.budgetAmount)
        assertEquals(earlier.toString(), cached.budgetRateDate)
        assertNull(rates.cached("EUR", "RUB", sunday, allowOlder = true))
    }

    @Test fun failuresLeaveTransactionsIntactAndRetryOnlyMissingPairs() = runBlocking {
        repo.saveEntry(budget.id, draft(sunday))
        provider.response = { base, targets, day ->
            if (day == sunday) throw IOException("timeout")
            targets.map { observation(base, it, day, day, "117") }
        }
        assertTrue(refresh.run(online = true, today = today))
        assertNull(repo.dao.entries(budget.id).single().budgetAmount)
        val failed = requireNotNull(repo.dao.rateCheck("EUR", "RSD", sunday.toString()))
        assertTrue(failed.retryable)
        val totalCalls = provider.requests.size
        assertTrue(refresh.run(online = true, today = today))
        assertEquals(totalCalls, provider.requests.size)
        repo.dao.put(failed.copy(checkedAt = 0))
        provider.response = { base, targets, date -> targets.map { observation(base, it, date, date, "117.25") } }
        assertFalse(refresh.run(online = true, today = today))
        assertEquals(totalCalls + 1, provider.requests.size)
        assertEquals("2345", repo.dao.entries(budget.id).single().budgetAmount)
    }

    @Test fun sharedBackfillUsesVersionedOutboxAndLeavesOtherUsersAndViewersPending() = runBlocking {
        val user = id()
        repo.principal.value = user
        repo.promote(budget.id)
        repo.saveEntry(budget.id, draft(sunday))
        val pending = repo.dao.entries(budget.id).single()
        repo.dao.removePending("entry", pending.id)
        repo.dao.put(RemoteVersion("entry", pending.id, budget.id, 3))
        repo.principal.value = id()
        assertFalse(refresh.run(online = true, today = today))
        assertNull(repo.dao.entry(pending.id)?.budgetAmount)

        repo.principal.value = user
        val original = requireNotNull(repo.dao.budget(budget.id))
        repo.dao.put(original.copy(role = Role.VIEWER))
        assertFalse(refresh.run(online = true, today = today))
        assertNull(repo.dao.entry(pending.id)?.budgetAmount)

        repo.dao.put(original.copy(role = Role.EDITOR))
        assertFalse(refresh.run(online = true, today = today))
        val outbox = requireNotNull(repo.dao.pending("entry", pending.id))
        assertTrue(outbox.rateOnly)
        assertEquals(3L, outbox.baseVersion)
        val serialized = ledgerJson.decodeFromString<Entry>(outbox.body)
        assertEquals("2340", serialized.budgetAmount)
        assertEquals("117", serialized.budgetRate)
        assertEquals(sunday.toString(), serialized.budgetRateDate)
    }

    @Test fun migrationDeduplicatesPublicationsAndRetainsRequestedDatesAndManualRates() {
        val database = "rates-migration-2-3"
        instrumentation.targetContext.deleteDatabase(database)
        migrations.createDatabase(database, 2).use {
            it.execSQL("INSERT INTO settings (`key`, theme, dynamicColor, defaultCurrency) VALUES (0, 'SYSTEM', 0, 'RSD')")
            it.execSQL("""INSERT INTO rates (base,target,requestedDate,date,value,source,fetchedAt) VALUES
                ('EUR','RSD','2025-01-18','2025-01-17','117.11','Frankfurter',1),
                ('EUR','RSD','2025-01-19','2025-01-17','117.12','Frankfurter',2),
                ('EUR','RSD','2025-01-17','2025-01-17','118','Manual',3)""")
        }
        migrations.runMigrationsAndValidate(database, 3, true, LedgerDatabase.MIGRATION_2_3).use {
            it.query("SELECT count(*) FROM rates").use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
            it.query("SELECT count(*) FROM rate_checks").use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
            it.query("SELECT automaticRates FROM settings").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }

    private fun draft(date: LocalDate, account: String = cash.id) =
        EntryDraft(EntryType.EXPENSE, BigDecimal("20"), date, account, category.id, "EUR")

    private data class Request(val base: String, val targets: Set<String>, val day: LocalDate)

    private class TestProvider(override val name: String) : RateProvider {
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        var response: (String, Set<String>, LocalDate) -> List<Rate> = { base, targets, day ->
            targets.map { observation(base, it, day, day, "117", name) }
        }

        override suspend fun lookup(base: String, targets: Set<String>, day: LocalDate): List<Rate> {
            requests.add(Request(base, targets, day))
            return response(base, targets, day)
        }
    }

    private companion object {
        fun observation(base: String, target: String, requested: LocalDate, effective: LocalDate, value: String,
                        source: String = "Frankfurter") =
            Rate(base, target, requested.toString(), effective.toString(), value, source)
    }
}
