package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Milestones
import com.pawpixel.core.Pet
import com.pawpixel.core.WeightTrend
import com.pawpixel.i18n.tr

/** "100 days of care!" once, when a milestone is reached, with a card to share. */
@Composable
fun MilestoneBanner(app: AppScope, pet: Pet) {
    val days = Milestones.toCelebrate(pet) ?: return
    PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🎉 ${Milestones.title(days)}!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(tr("You've looked after {0} on {1} different days.", pet.name, days) + " " + tr("That's a lot of love."))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.repo.shareMilestone(pet, days) }) { Text(tr("Share the card")) }
                TextButton(onClick = { app.launch { app.repo.celebrate(pet.id, days) } }) { Text(tr("Nice!")) }
            }
        }
    }
}

/** The next milestone, as a quiet line under the weekly dots. */
fun nextMilestoneLine(pet: Pet): String? = Milestones.next(pet)?.let { (at, left) ->
    if (Milestones.caredDays(pet) == 0) null else tr("{0} days of care so far · {1} to go to {2}", Milestones.caredDays(pet), left, Milestones.title(at))
}

/** Weigh-ins and a small chart, for spotting slow gains or losses (vets ask about both). */
@Composable
fun WeightSection(app: AppScope, state: AppState, pet: Pet) {
    val weights = state.weightsFor(pet.id)
    var adding by remember { mutableStateOf(false) }
    Text(tr("Weight"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (weights.isEmpty()) {
        Text(tr("Weigh {0} now and then (a bathroom scale works: weigh yourself holding them, then subtract).", pet.name), style = MaterialTheme.typography.bodySmall)
    } else {
        val last = weights.last()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(WeightTrend.kg(last.grams), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("  " + tr("on {0}", LocalClock.shortDate(last.day)), style = MaterialTheme.typography.bodySmall)
        }
        WeightTrend.change(weights)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (weights.size >= 2) WeightChart(weights.takeLast(12).map { it.grams })
    }
    OutlinedButton(onClick = { adding = true }) { Text(tr("+ Add today's weight")) }
    if (adding) WeightDialog(pet.name, onDismiss = { adding = false }) { grams -> adding = false; app.launch { app.repo.logWeight(pet.id, grams) } }
}

@Composable
private fun WeightChart(grams: List<Int>) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val desc = tr("Weight over the last {0} weigh-ins, from {1} to {2}", grams.size, WeightTrend.kg(grams.first()), WeightTrend.kg(grams.last()))
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = desc }) {
        val lo = grams.min(); val hi = grams.max()
        val pad = maxOf(100, (hi - lo) / 5)
        val min = lo - pad; val span = (hi + pad - min).toFloat()
        fun x(i: Int) = if (grams.size == 1) size.width / 2 else i * size.width / (grams.size - 1)
        fun y(g: Int) = size.height - (g - min) / span * size.height
        for (k in 0..2) {
            val gy = size.height * k / 2
            drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        val path = Path()
        grams.forEachIndexed { i, g -> if (i == 0) path.moveTo(x(i), y(g)) else path.lineTo(x(i), y(g)) }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        grams.forEachIndexed { i, g ->
            val r = if (i == grams.lastIndex) 5.dp.toPx() else 3.dp.toPx()
            drawCircle(line, r, Offset(x(i), y(g)))
        }
    }
}

@Composable
private fun WeightDialog(name: String, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val grams = WeightTrend.parseKg(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("{0}'s weight", name)) },
        text = {
            OutlinedTextField(
                text, { text = it.take(8) }, label = { Text(tr("Kilograms, e.g. 4.2")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = text.isNotBlank() && grams == null,
            )
        },
        confirmButton = { TextButton(enabled = grams != null, onClick = { grams?.let(onSave) }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}
