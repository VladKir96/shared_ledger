package dev.sharedledger.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.sharedledger.R
import dev.sharedledger.data.Category
import dev.sharedledger.data.Entry
import dev.sharedledger.domain.EntryType
import dev.sharedledger.domain.LedgerFormats
import dev.sharedledger.domain.totals
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.hypot

@Stable
class DonutSelection(type: EntryType = EntryType.EXPENSE, category: String? = null) {
    var type by mutableStateOf(type)
        private set
    var category by mutableStateOf(category)
        private set

    val canReset get() = type != EntryType.EXPENSE || category != null

    fun changeType(next: EntryType) {
        if (type != next) {
            type = next
            category = null
        }
    }

    fun select(key: String?) {
        category = key.takeUnless { key == category }
    }

    fun reset() {
        type = EntryType.EXPENSE
        category = null
    }

    companion object {
        val Saver = listSaver<DonutSelection, String>(
            save = { listOf(it.type.name, it.category.orEmpty()) },
            restore = { DonutSelection(EntryType.valueOf(it[0]), it[1].ifEmpty { null }) }
        )
    }
}

@Composable
fun rememberDonutSelection(budgetId: String? = null) =
    rememberSaveable(budgetId, saver = DonutSelection.Saver) { DonutSelection() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Distribution(
    entries: List<Entry>,
    currency: String,
    categories: List<Category>,
    onDrill: (EntryType, String?) -> Unit,
    selection: DonutSelection = rememberDonutSelection(),
    resetOverview: (() -> Unit)? = null
) {
    val type = selection.type
    val sectors = remember(entries, currency, categories, type) { donutSectors(slices(entries, currency, type, categories)) }
    val sums = remember(entries, currency) { totals(entries, currency) }
    val total = if (type == EntryType.EXPENSE) sums.outgoing else sums.incoming
    val selected = sectors.firstOrNull { it.slice.key == selection.category }
    LaunchedEffect(sectors, selection.category) {
        if (selected == null && selection.category != null) selection.select(null)
    }

    var showLegend by rememberSaveable { mutableStateOf(false) }
    val categoriesLabel = stringResource(R.string.chart_categories)
    val centerTitle = selected?.slice?.let { categoryName(it) } ?: tr(if (type == EntryType.EXPENSE) "Total expenses" else "Total income")
    val centerAmount = if (selected != null) valuationMoney(selected.slice.amount, currency, selected.slice.approximate)
        else if (total.hasAmount || total.pending == 0) valuationMoney(total.amount, currency, total.approximate)
        else tr("Rate pending")
    val centerShare = selected?.let { shareLabel(it.share) }

    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Breakdown"), style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == EntryType.EXPENSE, { selection.changeType(EntryType.EXPENSE) }, { Text(tr("Expenses")) })
                    FilterChip(type == EntryType.INCOME, { selection.changeType(EntryType.INCOME) }, { Text(tr("Income")) })
                }
            }
            DonutPlot(sectors, selected, currency, centerTitle, centerAmount, centerShare, categoriesLabel,
                onLegend = { showLegend = true }, onSelect = selection::select)
            if (total.pending > 0) {
                Text(pluralStringResource(R.plurals.history_group_partial, total.pending, total.pending),
                    modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            } else if (sectors.isEmpty()) {
                Text(tr("Your recorded transactions will appear here."), modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center)
            }
            if (sectors.isNotEmpty() || selection.canReset) FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                if (sectors.isNotEmpty()) TextButton({ showLegend = true }, Modifier.testTag("donut-open-legend")) { Text(categoriesLabel) }
                selected?.slice?.id?.let { id ->
                    TextButton({ onDrill(type, id) }) { Text(tr("View transactions")) }
                }
                if (selection.canReset) TextButton(resetOverview ?: selection::reset, Modifier.testTag("donut-show-all")) {
                    Text(stringResource(R.string.overview_show_all))
                }
            }
        }
    }
    if (showLegend && sectors.isNotEmpty()) DonutLegend(sectors, selected, currency, close = { showLegend = false }) { key ->
        selection.select(key)
        showLegend = false
    }
}

