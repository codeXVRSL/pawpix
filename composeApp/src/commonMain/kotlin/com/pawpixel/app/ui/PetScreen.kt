package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.pawpixel.sprite.PixelIcons
import com.pawpixel.sprite.Room
import com.pawpixel.core.LocalClock
import com.pawpixel.core.WeightTrend
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetScreen(app: AppScope, state: AppState, pet: Pet) {
    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val art = remember(pet.lookKey) { app.repo.art(pet) }
    // Everyday care here; health care (a vaccine, a vet visit) lives behind the Health door.
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    var renaming by remember { mutableStateOf(false) }
    var reaction by remember { mutableStateOf<Reaction?>(null) }
    var burst by remember { mutableStateOf<Long?>(null) }
    val haptics = LocalHapticFeedback.current
    fun react(event: PetEvent) {
        reaction = Reaction(event, (reaction?.nonce ?: 0) + 1)
        if (event is PetEvent.Cared) { burst = app.now + reaction!!.nonce; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    val clock = app.repo.clock
    val phase = phaseFor(app, state)
    val night = phase.dark

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // The pet's room fills the top of the screen, edge to edge and under the status bar; the page
    // rises over its floor with a rounded top.
    val heroHeight = (maxHeight * 0.4f).coerceIn(260.dp, 380.dp)
    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(heroHeight).background(Color(Room.floorColor(phase)))) {
            LivePet(
                art, pet.eyes, reading.mood, seed = pet.id.hashCode(), modifier = Modifier.fillMaxWidth().height(heroHeight), reaction = reaction,
                description = MoodEngine.describe(pet.name, reading.mood), phase = phase, keepAspect = false, floorDepth = 34.dp,
                onPetted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            )
            HeartBurst(burst, Modifier.matchParentSize())
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassButton(PixelIcons.CHEVRON_LEFT, tr("Back"), night = night, onClick = app.back)
                Spacer(Modifier.weight(1f))
                GlassPill(tr("Edit"), tr("Edit {0}'s name, type and birthday", pet.name), night = night) { renaming = true }
            }
            // The page's rounded top, drawn inside the hero in the layout's own flow (a sheet pulled
            // over its neighbour with a layout trick lost taps far down the page).
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            )
        }
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        // Name, mood and age, hearts.
        Column(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(pet.name, style = MaterialTheme.typography.petName, modifier = Modifier.semantics { heading() }, textAlign = TextAlign.Center)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusPill(reading.caption, moodColor(reading.mood), modifier = Modifier.align(Alignment.CenterVertically))
                pet.birthDay?.let { born -> Hint(HealthPlan.ageLabel(born, clock.dayIndex(app.now)), modifier = Modifier.align(Alignment.CenterVertically)) }
            }
            Hearts(reading.score, Modifier.padding(top = 4.dp), description = tr("Happiness {0} of 5", (reading.score + 10) / 20))
        }

        MilestoneBanner(app, pet)
        RemindersCard(app, state, pet)

        // Today's care: one meter per task, the way a virtual pet shows hunger and energy.
        SectionTitle(tr("Care")) {
            LinkButton(tr("+ Add care task"), color = MaterialTheme.colorScheme.primary) { app.navigate(Screen.EditTask(pet.id, null)) }
        }
        if (statuses.isEmpty()) Hint(tr("No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care.", pet.name))
        statuses.forEach { s -> TaskRow(app, state, pet, s, onDone = { react(PetEvent.Cared(s.task.kind)) }) }

        // Gentle progress: days cared for this week, never a streak that breaks.
        val week = CareStats.week(state, pet.id, app.now, clock)
        val summary = CareStats.summary(state, pet.id, app.now, clock)
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("This week"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    WeekDots(week, weekdayOf(clock.dayIndex(app.now)))
                }
                Text(summary, style = MaterialTheme.typography.bodySmall)
                nextMilestoneLine(pet)?.let { Hint(it) }
            }
        }
        val household = app.repo.family.household
        if (pet.shared && household != null) {
            val others = household.members.filter { it.userId != app.repo.family.myUserId }.joinToString { it.name }
            LinkButton(if (others.isEmpty()) tr("Shared with {0}", household.name) else tr("Cared for with {0}", others), icon = PixelIcons.PEOPLE) { app.navigate(Screen.Family()) }
        }

        // The rest of the pet's life, behind four doors.
        val healthItems = CareStats.healthDue(state, pet.id, app.now, clock)
        val healthDetail = when {
            healthItems.isEmpty() -> tr("Vaccines, deworming, vet visits")
            healthItems.any { it.due } -> tr("{0} due now", healthItems.count { it.due })
            else -> healthItems.minByOrNull { it.dueMs ?: Long.MAX_VALUE }?.let { CareStats.dueLabel(it, app.now, clock) } ?: tr("All on schedule")
        }
        val weights = state.weightsFor(pet.id)
        val weightDetail = weights.lastOrNull()?.let { tr("{0} on {1}", WeightTrend.kg(it.grams), LocalClock.shortDate(it.day)) } ?: tr("Keep track of weigh-ins")
        val outfit = com.pawpixel.sprite.Accessory.of(pet.accessory)
        val wardrobeDetail = if (outfit != null) tr("Wearing: {0}", tr(outfit.label)) else tr("Outfits and the Pet Studio")
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DoorTile(PixelIcons.HEART, tr("Health"), healthDetail, Modifier.weight(1f), toy = Candy.Coral) { app.navigate(Screen.PetSection(pet.id, "health")) }
                DoorTile(PixelIcons.SCALE, tr("Weight"), weightDetail, Modifier.weight(1f), toy = Candy.Sky) { app.navigate(Screen.PetSection(pet.id, "weight")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DoorTile(PixelIcons.SHIRT, tr("Wardrobe"), wardrobeDetail, Modifier.weight(1f), toy = Candy.Pink) { app.navigate(Screen.PetSection(pet.id, "wardrobe")) }
                DoorTile(PixelIcons.SHARE, tr("Share"), tr("GIF, before/after, household"), Modifier.weight(1f), toy = Candy.Lavender) { app.navigate(Screen.PetSection(pet.id, "share")) }
            }
        }
        Spacer(Modifier.height(20.dp))
        }
    }
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
    val toy = Candy.forKind(t.kind)
    val detail = when {
        s.isOverdue -> tr("Waiting since {0}", formatTime(s.overdueSinceMs!!, clock))
        s.allDoneThisCycle && t.everyDays > 1 -> tr("Done · next {0}", s.nextDueMs?.let { relativeDay(it, app.now, clock) } ?: "-")
        s.allDoneThisCycle -> tr("All done today ✓")
        else -> tr("Next {0}", s.nextDueMs?.let { relativeDay(it, app.now, clock) } ?: "-")
    }
    val done = s.allDoneThisCycle
    // How much is left before it's needed again: full right after care, empty when overdue.
    val interval = if (t.everyDays > 1) t.everyDays * com.pawpixel.core.DAY_MS else com.pawpixel.core.DAY_MS / t.slots.size.coerceAtLeast(1)
    val nextDue = s.nextDueMs
    val fraction = when {
        s.isOverdue -> 0f
        nextDue != null -> ((nextDue - app.now).toFloat() / interval).coerceIn(0.1f, 1f)
        else -> 1f
    }
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CandyTile(PixelIcons.forKind(t.kind), toy)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Meter(fraction, toy, Modifier.padding(end = 8.dp))
                    Text(
                        detail, style = MaterialTheme.typography.bodySmall,
                        color = when { s.isOverdue -> MaterialTheme.colorScheme.primary; done -> Paw.palette.good; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    )
                }
                // Undo takes back your own Done, never someone else's.
                val mineThisCycle = s.logged > 0 && state.completions
                    .any { it.taskId == t.id && it.localDay >= s.cycleStartDay && app.repo.family.isMine(it.by) }
                val undoLabel = tr("Undo {0} for {1}", name, pet.name)
                val editLabel = tr("Edit {0}", name)
                if (!done) {
                    val doneLabel = tr("Mark {0} done for {1}", name, pet.name)
                    ToyButton(tr("Done"), modifier = Modifier.padding(start = 4.dp).semantics { contentDescription = doneLabel }, style = ToyStyle.Colored(toy)) {
                        onDone(); app.launch { app.repo.complete(t.id) }
                    }
                } else if (mineThisCycle) {
                    LinkButton(tr("Undo"), modifier = Modifier.semantics { contentDescription = undoLabel }) { app.launch { app.repo.undo(t.id) } }
                }
                // A small pencil opens the task (times, repeat, reminders); the row stays one line.
                Box(
                    Modifier.padding(start = 2.dp).size(36.dp).clip(Pill)
                        .clickable(role = Role.Button, onClickLabel = editLabel) { app.navigate(Screen.EditTask(pet.id, t.id)) }
                        .semantics { contentDescription = editLabel },
                    contentAlignment = Alignment.Center,
                ) { PixelIcon(PixelIcons.PENCIL, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp) }
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
        }
    }
}

@Composable
private fun RenameDialog(app: AppScope, pet: Pet, onClose: () -> Unit) {
    var name by remember { mutableStateOf(pet.name) }
    var species by remember { mutableStateOf(pet.species) }
    var birthDay by remember { mutableStateOf(pet.birthDay) }
    var askBirthday by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
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
                LinkButton(tr("Delete {0}", pet.name), color = MaterialTheme.colorScheme.error) { confirmDelete = true }
            }
        },
        confirmButton = { TextButton(onClick = { app.launch { app.repo.renamePet(pet, name, species, birthDay) }; onClose() }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
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
                TextButton(onClick = { confirmDelete = false; onClose(); app.launch { app.repo.deletePet(pet.id) } }) {
                    Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
    if (askBirthday) {
        BirthdayDialog(
            name.ifBlank { pet.name }, birthDay, today, skipLabel = tr("Cancel"),
            onSkip = { askBirthday = false }, onSave = { birthDay = it; askBirthday = false },
        )
    }
}
