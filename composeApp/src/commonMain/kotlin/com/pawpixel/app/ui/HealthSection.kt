package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import com.pawpixel.app.rememberPhotoPicker
import com.pawpixel.core.AppState
import com.pawpixel.core.CareStats
import com.pawpixel.core.CareTask
import com.pawpixel.core.HealthItem
import com.pawpixel.core.HealthPlan
import com.pawpixel.core.HealthRecord
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcons
import com.pawpixel.i18n.trName

/**
 * The pet's health care: vaccines, deworming, tick & flea, check-ups. Each item says when it's next
 * due (for a puppy or kitten, which dose of its series), records when it was given with an optional
 * photo of the vaccination card, and keeps the history. Then the Philippine rules and where to go.
 */
@Composable
fun HealthSection(app: AppScope, state: AppState, pet: Pet) {
    val today = app.repo.clock.dayIndex(app.now)
    val health = CareStats.healthDue(state, pet.id, app.now, app.repo.clock)
    var askBirthday by remember { mutableStateOf(false) }

    if (health.isEmpty()) {
        Hint(
            tr("Keep track of {0}'s anti-rabies shot, other vaccines, deworming, tick & flea care and vet check-ups.", pet.name) + " " +
                tr("PawPixel reminds you a few days before each is due."),
        )
        PrimaryPill(tr("+ Add health reminders")) { if (pet.birthDay == null) askBirthday = true else app.launch { app.repo.addHealthCare(pet, null) } }
    } else if (health.any { it.scheduled }) {
        Text(
            tr("Typical schedule — confirm with your vet."),
            style = MaterialTheme.typography.labelMedium, color = Paw.palette.good,
        )
    }
    // Keyed by task, so each row keeps its own dialogs when the order changes.
    health.forEach { h -> key(h.task.id) { HealthRow(app, state, pet, h) } }
    if (health.isNotEmpty()) {
        GhostPill(tr("+ Add health item")) { app.navigate(Screen.EditTask(pet.id, null, health = true)) }
        Hint(
            if (HealthPlan.isYoung(pet.birthDay, today))
                tr("The first-year plan follows common Philippine schedules. If {0} missed a dose, ask your vet how to catch up; tap Edit to change anything.", pet.name)
            else tr("Schedules are typical for adult pets in the Philippines. Your vet's advice comes first: tap Edit to change them."),
        )
    }
    if (pet.species != Species.OTHER) PhilippineInfoCard(app)

    if (askBirthday) {
        BirthdayDialog(
            pet.name, initial = null, today = today, skipLabel = tr("Adult / not sure"),
            onSkip = { askBirthday = false; app.launch { app.repo.addHealthCare(pet, null) } },
            onDismiss = { askBirthday = false },
            onSave = { day -> askBirthday = false; app.launch { app.repo.addHealthCare(pet, day) } },
        )
    }
}

@Composable
private fun HealthRow(app: AppScope, state: AppState, pet: Pet, h: HealthItem) {
    val clock = app.repo.clock
    val t = h.task
    var recording by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<HealthRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val revision by app.repo.cardRevision.collectAsState()
    val records = CareStats.healthRecords(state, t.id)
    val latest = records.firstOrNull()
    val latestHasPhoto = remember(latest?.completion?.id, revision) { latest != null && app.repo.hasRecordPhoto(t.petId, latest.completion.id) }
    val name = trName(t.title)

    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val toy = Candy.forKind(t.kind)
                CandyTile(PixelIcons.forKind(t.kind), toy, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        CareStats.dueLabel(h, app.now, clock), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (h.due) FontWeight.Bold else null,
                        color = if (h.due) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    val detail = listOfNotNull(
                        h.dose?.let { tr("Dose {0} of {1}", it, h.doses) },
                        h.dueMs?.let { formatDate(it, clock) },
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = Paw.palette.good)
                    Hint(
                        (latest?.let { tr("Last: {0}", LocalClock.shortDate(it.completion.localDay)) } ?: tr("Not recorded yet")) +
                            " · " + tr("then {0}", everyLabel(repeatNow(pet, t, clock.dayIndex(app.now)))),
                    )
                }
                val recordLabel = tr("Record {0} for {1}", name, pet.name)
                ToyButton(
                    tr("Done"), style = if (h.due) ToyStyle.Primary else ToyStyle.Colored(toy),
                    modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = recordLabel },
                ) { recording = true }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (latest != null && latestHasPhoto) {
                    PhotoThumb(app, recordPhoto(app, t, latest, revision), tr("Photo of {0}'s card for {1}", pet.name, name), size = 48.dp) { viewing = latest }
                }
                if (records.isNotEmpty() || app.repo.hasCard(t)) {
                    LinkButton(tr("History ({0})", records.size)) { history = true }
                }
                Spacer(Modifier.weight(1f))
                val editLabel = tr("Edit {0}", name)
                LinkButton(tr("Edit"), modifier = Modifier.semantics { contentDescription = editLabel }, color = MaterialTheme.colorScheme.onSurfaceVariant) { app.navigate(Screen.EditTask(pet.id, t.id)) }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (recording) RecordDialog(app, pet, t, h) { msg -> recording = false; message = msg }
    if (history) HistoryDialog(app, pet, t, records, revision) { history = false }
    viewing?.let { r ->
        PhotoViewer(
            recordPhoto(app, t, r, revision), "$name · ${LocalClock.shortDate(r.completion.localDay)}",
            tr("Photo of {0}'s card for {1}", pet.name, name), onClose = { viewing = null },
            onDelete = { viewing = null; app.repo.deleteRecordPhoto(t.petId, r.completion.id) },
        )
    }
}

