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
        val manual = draft(sunday).copy(note = "Manual", accountRate = BigDecimal("118"),
            budgetValuation = BudgetValuationEdit.Rate(BigDecimal("119")))
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
        assertEquals("2360", manualEntry.accountAmount)
        assertEquals("2380", manualEntry.budgetAmount)
        assertEquals(USER_DEFINED_BUDGET_RATE, manualEntry.budgetRateSource)
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
        assertTrue(cached.budgetRateSource?.startsWith(CACHED_BUDGET_RATE_PREFIX) == true)
        assertNull(rates.cached("EUR", "RUB", sunday, allowOlder = true))
    }

    @Test fun usdIncomeAndExpenseUseIndependentBudgetValuationAndKeepSavedAmounts() = runBlocking {
        val usd = cash.copy(id = id(), name = "Debit USD", currency = "USD")
        repo.saveAccount(usd)
        val salary = repo.dao.categories(budget.id).first { it.type == EntryType.INCOME }
        val income = EntryDraft(EntryType.INCOME, BigDecimal("100"), today, usd.id, salary.id, "USD", note = "First")
        rates.ensure("USD", setOf("RSD"), today, today)
        repo.saveEntry(budget.id, income)
        val first = repo.dao.entries(budget.id).single()
        assertEquals("11700", first.budgetAmount)
        assertEquals("Frankfurter", first.budgetRateSource)
        repo.saveEntry(budget.id, income.copy(budgetValuation = BudgetValuationEdit.Rate(BigDecimal("114"))), first.id)
        val manual = requireNotNull(repo.dao.entry(first.id))
        assertEquals("11400", manual.budgetAmount)
        assertEquals("100", manual.accountAmount)
        assertEquals("1", manual.accountRate)
        assertNotNull(manual.budgetRateConfirmedAt)
        provider.response = { base, targets, day -> targets.map { observation(base, it, day, day, "140") } }
        rates.ensure("USD", setOf("RSD"), today, today, force = true)
        repo.saveEntry(budget.id, income.copy(note = "Edited note"), first.id)
        assertEquals(manual.budgetRateConfirmedAt, repo.dao.entry(first.id)?.budgetRateConfirmedAt)
        assertEquals("11400", repo.dao.entry(first.id)?.budgetAmount)
        repo.saveEntry(budget.id, income.copy(amount = BigDecimal("200"), note = "Second"), allowOlder = true)
        val second = repo.dao.entries(budget.id).single { it.note == "Second" }
        assertEquals("22800", second.budgetAmount)
        assertEquals(LAST_CONFIRMED_BUDGET_RATE, second.budgetRateSource)
        assertNull(second.budgetRateConfirmedAt)
        val expense = EntryDraft(EntryType.EXPENSE, BigDecimal("50"), today, usd.id, category.id, "USD")
        val expensePair = BudgetRatePair(budget.id, usd.id, "USD", "RSD", EntryType.EXPENSE)
        assertEquals("140", repo.budgetRate(expensePair, today, null, useReference = false, offline = false)?.value)
        repo.saveEntry(budget.id, expense.copy(budgetValuation = BudgetValuationEdit.Amount(BigDecimal("5700"))))
        val entries = repo.dao.entries(budget.id)
        val purchase = entries.single { it.type == EntryType.EXPENSE }
        assertEquals("114", purchase.budgetRate)
        assertEquals("50", purchase.accountAmount)
        assertFalse(purchase.isBudgetApproximate())
        assertEquals("250", accountBalance(usd, entries).value.storage())
        assertEquals("34200", totals(entries, "RSD").income.storage())
        assertEquals("5700", totals(entries, "RSD").expenses.storage())
    }

    @Test fun latestBudgetRateIsScopedDatedAndCanBeReplacedOrRemoved() = runBlocking {
        val usd = cash.copy(id = id(), name = "Debit", currency = "USD")
        val other = usd.copy(id = id(), name = "Savings")
        repo.saveAccount(usd)
        repo.saveAccount(other)
        val anotherBudget = Budget(id(), "Travel", "RSD")
        repo.dao.put(anotherBudget)
        val travel = usd.copy(id = id(), budgetId = anotherBudget.id)
        repo.saveAccount(travel)
        val pair = BudgetRatePair(budget.id, usd.id, "USD", "RSD", EntryType.EXPENSE)
        val oldDay = today.minusDays(2)
        val earlier = EntryDraft(EntryType.EXPENSE, BigDecimal("100"), oldDay, usd.id, category.id, "USD",
            budgetValuation = BudgetValuationEdit.Rate(BigDecimal("114")))
        repo.saveEntry(budget.id, earlier)
        repo.saveEntry(budget.id, earlier.copy(date = today, note = "Latest",
            budgetValuation = BudgetValuationEdit.Rate(BigDecimal("115"))))
        val latest = repo.dao.entries(budget.id).single { it.note == "Latest" }
        suspend fun suggestion(scope: BudgetRatePair, day: LocalDate = today) =
            repo.budgetRate(scope, day, null, useReference = false, offline = false)
        repo.dao.put(observation("USD", "RSD", today, today, "117"))
        repo.dao.put(observation("USD", "RSD", today, today, "116", "Manual"))
        assertEquals("114", suggestion(pair, oldDay)?.value)
        assertNull(suggestion(pair, oldDay.minusDays(1)))
        assertEquals("115", suggestion(pair)?.value)
        assertEquals("116", suggestion(pair.copy(accountId = other.id))?.value)
        assertEquals("116", suggestion(pair.copy(budgetId = anotherBudget.id, accountId = travel.id))?.value)
        repo.saveEntry(budget.id, earlier.copy(date = today, note = "Latest",
            budgetValuation = BudgetValuationEdit.Amount(BigDecimal("11250"))), latest.id)
        assertEquals("112.5", suggestion(pair)?.value)
        repo.saveEntry(budget.id, earlier.copy(date = today, budgetValuation = BudgetValuationEdit.Reference), latest.id)
        assertEquals("117", repo.dao.entry(latest.id)?.budgetRate)
        assertNull(repo.dao.entry(latest.id)?.budgetRateConfirmedAt)
        assertEquals("114", suggestion(pair)?.value)
        repo.deleteEntry(repo.dao.entries(budget.id).single { it.date == oldDay.toString() })
        assertEquals("116", suggestion(pair)?.value)
    }

    @Test fun sharedThreeCurrencyEntryKeepsIndependentConversionsInOutboxAndJson() = runBlocking {
        val user = id()
        repo.principal.value = user
        repo.promote(budget.id)
        val usd = cash.copy(id = id(), name = "USD", currency = "USD")
        repo.saveAccount(usd)
        val salary = repo.dao.categories(budget.id).first { it.type == EntryType.INCOME }
        repo.dao.put(requireNotNull(repo.dao.budget(budget.id)).copy(role = Role.EDITOR))
        val income = EntryDraft(EntryType.INCOME, BigDecimal("100000"), today, usd.id, salary.id, "RUB",
            budgetValuation = BudgetValuationEdit.Amount(BigDecimal("113500")), bankAmount = BigDecimal("1050"), bankConfirmed = true)
        repo.saveEntry(budget.id, income)
        val saved = repo.dao.entries(budget.id).single()
        val queued = requireNotNull(repo.dao.pending("entry", saved.id))
        assertEquals(saved, ledgerJson.decodeFromString<Entry>(queued.body))
        assertEquals("1050", saved.accountAmount)
        assertEquals("0.0105", saved.accountRate)
        assertEquals(BankSource.BANK_ACTUAL, saved.bankConversion?.source)
        assertEquals("113500", saved.budgetAmount)
        assertEquals("1.135", saved.budgetRate)
        val files = Portability(repo)
        val archive = files.decodeJson(files.encode(files.export(listOf(budget.id), DateSpan()), csv = false))
        val restored = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, LedgerDatabase::class.java).build()
        try {
            val local = LedgerRepository(restored, Rates(restored, emptyList()))
            Portability(local).restore(archive)
            assertEquals(saved.budgetRateConfirmedAt, local.dao.entry(saved.id)?.budgetRateConfirmedAt)
            assertEquals("113500", local.dao.entry(saved.id)?.budgetAmount)
            val pair = BudgetRatePair(budget.id, usd.id, "RUB", "RSD", EntryType.INCOME)
            assertEquals("1.135", local.budgetRate(pair, today, null, useReference = false, offline = true)?.value)
        } finally {
            restored.close()
        }
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

    @Test fun valuationMigrationKeepsHistoricalRatesWithoutInferringConfirmation() {
        val database = "valuation-migration-6-7"
        migrations.createDatabase(database, 6).use {
            it.execSQL("INSERT INTO budgets (id,name,currency,type,icon,color,role,createdAt,updatedAt) VALUES ('b','Test','RSD','PERSONAL','wallet',1,'OWNER',1,1)")
            it.execSQL("INSERT INTO accounts (id,budgetId,name,currency,type,opening,icon,color,`order`,archived,createdAt,updatedAt) VALUES ('a','b','Debit','USD','CASH','0','wallet',1,0,0,1,1)")
            it.execSQL("""INSERT INTO entries (id,budgetId,accountId,type,amount,currency,accountCurrency,budgetCurrency,date,
                accountAmount,budgetAmount,budgetRate,budgetRateDate,budgetRateSource,note,createdAt,updatedAt) VALUES
                ('e','b','a','INCOME','100','USD','USD','RSD','2026-11-22','100','11400','114','2026-11-22','Manual','',1,1)""")
        }
        migrations.runMigrationsAndValidate(database, 7, true, LedgerDatabase.MIGRATION_6_7).use {
            it.query("SELECT accountAmount,budgetAmount,budgetRate,budgetRateConfirmedAt FROM entries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("100", cursor.getString(0))
                assertEquals("11400", cursor.getString(1))
                assertEquals("114", cursor.getString(2))
                assertTrue(cursor.isNull(3))
            }
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
