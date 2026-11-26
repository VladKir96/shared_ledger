package dev.sharedledger.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharedledger.domain.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun HistorySeriesPicker(label: String, options: Map<String, String>, selected: Set<String>, onSelect: (Set<String>) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton({ show = true }, Modifier.fillMaxWidth().testTag("history-picker-${label.lowercase(Locale.ROOT)}"),
        enabled = options.isNotEmpty(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(tr(label), style = MaterialTheme.typography.labelSmall)
            Text(options.filterKeys { it in selected }.values.joinToString().ifEmpty { tr("Select series") },
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
    }
    if (show) {
        var checked by remember { mutableStateOf(selected) }
        AlertDialog(onDismissRequest = { show = false }, title = { Text(tr(label)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(options.entries.toList(), key = { it.key }) { (id, name) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(id in checked, role = Role.Checkbox) {
                            checked = if (it) checked + id else checked - id
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
                            Text(name, Modifier.padding(end = 8.dp))
                        }
                    }
                }
            },
            dismissButton = { TextButton({ show = false }) { Text(tr("Cancel")) } },
            confirmButton = { TextButton({ onSelect(checked); show = false }) { Text(tr("Apply")) } })
    }
}

@Composable
internal fun HistoryChart(history: FinancialHistory, currency: String, tag: String) {
    val (buckets, series) = history
    if (buckets.isEmpty() || series.isEmpty()) {
        EmptyMessage(tr("No history in this period"), tr("Choose a different date range or series."), Icons.Outlined.BarChart)
        return
    }
    var selected by remember(buckets) { mutableIntStateOf(buckets.lastIndex) }
    val bucket = buckets[selected]
    val surface = MaterialTheme.colorScheme.surface
    val colors = remember(series, surface) { series.map { readableSeriesColor(Color(it.color).copy(alpha = 1f), surface) } }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
    val (low, high) = remember(history) { chartBounds(history) }
    val span = high - low
    val ticks = listOf(high, low + span.divide(BigDecimal(2)), low)
    val descriptions = series.map { valueDescription(it.values[selected], currency) }
    val date = if (bucket.from == bucket.through) bucket.through.shortDate()
        else "${bucket.from.shortDate()} – ${bucket.through.shortDate()}"
    val title = if (bucket.opening) "${tr("Opening balance")} · $date" else date
    val previous = tr("Previous point")
    val next = tr("Next point")
    Text(currency, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Column(Modifier.width(77.dp).height(164.dp), verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End) {
            ticks.forEach { tick ->
                val amount = tick.setScale(currencyScale(currency), RoundingMode.HALF_EVEN).storage()
                Text(groupAmount(amount), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val scale = LocalDensity.current.fontScale
            val labelIndices = when {
                buckets.size == 1 -> listOf(0)
                maxWidth.value / scale >= 235 && buckets.size > 2 -> listOf(0, buckets.lastIndex / 2, buckets.lastIndex)
                else -> listOf(0, buckets.lastIndex)
            }
            Column {
                Canvas(Modifier.fillMaxWidth().height(164.dp).testTag(tag).semantics {
                    contentDescription = "$title. " + series.zip(descriptions).joinToString(". ") { (row, value) -> "${row.name}, $value" }
                    customActions = buildList {
                        if (selected > 0) add(CustomAccessibilityAction(previous) { selected--; true })
                        if (selected < buckets.lastIndex) add(CustomAccessibilityAction(next) { selected++; true })
                    }
                }.pointerInput(buckets) {
                    detectTapGestures { position ->
                        if (size.width > 0) selected = (position.x / size.width * buckets.size - .5f)
                            .roundToInt().coerceIn(buckets.indices)
                    }
                }) {
                    val gutter = 6.dp.toPx()
                    val height = size.height - 2 * gutter
                    fun y(amount: BigDecimal): Float =
                        gutter + height * (1f - (amount - low).divide(span, MathContext.DECIMAL64).toFloat())
                    val lane = size.width / buckets.size
                    fun x(i: Int) = lane * (i + .5f)
                    ticks.forEach { tick ->
                        drawLine(grid, Offset(0f, y(tick)), Offset(size.width, y(tick)), strokeWidth = 1.dp.toPx())
                    }
                    drawLine(cursor, Offset(x(selected), gutter), Offset(x(selected), size.height - gutter),
                        strokeWidth = 1.dp.toPx())
                    series.forEachIndexed { seriesIndex, row ->
                        val color = colors[seriesIndex]
                        val dash = linePattern(seriesIndex, density)
                        var point: Offset? = null
                        row.values.forEachIndexed { i, value ->
                            val plotted = value.amount?.let { Offset(x(i), y(it)) }
                            val tint = if (value.pending > 0 || value.approximate) color.copy(alpha = .7f) else color
                            if (plotted != null) {
                                point?.let { drawLine(tint, it, plotted, 2.dp.toPx(), pathEffect = dash) }
                                if (i == selected) {
                                    drawCircle(surface, 5.dp.toPx(), plotted)
                                    drawCircle(tint, 3.5.dp.toPx(), plotted)
                                } else if (point == null || row.values.getOrNull(i + 1)?.amount == null) {
                                    drawCircle(tint, 2.5.dp.toPx(), plotted)
                                }
                            }
                            point = plotted
                        }
                    }
                }
                val axisLabels = labelIndices.map { axisDate(buckets[it]) }.distinct()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    axisLabels.forEachIndexed { index, label ->
                        val alignment = when {
                            axisLabels.size == 1 -> TextAlign.Center
                            index == 0 -> TextAlign.Start
                            index == axisLabels.lastIndex -> TextAlign.End
                            else -> TextAlign.Center
                        }
                        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = alignment,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    Surface(Modifier.fillMaxWidth().testTag("$tag-details"), color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            series.forEachIndexed { index, row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.width(20.dp).height(8.dp)) {
                        drawLine(colors[index], Offset(0f, center.y), Offset(size.width, center.y), 2.dp.toPx(),
                            pathEffect = linePattern(index, density))
                    }
                    Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(descriptions[index], Modifier.weight(1f, fill = false), textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun valueDescription(value: HistoryValue, currency: String): String {
    val amount = value.amount ?: return tr(if (value.pending > 0) "Rate pending" else "No balance recorded")
    val precision = maxOf(currencyScale(currency), amount.stripTrailingZeros().scale())
    val formatted = "${groupAmount(amount.setScale(precision).toPlainString())} $currency"
    return when {
        value.pending > 0 -> "$formatted · ${tr("Partial total")}"
        value.approximate -> "≈ $formatted"
        else -> formatted
    }
}

private fun axisDate(bucket: HistoryBucket): String {
    val template = if (ChronoUnit.DAYS.between(bucket.from, bucket.through) >= 20) "MMM yyyy" else "MMM d"
    return bucket.through.format(DateTimeFormatter.ofPattern(template, LedgerFormats.locale))
}

private fun chartBounds(history: FinancialHistory): Pair<BigDecimal, BigDecimal> {
    val amounts = history.series.flatMap { it.values.mapNotNull(HistoryValue::amount) }
    var low = amounts.minOrNull() ?: BigDecimal.ZERO
    val high = amounts.maxOrNull() ?: BigDecimal.ONE
    if (!history.buckets.first().opening) low = minOf(low, BigDecimal.ZERO)
    if (low.compareTo(high) != 0) return low to high
    val padding = maxOf(high.abs().multiply(BigDecimal(".05")), BigDecimal.ONE)
    return if (high.signum() == 0 && !history.buckets.first().opening) low to padding
        else (low - padding) to (high + padding)
}

private fun linePattern(index: Int, density: Float): PathEffect? =
    if (index == 0) null else PathEffect.dashPathEffect(floatArrayOf((4 + index % 3 * 2) * density, 3 * density))

private fun readableSeriesColor(color: Color, surface: Color): Color {
    val background = surface.luminance()
    val target = if (background > .5f) Color.Black else Color.White
    for (step in 0..10) {
        val tint = lerp(color, target, step / 10f)
        val luminance = tint.luminance()
        if ((maxOf(luminance, background) + .05f) / (minOf(luminance, background) + .05f) >= 3f) return tint
    }
    return target
}
