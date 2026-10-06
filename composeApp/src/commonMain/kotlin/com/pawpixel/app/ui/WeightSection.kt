package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.DAY_MS
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.Weight
import com.pawpixel.core.Units
import com.pawpixel.core.WeightTrend
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcons

/** Weigh-ins, the latest one and a small trend chart: vets ask about slow gains and losses. */
@Composable
fun WeightSection(app: AppScope, state: AppState, pet: Pet) {
    val weights = state.weightsFor(pet.id)
    val today = app.repo.clock.dayIndex(app.now)
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Weight?>(null) }
    var showAll by remember { mutableStateOf(false) }

    if (weights.isEmpty()) {
        Hint(tr("Weigh {0} now and then (a bathroom scale works: weigh yourself holding them, then subtract).", pet.name))
    } else {
        val last = weights.last()
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Units.weight(last.grams), style = MaterialTheme.typography.headlineMedium)
                    Hint(tr("on {0}", LocalClock.shortDate(last.day)), Modifier.padding(bottom = 6.dp))
                }
                WeightTrend.change(weights)?.let { Hint(it) }
                if (weights.size >= 2) WeightChart(weights.takeLast(CHART_POINTS))
                else Hint(tr("Add another weigh-in later to see the trend."))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostPill(tr("+ Add weight"), icon = PixelIcons.SCALE) { adding = true }
        if (weights.isNotEmpty()) LinkButton(if (showAll) tr("Hide weigh-ins") else tr("All weigh-ins ({0})", weights.size)) { showAll = !showAll }
    }
    if (showAll) {
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
            Column {
                weights.asReversed().forEachIndexed { i, w ->
                    if (i > 0) HorizontalDivider(color = Paw.palette.hairline)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(LocalClock.shortDate(w.day), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(Units.weight(w.grams), fontWeight = FontWeight.Bold)
                        val editLabel = tr("Edit the weigh-in on {0}", LocalClock.shortDate(w.day))
                        LinkButton(tr("Edit"), modifier = Modifier.semantics { contentDescription = editLabel }, color = MaterialTheme.colorScheme.onSurfaceVariant) { editing = w }
                    }
                }
            }
        }
    }
    if (adding) {
        WeightDialog(pet.name, today, initial = null, last = weights.lastOrNull(), onDismiss = { adding = false }, onDelete = null) { day, grams ->
            adding = false; app.launch { app.repo.logWeight(pet.id, day, grams) }
        }
    }
    editing?.let { w ->
        WeightDialog(pet.name, today, initial = w, last = null, onDismiss = { editing = null },
            onDelete = { editing = null; app.launch { app.repo.removeWeight(pet.id, w.day) } }) { day, grams ->
            editing = null; app.launch { app.repo.editWeight(pet.id, w.day, day, grams) }
        }
    }
}

/**
 * The last weigh-ins over time: kg on the left on round steps, dates along the bottom (spaced by
 * the real days between them), each point labelled where there's room, the latest always.
 */
