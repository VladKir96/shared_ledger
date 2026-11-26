package dev.sharedledger

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.data.Account
import dev.sharedledger.data.Budget
import dev.sharedledger.data.Category
import dev.sharedledger.data.Entry
import dev.sharedledger.domain.*
import dev.sharedledger.presentation.LedgerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AnalyticsHistoryUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun showsDailyHistoryForMultipleSeriesAndKeepsNativeCurrencyAxes() {
        lateinit var vm: LedgerViewModel
        ui.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[LedgerViewModel::class.java] }
        val repo = vm.repository
        val start = LocalDate.of(2026, 10, 1)
        val openingDate = start.minusMonths(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val budget = Budget(id(), "Household history", "RSD")
        val cash = Account(id(), budget.id, "Everyday", "RSD", opening = "95000", color = 0xFF397D63, createdAt = openingDate)
        val savings = Account(id(), budget.id, "Savings", "RSD", opening = "65000", color = 0xFF9270AC,
            order = 1, createdAt = openingDate)
        val dollars = Account(id(), budget.id, "USD wallet", "USD", opening = "250", order = 2, createdAt = openingDate)
        val groceries = Category(id(), budget.id, "Groceries history", EntryType.EXPENSE, "basket", 0xFF427B62)
        val transport = Category(id(), budget.id, "Transport history", EntryType.EXPENSE, "train", 0xFFBB6765)
        val salary = Category(id(), budget.id, "Salary history", EntryType.INCOME, "wallet", 0xFF6986B5)
        fun entry(day: Int, account: Account, category: Category, amount: String, budgetAmount: String = amount) =
            Entry(id(), budget.id, account.id, category.id, category.type, amount, account.currency, account.currency,
                budget.currency, start.plusDays(day.toLong()).toString(), accountAmount = amount, budgetAmount = budgetAmount)
        val previous = runBlocking {
            repo.initialize()
            val saved = repo.dao.preferences()
            repo.dao.put(budget)
            listOf(cash, savings, dollars).forEach { repo.dao.put(it) }
            listOf(groceries, transport, salary).forEach { repo.dao.put(it) }
            listOf(
                entry(-3, cash, salary, "25000"), entry(0, cash, groceries, "2500"), entry(2, savings, groceries, "6000"),
                entry(4, cash, transport, "1400"), entry(5, cash, groceries, "5800"), entry(7, savings, salary, "14000"),
                entry(8, dollars, transport, "13", "1350"), entry(10, cash, transport, "3000"), entry(12, cash, groceries, "4100"),
                entry(16, cash, salary, "33000"), entry(17, cash, groceries, "7000"), entry(18, savings, transport, "3300"),
                entry(20, cash, groceries, "3200"), entry(22, cash, transport, "1500"), entry(23, cash, groceries, "5400"),
                entry(26, savings, groceries, "3400"), entry(28, cash, transport, "6500"), entry(30, cash, groceries, "2100")
            ).forEach { repo.dao.put(it) }
            repo.preferences { it.copy(activeBudget = budget.id, theme = ThemeMode.LIGHT, dynamicColor = false) }
            saved
        }
        val savedPeriod = vm.period.value
        val savedFilters = vm.filters.value
        val balance = "account-history-chart-RSD"
        val categories = "category-history-chart"
        try {
            vm.filters.value = Filters()
            vm.period.value = Period(PeriodMode.MONTH, start)
            ui.waitUntil(12_000) { vm.state.value.budget?.id == budget.id && vm.state.value.entries.size == 17 }
            ui.onNodeWithTag("tab-analytics").performClick()
            show("history-picker-accounts")
            select("history-picker-accounts", "Everyday · RSD", "Savings · RSD")
            show(balance)
            point(balance, .0f)
            description(balance, "Opening balance", "120,000.00 RSD", "65,000.00 RSD")
            point(balance, 1.5f / 32f)
            description(balance, "117,500.00 RSD")
            point(balance, 2.5f / 32f)
            description(balance, "117,500.00 RSD")
            point(balance, .7f)
            show("account-history-panel")
            capture("account-balance-history.png")
            select("history-picker-accounts", "USD wallet · USD")
            show("account-history-chart-USD")
            description("account-history-chart-USD", "237.00 USD")

            show("history-picker-categories")
            select("history-picker-categories", "Groceries history", "Transport history")
            show(categories)
            point(categories, .01f)
            description(categories, "Groceries history, 2,500.00 RSD", "Transport history, 0.00 RSD")
            point(categories, 1.5f / 31f)
            description(categories, "Groceries history, 0.00 RSD")
            point(categories, 17.5f / 31f)
            description(categories, "7,000.00 RSD")
            show("category-history-panel")
            capture("category-history.png")
            runBlocking { repo.preferences { it.copy(theme = ThemeMode.DARK) } }
            ui.waitUntil(8_000) { vm.state.value.preferences.theme == ThemeMode.DARK }
            show("category-history-panel")
            capture("category-history-dark.png")

            vm.filters.value = Filters(account = dollars.id)
            ui.waitUntil(8_000) { vm.state.value.entries.singleOrNull()?.accountId == dollars.id }
            show(categories)
            point(categories, 8.5f / 31f)
            description(categories, "Transport history, ≈ 1,350.00 RSD")
            vm.filters.value = Filters()
            show("category-history-income")
            ui.onNodeWithTag("category-history-income").performClick()
            show(categories)
            point(categories, 16.5f / 31f)
            description(categories, "Salary history, 33,000.00 RSD")

            val date = start.plusDays(16)
            vm.period.value = Period(PeriodMode.DAY, date)
            show("category-history-panel")
            ui.waitUntil(8_000) {
                ui.onAllNodes(hasTestTag(categories) and hasContentDescription("33,000.00 RSD", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            runBlocking { repo.dao.put(entry(16, cash, salary, "1500")) }
            ui.waitUntil(8_000) {
                ui.onAllNodes(hasTestTag(categories) and hasContentDescription("34,500.00 RSD", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            vm.period.value = Period(PeriodMode.DAY, LocalDate.now().plusDays(1))
            show("category-history-panel")
            ui.waitUntil(8_000) { ui.onAllNodesWithText("No history in this period").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            vm.filters.value = savedFilters
            vm.period.value = savedPeriod
            if (previous != null) runBlocking {
                repo.preferences { it.copy(activeBudget = previous.activeBudget, theme = previous.theme, dynamicColor = previous.dynamicColor) }
            }
        }
    }

    private fun show(tag: String) = ui.onNodeWithTag("analytics-list").performScrollToNode(hasTestTag(tag))

    private fun select(tag: String, vararg names: String) {
        show(tag)
        ui.onNodeWithTag(tag).performClick()
        for (name in names) {
            val option = ui.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            if (option.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off) option.performClick()
        }
        ui.onNodeWithText("Apply").performClick()
    }

    private fun point(tag: String, fraction: Float) {
        ui.onNodeWithTag(tag).performTouchInput { click(Offset(width * fraction.coerceIn(.005f, .995f), center.y)) }
    }

    private fun description(tag: String, vararg contents: String) {
        for (content in contents) ui.onNodeWithTag(tag).assert(hasContentDescription(content, substring = true))
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("historyScreenshots") != "true") return
        ui.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        instrumentation.targetContext.cacheDir.resolve(name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
