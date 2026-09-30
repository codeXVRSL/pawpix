package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pawpixel.core.DAY_MS
import com.pawpixel.core.HealthPlan
import com.pawpixel.core.HealthPlan.AgeUnit
import com.pawpixel.core.LocalClock
import com.pawpixel.i18n.tr

/**
 * "When was Mochi born?": an exact date, or "about 10 weeks old" (most owners of a rescued or
 * adopted pet only know roughly). [skipLabel] names the way out ("Adult / not sure", "Cancel").
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BirthdayDialog(
    petName: String,
    initial: Long?,
    today: Long,
    skipLabel: String,
    onSkip: () -> Unit,
    onSave: (Long) -> Unit,
    /** Tapped outside the dialog (or Back): nothing chosen. */
    onDismiss: () -> Unit = onSkip,
) {
    var exact by remember { mutableStateOf(initial != null) }
    var amount by remember { mutableIntStateOf(8) }
    var unit by remember { mutableStateOf(AgeUnit.WEEKS) }
    // Dates are UTC midnights in the picker; a local day index is the same number of days.
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = initial?.let { it * DAY_MS },
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS <= today
            override fun isSelectableYear(year: Int) = year <= LocalClock.civil(today).first
        },
    )
    val chosen: Long? = if (exact) picker.selectedDateMillis?.let { it / DAY_MS } else HealthPlan.birthDayFromAge(today, amount, unit)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = chosen != null, onClick = { chosen?.let(onSave) }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(skipLabel) } },
    ) {
        // One scrolling column: the dialog stacks its content in a single box, and a small phone
        // may not fit the calendar and the header together.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("When was {0} born?", petName), style = MaterialTheme.typography.titleLarge)
                Hint(tr("A guess is fine. It plans puppy and kitten shots and deworming."))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(!exact, { exact = false }, tr("About how old"))
                    ChoiceChip(exact, { exact = true }, tr("Exact date"))
                }
            }
            if (exact) {
                DatePicker(state = picker, title = null, showModeToggle = true)
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val limit = maxAmount(unit)
                        StepperButton("−", tr("Younger"), enabled = amount > 1) { amount-- }
                        Text(
                            "$amount", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 40.dp),
                        )
                        StepperButton("+", tr("Older"), enabled = amount < limit) { amount++ }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AgeUnit.entries.forEach { u ->
                            ChoiceChip(unit == u, { unit = u; amount = amount.coerceAtMost(maxAmount(u)) }, unitLabel(u))
                        }
                    }
                    chosen?.let {
                        Text(
                            tr("About {0} · born around {1}", ageText(amount, unit), LocalClock.shortDate(it)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun maxAmount(unit: AgeUnit) = when (unit) { AgeUnit.WEEKS -> 52; AgeUnit.MONTHS -> 24; AgeUnit.YEARS -> 30 }

private fun unitLabel(unit: AgeUnit) = when (unit) { AgeUnit.WEEKS -> tr("Weeks"); AgeUnit.MONTHS -> tr("Months"); AgeUnit.YEARS -> tr("Years") }

/** "10 weeks old", "1 year old". */
private fun ageText(n: Int, unit: AgeUnit) = when (unit) {
    AgeUnit.WEEKS -> if (n == 1) tr("1 week old") else tr("{0} weeks old", n)
    AgeUnit.MONTHS -> if (n == 1) tr("1 month old") else tr("{0} months old", n)
    AgeUnit.YEARS -> if (n == 1) tr("1 year old") else tr("{0} years old", n)
}

@Composable
private fun StepperButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    RoundIconButton(label, description, modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) { if (enabled) onClick() }
}

/** The pet's birthday in a form: "4 months old · born May 29, 2026   Change", or an invitation to add it. */
@Composable
fun BirthdayRow(birthDay: Long?, today: Long, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Birthday"), style = MaterialTheme.typography.titleSmall)
            Hint(
                birthDay?.let { tr("{0} · born {1}", HealthPlan.ageLabel(it, today), LocalClock.shortDate(it)) }
                    ?: tr("Optional. It plans puppy and kitten shots."),
            )
        }
        LinkButton(if (birthDay == null) tr("Add birthday") else tr("Change"), onClick = onEdit)
    }
}
