package dev.sharedledger

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sharedledger.data.Account
import dev.sharedledger.data.Budget
import dev.sharedledger.data.Category
import dev.sharedledger.data.EntryDraft
import dev.sharedledger.data.categoryColors
import dev.sharedledger.domain.*
import dev.sharedledger.presentation.LedgerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class OverviewControlsUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun sectorsAndLegendShareSelectionAndOnlyTopPullResetsHome() = withDemo { demo ->
        val chart = hasTestTag("donut-chart")
        await(hasText(demo.budget.name))
        show("home-list", chart)
        await(chart and hasContentDescription("Total expenses", substring = true))
        capture("overview-home.png")
        val housing = chart and hasContentDescription(demo.categories.first().name, substring = true)
        fun tapRightArc(fraction: Float) = ui.onNode(chart).performTouchInput { click(center + Offset(width * fraction, 0f)) }
        val fraction = listOf(.27f, .18f).first { candidate ->
            tapRightArc(candidate)
            ui.onAllNodes(housing).fetchSemanticsNodes().isNotEmpty()
        }
        tapRightArc(fraction)
        await(chart and hasContentDescription("Total expenses", substring = true))

        val label = hasTestTag("donut-label-${demo.categories[1].id}")
        show("home-list", label)
        click(ui.onNode(label))
        await(chart and hasContentDescription(demo.categories[1].name, substring = true))
        show("home-list", hasTestTag("donut-open-legend"))
        click(ui.onNodeWithTag("donut-open-legend"))
        click(ui.onNodeWithTag("donut-legend-${demo.categories[2].id}"))
        await(chart and hasContentDescription(demo.categories[2].name, substring = true))

        demo.vm.filters.value = Filters(account = demo.account.id)
        ui.onNodeWithTag("home-list").performScrollToIndex(1)
        pull("home-list")
        assertEquals(demo.account.id, demo.vm.filters.value.account)
        show("home-list", chart)
        await(chart and hasContentDescription(demo.categories[2].name, substring = true))

        ui.onNodeWithTag("home-list").performScrollToIndex(0)
        pull("home-list")
        ui.waitUntil(8_000) { demo.vm.filters.value == Filters() }
        show("home-list", chart)
        await(chart and hasContentDescription("Total expenses", substring = true))
        assertOverview(demo)

        val last = runBlocking {
            var added = demo.categories.last()
            repeat(24) { index ->
                added = Category(id(), demo.budget.id, "Small category ${index + 1}", EntryType.EXPENSE, "basket", categoryColors[index % categoryColors.size])
                demo.vm.repository.saveCategory(added)
                val amount = if (index == 23) "0.01" else "1"
                demo.vm.repository.saveEntry(demo.budget.id, EntryDraft(EntryType.EXPENSE, amount.toBigDecimal(), demo.today,
                    demo.account.id, added.id, "RSD"))
            }
            added
        }
        ui.waitUntil(8_000) { demo.vm.state.value.entries.any { it.categoryId == last.id } }
        val majorLabels = demo.categories.take(3).map { hasTestTag("donut-label-${it.id}") }
        for (major in majorLabels) {
            show("home-list", major)
            ui.onNode(major).assertIsDisplayed()
        }
        val lastLabel = hasTestTag("donut-label-${last.id}")
        ui.onNode(lastLabel).assertDoesNotExist()
        capture("overview-dense-donut.png")

        show("home-list", hasTestTag("donut-open-legend"))
        click(ui.onNodeWithTag("donut-open-legend"))
        val lastRow = hasTestTag("donut-legend-${last.id}")
        ui.onNodeWithTag("donut-legend").performScrollToNode(lastRow)
        click(ui.onNode(lastRow))
        await(chart and hasContentDescription(last.name, substring = true))
        show("home-list", lastLabel)
        ui.onNode(lastLabel).assertIsDisplayed().assertIsSelected()
        ui.onNode(chart).assert(hasContentDescription("<0.1%", substring = true))
        ui.onNode(chart).assert(hasContentDescription(money("0.01".toBigDecimal(), "RSD"), substring = true))
        ui.onNode(majorLabels.first()).assertExists()
        capture("overview-dense-donut-selected.png")
        val details = hasAnyAncestor(hasTestTag("donut-details"))
        val detailTitle = details and hasText(last.name)
        val detailAmount = details and hasText(money("0.01".toBigDecimal(), "RSD"))
        show("home-list", detailTitle)
        ui.onNode(detailTitle).performScrollTo().assertIsDisplayed()
        ui.onNode(detailAmount).performScrollTo().assertIsDisplayed()
        show("home-list", hasTestTag("donut-show-all"))
        click(ui.onNodeWithTag("donut-show-all"))
        await(chart and hasContentDescription("Total expenses", substring = true))
        ui.onNode(lastLabel).assertDoesNotExist()
        majorLabels.forEach { ui.onNode(it).assertExists() }
        assertOverview(demo)
    }

    @Test fun groupControlsPreserveHeadersAndResetClearsFiltersWithoutChangingGrouping() = withDemo { demo ->
        await(hasText(demo.budget.name))
        click(ui.onNodeWithTag("tab-history"))
        val dateHeader = hasTestTag("history-group-${demo.today}")
        show("history-list", hasTestTag("collapse-all-groups"))
        click(ui.onNodeWithTag("collapse-all-groups"))
        show("history-list", dateHeader)
        ui.onNode(dateHeader).assertTextContains("−${money("146382".toBigDecimal(), "RSD")}")
        ui.onNodeWithText(demo.categories.first().name).assertDoesNotExist()
        capture("overview-transactions.png")

        click(ui.onNode(dateHeader))
        show("history-list", hasText(demo.categories.first().name))
        show("history-list", hasTestTag("expand-all-groups"))
        click(ui.onNodeWithTag("expand-all-groups"))
        show("history-list", hasText(demo.categories.last().name))
        show("history-list", hasTestTag("collapse-all-groups"))

        val selector = hasText("Group by") and hasClickAction()
        show("history-list", selector)
        click(ui.onNode(selector))
        click(ui.onNode(hasText("Category") and hasClickAction()))
        val categoryHeader = hasTestTag("history-group-${demo.categories.first().id}")
        show("history-list", categoryHeader)

        val search = hasText("Search notes and categories")
        show("history-list", search)
        ui.onNode(search).performTextInput("Market")
        ui.waitUntil(8_000) { demo.vm.filters.value.query == "Market" }
        ui.onNodeWithTag("history-list").performScrollToIndex(0)
        pull("history-list")
        assertEquals("Market", demo.vm.filters.value.query)
        show("history-list", search)
        ui.onNode(search).performImeAction()
        demo.vm.filters.value = demo.vm.filters.value.copy(account = demo.account.id, type = EntryType.EXPENSE,
            category = demo.categories[1].id)
        ui.onNodeWithTag("history-list").performScrollToIndex(0)
        pull("history-list")
        ui.waitUntil(8_000) { demo.vm.filters.value == Filters() }
        show("history-list", categoryHeader)
        assertOverview(demo)

        demo.vm.filters.value = Filters(type = EntryType.INCOME)
        show("history-list", hasTestTag("history-clear-filters"))
        click(ui.onNodeWithTag("history-clear-filters"))
        ui.waitUntil(8_000) { demo.vm.filters.value == Filters() }
        show("history-list", categoryHeader)
        assertOverview(demo)
    }

    private fun assertOverview(demo: Demo) {
        assertEquals(demo.period, demo.vm.period.value)
        assertEquals(demo.budget.id, demo.vm.state.value.budget?.id)
    }

    private fun pull(list: String) = ui.onNodeWithTag(list).performTouchInput {
        swipe(Offset(center.x, height * .045f), Offset(center.x, height * .64f), durationMillis = 650)
    }

    private fun show(list: String, target: SemanticsMatcher) {
        ui.onNodeWithTag(list).performScrollToNode(target)
    }

    private fun click(node: SemanticsNodeInteraction) = node.performSemanticsAction(SemanticsActions.OnClick)
    private fun await(matcher: SemanticsMatcher) =
        ui.waitUntil(12_000) { ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("overviewScreenshots") != "true") return
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        instrumentation.targetContext.cacheDir.resolve(name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun withDemo(test: (Demo) -> Unit) {
        lateinit var vm: LedgerViewModel
        ui.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[LedgerViewModel::class.java] }
        val repo = vm.repository
        val budget = Budget(id(), "Household", "RSD")
        val account = Account(id(), budget.id, "Everyday", "RSD")
        val names = listOf("Housing", "Food", "Sports", "Transport", "Utilities", "Health")
        val icons = listOf("home", "basket", "sports", "train", "bill", "health")
        val categories = names.mapIndexed { index, name ->
            Category(id(), budget.id, name, EntryType.EXPENSE, icons[index], categoryColors[index])
        }
        val day = LocalDate.now()
        val period = Period(PeriodMode.MONTH, day)
        val savedPeriod = vm.period.value
        val savedFilters = vm.filters.value
        val previous = runBlocking {
            repo.initialize()
            val saved = repo.dao.preferences()
            repo.dao.put(budget)
            repo.saveAccount(account)
            categories.forEach { repo.saveCategory(it) }
            val income = Category(id(), budget.id, "Salary", EntryType.INCOME, "wallet", categoryColors[1])
            repo.saveCategory(income)
            val amounts = listOf("100882", "20000", "25500", "15400", "8950", "6320")
            categories.forEachIndexed { index, category ->
                repo.saveEntry(budget.id, EntryDraft(EntryType.EXPENSE, amounts[index].toBigDecimal(),
                    day.minusDays(if (index < 3) 0 else (index - 2).toLong()), account.id, category.id, "RSD", note = "Market"))
            }
            repo.saveEntry(budget.id, EntryDraft(EntryType.INCOME, "125000".toBigDecimal(), day.minusDays(1), account.id, income.id, "RSD"))
            repo.preferences { it.copy(activeBudget = budget.id, lastAccount = account.id) }
            saved
        }
        vm.filters.value = Filters()
        vm.period.value = period
        try {
            test(Demo(vm, budget, account, day, period, categories))
        } finally {
            vm.filters.value = savedFilters
            vm.period.value = savedPeriod
            if (previous != null) runBlocking {
                repo.preferences { it.copy(activeBudget = previous.activeBudget, lastAccount = previous.lastAccount) }
            }
        }
    }

    private data class Demo(val vm: LedgerViewModel, val budget: Budget, val account: Account, val today: LocalDate,
                            val period: Period, val categories: List<Category>)
}
