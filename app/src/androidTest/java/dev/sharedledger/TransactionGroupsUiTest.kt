package dev.sharedledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sharedledger.data.Account
import dev.sharedledger.data.Budget
import dev.sharedledger.data.BudgetValuationEdit
import dev.sharedledger.data.Category
import dev.sharedledger.data.EntryDraft
import dev.sharedledger.domain.*
import dev.sharedledger.presentation.LedgerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TransactionGroupsUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun collapsedTotalsFollowEditsFiltersAndTransferFocus() {
        lateinit var vm: LedgerViewModel
        ui.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[LedgerViewModel::class.java] }
        val repo = vm.repository
        val day = LocalDate.now()
        val budget = Budget(id(), "History totals fixture", "RSD")
        val cash = Account(id(), budget.id, "Cash fixture", "RSD")
        val usd = Account(id(), budget.id, "USD fixture", "USD")
        val jpy = Account(id(), budget.id, "JPY fixture", "JPY")
        val food = Category(id(), budget.id, "History groceries", EntryType.EXPENSE, "basket", 1)
        val salary = Category(id(), budget.id, "History salary", EntryType.INCOME, "wallet", 1)
        val expense = EntryDraft(EntryType.EXPENSE, BigDecimal("2500"), day, cash.id, food.id, "RSD", note = "Market groceries")
        val previous = runBlocking {
            repo.initialize()
            val saved = repo.dao.preferences()
            repo.dao.put(budget)
            listOf(cash, usd, jpy).forEach { repo.saveAccount(it) }
            listOf(food, salary).forEach { repo.saveCategory(it) }
            repo.saveEntry(budget.id, expense)
            repo.saveEntry(budget.id, expense.copy(amount = BigDecimal("20"), accountId = usd.id, currency = "USD",
                note = "Market coffee", budgetValuation = BudgetValuationEdit.Amount(BigDecimal("2300"))))
            repo.saveEntry(budget.id, expense.copy(type = EntryType.INCOME, amount = BigDecimal("5000"), categoryId = salary.id))
            repo.saveEntry(budget.id, expense.copy(amount = BigDecimal("100"), accountId = jpy.id, currency = "JPY", note = "Pending"))
            repo.saveEntry(budget.id, expense.copy(amount = BigDecimal("400"), date = day.minusDays(1), note = "Earlier"))
            repo.preferences { it.copy(activeBudget = budget.id) }
            saved
        }
        val savedFilters = vm.filters.value
        val savedPeriod = vm.period.value
        try {
            vm.filters.value = Filters()
            vm.period.value = Period(PeriodMode.DAY, day)
            await(hasText(budget.name))
            click(ui.onNodeWithTag("tab-history"))
            showGroup(day)
            assertHeader(day, "Income · +${money(BigDecimal("5000"), "RSD")}")
            assertHeader(day, expenseLine("4800"))
            assertHeader(day, "Partial total · 1 rate pending")
            ui.onNodeWithContentDescription("4 transactions", useUnmergedTree = true).assertExists()
            ui.onNodeWithTag("history-group-$day").assert(hasStateDescription("Collapsed"))
            ui.onNodeWithText(food.name).assertDoesNotExist()
            assertHeader(day, expenseLine("4800"))

            val created = runBlocking {
                repo.saveEntry(budget.id, expense.copy(amount = BigDecimal("50"), note = "Added"))
                repo.dao.entries(budget.id).single { it.note == "Added" }
            }
            assertHeader(day, expenseLine("4850"))
            runBlocking { repo.saveEntry(budget.id, expense.copy(amount = BigDecimal("75")), created.id) }
            assertHeader(day, expenseLine("4875"))
            runBlocking { repo.deleteEntry(created) }
            assertHeader(day, expenseLine("4800"))
            ui.onNodeWithText(food.name).assertDoesNotExist()
            click(ui.onNodeWithTag("history-group-$day"))
            ui.onNodeWithTag("history-list").performScrollToNode(hasText(food.name))
            ui.onAllNodesWithText(food.name).onFirst().assertIsDisplayed()

            val search = hasText("Search notes and categories")
            ui.onNodeWithTag("history-list").performScrollToNode(search)
            ui.onNode(search).performTextInput("Market coffee")
            ui.onNode(search).performImeAction()
            showGroup(day)
            assertHeader(day, "−${money(BigDecimal("2300"), "RSD")}")
            vm.period.value = Period(PeriodMode.DAY, day.minusDays(1))
            awaitAbsent(hasTestTag("history-group-$day"))
            await(hasText("No matching transactions"))
            vm.filters.value = Filters()
            ui.waitUntil(12_000) { vm.state.value.entries.singleOrNull()?.note == "Earlier" }
            showGroup(day.minusDays(1))
            assertHeader(day.minusDays(1), "−${money(BigDecimal("400"), "RSD")}")

            runBlocking {
                repo.saveEntry(budget.id, EntryDraft(EntryType.TRANSFER, BigDecimal("20"), day, usd.id, null, "USD",
                    destinationId = cash.id, destinationAmount = BigDecimal("2300")))
            }
            vm.filters.value = Filters(type = EntryType.TRANSFER)
            vm.period.value = Period(PeriodMode.DAY, day)
            ui.waitUntil(12_000) { vm.state.value.entries.singleOrNull()?.type == EntryType.TRANSFER }
            showGroup(day)
            assertHeader(day, "Transfers sent")
            assertHeader(day, money(BigDecimal("20"), "USD"))
        } finally {
            vm.filters.value = savedFilters
            vm.period.value = savedPeriod
            if (previous != null) runBlocking {
                repo.preferences { it.copy(activeBudget = previous.activeBudget, lastAccount = previous.lastAccount) }
            }
        }
    }

    private fun expenseLine(amount: String) = "Expenses · −${money(BigDecimal(amount), "RSD")}"

    private fun showGroup(day: LocalDate) {
        val tag = hasTestTag("history-group-$day")
        ui.onNodeWithTag("history-list").performScrollToNode(tag)
        ui.onNode(tag).assertIsDisplayed()
    }

    private fun assertHeader(day: LocalDate, text: String) {
        await(hasTestTag("history-group-$day") and hasText(text))
        ui.onNodeWithTag("history-group-$day").assertTextContains(text)
    }

    private fun click(node: SemanticsNodeInteraction) = node.performSemanticsAction(SemanticsActions.OnClick)
    private fun await(matcher: SemanticsMatcher) = ui.waitUntil(12_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitAbsent(matcher: SemanticsMatcher) = ui.waitUntil(12_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
}
