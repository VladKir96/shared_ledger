package dev.sharedledger

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sharedledger.data.Account
import dev.sharedledger.data.Budget
import dev.sharedledger.data.Category
import dev.sharedledger.data.Rate
import dev.sharedledger.domain.EntryType
import dev.sharedledger.domain.id
import dev.sharedledger.presentation.LedgerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BudgetValuationUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun editEitherBudgetFieldRestoreReferenceAndReuseManualRate() {
        lateinit var vm: LedgerViewModel
        ui.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[LedgerViewModel::class.java] }
        val repo = vm.repository
        val budget = Budget(id(), "Budget valuation fixture", "RSD")
        val account = Account(id(), budget.id, "USD valuation wallet", "USD")
        val day = LocalDate.now().toString()
        val previous = runBlocking {
            repo.initialize()
            val before = repo.dao.preferences()
            repo.dao.put(budget)
            repo.saveAccount(account)
            repo.saveCategory(Category(id(), budget.id, "Valuation salary", EntryType.INCOME, "wallet", 1))
            repo.dao.put(Rate("USD", "RSD", day, day, "117", "Fixture"))
            repo.preferences { it.copy(activeBudget = budget.id, lastAccount = account.id) }
            before
        }
        try {
            await(hasText(budget.name))
            click(ui.onNodeWithTag("add-income"))
            replace("transaction-amount", "100")
            assertValue("budget-rate", "117")
            assertValue("budget-amount", "11700")
            replace("budget-rate", "114,50")
            assertValue("budget-amount", "11450")
            replace("budget-amount", "11400")
            assertValue("budget-rate", "114")
            replace("transaction-amount", "200")
            assertValue("budget-amount", "22800")
            click(ui.onNodeWithText("Use reference rate").performScrollTo())
            assertValue("budget-amount", "23400")
            replace("transaction-amount", "100")
            replace("budget-rate", "114")
            click(ui.onNode(hasText("Choose category") and hasClickAction()).performScrollTo())
            val salary = hasText("Valuation salary") and hasClickAction()
            await(salary)
            click(ui.onNode(salary))
            click(ui.onNodeWithText("Save income").assertIsEnabled())
            await(hasTestTag("add-income"))
            click(ui.onNodeWithTag("add-income"))
            replace("transaction-amount", "200")
            assertValue("budget-rate", "114")
            assertValue("budget-amount", "22800")
            ui.onNodeWithText("Last manually confirmed rate", substring = true).assertExists()
        } finally {
            if (previous != null) runBlocking {
                repo.preferences { it.copy(activeBudget = previous.activeBudget, lastAccount = previous.lastAccount) }
            }
        }
    }

    private fun replace(tag: String, value: String) =
        ui.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)

    private fun assertValue(tag: String, value: String) {
        await(hasTestTag(tag) and hasText(value))
        ui.onNodeWithTag(tag).assertTextContains(value)
    }

    private fun click(node: SemanticsNodeInteraction) = node.performSemanticsAction(SemanticsActions.OnClick)

    private fun await(matcher: SemanticsMatcher) {
        ui.waitUntil(12_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
}
