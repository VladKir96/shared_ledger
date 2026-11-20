package dev.sharedledger

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun enterExpenseAndIncomeThenFilterHistory() {
        ui.waitUntil(20_000) { ui.onAllNodesWithTag("add-expense", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("add-expense").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("1", substring = false).performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("2", substring = false).performSemanticsAction(SemanticsActions.OnClick)
        selectCategory("Groceries")
        ui.onNodeWithText("Note (optional)").performScrollTo().performTextInput("UI groceries")
        ui.onNodeWithText("Save expense").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("add-income").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("add-income").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("5", substring = false).performSemanticsAction(SemanticsActions.OnClick)
        selectCategory("Salary")
        ui.onNodeWithText("Save income").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("add-income").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("tab-history").performSemanticsAction(SemanticsActions.OnClick)
        ui.onNodeWithText("Search notes and categories").performTextInput("UI groceries")
        ui.onNodeWithText("Search notes and categories").performImeAction()
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
