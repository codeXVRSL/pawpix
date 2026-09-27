package com.pawpixel.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

@Composable
fun TaskEditorScreen(app: AppScope, state: AppState, pet: Pet, taskId: String?) {
    val original = taskId?.let { state.task(it) }
    val today = app.repo.clock.dayIndex(app.now)
    var task by remember {
        mutableStateOf(original ?: StateOps.defaultTask(pet, TaskKind.FEED, today, Ids.newId(), app.now))
    }

    fun setKind(kind: TaskKind) {
        val keepTitle = task.title != task.kind.label
        task = task.copy(
            kind = kind,
            title = if (keepTitle) task.title else kind.label,
            slots = if (original == null) TaskDefaults.slotsFor(kind, pet.species) else task.slots,
            everyDays = if (original == null) TaskDefaults.everyDaysFor(kind) else task.everyDays,
            adaptive = if (original == null) kind != TaskKind.MEDS else task.adaptive,
        )
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = app.back) { Text("‹ Back") }
            Text(if (original == null) "New care task" else "Edit care task", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TaskKind.entries.forEach { k ->
                FilterChip(task.kind == k, { setKind(k) }, label = { Text("${k.emoji} ${k.label}") })
            }
        }
        OutlinedTextField(task.title, { task = task.copy(title = it.take(30)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Times", fontWeight = FontWeight.Bold)
        task.slots.forEachIndexed { i, minute ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { task = task.copy(slots = task.slots.shift(i, -60)) }) { Text("−1h") }
                OutlinedButton(onClick = { task = task.copy(slots = task.slots.shift(i, -15)) }) { Text("−15") }
                Text(formatMinute(minute), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { task = task.copy(slots = task.slots.shift(i, 15)) }) { Text("+15") }
                OutlinedButton(onClick = { task = task.copy(slots = task.slots.shift(i, 60)) }) { Text("+1h") }
                if (task.slots.size > 1) TextButton(onClick = { task = task.copy(slots = task.slots.filterIndexed { j, _ -> j != i }) }) { Text("✕") }
            }
        }
        if (task.slots.size < 4 && task.everyDays == 1) {
            TextButton(onClick = {
                val last = task.slots.maxOrNull() ?: (8 * 60)
                task = task.copy(slots = (task.slots + ((last + 4 * 60) % MINUTES_PER_DAY)).distinct())
            }) { Text("+ Add a time") }
        }

        Text("Repeat", fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "Daily", 2 to "Every 2 days", 7 to "Weekly", 14 to "Every 2 weeks", 30 to "Monthly").forEach { (d, label) ->
                FilterChip(task.everyDays == d, {
                    task = task.copy(everyDays = d, anchorDay = today, slots = if (d > 1) task.slots.take(1) else task.slots)
                }, label = { Text(label) })
            }
        }

        SwitchRow("Reminders", "Get a notification when it's time.", task.remindersOn) { task = task.copy(remindersOn = it) }
        SwitchRow("Learn my routine", "Moves reminders toward when you actually do this (up to 2 hours).", task.adaptive) { task = task.copy(adaptive = it) }
        SwitchRow("Exact time", "Remind at the exact minute. Good for medicine. Android may ask for permission.", task.exactAlarm) { task = task.copy(exactAlarm = it) }

        Button(onClick = { app.launch { app.repo.update { StateOps.upsertTask(it, task) }; app.back() } }, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }
        if (original != null) {
            TextButton(onClick = { app.launch { app.repo.update { StateOps.removeTask(it, original.id) }; app.back() } }) {
                Text("Delete task", color = MaterialTheme.colorScheme.error)
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