/** How often the item repeats now: a puppy's or kitten's deworming is more frequent while it's young. */
private fun repeatNow(pet: Pet, t: CareTask, today: Long): Int {
    val born = pet.birthDay ?: return t.everyDays
    return HealthPlan.scheduleFor(pet, t)?.everyAt(today - born, t.everyDays) ?: t.everyDays
}

private fun recordPhoto(app: AppScope, t: CareTask, r: HealthRecord, revision: Long) =
    HealthPhoto("rec-${r.completion.id}", revision) { app.repo.recordPhoto(t.petId, r.completion.id) }

/** "When was it done?", with an optional photo of the card or receipt, then Save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordDialog(app: AppScope, pet: Pet, t: CareTask, h: HealthItem, onClose: (String?) -> Unit) {
    var daysAgo by remember { mutableIntStateOf(0) }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var picks by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val pick = rememberPhotoPicker { bytes -> if (bytes != null) { photo = bytes; picks++ } }
    AlertDialog(
        onDismissRequest = { if (!saving) onClose(null) },
        title = { Text(tr("When was it done?")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                h.dose?.let { Text(tr("{0}, dose {1} of {2}", trName(t.title), it, h.doses), fontWeight = FontWeight.Bold) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WHEN_CHOICES.forEach { (days, label) -> ChoiceChip(daysAgo == days, { daysAgo = days }, tr(label)) }
                }
                Text(tr("Photo of the vaccination card or receipt (optional)"), style = MaterialTheme.typography.bodyMedium)
                val chosen = photo
                if (chosen == null) {
                    GhostPill(tr("Add photo"), icon = PixelIcons.CAMERA, onClick = pick)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhotoThumb(app, HealthPhoto("new", picks.toLong()) { chosen }, tr("The photo you picked"), onClick = null)
                        Text(tr("Photo added"), modifier = Modifier.weight(1f))
                        LinkButton(tr("Remove")) { photo = null }
                    }
                }
                Hint(tr("Photos stay on this phone (and in your backup files)."))
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                app.launch { onClose(app.repo.recordHealth(t, daysAgo, photo)) }
            }) { Text(if (saving) tr("Saving…") else tr("Save")) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { onClose(null) }) { Text(tr("Cancel")) } },
    )
}

/** Every time the item was given: date, dose, who (with family sharing) and its photo. */
@Composable
private fun HistoryDialog(app: AppScope, pet: Pet, t: CareTask, records: List<HealthRecord>, revision: Long, onClose: () -> Unit) {
    val name = trName(t.title)
    var viewing by remember { mutableStateOf<HealthRecord?>(null) }
    var viewCard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<HealthRecord?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberPhotoPicker { bytes ->
        val id = pickFor
        if (bytes != null && id != null) app.launch {
            error = if (app.repo.saveRecordPhoto(t.petId, id, bytes)) null else tr("Couldn't read that photo. Try another one.")
        }
    }
    val mine = setOf(null, app.repo.family.myUserId)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("$name · ${pet.name}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasCard = remember(t.id, revision) { app.repo.hasCard(t) }
                if (hasCard) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PhotoThumb(app, HealthPhoto("card-${t.id}", revision) { app.repo.card(t) }, tr("Photo of {0}'s card for {1}", pet.name, name)) { viewCard = true }
                        Text(tr("Card photo"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    }
                    HorizontalDivider()
                }
                records.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider()
                    HistoryRow(app, pet, t, r, revision, canDelete = r.completion.by in mine,
                        onView = { viewing = r }, onAddPhoto = { pickFor = r.completion.id; pick() }, onDelete = { confirmDelete = r })
                }
                if (records.isEmpty()) Text(tr("Not recorded yet"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Close")) } },
    )
    viewing?.let { r ->
        PhotoViewer(
            recordPhoto(app, t, r, revision), "$name · ${LocalClock.shortDate(r.completion.localDay)}",
            tr("Photo of {0}'s card for {1}", pet.name, name), onClose = { viewing = null },
            onDelete = { viewing = null; app.repo.deleteRecordPhoto(t.petId, r.completion.id) },
        )
    }
    if (viewCard) {
        PhotoViewer(
            HealthPhoto("card-${t.id}", revision) { app.repo.card(t) }, "$name · ${pet.name}",
            tr("Photo of {0}'s card for {1}", pet.name, name), onClose = { viewCard = false },
            onDelete = { viewCard = false; app.repo.deleteCard(t) },
        )
    }
    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(tr("Delete this record?")) },
            text = { Text(tr("{0} on {1}. Its photo is deleted too.", name, LocalClock.shortDate(r.completion.localDay))) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; app.launch { app.repo.deleteRecord(t, r.completion.id) } }) {
                    Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun HistoryRow(
    app: AppScope, pet: Pet, t: CareTask, r: HealthRecord, revision: Long, canDelete: Boolean,
    onView: () -> Unit, onAddPhoto: () -> Unit, onDelete: () -> Unit,
) {
    val hasPhoto = remember(r.completion.id, revision) { app.repo.hasRecordPhoto(t.petId, r.completion.id) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasPhoto) PhotoThumb(app, recordPhoto(app, t, r, revision), tr("Photo of {0}'s card for {1}", pet.name, trName(t.title)), onClick = onView)
        Column(Modifier.weight(1f)) {
            Text(LocalClock.shortDate(r.completion.localDay), fontWeight = FontWeight.Bold)
            val detail = listOfNotNull(
                r.dose?.let { tr("Dose {0}", it) },
                app.repo.family.nameOf(r.completion.by)?.let { tr("by {0}", it) },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                if (!hasPhoto) LinkButton(tr("Add photo"), icon = PixelIcons.CAMERA, onClick = onAddPhoto)
                if (canDelete) LinkButton(tr("Delete"), color = MaterialTheme.colorScheme.error, onClick = onDelete)
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
