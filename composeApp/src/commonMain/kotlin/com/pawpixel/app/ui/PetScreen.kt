package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AdaptiveTiming
import com.pawpixel.core.AppState
import com.pawpixel.core.CareStats
import com.pawpixel.core.HealthItem
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.core.TaskStatus
import com.pawpixel.sprite.PetEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PetScreen(app: AppScope, state: AppState, pet: Pet) {
    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val art = remember(pet.id, pet.spriteVersion, pet.species) { app.repo.art(pet) }
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    val health = CareStats.healthDue(state, pet.id, app.now, app.repo.clock)
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
            TextButton(onClick = app.back) { Text("‹ Back") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { renaming = true }) { Text("Edit") }
        }
        PixelCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                LivePet(art, pet.eyes, reading.mood, seed = pet.id.hashCode(), modifier = Modifier.fillMaxWidth(), reaction = reaction)
                Text(pet.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(reading.caption, textAlign = TextAlign.Center)
                Text(
                    hearts(reading.score), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { contentDescription = "Happiness ${(reading.score + 10) / 20} of 5" },
                )
                Text(
                    "Tap ${pet.name} to give pets",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
        }

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

        Text("Care", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (statuses.isEmpty()) Text("No care tasks yet. Add feeding, walks or medicine so ${pet.name}'s mood can follow real care.")
        statuses.forEach { s -> TaskRow(app, pet, s, onDone = { react(PetEvent.Cared(s.task.kind)) }) }
        OutlinedButton(onClick = { app.navigate(Screen.EditTask(pet.id, null)) }) { Text("+ Add care task") }

        Spacer(Modifier.height(8.dp))
        Text("Health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (health.isEmpty()) {
            Text(
                "Keep track of ${pet.name}'s anti-rabies shot, deworming, tick & flea care and vet check-ups. " +
                    "PawPixel reminds you a few days before each is due.",
            )
            Button(onClick = { app.launch { app.repo.addHealthCare(pet) } }) { Text("+ Add health reminders") }
        }
        health.forEach { h -> HealthRow(app, pet, h) }
        if (health.isNotEmpty()) {
            TextButton(onClick = { app.navigate(Screen.EditTask(pet.id, null, health = true)) }) { Text("+ Add health item") }
            Text(
                "Schedules are typical for adult pets in the Philippines. Puppies, kittens and your vet's advice may differ: tap Edit to change them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Share & sprite", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                shareError = "Couldn't make the animation. Please try again."
                            }
                        } finally {
                            makingGif = false
                        }
                    }
                },
            ) { Text(if (makingGif) "Making GIF…" else "Share animation") }
            OutlinedButton(onClick = { app.repo.shareReveal(pet) }) { Text("Before/after") }
        }
        shareError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { app.navigate(Screen.RemakeSprite(pet.id)) }) { Text("Edit look: photo, face, ears") }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete ${pet.name}", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${pet.name}?") },
            text = { Text("This removes the sprite, tasks and history from this phone. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; app.launch { app.repo.deletePet(pet.id) } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (renaming) RenameDialog(app, pet) { renaming = false }
}

private fun hearts(score: Int): String {
    val full = ((score + 10) / 20).coerceIn(0, 5)
    return "♥".repeat(full) + "♡".repeat(5 - full)
}

@Composable
private fun TaskRow(app: AppScope, pet: Pet, s: TaskStatus, onDone: () -> Unit) {
    val clock = app.repo.clock
    val t = s.task
    val detail = when {
        s.isOverdue -> "Waiting since ${formatTime(s.overdueSinceMs!!, clock)}"
        s.allDoneThisCycle && t.everyDays > 1 -> "Done · next ${s.nextDueMs?.let { relativeDay(it, app.now, clock) } ?: "-"}"
        s.allDoneThisCycle -> "All done today ✓"
        else -> "Next ${s.nextDueMs?.let { relativeDay(it, app.now, clock) } ?: "-"}"
    }
    val times = s.slotTimes.joinToString(" · ") { formatTime(it, clock) } +
        if (t.everyDays > 1) "  (every ${t.everyDays} days)" else ""
    PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${t.kind.emoji} ${t.title}", fontWeight = FontWeight.Bold)
                Text(detail, color = if (s.isOverdue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(times, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.adaptive && AdaptiveTiming.effectiveSlots(t, app.repo.state.value.completions, app.now, clock) != t.slots.sorted()) {
                    Text("Adjusted to your routine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (!s.allDoneThisCycle) {
                    Button(onClick = { onDone(); app.launch { app.repo.complete(t.id) } }) { Text("Done") }
                }
                Row {
                    if (s.logged > 0) TextButton(onClick = { app.launch { app.repo.undo(t.id) } }) { Text("Undo") }
                    TextButton(onClick = { app.navigate(Screen.EditTask(pet.id, t.id)) }) { Text("Edit") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthRow(app: AppScope, pet: Pet, h: HealthItem) {
    val clock = app.repo.clock
    val t = h.task
    var askWhen by remember { mutableStateOf(false) }
    PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${t.kind.emoji} ${t.title}", fontWeight = FontWeight.Bold)
                Text(
                    CareStats.dueLabel(h, app.now, clock),
                    color = if (h.due) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    (h.lastDoneMs?.let { "Last: ${formatDate(it, clock)}" } ?: "Not recorded yet") + " · ${everyLabel(t.everyDays)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = { askWhen = true }) { Text("Done") }
                Row {
                    if (h.lastDoneMs != null) TextButton(onClick = { app.launch { app.repo.undo(t.id) } }) { Text("Undo") }
                    TextButton(onClick = { app.navigate(Screen.EditTask(pet.id, t.id)) }) { Text("Edit") }
                }
            }
        }
    }
    if (askWhen) {
        AlertDialog(
            onDismissRequest = { askWhen = false },
            title = { Text("When was it done?") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WHEN_CHOICES.forEach { (days, label) ->
                        AssistChip(onClick = { askWhen = false; app.launch { app.repo.givenDaysAgo(t.id, days) } }, label = { Text(label) })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askWhen = false }) { Text("Cancel") } },
        )
    }
}

/** "When was it given?" choices, in days ago. */
val WHEN_CHOICES = listOf(0 to "Today", 1 to "Yesterday", 7 to "A week ago", 30 to "A month ago", 91 to "3 months ago", 182 to "6 months ago", 365 to "A year ago")

fun everyLabel(days: Int): String = when (days) {
    1 -> "daily"; 7 -> "weekly"; 14 -> "every 2 weeks"; 30 -> "monthly"; 90 -> "every 3 months"; 180 -> "every 6 months"; 365 -> "yearly"
    else -> "every $days days"
}

@Composable
private fun RenameDialog(app: AppScope, pet: Pet, onClose: () -> Unit) {
    var name by remember { mutableStateOf(pet.name) }
    var species by remember { mutableStateOf(pet.species) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit pet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Species.entries.forEach { sp ->
                        FilterChip(selected = species == sp, onClick = { species = sp }, label = { Text(sp.label) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { app.launch { app.repo.renamePet(pet, name, species) }; onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
