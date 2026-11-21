package dev.sharedledger

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.data.*
import dev.sharedledger.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LedgerDataTest {
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LedgerDatabase
    private lateinit var repo: LedgerRepository
    private lateinit var budget: Budget
    private lateinit var cash: Account
    private lateinit var groceries: Category

    @get:org.junit.Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(target, LedgerDatabase::class.java).build()
        repo = LedgerRepository(db, Rates(db, emptyList()))
        repo.initialize()
        budget = requireNotNull(repo.dao.localBudget())
        cash = repo.dao.accounts(budget.id).single()
        groceries = repo.dao.categories(budget.id).first()
    }

    @After fun close() = db.close()

    @Test fun migrationAddsIndexWithoutRemovingTransactions() {
        migrations.createDatabase("migration", 1).use {
            it.execSQL("INSERT INTO budgets (id,name,currency,type,icon,color,role,createdAt,updatedAt) VALUES ('b','Personal','RSD','PERSONAL','wallet',1,'OWNER',1,1)")
        }
        migrations.runMigrationsAndValidate("migration", 2, true, LedgerDatabase.MIGRATION_1_2).use {
            it.query("SELECT name FROM budgets WHERE id='b'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Personal", cursor.getString(0))
            }
        }
    }

    @Test fun historicalRatesRemainStableAndTransactionsCanBeDeleted() = runBlocking {
        val day = LocalDate.parse("2025-01-15")
        repo.dao.put(Rate("EUR", "RSD", day.toString(), day.toString(), "117", "Manual"))
        val draft = EntryDraft(EntryType.EXPENSE, BigDecimal("20"), day, cash.id, groceries.id, "EUR")
        repo.saveEntry(budget.id, draft)
        val entry = repo.dao.entries(budget.id).single()
        assertEquals("2340", entry.budgetAmount)
        repo.dao.put(Rate("EUR", "RSD", day.toString(), day.toString(), "118", "Manual"))
        repo.saveEntry(budget.id, draft.copy(note = "Edited"), entry.id)
        val edited = repo.dao.entries(budget.id).single()
        assertEquals("2340", edited.budgetAmount)
        assertEquals("Edited", edited.note)
        repo.deleteEntry(edited)
        assertTrue(repo.observe(budget.id).first().entries.isEmpty())
        assertNotNull(repo.dao.entry(entry.id)?.deletedAt)
    }

    @Test fun recurrenceAndBackupRetriesAreIdempotent() = runBlocking {
        val due = LocalDate.parse("2025-01-31")
        val rule = dev.sharedledger.data.Rule(id(), budget.id, EntryType.EXPENSE, cash.id, groceries.id, "500", "RSD",
            Frequency.MONTHLY, 1, due.toString(), due.toString())
        val recurring = Recurring(repo)
        recurring.save(rule)
        coroutineScope { listOf(async { recurring.runDue(due) }, async { recurring.runDue(due) }).forEach { it.await() } }
        assertEquals(occurrenceId(rule.id, due), repo.dao.entries(budget.id).single().id)
        val files = Portability(repo)
        val original = files.export(listOf(budget.id), DateSpan())
        val preview = files.decodeJson(files.encode(original, false))
        val fresh = Room.inMemoryDatabaseBuilder(target, LedgerDatabase::class.java).build()
        try {
            val destination = Portability(LedgerRepository(fresh, Rates(fresh, emptyList())))
            assertEquals(1 to 0, destination.restore(preview))
            assertEquals(0 to 1, destination.restore(preview))
            assertEquals(cash.id, fresh.ledger().entries(budget.id).single().accountId)
            assertEquals(groceries.id, fresh.ledger().entries(budget.id).single().categoryId)
        } finally { fresh.close() }
    }

    @Test fun promoteKeepsDataAndQueuesAtomicWrites() = runBlocking {
        val date = LocalDate.parse("2025-01-15")
        repo.saveEntry(budget.id, EntryDraft(EntryType.EXPENSE, BigDecimal("20"), date, cash.id, groceries.id, "RSD"))
        repo.principal.value = id()
        repo.promote(budget.id)
        val entry = repo.dao.entries(budget.id).single()
        assertEquals(repo.principal.value, entry.payer)
        val outbox = repo.dao.pending()
        assertEquals("budget", outbox.first().kind)
        assertTrue(outbox.any { it.kind == "entry" && it.recordId == entry.id && it.baseVersion == 0L })
        repo.principal.value = id()
        assertFalse(repo.dao.budgets(repo.principal.value).first().any { it.id == budget.id })
    }

    @Test fun reportUsesAllTenThousandTransactions() = runBlocking {
        val day = LocalDate.parse("2025-01-15").toString()
        db.withTransaction {
            repeat(10_000) {
                repo.dao.put(Entry(id(), budget.id, cash.id, groceries.id, EntryType.EXPENSE, "1", "RSD",
                    "RSD", "RSD", day, accountAmount = "1", budgetAmount = "1"))
            }
        }
        val ledger = repo.observe(budget.id).first()
        assertEquals(10_000, ledger.entries.size)
        assertEquals("10000", totals(ledger.entries, budget.currency).expenses.storage())
    }
}
