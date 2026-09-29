package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AdaptiveTiming
import com.pawpixel.core.AppState
import com.pawpixel.core.CareStats
import com.pawpixel.core.HealthPlan
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.core.TaskKind
import com.pawpixel.core.TaskStatus
import com.pawpixel.sprite.PetEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetScreen(app: AppScope, state: AppState, pet: Pet) {

    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val art = remember(pet.lookKey) { app.repo.art(pet) }
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var reaction by remember { mutableStateOf<Reaction?>(null) }
    var makingGif by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    fun react(event: PetEvent) { reaction = Reaction(event, (reaction?.nonce ?: 0) + 1) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(app)
            Spacer(Modifier.weight(1f))
            val editLabel = tr("Edit {0}'s name, type and birthday", pet.name)
            TextButton(onClick = { renaming = true }, modifier = Modifier.semantics { contentDescription = editLabel }) { Text(tr("Edit")) }
        }
        PixelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LivePet(
                    art, pet.eyes, reading.mood, seed = pet.id.hashCode(), modifier = Modifier.fillMaxWidth(), reaction = reaction,
                    description = MoodEngine.describe(pet.name, reading.mood),
                )
                Text(pet.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, modifier = Modifier.semantics { heading() })
                pet.birthDay?.let { born ->
                    Text(HealthPlan.ageLabel(born, app.repo.clock.dayIndex(app.now)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(reading.caption, textAlign = TextAlign.Center)
                Text(
                    hearts(reading.score), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = tr("Happiness {0} of 5", (reading.score + 10) / 20) },
                )
                Text(
                    tr("Tap {0} to give pets", pet.name),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }

        MilestoneBanner(app, pet)
        // Where a new owner lands after making their pet: the next step is the home screen.
        WidgetTip(app, state, pet)

        // Gentle progress: days cared for this week, never a streak that breaks.
        val week = CareStats.week(state, pet.id, app.now, app.repo.clock)
        val summary = CareStats.summary(state, pet.id, app.now, app.repo.clock)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = summary },
        ) {
            Text(week.joinToString(" ") { if (it) "●" else "○" }, color = MaterialTheme.colorScheme.primary)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        nextMilestoneLine(pet)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // Household: who else cares for this pet, or an invitation to share it.
        val household = app.repo.family.household
        if (pet.shared && household != null) {
            val others = household.members.filter { it.userId != app.repo.family.myUserId }.joinToString { it.name }
            TextButton(onClick = { app.navigate(Screen.Family()) }) {
                Text(if (others.isEmpty()) tr("Shared with {0}", household.name) else tr("Cared for with {0}", others), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            OutlinedButton(onClick = { app.navigate(Screen.Family(sharePetId = pet.id)) }) { Text(tr("👪 Share with your household")) }
        }

        SectionTitle(tr("Care"))
        if (statuses.isEmpty()) Text(tr("No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care.", pet.name))
        statuses.forEach { s -> TaskRow(app, state, pet, s, onDone = { react(PetEvent.Cared(s.task.kind)) }) }
        RemindersCard(app, state, pet)
        OutlinedButton(onClick = { app.navigate(Screen.EditTask(pet.id, null)) }) { Text(tr("+ Add care task")) }

        Spacer(Modifier.height(8.dp))
        HealthSection(app, state, pet)

        Spacer(Modifier.height(8.dp))
        WeightSection(app, state, pet)

        Spacer(Modifier.height(8.dp))
        OutfitSection(app, pet)

        Spacer(Modifier.height(8.dp))
        SectionTitle(tr("Share & sprite"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !makingGif && art != null,
                onClick = {
                    val s = art ?: return@Button
                    makingGif = true
                    shareError = null
                    app.launch {
                        try {
                            val gif = withContext(Dispatchers.Default) {
                                runCatching { app.repo.animationGif(pet, s) }
                                    .onFailure { app.repo.platform.log("GIF failed: ${it.stackTraceToString()}") }
                                    .getOrNull()
                            }
                            if (gif != null) {
                                app.repo.platform.log("GIF ready: ${gif.size} bytes")
                                app.repo.shareGif(pet, gif)
                            } else {
                                shareError = tr("Couldn't make the animation. Please try again.")
                            }
                        } finally {
                            makingGif = false
                        }
                    }
                },
            ) { Text(if (makingGif) tr("Making GIF…") else tr("Share animation")) }
            // A pet from a family member's phone has no photo here, so no before/after card.
            val hasPhoto = remember(pet.id, pet.spriteVersion) { app.repo.photoCrop(pet.id) != null }
            if (hasPhoto) OutlinedButton(onClick = { app.launch { app.repo.shareReveal(pet) } }) { Text(tr("Before/after")) }
        }
        shareError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { app.navigate(Screen.RemakeSprite(pet.id)) }) { Text(tr("Edit look: photo, face, ears")) }
        TextButton(onClick = { confirmDelete = true }) { Text(tr("Delete {0}", pet.name), color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete {0}?", pet.name)) },
            text = {
                Text(
                    tr("This removes the sprite, tasks and history from this phone. It can't be undone.") +
                        if (pet.shared) " " + tr("Your household keeps their copy of {0}, no longer shared.", pet.name) else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; app.launch { app.repo.deletePet(pet.id) } }) {
                    Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
    if (renaming) RenameDialog(app, pet) { renaming = false }
}

/**
 * Asks for the notification permission here, once, next to the care it's for: never out of the
 * blue at first launch. Hidden once answered (either way) or when notifications already work.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindersCard(app: AppScope, state: AppState, pet: Pet) {
    val s = state.settings
    if (s.remindersAsked || !s.remindersEnabled || state.tasksFor(pet.id).none { it.remindersOn }) return
    val allowed = remember(app.now) { app.repo.platform.notificationsAllowed() }
    if (allowed == true) return
    PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("🔔 Reminders for {0}?", pet.name), fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(
                tr("A gentle nudge when it's time for these, only for the tasks you set. Change them any time."),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    app.repo.platform.requestNotificationPermission()
                    app.launch { app.repo.setSettings(s.copy(remindersAsked = true)) }
                }) { Text(tr("Turn on reminders")) }
                TextButton(onClick = { app.launch { app.repo.setSettings(s.copy(remindersAsked = true)) } }) { Text(tr("Not now")) }
            }
        }
    }
}

/** "Fed by Jamaica · 7:02 AM": care someone else in the household logged, from the owner's side. */
fun doneBy(kind: TaskKind, who: String, time: String): String = when (kind) {
    TaskKind.FEED -> tr("Fed by {0} · {1}", who, time)
    TaskKind.WATER -> tr("Water refilled by {0} · {1}", who, time)
    TaskKind.WALK -> tr("Walked by {0} · {1}", who, time)
    TaskKind.PLAY -> tr("Playtime with {0} · {1}", who, time)
    TaskKind.MEDS -> tr("Medicine given by {0} · {1}", who, time)
    TaskKind.GROOM -> tr("Groomed by {0} · {1}", who, time)
    TaskKind.LITTER -> tr("Litter cleaned by {0} · {1}", who, time)
    else -> tr("Done by {0} · {1}", who, time)
}

private fun hearts(score: Int): String {
    val full = ((score + 10) / 20).coerceIn(0, 5)
    return "♥".repeat(full) + "♡".repeat(5 - full)
}

@Composable
private fun TaskRow(app: AppScope, state: AppState, pet: Pet, s: TaskStatus, onDone: () -> Unit) {
    val clock = app.repo.clock
    val t = s.task
    val name = trName(t.title)
    val detail = when {
        s.isOverdue -> tr("Waiting since {0}", formatTime(s.overdueSinceMs!!, clock))
        s.allDoneThisCycle && t.everyDays > 1 -> tr("Done · next {0}", s.nextDueMs?.let { relativeDay(it, app.now, clock) } ?: "-")
        s.allDoneThisCycle -> tr("All done today ✓")
        else -> tr("Next {0}", s.nextDueMs?.let { relativeDay(it, app.now, clock) } ?: "-")
    }
    val times = s.slotTimes.joinToString(" · ") { formatTime(it, clock) } +
        if (t.everyDays > 1) "  " + tr("(every {0} days)", t.everyDays) else ""
    PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${t.kind.emoji} $name", fontWeight = FontWeight.Bold)
                Text(detail, color = if (s.isOverdue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(times, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Household: "Fed by Jamaica · 7:02 AM" when someone else did it today.
                val today = clock.dayIndex(app.now)
                state.completions.lastOrNull { it.taskId == t.id && it.localDay == today }?.let { c ->
                    app.repo.family.nameOf(c.by)?.let { who ->
                        Text(doneBy(t.kind, who, formatTime(c.atMs, clock)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (t.adaptive && AdaptiveTiming.effectiveSlots(t, state.completions, app.now, clock) != t.slots.sorted()) {
                    Text(tr("Adjusted to your routine"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!s.allDoneThisCycle) {
                    val doneLabel = tr("Mark {0} done for {1}", name, pet.name)
                    Button(
                        onClick = { onDone(); app.launch { app.repo.complete(t.id) } },
                        modifier = Modifier.semantics { contentDescription = doneLabel },
                    ) { Text(tr("Done")) }
                }
                Row {
                    // Undo takes back your own Done, never someone else's.
                    val mineThisCycle = s.logged > 0 && state.completions
                        .any { it.taskId == t.id && it.localDay >= s.cycleStartDay && app.repo.family.isMine(it.by) }
                    val undoLabel = tr("Undo {0} for {1}", name, pet.name)
                    val editLabel = tr("Edit {0}", name)
                    if (mineThisCycle) TextButton(onClick = { app.launch { app.repo.undo(t.id) } }, modifier = Modifier.semantics { contentDescription = undoLabel }) { Text(tr("Undo")) }
                    TextButton(onClick = { app.navigate(Screen.EditTask(pet.id, t.id)) }, modifier = Modifier.semantics { contentDescription = editLabel }) { Text(tr("Edit")) }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(app: AppScope, pet: Pet, onClose: () -> Unit) {
    var name by remember { mutableStateOf(pet.name) }
    var species by remember { mutableStateOf(pet.species) }
    var birthDay by remember { mutableStateOf(pet.birthDay) }
    var askBirthday by remember { mutableStateOf(false) }
    val today = app.repo.clock.dayIndex(app.now)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Edit pet")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text(tr("Name")) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Species.entries.forEach { sp ->
                        FilterChip(selected = species == sp, onClick = { species = sp }, label = { Text(tr(sp.label)) })
                    }
                }
                BirthdayRow(birthDay, today) { askBirthday = true }
            }
        },
        confirmButton = { TextButton(onClick = { app.launch { app.repo.renamePet(pet, name, species, birthDay) }; onClose() }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
    if (askBirthday) {
        BirthdayDialog(
            name.ifBlank { pet.name }, birthDay, today, skipLabel = tr("Cancel"),
            onSkip = { askBirthday = false }, onSave = { birthDay = it; askBirthday = false },
        )
    }
}
