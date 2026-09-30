package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName
import com.pawpixel.sprite.PetEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetScreen(app: AppScope, state: AppState, pet: Pet) {
    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val art = remember(pet.lookKey) { app.repo.art(pet) }
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var reaction by remember { mutableStateOf<Reaction?>(null) }
    var burst by remember { mutableStateOf<Long?>(null) }
    var makingGif by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    fun react(event: PetEvent) {
        reaction = Reaction(event, (reaction?.nonce ?: 0) + 1)
        if (event is PetEvent.Cared) { burst = app.now + reaction!!.nonce; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    val clock = app.repo.clock
    val scene = sceneFor(clock.minuteOfDay(app.now), state.settings.nightStart, state.settings.nightEnd)

    Box(Modifier.fillMaxSize().background(heroGlow())) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar(app, title = null) {
            val editLabel = tr("Edit {0}'s name, type and birthday", pet.name)
            GhostPill(tr("Edit"), modifier = Modifier.semantics { contentDescription = editLabel }) { renaming = true }
        }

        // The pet's little world: the sky of the hour, the pet living on its floor, its name and mood under it.
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 0.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth()) {
                    LivePet(
                        art, pet.eyes, reading.mood, seed = pet.id.hashCode(), modifier = Modifier.fillMaxWidth(), reaction = reaction,
                        description = MoodEngine.describe(pet.name, reading.mood), scene = scene,
                        onPetted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    )
                    HeartBurst(burst, Modifier.matchParentSize())
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(pet.name, style = MaterialTheme.typography.petName, modifier = Modifier.semantics { heading() }, textAlign = TextAlign.Center)
                    pet.birthDay?.let { born -> Hint(HealthPlan.ageLabel(born, clock.dayIndex(app.now))) }
                    StatusPill(reading.caption, moodColor(reading.mood), modifier = Modifier.padding(top = 2.dp))
                    Hearts(
                        reading.score, Modifier.padding(top = 6.dp),
                        description = tr("Happiness {0} of 5", (reading.score + 10) / 20),
                    )
                    Hint(tr("Tap {0} to give pets", pet.name), align = TextAlign.Center)
                }
            }
        }

        MilestoneBanner(app, pet)

        // Gentle progress: days cared for this week, never a streak that breaks.
        val week = CareStats.week(state, pet.id, app.now, clock)
        val summary = CareStats.summary(state, pet.id, app.now, clock)
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("This week"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    WeekDots(week, weekdayOf(clock.dayIndex(app.now)))
                }
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                nextMilestoneLine(pet)?.let { Hint(it) }
            }
        }

        // Household: who else cares for this pet, or an invitation to share it.
        val household = app.repo.family.household
        if (pet.shared && household != null) {
            val others = household.members.filter { it.userId != app.repo.family.myUserId }.joinToString { it.name }
            LinkButton(if (others.isEmpty()) tr("Shared with {0}", household.name) else tr("Cared for with {0}", others)) { app.navigate(Screen.Family()) }
        } else {
            GhostPill(tr("👪 Share with your household")) { app.navigate(Screen.Family(sharePetId = pet.id)) }
        }

        SectionTitle(tr("Care"))
        if (statuses.isEmpty()) Text(tr("No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care.", pet.name))
        statuses.forEach { s -> TaskRow(app, state, pet, s, onDone = { react(PetEvent.Cared(s.task.kind)) }) }
        GhostPill(tr("+ Add care task")) { app.navigate(Screen.EditTask(pet.id, null)) }
        // Reminders are offered here, under the care they're for.
        RemindersCard(app, state, pet)

        Spacer(Modifier.height(4.dp))
        HealthSection(app, state, pet)

        Spacer(Modifier.height(4.dp))
        WeightSection(app, state, pet)

        Spacer(Modifier.height(4.dp))
        OutfitSection(app, pet)

        Spacer(Modifier.height(4.dp))
        SectionTitle(tr("Share & sprite"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryPill(
                if (makingGif) tr("Making GIF…") else tr("Share animation"),
                enabled = !makingGif && art != null,
                onClick = {
                    val s = art ?: return@PrimaryPill
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
            )
            // A pet from a family member's phone has no photo here, so no before/after card.
            val hasPhoto = remember(pet.id, pet.spriteVersion) { app.repo.photoCrop(pet.id) != null }
            if (hasPhoto) TonalPill(tr("Before/after")) { app.launch { app.repo.shareReveal(pet) } }
            GhostPill(tr("Edit look: photo, face, ears")) { app.navigate(Screen.RemakeSprite(pet.id)) }
        }
        shareError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LinkButton(tr("Delete {0}", pet.name), color = MaterialTheme.colorScheme.error) { confirmDelete = true }
        Spacer(Modifier.height(20.dp))
    }
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
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("🔔 Reminders for {0}?", pet.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                tr("A gentle nudge when it's time for these, only for the tasks you set. Change them any time."),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryPill(tr("Turn on reminders")) {
                    app.repo.platform.requestNotificationPermission()
                    app.launch { app.repo.setSettings(s.copy(remindersAsked = true)) }
                }
                LinkButton(tr("Not now"), color = MaterialTheme.colorScheme.onTertiaryContainer) { app.launch { app.repo.setSettings(s.copy(remindersAsked = true)) } }
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
    val done = s.allDoneThisCycle
    SoftCard(Modifier.fillMaxWidth(), tone = if (done) Tone.Tonal else Tone.Surface, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(t.kind.emoji, tone = if (s.isOverdue) Tone.Accent else if (done) Tone.Good else Tone.Tonal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        detail, style = MaterialTheme.typography.bodyMedium,
                        color = when { s.isOverdue -> MaterialTheme.colorScheme.primary; done -> Paw.palette.good; else -> MaterialTheme.colorScheme.onSurface },
                    )
                    Hint(times)
                }
                if (!done) {
                    val doneLabel = tr("Mark {0} done for {1}", name, pet.name)
                    PrimaryPill(
                        tr("Done"), modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = doneLabel },
                    ) { onDone(); app.launch { app.repo.complete(t.id) } }
                }
            }
            // Household: "Fed by Jamaica · 7:02 AM" when someone else did it today.
            val today = clock.dayIndex(app.now)
            state.completions.lastOrNull { it.taskId == t.id && it.localDay == today }?.let { c ->
                app.repo.family.nameOf(c.by)?.let { who ->
                    Text(doneBy(t.kind, who, formatTime(c.atMs, clock)), style = MaterialTheme.typography.bodySmall, color = Paw.palette.good)
                }
            }
            if (t.adaptive && AdaptiveTiming.effectiveSlots(t, state.completions, app.now, clock) != t.slots.sorted()) {
                Text(tr("Adjusted to your routine"), style = MaterialTheme.typography.bodySmall, color = Paw.palette.good)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Undo takes back your own Done, never someone else's.
                val mineThisCycle = s.logged > 0 && state.completions
                    .any { it.taskId == t.id && it.localDay >= s.cycleStartDay && app.repo.family.isMine(it.by) }
                val undoLabel = tr("Undo {0} for {1}", name, pet.name)
                val editLabel = tr("Edit {0}", name)
                if (mineThisCycle) LinkButton(tr("Undo"), modifier = Modifier.semantics { contentDescription = undoLabel }) { app.launch { app.repo.undo(t.id) } }
                LinkButton(tr("Edit"), modifier = Modifier.semantics { contentDescription = editLabel }, color = MaterialTheme.colorScheme.onSurfaceVariant) { app.navigate(Screen.EditTask(pet.id, t.id)) }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text(tr("Name")) }, singleLine = true, shape = MaterialTheme.shapes.small)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Species.entries.forEach { sp -> ChoiceChip(species == sp, { species = sp }, tr(sp.label)) }
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