@Composable
private fun DonutPlot(
    sectors: List<DonutSector>,
    selected: DonutSector?,
    currency: String,
    centerTitle: String,
    centerAmount: String,
    centerShare: String?,
    categoriesLabel: String,
    onLegend: () -> Unit,
    onSelect: (String?) -> Unit
) {
    val onSelectLatest by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val percentageStyle = MaterialTheme.typography.labelSmall
    val locale = LedgerFormats.locale
    val labelDimensions = remember(sectors, textMeasurer, density, percentageStyle, locale) {
        val percentages = sectors.map { shareLabel(it.share, locale) }.distinct()
        val textSizes = percentages.map { textMeasurer.measure(it, percentageStyle, maxLines = 1).size }
        with(density) {
            val width = maxOf(48.dp, (textSizes.maxOfOrNull { it.width } ?: 0).toDp() + 8.dp)
            val height = maxOf(48.dp, 32.dp + (textSizes.maxOfOrNull { it.height } ?: 0).toDp())
            width to height
        }
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val selection by animateFloatAsState(if (selected == null) 0f else 1f, label = "Category selection")
    val emphasis = sectors.map { sector ->
        key(sector.slice.key) {
            animateFloatAsState(if (selected == sector) 1f else 0f, label = "Sector emphasis")
        }
    }
    BoxWithConstraints(Modifier.widthIn(max = 540.dp).fillMaxWidth()) {
        val (labelWidth, labelHeight) = labelDimensions
        val labeledDiameter = minOf(maxWidth * .77f, maxWidth - labelWidth * 2 - 8.dp, 380.dp)
        val hasLabelSpace = sectors.isNotEmpty() && labeledDiameter >= 112.dp
        val diameter = if (hasLabelSpace) labeledDiameter else minOf(maxWidth * .85f, 380.dp)
        val minimumLabelRows = minOf(3, sectors.size)
        val height = if (hasLabelSpace) {
            maxOf(diameter + 16.dp, (labelHeight + DONUT_LABEL_GAP.dp) * minimumLabelRows)
        } else {
            diameter + 16.dp
        }
        val selectedKey = selected?.slice?.key
        val labels = remember(sectors, selectedKey, maxWidth, height, diameter, labelHeight, hasLabelSpace) {
            if (hasLabelSpace) {
                donutLabels(sectors, maxWidth.value, height.value, (diameter / 2 - 4.dp).value, labelHeight.value, selectedKey)
            } else {
                emptyList()
            }
        }
        val labelFarX = maxWidth - labelWidth
        val strokeWidth = (diameter * .14f).coerceIn(24.dp, 48.dp)
        val descriptions = sectors.takeIf { it.size <= 8 }.orEmpty().map { sectorDescription(it, currency) }
        val chartDescription = if (centerShare == null) "$centerTitle, $centerAmount" else "$centerTitle, $centerAmount, $centerShare"
        val detailsWidth = diameter * .59f
        val titleStyle = MaterialTheme.typography.labelLarge
        val amountStyle = if (diameter >= 230.dp && density.fontScale <= 1.3f) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.titleMedium
        val detailsInside = remember(centerTitle, centerAmount, centerShare, diameter, strokeWidth, textMeasurer, titleStyle, amountStyle) {
            with(density) {
                val constraints = Constraints(maxWidth = detailsWidth.roundToPx())
                val layouts = listOfNotNull(
                    textMeasurer.measure(centerTitle, titleStyle, maxLines = 2, constraints = constraints),
                    textMeasurer.measure(centerAmount, amountStyle, maxLines = 2, constraints = constraints),
                    centerShare?.let { textMeasurer.measure(it, titleStyle, maxLines = 1, constraints = constraints) }
                )
                val textWidth = layouts.maxOf { it.size.width }
                val textHeight = layouts.sumOf { it.size.height } + 3.dp.toPx() * layouts.lastIndex
                val innerRadius = (diameter / 2 - strokeWidth - 8.dp).toPx()
                layouts.none { it.hasVisualOverflow } && hypot(textWidth / 2f, textHeight / 2f) <= innerRadius
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().testTag("donut-chart").semantics {
                    contentDescription = chartDescription
                    customActions = if (descriptions.isNotEmpty()) {
                        descriptions.mapIndexed { index, description ->
                            CustomAccessibilityAction(description) { onSelect(sectors[index].slice.key); true }
                        }
                    } else if (sectors.isNotEmpty()) listOf(CustomAccessibilityAction(categoriesLabel) { onLegend(); true }) else emptyList()
                }.pointerInput(sectors, diameter, strokeWidth) {
                    detectTapGestures { point ->
                        val outer = (diameter / 2).toPx()
                        val hit = hitDonut(sectors, point.x - size.width / 2f, point.y - size.height / 2f,
                            outer - strokeWidth.toPx() - 5.dp.toPx(), outer + 3.dp.toPx())
                        onSelectLatest(hit?.slice?.key)
                    }
                }) {
                    val radius = diameter.toPx() / 2 - strokeWidth.toPx() / 2 - 4.dp.toPx()
                    val trackSize = Size(radius * 2, radius * 2)
                    drawArc(track, -90f, 360f, false, center - Offset(radius, radius), trackSize, style = Stroke(strokeWidth.toPx()))
                    sectors.forEachIndexed { index, sector ->
                        val active = emphasis[index].value
                        val stretch = 3.dp.toPx() * active
                        val ringRadius = radius + stretch
                        val gap = if (sectors.size > 1) minOf(1.5, sector.sweep * .14) else 0.0
                        val alpha = 1f - .72f * selection * (1f - active)
                        drawArc(Color(sector.slice.color).copy(alpha = alpha), (sector.start - 90 + gap / 2).toFloat(),
                            (sector.sweep - gap).toFloat(), false, center - Offset(ringRadius, ringRadius),
                            Size(ringRadius * 2, ringRadius * 2), style = Stroke(strokeWidth.toPx() + stretch))
                    }
                    labels.forEach { label ->
                        val x = if (label.left) labelWidth.toPx() else labelFarX.toPx()
                        val gutter = x + if (label.left) 3.dp.toPx() else -3.dp.toPx()
                        val anchor = Offset(label.anchorX.dp.toPx(), label.anchorY.dp.toPx())
                        val elbow = Offset(gutter, anchor.y)
                        val edge = Offset(x, label.centerY.dp.toPx())
                        val alpha = if (selected == null || selected == label.sector) .9f else .38f
                        val tint = Color(label.sector.slice.color).copy(alpha = alpha)
                        drawLine(tint, anchor, elbow, 1.dp.toPx(), cap = StrokeCap.Round)
                        drawLine(tint, elbow, edge, 1.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
                if (detailsInside) {
                    DonutDetails(centerTitle, centerAmount, centerShare, amountStyle, Modifier.width(detailsWidth))
                }
                labels.forEach { label ->
                    Box(Modifier.align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset(if (label.left) 0.dp else labelFarX, label.centerY.dp - labelHeight / 2)) {
                        DonutLabelCell(label.sector, selected == label.sector, currency, labelWidth, labelHeight, onSelect)
                    }
                }
            }
            if (!detailsInside) {
                DonutDetails(centerTitle, centerAmount, centerShare, amountStyle, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun DonutDetails(title: String, amount: String, share: String?, amountStyle: TextStyle, modifier: Modifier) {
    Column(modifier.testTag("donut-details"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val labelStyle = MaterialTheme.typography.labelLarge
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = labelStyle, textAlign = TextAlign.Center)
        Text(amount, style = amountStyle, textAlign = TextAlign.Center)
        if (share != null) {
            Text(share, style = labelStyle, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DonutLabelCell(sector: DonutSector, active: Boolean, currency: String, width: Dp, height: Dp,
                           onSelect: (String) -> Unit) {
    val description = sectorDescription(sector, currency)
    Surface(
        Modifier.size(width, height).testTag("donut-label-${sector.slice.key}")
            .selectable(active, role = Role.RadioButton, onClick = { onSelect(sector.slice.key) })
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CategoryMark(sector.slice.icon, sector.slice.color, Modifier.size(28.dp))
            Text(shareLabel(sector.share), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun DonutLegend(sectors: List<DonutSector>, selected: DonutSector?, currency: String, close: () -> Unit, onChoose: (String) -> Unit) {
    Dialog(onDismissRequest = close) {
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.chart_categories), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 440.dp).testTag("donut-legend"),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sectors, key = { it.slice.key }) { sector ->
                    val slice = sector.slice
                    val active = sector == selected
                    val description = sectorDescription(sector, currency)
                    Surface(Modifier.fillMaxWidth().testTag("donut-legend-${slice.key}")
                        .selectable(active, role = Role.RadioButton, onClick = { onChoose(slice.key) })
                        .semantics { contentDescription = description },
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.heightIn(min = 52.dp).padding(6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CategoryMark(slice.icon, slice.color)
                            Text(categoryName(slice), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Column(horizontalAlignment = Alignment.End) {
                                Text(shareLabel(sector.share), style = MaterialTheme.typography.labelLarge)
                                Text(valuationMoney(slice.amount, currency, slice.approximate), style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
            TextButton(close, Modifier.align(Alignment.End)) { Text(tr("Close")) }
        }
    }
}

@Composable
private fun categoryName(slice: Slice): String = if (slice.id == null) tr("Uncategorized") else slice.name

@Composable
private fun sectorDescription(sector: DonutSector, currency: String): String =
    stringResource(R.string.chart_category_description, categoryName(sector.slice),
        valuationMoney(sector.slice.amount, currency, sector.slice.approximate), shareLabel(sector.share))

private fun shareLabel(share: Double, locale: Locale = LedgerFormats.locale): String {
    val formatter = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }
    return if (share < .001) "<" + formatter.format(.001) else formatter.format(share)
}
