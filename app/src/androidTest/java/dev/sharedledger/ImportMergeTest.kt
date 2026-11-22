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

@RunWith(AndroidJUnit4::class)
class ImportMergeTest {
    @get:TestRule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)

    @Test fun ownersAndEditorsMergePreservingMembershipMetadataTransactionsAndRates() = runBlocking {
        for (role in listOf(Role.OWNER, Role.EDITOR)) withBudget(role) { fixture ->
            val (repo, files, target, cash, groceries) = fixture
            val custom = Category(id(), target.id, "Custom", EntryType.EXPENSE, "chart", 456, 64, archived = true)
            val before = expense(target.id, cash.id, groceries.id, "12", "Existing")
            val member = Member(target.id, target.owner!!, "Owner", Role.OWNER)
            repo.dao.put(custom)
            repo.dao.put(before)
            repo.dao.put(member)
            val source = source()
            val destination = ImportDestination.Existing(target.id)
            val preview = files.decodeJson(files.encode(source, false))
            val reviewed = files.reviewMerge(preview, destination)
            assertEquals(3, reviewed.transactions)
            assertEquals(1, reviewed.accountsCreated)
            assertEquals(1, reviewed.accountsReused)
            assertEquals(2, reviewed.categoriesCreated)
            assertEquals(1, reviewed.categoriesReused)
            files.applyMerge(preview, destination, reviewed)

            assertEquals(target, repo.dao.budget(target.id))
            assertNull(repo.dao.budget(source.budgets.single().budget.id))
            assertEquals(listOf(member), repo.dao.membersOnce(target.id))
            assertEquals(cash, repo.dao.account(cash.id))
            assertEquals(groceries, repo.dao.category(groceries.id))
            assertEquals(custom, repo.dao.category(custom.id))
            assertEquals(before, repo.dao.entry(before.id))
            assertEquals(target.id, repo.dao.preferences()?.activeBudget)
            val stored = repo.dao.entries(target.id)
            val purchase = stored.single { it.note == "Imported historical expense" }
            assertEquals(groceries.id, purchase.categoryId)
            assertEquals("20.05", purchase.amount)
            assertEquals("EUR", purchase.currency)
            assertEquals("2026-01-02", purchase.date)
            assertEquals("117.2", purchase.budgetRate)
            assertEquals("2349.86", purchase.budgetAmount)
            val foodGroup = transactionGroups(stored, target.currency) { it.categoryId.orEmpty() }.single { it.key == groceries.id }
            assertEquals(2, foodGroup.entries.size)
            assertEquals(BigDecimal("2361.86"), foodGroup.totals.expenses)
            val card = repo.dao.accounts(target.id).single { it.currency == "EUR" }
            assertEquals("Cash", card.name)
            assertEquals(AccountType.CASH, card.type)
            val gift = stored.single { it.type == EntryType.INCOME }
            assertEquals(EntryType.INCOME, repo.dao.category(gift.categoryId!!)?.type)
            val transfer = stored.single { it.type == EntryType.TRANSFER }
            assertEquals(cash.id, transfer.accountId)
            assertEquals(card.id, transfer.destinationId)
            assertEquals("2.5", transfer.destinationAmount)
            assertNull(transfer.budgetAmount)
            assertTrue(stored.all { it.budgetId == target.id })
            assertNull(gift.payer)
            assertEquals(6, repo.dao.pending().size)
            assertTrue(repo.dao.pending().all { it.imported && it.budgetId == target.id && it.baseVersion == 0L })
            assertEquals(BigDecimal("768.14"), accountBalance(cash, stored).value.stripTrailingZeros())
            assertEquals(BigDecimal("29.5"), accountBalance(card, stored).value.stripTrailingZeros())

            repo.dao.put(groceries.copy(name = "Renamed Groceries"))
            repo.dao.put(cash.copy(name = "Renamed Cash"))
            val repeat = files.reviewMerge(preview, destination)
            assertEquals(3, repeat.duplicates)
            assertEquals(0, repeat.transactions)
            assertEquals(0, repeat.accountsCreated)
            assertEquals(0, repeat.categoriesCreated)
            files.applyMerge(preview, destination, repeat)
            assertEquals(4, repo.dao.entries(target.id).size)

            val scoped = source.budgets.single()
            val changed = ImportPreview(source.copy(budgets = listOf(scoped.copy(entries = scoped.entries.map {
                if (it.type == EntryType.EXPENSE) it.copy(note = "Changed at source") else it
            }))))
            val conflict = files.reviewMerge(changed, destination)
            assertEquals(1, conflict.attention)
            assertEquals(0, conflict.transactions)
            assertEquals(2, conflict.duplicates)
            files.applyMerge(changed, destination, conflict)
            assertEquals(purchase, repo.dao.entry(purchase.id))
        }
    }

    @Test fun viewersAndChangesAfterPreviewCannotWrite() = runBlocking {
        withBudget(Role.EDITOR) { (repo, files, target) ->
            val preview = ImportPreview(source())
            val destination = ImportDestination.Existing(target.id)
            val review = files.reviewMerge(preview, destination)
            repo.dao.put(target.copy(role = Role.VIEWER))
            expectImportFailure { files.applyMerge(preview, destination, review) }
            expectImportFailure { files.reviewMerge(preview, destination) }
            assertEquals(0, repo.dao.pending().size)
            assertEquals(0, repo.dao.entries(target.id).size)
            assertEquals(1, repo.dao.accounts(target.id).size)
            assertEquals(startingCategories(target.id).size, repo.dao.categories(target.id).size)
        }
    }

    @Test fun newPersonalAndSharedBudgetsSeedDefaultsAndReuseTheSameDestination() = runBlocking {
        withBudget(Role.OWNER) { (repo, files) ->
            val preview = ImportPreview(source())
            for (destination in listOf(ImportDestination.NewPersonal, ImportDestination.NewShared(checkNotNull(repo.principal.value)))) {
                val review = files.reviewMerge(preview, destination)
                val saved = files.applyMerge(preview, destination, review)
                val categories = repo.dao.categories(saved.budget.id)
                assertEquals(1, categories.count { it.name.equals("groceries", true) && it.type == EntryType.EXPENSE })
                assertTrue(categories.any { it.name == "Rent" })
                assertTrue(categories.any { it.name == "Travel" })
                assertTrue(repo.dao.membersOnce(saved.budget.id).isEmpty())
                assertEquals(3, files.reviewMerge(preview, destination).duplicates)
                assertEquals(saved.budget.id, files.reviewMerge(preview, destination).budget.id)
                if (destination is ImportDestination.NewShared) {
                    assertEquals(BudgetType.SHARED, saved.budget.type)
                    assertEquals("budget", repo.dao.pending().first { it.budgetId == saved.budget.id }.kind)
                } else assertNull(saved.budget.principal)
            }
        }
    }

    @Test fun largeCsvKeepsIdenticalPurchasesAndDoesNotDuplicateOnReimport() = runBlocking {
        withBudget(null) { (repo, files, budget, cash, groceries) ->
            val rows = 3_000
            val csv = buildString {
                appendLine("Date,Amount,Account,Category")
                repeat(rows) { appendLine("2026-01-02,-1.005,Cash,Groceries") }
            }
            val preview = files.decodeCsv(files.parseCsv(csv),
                CsvMapping("Date", "Amount", null, null, "Category", "Account", null, null), "CSV", "RSD")
            val destination = ImportDestination.Existing(budget.id)
            val first = files.reviewMerge(preview, destination)
            assertEquals(rows, first.transactions)
            files.applyMerge(preview, destination, first)
            val saved = repo.dao.entries(budget.id)
            assertEquals(rows, saved.size)
            assertTrue(saved.all { it.amount == "1.005" && it.accountId == cash.id && it.categoryId == groceries.id })
            assertEquals(rows, files.reviewMerge(preview, destination).duplicates)
        }
    }

    @Test fun roomMigrationRetainsExistingBudgetsAndAddsImportLinks() {
        migrations.createDatabase("import-upgrade", 3).use {
            it.execSQL("INSERT INTO budgets (id,name,currency,type,icon,color,role,createdAt,updatedAt) VALUES ('b','Legacy','RSD','PERSONAL','wallet',1,'OWNER',1,1)")
            it.execSQL("INSERT INTO outbox (kind,recordId,budgetId,operationId,body,baseVersion,rateOnly) VALUES ('budget','b','b','operation','{}',0,0)")
        }
        migrations.runMigrationsAndValidate("import-upgrade", 4, true, LedgerDatabase.MIGRATION_3_4).use {
            it.query("SELECT name, imported FROM budgets JOIN outbox ON budgets.id=outbox.budgetId").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Legacy", row.getString(0))
                assertEquals(0, row.getInt(1))
            }
        }
    }

    private data class Fixture(val repo: LedgerRepository, val files: Portability, val budget: Budget, val cash: Account, val groceries: Category)

    private suspend fun withBudget(role: Role?, test: suspend (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db, Rates(db, emptyList()))
            val actor = id()
            repo.principal.value = actor.takeIf { role != null }
            val budget = Budget(id(), "Target", "RSD", type = if (role == null) BudgetType.PERSONAL else BudgetType.SHARED,
                principal = repo.principal.value, owner = if (role == Role.OWNER) actor else id().takeIf { role != null }, role = role ?: Role.OWNER)
            repo.dao.put(budget)
            val cash = Account(id(), budget.id, "Cash", "RSD", opening = "5420", icon = "wallet", color = 123)
            repo.dao.put(cash)
            val categories = startingCategories(budget.id)
            for (category in categories) repo.dao.put(category)
            test(Fixture(repo, Portability(repo), budget, cash, categories.single { it.name == "Groceries" }))
        } finally {
            db.close()
        }
    }

    private fun source(): Archive {
        val budget = Budget(id(), "Archive", "RSD")
        val cash = Account(id(), budget.id, "cash", "RSD")
        val card = Account(id(), budget.id, "Cash", "EUR")
        val groceries = Category(id(), budget.id, " groceries ", EntryType.EXPENSE, "other", 1)
        val income = Category(id(), budget.id, "Groceries", EntryType.INCOME, "gift", 11)
        val other = Category(id(), budget.id, "Other label", EntryType.EXPENSE, "gift", 98)
        val expense = expense(budget.id, cash.id, groceries.id, "2349.86", "Imported historical expense")
            .copy(amount = "20.05", currency = "EUR", accountRate = "117.2", budgetRate = "117.2")
        val deposit = Entry(id(), budget.id, card.id, income.id, EntryType.INCOME, "27", "EUR", "EUR", "RSD", "2026-01-02",
            accountAmount = "27", accountRate = "1", accountRateDate = "2026-01-02", accountRateSource = "Same currency", payer = id())
        val transfer = Entry(id(), budget.id, cash.id, null, EntryType.TRANSFER, "2290", "RSD", "RSD", "RSD", "2026-01-02",
            accountAmount = "2290", accountRate = "1", accountRateDate = "2026-01-02", accountRateSource = "Same currency",
            destinationId = card.id, destinationCurrency = "EUR", destinationAmount = "2.5", transferRate = "0.0010917")
        return Archive(budgets = listOf(BudgetArchive(budget, listOf(cash, card), listOf(groceries, income, other),
            listOf(expense, deposit, transfer), emptyList(), listOf(Member(budget.id, id(), "Source Member", Role.EDITOR)))))
    }

    private fun expense(budget: String, account: String, category: String, amount: String, note: String) =
        Entry(id(), budget, account, category, EntryType.EXPENSE, amount, "RSD", "RSD", "RSD", "2026-01-02",
            accountAmount = amount, budgetAmount = amount, accountRate = "1", budgetRate = "1", note = note,
            accountRateDate = "2026-01-02", budgetRateDate = "2026-01-02", accountRateSource = "Manual", budgetRateSource = "Manual")

    private suspend fun expectImportFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected the import to be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }
}
