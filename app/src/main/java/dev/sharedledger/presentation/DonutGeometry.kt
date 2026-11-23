package dev.sharedledger.presentation

import dev.sharedledger.data.Category
import dev.sharedledger.data.Entry
import dev.sharedledger.domain.EntryType
import dev.sharedledger.domain.estimatedGroups
import dev.sharedledger.domain.spendingBy
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class Slice(
    val id: String?, val name: String, val color: Long, val icon: String, val amount: BigDecimal, val approximate: Boolean
) {
    val key: String get() = id ?: "uncategorized"
}

internal fun slices(entries: List<Entry>, currency: String, type: EntryType, categories: List<Category>): List<Slice> {
    val index = categories.associateBy { it.id }
    val approximations = estimatedGroups(entries, currency, type, Entry::categoryId)
    return spendingBy(entries, currency, type, Entry::categoryId).filterValues { it.signum() > 0 }.map { (key, amount) ->
        val category = index[key]
        Slice(key, category?.name ?: "Uncategorized", category?.color ?: 0xFF82968A, category?.icon ?: "more", amount, key in approximations)
    }
}

internal data class DonutSector(val slice: Slice, val start: Double, val sweep: Double) {
    val share get() = sweep / 360
    val midpoint get() = Math.toRadians(start + sweep / 2 - 90)
}

internal fun donutSectors(slices: List<Slice>): List<DonutSector> {
    val total = slices.sumOf { it.amount }
    var cumulative = BigDecimal.ZERO
    return slices.map { slice ->
        val start = cumulative.divide(total, MathContext.DECIMAL128).toDouble() * 360
        cumulative += slice.amount
        val sweep = slice.amount.divide(total, MathContext.DECIMAL128).toDouble() * 360
        DonutSector(slice, start, sweep)
    }
}

internal fun hitDonut(sectors: List<DonutSector>, x: Float, y: Float, innerRadius: Float, outerRadius: Float): DonutSector? {
    if (hypot(x, y) !in innerRadius..outerRadius) return null
    val degrees = (Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 450) % 360
    return sectors.lastOrNull { it.start <= degrees }
}

internal data class DonutLabel(
    val sector: DonutSector, val left: Boolean, val anchorX: Float, val anchorY: Float, val centerY: Float
)

internal const val DONUT_LABEL_GAP = 5f

internal fun donutLabels(
    sectors: List<DonutSector>, width: Float, height: Float, radius: Float, labelHeight: Float,
    selectedKey: String? = null
): List<DonutLabel> {
    if (height < labelHeight) return emptyList()
    val minimumGap = labelHeight + DONUT_LABEL_GAP
    val minimum = labelHeight / 2
    val maximum = height - minimum
    val laneCapacity = ((maximum - minimum) / minimumGap).toInt() + 1
    val left = mutableListOf<DonutLabel>()
    val right = mutableListOf<DonutLabel>()
    val ranked = sectors.sortedWith(compareByDescending<DonutSector> { it.slice.key == selectedKey }
        .thenByDescending { it.slice.amount })
    for (sector in ranked) {
        if (left.size + right.size >= 7) break
        val dx = cos(sector.midpoint).toFloat()
        val lane = if (dx < 0) left else right
        if (lane.size >= laneCapacity) continue
        val dy = sin(sector.midpoint).toFloat()
        val anchorY = height / 2 + dy * radius
        lane += DonutLabel(sector, dx < 0, width / 2 + dx * radius, anchorY, anchorY)
    }

    return listOf(left, right).flatMap { labels ->
        val sorted = labels.sortedBy(DonutLabel::anchorY)
        var previous = minimum - minimumGap
        sorted.mapIndexed { index, label ->
            val upper = maximum - (sorted.lastIndex - index) * minimumGap
            previous = label.anchorY.coerceIn(minOf(previous + minimumGap, upper), upper)
            label.copy(centerY = previous)
        }
    }
}
