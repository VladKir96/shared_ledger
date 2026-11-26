package dev.sharedledger

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sharedledger.domain.EntryType
import dev.sharedledger.presentation.LedgerViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun enterExpenseAndIncomeThenFilterHistory() {
        ui.waitUntil(20_000) { ui.onAllNodesWithTag("add-expense", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("add-expense").performSemanticsAction(SemanticsActions.OnClick)
        val amount = ui.onNodeWithTag("transaction-amount")
        amount.performClick().performTextInput("12,50")
        ui.waitUntil(8_000) {
            ui.runOnIdle {
                val insets = ui.activity.window.decorView.rootWindowInsets
                insets != null && WindowInsetsCompat.toWindowInsetsCompat(insets).isVisible(WindowInsetsCompat.Type.ime())
            }
        }
        ui.onNodeWithText("Save expense").assertIsDisplayed()
        ui.onNodeWithTag("transaction-amount-calculator").performClick()
        ui.onNodeWithTag("calculator-key-C").performScrollTo().performClick()
        ui.onNodeWithText("Cancel").performClick()
        amount.assertTextContains("12,50")
        amount.performClick()
        ui.runOnIdle {
            ui.activity.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Test amount", "2\u202f500,25"))
        }
        amount.performTextClearance()
        amount.performSemanticsAction(SemanticsActions.PasteText)
        amount.assertTextContains("2\u202f500,25")
        selectCategory("Groceries")
        ui.onNodeWithText("Note (optional)").performScrollTo().performTextInput("UI groceries")
        ui.onNodeWithText("Save expense").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("add-income").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("add-income").performSemanticsAction(SemanticsActions.OnClick)
        amount.performTextInput("2")
        ui.onNodeWithTag("transaction-amount-calculator").performClick()
        listOf("+", "1", "000", "=").forEach { key ->
            ui.onNodeWithTag("calculator-key-$key").performScrollTo().performClick()
        }
        ui.onNodeWithTag("calculator-result").assertTextContains("1,002")
        ui.onNodeWithTag("calculator-apply").performClick()
        amount.assertTextContains("1002")
        selectCategory("Salary")
        ui.onNodeWithText("Save income").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("add-income").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("tab-history").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("Search notes and categories").performTextInput("UI groceries")
        ui.onNodeWithText("Search notes and categories").performImeAction()
        lateinit var vm: LedgerViewModel
        ui.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[LedgerViewModel::class.java] }
        ui.waitUntil(8_000) {
            val state = vm.state.value
            state.filters.query == "UI groceries" && state.entries.isNotEmpty() && state.entries.none { it.type == EntryType.INCOME }
        }
        ui.onNodeWithTag("history-list").performScrollToNode(hasTestTag("expand-all-groups"))
        ui.onNodeWithTag("expand-all-groups").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithTag("history-list").performScrollToNode(hasText("Groceries"))
        ui.onNodeWithText("Groceries", substring = false).assertIsDisplayed()
        ui.onNodeWithText("Salary", substring = false).assertDoesNotExist()
        ui.onNodeWithTag("tab-analytics").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("Period summary").assertIsDisplayed()
    }

    private fun selectCategory(name: String) {
        ui.onNode(hasText("Choose category") and hasClickAction()).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        val category = hasText(name) and hasClickAction()
        ui.waitUntil(8_000) { ui.onAllNodes(category).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(category).performSemanticsAction(SemanticsActions.OnClick)
    }
}
