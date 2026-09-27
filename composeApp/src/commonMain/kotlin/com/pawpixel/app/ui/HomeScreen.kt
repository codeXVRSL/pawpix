package com.pawpixel.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.pawpixel.core.AdaptiveTiming
import com.pawpixel.core.AppState
import com.pawpixel.core.CareEngine
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.core.StateOps
import com.pawpixel.core.TaskStatus

@Composable
fun HomeScreen(app: AppScope, state: AppState) {
    var showProDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PawPixel", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            TextButton(onClick = { app.navigate(Screen.Settings) }) { Text("Settings") }
        }
        if (state.pets.isEmpty()) {
            PixelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Turn your pet into pixel art", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Your pixel pet lives on your home screen and gets hungry, restless or sleepy based on the real care you give. Done a task? Tap it and watch them cheer up.")
                    Button(onClick = { app.navigate(Screen.CreatePet) }) { Text("Choose a photo") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(state.pets, key = { it.id }) { pet -> PetCard(app, state, pet) }
                item {
                    TextButton(onClick = {
                        if (StateOps.canAddPet(state)) app.navigate(Screen.CreatePet) else showProDialog = true
                    }) { Text("+ Add another pet") }
                }
                item {
                    Text(
                        "Tip: add the PawPixel widget to your home screen to see your pet's mood at a glance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (showProDialog) {
        AlertDialog(
            onDismissRequest = { showProDialog = false },
            title = { Text("More pets with Pro") },
            text = { Text("Your first pet is free forever. Extra pets are part of PawPixel Pro, which is coming soon.") },
            confirmButton = { TextButton(onClick = { showProDialog = false }) { Text("OK") } },
        )
    }
}

fun statusesFor(app: AppScope, state: AppState, petId: String): List<TaskStatus> =
    state.tasksFor(petId).map { t ->
        CareEngine.status(t, state.completions, app.now, app.repo.clock, AdaptiveTiming.effectiveSlots(t, state.completions, app.now, app.repo.clock))
    }

@Composable
private fun PetCard(app: AppScope, state: AppState, pet: Pet) {
    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val pose = remember(pet.id, pet.spriteVersion, pet.species, pet.eyes, reading.mood) { app.repo.pose(pet, reading.mood) }
    val statuses = statusesFor(app, state, pet.id)
    val urgent = statuses.filter { it.isOverdue }.maxByOrNull { MoodEngine.penalty(it, app.now) }
    val next = statuses.filter { !it.isOverdue }.mapNotNull { s -> s.nextDueMs?.let { s to it } }.minByOrNull { it.second }

    PixelCard(Modifier.fillMaxWidth().clickable { app.navigate(Screen.PetDetail(pet.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpriteView(pose, Modifier.size(104.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pet.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(reading.caption)
                if (next != null) {
                    Text(
                        "Next: ${next.first.task.kind.emoji} ${next.first.task.title} · ${relativeDay(next.second, app.now, app.repo.clock)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (urgent != null) {
                    Button(onClick = { app.launch { app.repo.complete(urgent.task.id) } }) {
                        Text("${urgent.task.kind.emoji} ${urgent.task.kind.verb}")
                    }
                }
            }
        }
    }
}
