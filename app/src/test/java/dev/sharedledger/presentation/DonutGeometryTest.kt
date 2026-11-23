package dev.sharedledger.presentation

import dev.sharedledger.data.Category
import dev.sharedledger.data.Entry
import dev.sharedledger.domain.EntryType
import dev.sharedledger.domain.totals
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class DonutGeometryTest {
    @Test fun everyNonzeroCategoryRetainsItsOwnFinancialProportion() {
        val categories = List(32) { Category("cat-$it", "budget", "Category $it", EntryType.EXPENSE, "basket", it.toLong()) }
        val entries = categories.mapIndexed { index, category ->
            entry(category.id, if (index == 0) "8000" else "0.01")
        }
        val sectors = donutSectors(slices(entries, "RSD", EntryType.EXPENSE, categories))
        assertEquals(32, sectors.size)
        assertEquals(categories.map { it.id }.toSet(), sectors.map { it.slice.id }.toSet())
        assertTrue(sectors.drop(1).all { it.share in 0.0..0.01 && it.sweep > 0.0 })
        assertEquals(360.0, sectors.sumOf { it.sweep }, 1e-10)
        assertEquals(BigDecimal("8000.31"), sectors.sumOf { it.slice.amount })
        assertEquals(categories.first().color, sectors.first().slice.color)
        val labels = donutLabels(sectors, 350f, 260f, 110f, 48f)
        assertEquals(6, labels.size)
        assertTrue(labels.any { it.sector == sectors.first() })
        val tiny = sectors.last()
        assertFalse(labels.any { it.sector == tiny })
        assertEquals(tiny, hitDonut(sectors, (cos(tiny.midpoint) * 90).toFloat(),
            (sin(tiny.midpoint) * 90).toFloat(), 75f, 105f))
    }

    @Test fun sectorMidpointsMapToTheCorrectCategoryAndEmptyAreasClear() {
        val sectors = donutSectors(listOf(slice("Food", "50"), slice("Home", "30"), slice("Sport", "20")))
        for (sector in sectors) {
            val x = (cos(sector.midpoint) * 90).toFloat()
            val y = (sin(sector.midpoint) * 90).toFloat()
            assertEquals(sector, hitDonut(sectors, x, y, 75f, 105f))
        }
        assertEquals("Food", hitDonut(sectors, 0f, -90f, 75f, 105f)?.slice?.key)
        assertNull(hitDonut(sectors, 5f, 5f, 75f, 105f))
        assertNull(hitDonut(sectors, 0f, 110f, 75f, 105f))
    }

    @Test fun calloutsKeepOrderedLanesAndStartAtTheirOwnSector() {
        val sectors = donutSectors(listOf(slice("Home", "46"), slice("Food", "19"), slice("Health", "13"),
            slice("Transport", "10"), slice("Sport", "7"), slice("Other", "5")))
        val labels = donutLabels(sectors, 380f, 285f, 125f, 48f)
        assertEquals(sectors.size, labels.size)
        for (lane in labels.groupBy(DonutLabel::left).values) {
            assertTrue(lane.zipWithNext().all { (first, next) ->
                first.anchorY <= next.anchorY && next.centerY - first.centerY >= 53
            })
        }
        for (label in labels) {
            assertTrue(label.centerY in 24f..261f)
            assertEquals(125f, hypot(label.anchorX - 190f, label.anchorY - 142.5f), .001f)
            assertEquals(label.sector, hitDonut(sectors, label.anchorX - 190f, label.anchorY - 142.5f, 120f, 130f))
        }
    }

    @Test fun phoneLayoutLabelsUpToSevenCategoriesAsTheChartGetsDenser() {
        for (count in listOf(3, 7, 15, 30)) {
            val sectors = donutSectors(List(count) { slice("Category $it", (count - it).toString()) })
            val labels = donutLabels(sectors, 380f, 285f, 125f, 48f)
            assertEquals(minOf(count, 7), labels.size)
            assertTrue(labels.map { it.sector }.containsAll(sectors.take(3)))
            assertEquals(count, sectors.size)
            for (lane in labels.groupBy(DonutLabel::left).values) {
                assertEquals(lane.size, lane.map { it.sector }.distinct().size)
                assertTrue(lane.zipWithNext().all { (first, next) -> next.centerY - first.centerY >= 53f })
            }
        }
    }

    @Test fun compactLanesPrioritizeByAmountAndMakeRoomForAnUnlabeledSelection() {
        val sectors = donutSectors(listOf(slice("Housing", "62"), slice("Food", "15"), slice("Transport", "12"),
            slice("Health", "8"), slice("Sports", "3"), slice("Tiny", "0.01")))
        val normal = donutLabels(sectors, 280f, 190f, 75f, 60f)
        assertEquals(setOf("Housing", "Food", "Transport", "Health"), normal.map { it.sector.slice.key }.toSet())
        assertEquals(normal, donutLabels(sectors.reversed(), 280f, 190f, 75f, 60f))

        val selected = donutLabels(sectors, 280f, 190f, 75f, 60f, selectedKey = "Tiny")
        assertEquals(setOf("Housing", "Food", "Transport", "Tiny"), selected.map { it.sector.slice.key }.toSet())
        assertEquals(normal, donutLabels(sectors, 280f, 190f, 75f, 60f, selectedKey = null))
    }

    @Test fun chartUsesBudgetValuationsAndMatchesTheLocalReport() {
        val recorded = entry("groceries", "2500")
        val foreign = entry("fuel", "2300").copy(amount = "20", currency = "USD", budgetRateSource = "Reference")
        val pending = entry("utilities", null)
        val transfer = entry("transfer", "470").copy(type = EntryType.TRANSFER)
        val deleted = entry("deleted", "400").copy(deletedAt = 1)
        val income = entry("income", "90000").copy(type = EntryType.INCOME)
        val rows = listOf(recorded, foreign, pending, transfer, deleted, income)
        val parts = slices(rows, "RSD", EntryType.EXPENSE, emptyList())
        assertEquals(listOf("groceries", "fuel"), parts.map { it.id })
        assertEquals(totals(rows, "RSD").expenses, parts.sumOf { it.amount })
        assertTrue(parts.last().approximate)
        assertTrue(donutSectors(emptyList()).isEmpty())
        assertEquals(BigDecimal("90000"), slices(rows, "RSD", EntryType.INCOME, emptyList()).single().amount)
    }

    private fun entry(category: String, valuation: String?) =
        Entry(category, "budget", "account", category, EntryType.EXPENSE, valuation ?: "1", "RSD", "RSD", "RSD",
            "2026-11-23", budgetAmount = valuation)

    private fun slice(name: String, amount: String) =
        Slice(name, name, 0xFF819164, "basket", BigDecimal(amount), false)
}
