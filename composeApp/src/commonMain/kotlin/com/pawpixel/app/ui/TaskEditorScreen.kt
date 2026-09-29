package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.Ids
import com.pawpixel.core.MINUTES_PER_DAY
import com.pawpixel.core.Pet
import com.pawpixel.core.StateOps
import com.pawpixel.core.TaskDefaults
import com.pawpixel.core.TaskKind
import com.pawpixel.i18n.tr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditorScreen(app: AppScope, state: AppState, pet: Pet, taskId: String?, healthOnly: Boolean = false) {
    val original = taskId?.let { state.task(it) }
    val today = app.repo.clock.dayIndex(app.now)
    val health = original?.kind?.health ?: healthOnly
    var task by remember {
        mutableStateOf(original ?: StateOps.defaultTask(pet, if (health) TaskKind.VACCINE else TaskKind.FEED, today, Ids.newId(), app.now))
    }
    /** New health items: when it was last done (days ago), or null for never / don't know. */
    var lastDoneDaysAgo by remember { mutableStateOf<Int?>(null) }

    fun setKind(kind: TaskKind) {
        val keepTitle = task.title != task.kind.defaultTitle
        task = task.copy(
            kind = kind,
            title = if (keepTitle) task.title else kind.defaultTitle,
            slots = if (original == null) TaskDefaults.slotsFor(kind, pet.species) else task.slots,
            everyDays = if (original == null) TaskDefaults.everyDaysFor(kind) else task.everyDays,
            adaptive = if (original == null) kind != TaskKind.MEDS else task.adaptive,
        )
    }

    val save: () -> Unit = {
        app.launch {
            app.repo.update { s ->
                val saved = StateOps.upsertTask(s, task)
                val ago = lastDoneDaysAgo
                if (original == null && ago != null) StateOps.logOnDay(saved, task.id, today - ago, app.repo.now(), app.repo.clock) else saved
            }
            app.back()
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .imePadding() // keeps the focused field and buttons above the keyboard
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Save sits in the top bar, always visible; the form below can be long.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = app.back) { Text(tr("‹ Back")) }
            Text(
                when {
                    original == null && health -> tr("New health item")
                    original == null -> tr("New care task")
                    health -> tr("Edit health item")
                    else -> tr("Edit care task")
                },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            Button(onClick = save) { Text(tr("Save")) }
        }

        // All kinds visible at once (wrapping), so Medicine or Litter aren't hidden off-screen.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TaskKind.entries.filter { it.health == health }.forEach { k ->
                FilterChip(task.kind == k, { setKind(k) }, label = { Text("${k.emoji} ${tr(k.label)}") })
            }
        }
        OutlinedTextField(task.title, { task = task.copy(title = it.take(30)) }, label = { Text(tr("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text(if (health) tr("Reminder time on the day") else tr("Times"), fontWeight = FontWeight.Bold)
        task.slots.forEachIndexed { i, minute ->
            // Time first, never wrapped; compact steppers so a row fits a small phone with the ✕.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    formatMinute(minute), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f),
                )
                StepButton("−1h") { task = task.copy(slots = task.slots.shift(i, -60)) }
                StepButton("−15") { task = task.copy(slots = task.slots.shift(i, -15)) }
                StepButton("+15") { task = task.copy(slots = task.slots.shift(i, 15)) }
                StepButton("+1h") { task = task.copy(slots = task.slots.shift(i, 60)) }
                if (task.slots.size > 1) {
                    TextButton(
                        onClick = { task = task.copy(slots = task.slots.filterIndexed { j, _ -> j != i }) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                    ) { Text("✕") }
                }
            }
        }
        if (!health && task.slots.size < 4 && task.everyDays == 1) {
            TextButton(onClick = {
                val last = task.slots.maxOrNull() ?: (8 * 60)
                task = task.copy(slots = (task.slots + ((last + 4 * 60) % MINUTES_PER_DAY)).distinct())
            }) { Text(tr("+ Add a time")) }
        }

        Text(tr("Repeat"), fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val choices = if (health) listOf(14 to "Every 2 weeks", 30 to "Monthly", 90 to "Every 3 months", 180 to "Every 6 months", 365 to "Yearly")
            else listOf(1 to "Daily", 2 to "Every 2 days", 7 to "Weekly", 14 to "Every 2 weeks", 30 to "Monthly")
            // Health items keep their cycle (it counts from when they were last done).
            choices.forEach { (d, label) ->
                FilterChip(task.everyDays == d, {
                    task = task.copy(everyDays = d, anchorDay = if (health) task.anchorDay else today, slots = if (d > 1) task.slots.take(1) else task.slots)
                }, label = { Text(tr(label)) })
            }
        }

        if (health && original == null) {
            Text(tr("Last done"), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(lastDoneDaysAgo == null, { lastDoneDaysAgo = null }, label = { Text(tr("Never / not sure")) })
                WHEN_CHOICES.forEach { (days, label) ->
                    FilterChip(lastDoneDaysAgo == days, { lastDoneDaysAgo = days }, label = { Text(tr(label)) })
                }
            }
            Text(
                if (lastDoneDaysAgo == null) tr("It'll show as due now. Tap Done once it's given.") else tr("The next one is counted from then."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SwitchRow(tr("Reminders"), if (health) tr("A heads-up 3 days before, and on the day.") else tr("Get a notification when it's time."), task.remindersOn) {
            task = task.copy(remindersOn = it)
        }
        if (!health) SwitchRow(tr("Learn my routine"), tr("Moves reminders toward when you actually do this (up to 2 hours)."), task.adaptive) { task = task.copy(adaptive = it) }
        if (!health) SwitchRow(tr("Exact time"), tr("Remind at the exact minute. Good for medicine. Android may ask for permission."), task.exactAlarm) { task = task.copy(exactAlarm = it) }

        Button(onClick = save, modifier = Modifier.fillMaxWidth()) { Text(tr("Save")) }
        if (original != null) {
            TextButton(onClick = { app.launch { app.repo.deleteTask(original); app.back() } }) {
                Text(tr("Delete task"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun List<Int>.shift(i: Int, by: Int): List<Int> =
    mapIndexed { j, m -> if (j == i) (m + by).mod(MINUTES_PER_DAY) else m }

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.defaultMinSize(minWidth = 1.dp).height(36.dp),
    ) { Text(label, maxLines = 1, softWrap = false) }
}
