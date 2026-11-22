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
import dev.sharedledger.domain.money
import dev.sharedledger.presentation.LedgerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BankEditorUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun enterTwoAmountsAndConfirmBothDirections() {
        lateinit var vm: LedgerViewModel
        ui.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[LedgerViewModel::class.java] }
        val repo = vm.repository
        val previous = runBlocking {
            repo.initialize()
            repo.dao.preferences()
        }
        val budget = Budget(id(), "Bank editor fixture", "RSD")
        val account = Account(id(), budget.id, "USD test account", "USD")
        val day = LocalDate.now().toString()
        try {
            runBlocking {
                repo.dao.put(budget)
                repo.saveAccount(account)
                repo.saveCategory(Category(id(), budget.id, "Bank groceries", EntryType.EXPENSE, "basket", 1))
                repo.saveCategory(Category(id(), budget.id, "Bank salary", EntryType.INCOME, "wallet", 1))
                repo.dao.put(Rate("RSD", "USD", day, day, "0.0085", "Fixture"))
                repo.dao.put(Rate("RUB", "USD", day, day, "0.0104", "Fixture"))
                repo.dao.put(Rate("RUB", "RSD", day, day, "1.17", "Fixture"))
                repo.preferences { it.copy(activeBudget = budget.id, lastAccount = account.id) }
            }
            await(hasText(budget.name))
            open(EntryType.EXPENSE, "RSD", "2500")
            assertAccount("21.25")
            correct("21.50", "116.2791")
            ui.onNodeWithTag("transaction-amount").assertTextContains("2500")
            ui.onNodeWithText("Note (optional)").performScrollTo().performTextInput("Bank debit")
            assertAccount("21.5")
            save("Bank groceries", "Save expense")

            open(EntryType.EXPENSE, "RSD", "3000")
            assertAccount("25.8")
            await(hasText("Last bank rate", substring = true))
            correct("25.72")
            save("Bank groceries", "Save expense")

            open(EntryType.INCOME, "RUB", "100000")
            assertAccount("1040")
            correct("1050", "95.2381")
            save("Bank salary", "Save income")

            open(EntryType.INCOME, "RUB", "110000")
            assertAccount("1155")
            await(hasText("Last bank rate", substring = true))
            correct("1153")
            save("Bank salary", "Save income")

            click(ui.onNodeWithTag("tab-history"))
            val expense = "−" + money("2500".toBigDecimal(), "RSD")
            val income = "≈ +" + money("117000".toBigDecimal(), "RSD")
            ui.onNodeWithTag("history-list").performScrollToNode(hasText(expense))
            ui.onNodeWithText(expense).assertIsDisplayed()
            ui.onNodeWithText("−" + money("21.50".toBigDecimal(), "USD")).assertIsDisplayed()
            ui.onNodeWithTag("history-list").performScrollToNode(hasText(income))
            ui.onNodeWithText(income).assertIsDisplayed()
            ui.onNodeWithText("+" + money("1050".toBigDecimal(), "USD") + " · +" +
                money("100000".toBigDecimal(), "RUB")).assertIsDisplayed()
        } finally {
            if (previous != null) runBlocking {
                repo.preferences { it.copy(activeBudget = previous.activeBudget, lastAccount = previous.lastAccount) }
            }
        }
    }

    private fun open(type: EntryType, currency: String, amount: String) {
        val tag = if (type == EntryType.EXPENSE) "add-expense" else "add-income"
        await(hasTestTag(tag))
        click(ui.onNodeWithTag(tag))
        choose("Currency", currency)
        ui.onNodeWithTag("transaction-amount").performScrollTo().performTextReplacement(amount)
    }

    private fun assertAccount(expected: String) {
        await(hasTestTag("bank-account-amount") and hasText(expected))
        ui.onNodeWithTag("bank-account-amount").assertTextContains(expected)
    }

    private fun correct(amount: String, rate: String? = null) {
        ui.onNodeWithTag("bank-account-amount").performScrollTo().performTextReplacement(amount)
        if (rate != null) ui.onNodeWithTag("effective-bank-rate").assertTextContains(rate, substring = true)
        click(ui.onNodeWithTag("confirm-bank-amount").performScrollTo())
        ui.onNodeWithText("Bank actual · Confirmed").assertExists()
    }

    private fun save(category: String, button: String) {
        choose("Choose category", category)
        click(ui.onNodeWithText(button).assertIsEnabled())
        await(hasTestTag("add-expense"))
    }

    private fun choose(selector: String, option: String) {
        click(ui.onNode(hasText(selector) and hasClickAction()).performScrollTo())
        val item = hasText(option) and hasClickAction()
        await(item)
        click(ui.onNode(item))
    }

    private fun click(node: SemanticsNodeInteraction) = node.performSemanticsAction(SemanticsActions.OnClick)

    private fun await(matcher: SemanticsMatcher) {
        ui.waitUntil(15_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
}
