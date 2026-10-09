package com.pawpixel.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AdaptiveTiming
import com.pawpixel.core.AppState
import com.pawpixel.core.CareStats
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.core.TaskKind
import com.pawpixel.core.TaskStatus
import com.pawpixel.core.Units
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName
import com.pawpixel.sprite.PixelIcons

/**
 * The Care panel: every care task with its meter, Done and Undo, the pencil to edit it, the week
 * of care, who in the household did what, and the one-time asks (reminders, the widget).
 */
@Composable
fun CarePanel(app: AppScope, state: AppState, pet: Pet) {
    val clock = app.repo.clock
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    RemindersCard(app, state, pet)
    SectionTitle(tr("Care")) {
        LinkButton(tr("+ Add care task"), color = MaterialTheme.colorScheme.primary) { app.navigate(Screen.EditTask(pet.id, null)) }
    }
    if (statuses.isEmpty()) Hint(tr("No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care.", pet.name))
    statuses.forEach { s -> TaskRow(app, state, pet, s) }

    // A walk timed with the app: the pet trots along, steps are counted, and it logs as care.
    if (!pet.remembered) {
        val walks = CareStats.walkWeek(state, pet.id, app.now, clock)
        val km = walks.km
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("Walks this week"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            walks.walks == 0 -> tr("None yet. Time one and {0} trots along.", pet.name)
                            walks.walks == 1 && km != null -> tr("1 walk · {0} min · about {1}", walks.minutes, Units.distance(km))
                            walks.walks == 1 -> tr("1 walk · {0} min", walks.minutes)
                            km != null -> tr("{0} walks · {1} min · about {2}", walks.walks, walks.minutes, Units.distance(km))
                            else -> tr("{0} walks · {1} min", walks.walks, walks.minutes)
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PrimaryPill(tr("Start a walk"), icon = PixelIcons.PAW) { app.navigate(Screen.Walk(pet.id)) }
            }
        }
    }

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
    if (!pet.remembered) ChallengeCard(app, state, pet)
    val household = app.repo.family.household
    if (pet.shared && household != null) {
        val others = household.members.filter { it.userId != app.repo.family.myUserId }.joinToString { it.name }
        LinkButton(if (others.isEmpty()) tr("Shared with {0}", household.name) else tr("Cared for with {0}", others), icon = PixelIcons.PEOPLE) { app.navigate(Screen.Family()) }
    }
    WidgetTip(app, state, pet)
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelIcon(PixelIcons.BELL, size = 18.dp)
                Text(tr("Reminders for {0}?", pet.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            Text(
                tr("A gentle nudge when it's time for these, only for the tasks you set. Change them any time."),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryPill(tr("Turn on reminders")) {
                    app.repo.platform.requestNotificationPermission()
                    app.launch { app.repo.editSettings { it.copy(remindersAsked = true) } }
                }
                LinkButton(tr("Not now"), color = MaterialTheme.colorScheme.onTertiaryContainer) { app.launch { app.repo.editSettings { it.copy(remindersAsked = true) } } }
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
private fun TaskRow(app: AppScope, state: AppState, pet: Pet, s: TaskStatus) {
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
    // How much is left before it's needed again: full right after care, empty when overdue (the same number as the HUD tile).
    val fraction = s.meterFraction(app.now)
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
                        app.launch { app.repo.complete(t.id) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenameDialog(app: AppScope, pet: Pet, onClose: () -> Unit) {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
