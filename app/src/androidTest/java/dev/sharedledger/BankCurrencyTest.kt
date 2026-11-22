package dev.sharedledger

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule as TestRule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BankCurrencyTest {
    @get:TestRule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)

    @Test fun actualDebitAndCreditTeachOnlyTheirOwnAccountPairAndDirection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val rates = Rates(db, emptyList())
            val repo = LedgerRepository(db, rates)
            val dao = repo.dao
            repo.initialize()
            val budget = checkNotNull(dao.localBudget())
            val debit = Account(id(), budget.id, "Debit", "USD", AccountType.DEBIT_CARD)
            val other = debit.copy(id = id(), name = "Other USD card")
            repo.saveAccount(debit)
            repo.saveAccount(other)
            val day = LocalDate.of(2026, 11, 21)
            fun quote(base: String, target: String, value: String) = Rate(base, target, day.toString(), day.toString(), value, "Fixture reference")
            dao.put(quote("RSD", "USD", "0.0085"))
            dao.put(quote("RUB", "USD", "0.0104"))
            dao.put(quote("RUB", "RSD", "1.17"))
            val category = dao.categories(budget.id).first { it.type == EntryType.EXPENSE }
            val incomeCategory = dao.categories(budget.id).first { it.type == EntryType.INCOME }
            val purchase = EntryDraft(EntryType.EXPENSE, BigDecimal("2500"), day, debit.id, category.id, "RSD")
            repo.saveEntry(budget.id, purchase)
            val estimated = dao.entries(budget.id).single()
            assertEquals("21.25", estimated.accountAmount)
            assertEquals(BankSource.REFERENCE_API, estimated.bankConversion?.source)
            val pair = BankPair(budget.id, debit.id, "RSD", "USD", EntryType.EXPENSE)
            assertTrue(repo.bankRates.observations(pair, day).isEmpty())

            repo.saveEntry(budget.id, purchase.copy(bankAmount = BigDecimal("21.50"), bankConfirmed = true), estimated.id)
            val actual = checkNotNull(dao.entry(estimated.id))
            assertEquals("21.5", actual.accountAmount)
            assertEquals("2500", actual.budgetAmount)
            assertEquals("0.0086", actual.accountRate)
            assertEquals(BankSource.BANK_ACTUAL, actual.bankConversion?.source)
            assertEquals(1, repo.bankRates.observations(pair, day).size)
            for (otherPair in listOf(pair.copy(account = other.id), pair.copy(currency = "RUB"), pair.copy(direction = EntryType.INCOME))) {
                assertTrue(repo.bankRates.observations(otherPair, day).isEmpty())
            }
            val bank = checkNotNull(repo.bankRates.offer(pair, day, offline = true))
            assertEquals(BankSource.BANK_STALE, bank.conversion.source)
            assertEquals(actual.bankConversion?.confirmedAt, bank.conversion.priorBankConfirmationAt)
            assertEquals("25.8", bank.amount(BigDecimal("3000"), "USD")?.storage())
            assertNull(repo.bankRates.offer(pair, day.minusDays(1)))
            repo.saveEntry(budget.id, purchase.copy(amount = BigDecimal("3000"), date = day.plusDays(1)))
            val second = dao.entries(budget.id).first { it.id != actual.id }
            assertEquals("25.8", second.accountAmount)
            assertEquals(1, repo.bankRates.observations(pair, day.plusDays(1)).size)
            val secondDraft = purchase.copy(amount = BigDecimal("3000"), date = day.plusDays(1), bankConfirmed = true)
            repo.saveEntry(budget.id, secondDraft.copy(bankAmount = BigDecimal("25.65")), second.id)
            assertEquals("0.00855", repo.bankRates.offer(pair, day.plusDays(1))?.quote?.value)
            repo.saveEntry(budget.id, secondDraft.copy(bankAmount = BigDecimal("25.62")), second.id)
            assertEquals("0.00854", repo.bankRates.offer(pair, day.plusDays(1))?.quote?.value)
            repo.deleteEntry(checkNotNull(dao.entry(second.id)))
            assertEquals("0.0086", repo.bankRates.offer(pair, day.plusDays(1))?.quote?.value)

            repo.saveEntry(budget.id, EntryDraft(EntryType.INCOME, BigDecimal("100000"), day, debit.id,
                incomeCategory.id, "RUB", bankAmount = BigDecimal("1050"), bankConfirmed = true))
            val credit = dao.entries(budget.id).single { it.type == EntryType.INCOME }
            assertEquals("1050", credit.accountAmount)
            assertEquals("117000", credit.budgetAmount)
            assertEquals("0.0105", credit.accountRate)
            assertEquals("1.17", credit.budgetRate)
            assertEquals(1, repo.bankRates.observations(pair.copy(currency = "RUB", direction = EntryType.INCOME), day).size)

            dao.put(quote("RSD", "USD", "0.01"))
            repo.saveEntry(budget.id, purchase.copy(note = "Changed note"), actual.id)
            val updated = checkNotNull(dao.entry(actual.id))
            assertEquals(actual.accountAmount, updated.accountAmount)
            assertEquals(actual.accountRate, updated.accountRate)
            assertEquals(actual.bankConversion, updated.bankConversion)
            val files = Portability(repo)
            val archive = files.decodeJson(files.encode(files.export(listOf(budget.id), DateSpan()), csv = false))
            assertEquals(updated.bankConversion, archive.archive.budgets.single().entries.find { it.id == actual.id }?.bankConversion)
            assertEquals("0.0086", repo.bankRates.offer(pair, day)?.quote?.value)
            assertEquals(updated, dao.entry(actual.id))
            assertEquals(BigDecimal("1028.5"), accountBalance(debit, dao.entries(budget.id)).value)
            repo.deleteEntry(credit)
            assertTrue(repo.bankRates.observations(pair.copy(currency = "RUB", direction = EntryType.INCOME), day).isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun migrationKeepsPriorAmountsWithoutInferringBankConfirmation() {
        migration.createDatabase("bank-upgrade", 4).use {
            it.execSQL("INSERT INTO budgets (id,name,currency,type,icon,color,role,createdAt,updatedAt) VALUES ('b','Legacy','RSD','PERSONAL','wallet',1,'OWNER',1,1)")
            it.execSQL("INSERT INTO accounts (id,budgetId,name,currency,type,opening,icon,color,`order`,archived,createdAt,updatedAt) VALUES ('a','b','Debit','USD','DEBIT_CARD','0','wallet',1,0,0,1,1)")
            it.execSQL("INSERT INTO entries (id,budgetId,accountId,type,amount,currency,accountCurrency,budgetCurrency,date,accountAmount,note,createdAt,updatedAt) VALUES ('e','b','a','EXPENSE','2500','RSD','USD','RSD','2026-11-21','21.5','',1,1)")
        }
        migration.runMigrationsAndValidate("bank-upgrade", 5, true, LedgerDatabase.MIGRATION_4_5).use {
            it.query("SELECT accountAmount,bankConversion FROM entries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("21.5", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }
}
