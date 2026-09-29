package com.pawpixel.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.app.decodeImage
import com.pawpixel.app.rememberPhotoPicker
import com.pawpixel.core.AppState
import com.pawpixel.core.CareStats
import com.pawpixel.core.DAY_MS
import com.pawpixel.core.HealthItem
import com.pawpixel.core.HealthPlan
import com.pawpixel.core.Pet
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

/**
 * The pet's health care: vaccines, deworming, tick & flea, check-ups. Each row says when it's due,
 * records when it was given, and can keep a photo of the vaccination card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthSection(app: AppScope, state: AppState, pet: Pet) {
    val clock = app.repo.clock
    val today = clock.dayIndex(app.now)
    val health = CareStats.healthDue(state, pet.id, app.now, clock)
    var askBirthday by remember { mutableStateOf(false) }

    Text(tr("Health"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    pet.birthDay?.let { Text(tr("{0} is {1}.", pet.name, HealthPlan.ageLabel(it, today)), style = MaterialTheme.typography.bodySmall) }
    if (health.isEmpty()) {
        Text(
            tr("Keep track of {0}'s anti-rabies shot, other vaccines, deworming, tick & flea care and vet check-ups.", pet.name) + " " +
                tr("PawPixel reminds you a few days before each is due."),
        )
        Button(onClick = { askBirthday = true }) { Text(tr("+ Add health reminders")) }
    }
    // Keyed by task, so each row keeps its own dialogs and picker when the order changes.
    health.forEach { h -> key(h.task.id) { HealthRow(app, state, pet, h) } }
    if (health.isNotEmpty()) {
        TextButton(onClick = { app.navigate(Screen.EditTask(pet.id, null, health = true)) }) { Text(tr("+ Add health item")) }
        Text(
            if (HealthPlan.isYoung(pet.birthDay, today))
                tr("The first-year plan follows common Philippine schedules. If {0} missed a dose, ask your vet how to catch up; tap Edit to change anything.", pet.name)
            else tr("Schedules are typical for adult pets in the Philippines. Your vet's advice comes first: tap Edit to change them."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    LocalHelpCard()

    if (askBirthday) {
        // Dates are UTC midnights in the picker; a local day index is the same number of days.
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = pet.birthDay?.let { it * DAY_MS },
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MS <= today
            },
        )
        DatePickerDialog(
            onDismissRequest = { askBirthday = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedDateMillis != null,
                    onClick = {
                        val day = picker.selectedDateMillis?.let { it / DAY_MS }
                        askBirthday = false
                        app.launch { app.repo.addHealthCare(pet, day) }
                    },
                ) { Text(tr("Use this birthday")) }
            },
            dismissButton = {
                TextButton(onClick = { askBirthday = false; app.launch { app.repo.addHealthCare(pet, null) } }) { Text(tr("Adult / not sure")) }
            },
        ) {
            DatePicker(
                state = picker,
                title = { Text(tr("When was {0} born? A guess is fine.", pet.name), modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthRow(app: AppScope, state: AppState, pet: Pet, h: HealthItem) {
    val clock = app.repo.clock
    val t = h.task
    var askWhen by remember { mutableStateOf(false) }
    var showCard by remember { mutableStateOf(false) }
    var cardError by remember { mutableStateOf<String?>(null) }
    val cardRevision by app.repo.cardRevision.collectAsState()
    val hasCard = remember(t.id, cardRevision) { app.repo.hasCard(t) }
    val pickCard = rememberPhotoPicker { bytes ->
        if (bytes != null) app.launch {
            cardError = if (app.repo.saveCard(t, bytes)) null else tr("Couldn't read that photo. Try another one.")
        }
    }
    val given = state.completions.count { it.taskId == t.id }
    val seriesLine = if (t.series.isNotEmpty() && given < t.series.size) tr("First-year series: dose {0} of {1}", given + 1, t.series.size) else null

    PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${t.kind.emoji} ${trName(t.title)}", fontWeight = FontWeight.Bold)
                    Text(
                        CareStats.dueLabel(h, app.now, clock),
                        color = if (h.due) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    seriesLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    Text(
                        (h.lastDoneMs?.let { tr("Last: {0}", formatDate(it, clock)) } ?: tr("Not recorded yet")) + " · " + tr("then {0}", everyLabel(t.everyDays)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { askWhen = true }) { Text(tr("Done")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (hasCard) showCard = true else pickCard() }) {
                    Text(if (hasCard) tr("📷 View card") else tr("📷 Add card photo"))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (h.lastDoneMs != null) TextButton(onClick = { app.launch { app.repo.undo(t.id) } }) { Text(tr("Undo")) }
                TextButton(onClick = { app.navigate(Screen.EditTask(pet.id, t.id)) }) { Text(tr("Edit")) }
            }
            cardError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (askWhen) {
        AlertDialog(
            onDismissRequest = { askWhen = false },
            title = { Text(tr("When was it done?")) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WHEN_CHOICES.forEach { (days, label) ->
                        AssistChip(onClick = { askWhen = false; app.launch { app.repo.givenDaysAgo(t.id, days) } }, label = { Text(tr(label)) })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askWhen = false }) { Text(tr("Cancel")) } },
        )
    }
    if (showCard) {
        // Read and decode the photo off the main thread; null until ready.
        val image by produceState<Result<ImageBitmap?>?>(null, t.id, cardRevision) {
            value = withContext(Dispatchers.Default) { runCatching { app.repo.card(t)?.let { decodeImage(it) } } }
        }
        AlertDialog(
            onDismissRequest = { showCard = false },
            title = { Text("${trName(t.title)} · ${pet.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val loaded = image
                    if (loaded == null) {
                        Text(tr("Opening…"))
                    } else if (loaded.getOrNull() != null) {
                        Image(loaded.getOrNull()!!, contentDescription = tr("Photo of {0}'s card for {1}", pet.name, trName(t.title)), contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp))
                    } else Text(tr("The photo couldn't be opened."))
                    Text(tr("Kept only on this phone (and in your backups)."), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showCard = false }) { Text(tr("Close")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { showCard = false; app.repo.deleteCard(t) }) { Text(tr("Remove"), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showCard = false; pickCard() }) { Text(tr("Replace")) }
                }
            },
        )
    }
}

/** What the law asks and where to go, for owners in the Philippines (the pilot is in Naga City). */
@Composable
private fun LocalHelpCard() {
    var open by remember { mutableStateOf(false) }
    PixelCard(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) tr("Hide") else tr("Show"), role = Role.Button) { open = !open }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Rabies rules and where to get shots"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (open) "▲" else "▼")
            }
            if (open) {
                Text(tr("The Anti-Rabies Act (RA 9482) asks every dog owner to:"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOf(
                        tr("have the dog vaccinated against rabies every year, and keep the card"),
                        tr("register the dog with the city or municipality"),
                        tr("keep it on a leash outside the home"),
                        tr("report a bite within 24 hours and help the person get treated"),
                    ).joinToString("\n") { "• $it" },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(tr("If someone is bitten or scratched"), fontWeight = FontWeight.Bold)
                Text(
                    tr("Wash the wound with soap and running water for 15 minutes, then go to the nearest Animal Bite Treatment Center the same day.") + " " +
                        tr("Watch the pet for 14 days."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(tr("In Naga City"), fontWeight = FontWeight.Bold)
                Text(
                    "City Veterinary Office, Maharlika Highway, Del Rosario · cvo@naga.gov.ph\n" +
                        tr("Anti-rabies shots for pets 3 months and older (₱75 walk-in in the city's 2023 list; ask for current fees).") + " " +
                        tr("Free consultations; deworming and spay/neuter services.") + " " +
                        tr("Free rabies drives are usually held in March, Rabies Awareness Month."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** "When was it given?" choices, in days ago. */
val WHEN_CHOICES = listOf(0 to "Today", 1 to "Yesterday", 7 to "A week ago", 30 to "A month ago", 91 to "3 months ago", 182 to "6 months ago", 365 to "A year ago")

fun everyLabel(days: Int): String = when (days) {
    1 -> tr("daily"); 7 -> tr("weekly"); 14 -> tr("every 2 weeks"); 30 -> tr("monthly"); 90 -> tr("every 3 months"); 180 -> tr("every 6 months"); 365 -> tr("yearly")
    else -> tr("every {0} days", days)
}