@Composable
private fun WeightChart(weights: List<Weight>) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onBackground
    val background = MaterialTheme.colorScheme.surfaceContainerLowest
    val measurer = rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall.copy(color = ink)
    val bold = MaterialTheme.typography.labelMedium.copy(color = strong, fontWeight = FontWeight.Bold)
    // The axis is laid out in the shown unit (kg or lb), in "hundredths" so the round steps fit either.
    fun shown(grams: Int) = Units.weightTenths(grams) * 100
    val (bottom, top, every) = WeightTrend.axis(weights.map { shown(it.grams) })
    val first = weights.first(); val last = weights.last()
    val desc = tr(
        "Weight chart: {0} weigh-ins, from {1} on {2} to {3} on {4}",
        weights.size, Units.weight(first.grams), LocalClock.shortDate(first.day), Units.weight(last.grams), LocalClock.shortDate(last.day),
    )
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = desc }) {
        val ticks = (bottom..top step every).toList()
        val tickLabels = ticks.map { measurer.measure(Units.tenthsText(it / 100), small) }
        val unit = measurer.measure(Units.weightUnit, small)
        val left = (tickLabels.maxOf { it.size.width }).toFloat() + 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val plotTop = unit.size.height + 6.dp.toPx()
        val xLabelHeight = measurer.measure("0", small).size.height
        val plotBottom = size.height - xLabelHeight - 6.dp.toPx()
        val span = (last.day - first.day).coerceAtLeast(1).toFloat()
        // Room inside the plot so the first and last points aren't cut by its edges.
        val inset = 10.dp.toPx()
        fun x(day: Long) = left + inset + (day - first.day) / span * (right - left - 2 * inset)
        fun y(g: Int) = plotBottom - (g - bottom).toFloat() / (top - bottom) * (plotBottom - plotTop)

        drawText(unit, topLeft = Offset(0f, 0f))
        ticks.forEachIndexed { i, g ->
            val gy = y(g)
            drawLine(grid, Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx(),
                pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            val label = tickLabels[i]
            drawText(label, topLeft = Offset(left - 6.dp.toPx() - label.size.width, gy - label.size.height / 2f))
        }
        // Dates: the first and the last, and the middle one when there's room.
        val dates = listOf(first.day, last.day).map { d -> d to measurer.measure(dayMonth(d), small) }
        for ((d, label) in dates) {
            val lx = (x(d) - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, size.height - label.size.height))
        }

        val path = Path()
        weights.forEachIndexed { i, w -> if (i == 0) path.moveTo(x(w.day), y(shown(w.grams))) else path.lineTo(x(w.day), y(shown(w.grams))) }
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
        var labelledX = Float.MAX_VALUE
        for (i in weights.indices.reversed()) {
            val w = weights[i]
            val px = x(w.day); val py = y(shown(w.grams))
            val latest = i == weights.lastIndex
            drawCircle(line, if (latest) 5.dp.toPx() else 4.dp.toPx(), Offset(px, py))
            drawCircle(background, if (latest) 2.dp.toPx() else 1.5.dp.toPx(), Offset(px, py))
            // Value labels right to left, skipping any that would crowd the one after.
            if (labelledX - px < 40.dp.toPx()) continue
            val label = measurer.measure(Units.weightInput(w.grams), if (latest) bold else small)
            val ly = (py - label.size.height - 6.dp.toPx()).let { if (it < plotTop - unit.size.height) py + 6.dp.toPx() else it }
            drawText(label, topLeft = Offset((px - label.size.width / 2f).coerceIn(left, size.width - label.size.width), ly))
            labelledX = px
        }
    }
}

/** "Sep 29" (the chart has no room for the year). */
private fun dayMonth(day: Long) = LocalClock.shortDate(day).substringBeforeLast(',')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WeightDialog(
    name: String, today: Long, initial: Weight?, last: Weight?,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (day: Long, grams: Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.let { Units.weightInput(it.grams) } ?: "") }
    var day by remember { mutableStateOf(initial?.day ?: today) }
    var picking by remember { mutableStateOf(false) }
    val grams = Units.parseWeight(text)
    val unit = Units.weightUnit
    fun nudge(tenths: Int) {
        val from = grams ?: last?.grams ?: 0
        text = Units.tenthsText((Units.weightTenths(from) + tenths).coerceIn(1, Units.weightTenths(WeightTrend.MAX_G)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) tr("{0}'s weight", name) else tr("Edit weigh-in")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    text, { text = it.take(6) }, label = { Text(if (Units.pounds) tr("Pounds, e.g. 9.3") else tr("Kilograms, e.g. 4.2")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = MaterialTheme.shapes.small,
                    isError = text.isNotBlank() && grams == null, modifier = Modifier.fillMaxWidth(),
                )
                // Or step from the last weigh-in, without the keyboard.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepKg("−1", tr("Subtract 1 {0}", unit)) { nudge(-10) }
                    StepKg("−0.1", tr("Subtract 0.1 {0}", unit)) { nudge(-1) }
                    StepKg("+0.1", tr("Add 0.1 {0}", unit)) { nudge(1) }
                    StepKg("+1", tr("Add 1 {0}", unit)) { nudge(10) }
                }
                Text(tr("Weighed on"), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(day == today, { day = today }, tr("Today"))
                    ChoiceChip(day == today - 1, { day = today - 1 }, tr("Yesterday"))
                    val other = day != today && day != today - 1
                    ChoiceChip(other, { picking = true }, if (other) LocalClock.shortDate(day) else tr("Other date…"))
                }
            }
        },
        confirmButton = { TextButton(enabled = grams != null, onClick = { grams?.let { onSave(day, it) } }) { Text(tr("Save")) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
            }
        },
    )
    if (picking) {
        // Dates are UTC midnights in the picker; a local day index is the same number of days.
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = day * DAY_MS,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = { picker.selectedDateMillis?.let { day = it / DAY_MS }; picking = false }) { Text(tr("OK")) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(tr("Cancel")) } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun StepKg(label: String, description: String, onClick: () -> Unit) {
    ChoiceChip(false, onClick, label, modifier = Modifier.defaultMinSize(minHeight = 44.dp).semantics { contentDescription = description }, role = Role.Button)
}

private const val CHART_POINTS = 12
