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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AdaptiveTiming
import com.pawpixel.core.AppState
import com.pawpixel.core.CareEngine
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.core.StateOps
import com.pawpixel.core.TaskStatus
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

@Composable
fun HomeScreen(app: AppScope, state: AppState) {
    var showProDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PawPixel", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f).semantics { heading() })
            if (state.pets.isNotEmpty()) TextButton(onClick = { app.navigate(Screen.PetMap) }) { Text(tr("Pet map")) }
            TextButton(onClick = { app.navigate(Screen.Settings) }) { Text(tr("Settings")) }
        }
        if (state.pets.isEmpty()) {
            PixelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Turn your pet into pixel art"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(tr("Your pixel pet lives on your home screen and gets hungry, restless or sleepy based on the real care you give. Done a task? Tap it and watch them cheer up."))
                    Button(onClick = { app.navigate(Screen.CreatePet) }) { Text(tr("Choose a photo")) }
                }
            }
            JoinHouseholdLink(app)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                items(state.pets, key = { it.id }) { pet -> PetCard(app, state, pet) }
                item {
                    Column {
                        TextButton(onClick = {
                            if (StateOps.canAddPet(state)) app.navigate(Screen.CreatePet) else showProDialog = true
                        }) { Text(tr("+ Add another pet")) }
                        if (app.repo.family.household == null) JoinHouseholdLink(app)
                    }
                }
                item {
                    WidgetTip(app, state)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    if (showProDialog) {
        AlertDialog(
            onDismissRequest = { showProDialog = false },
            title = { Text(tr("More pets with Pro")) },
            text = { Text(tr("Your first pet is free forever. Extra pets are part of PawPixel Pro, which is coming soon.")) },
            confirmButton = { TextButton(onClick = { showProDialog = false }) { Text(tr("OK")) } },
        )
    }
}

/** For someone whose partner already has the pet on PawPixel: join their household instead of making it again. */
@Composable
fun JoinHouseholdLink(app: AppScope) {
    TextButton(onClick = { app.navigate(Screen.Family(join = true)) }) { Text(tr("👪 Join a household")) }
}

fun statusesFor(app: AppScope, state: AppState, petId: String): List<TaskStatus> =
    state.tasksFor(petId).map { t ->
        CareEngine.status(t, state.completions, app.now, app.repo.clock, AdaptiveTiming.effectiveSlots(t, state.completions, app.now, app.repo.clock), state.pet(petId))
    }

@Composable
private fun PetCard(app: AppScope, state: AppState, pet: Pet) {
    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val pose = remember(pet.lookKey, reading.mood) { app.repo.pose(pet, reading.mood) }
    // Quick Done is for daily care; health care (a vaccine, a vet visit) is recorded on the pet's page.
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    val urgent = statuses.filter { it.isOverdue }.takeIf { !state.isAway(app.now) }
        ?.maxByOrNull { MoodEngine.penalty(it, app.now, state.settings.awayUntilMs) }
    val next = statuses.filter { !it.isOverdue }.mapNotNull { s -> s.nextDueMs?.let { s to it } }.minByOrNull { it.second }

    PixelCard(Modifier.fillMaxWidth().clickable(onClickLabel = tr("Open {0}'s page", pet.name)) { app.navigate(Screen.PetDetail(pet.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpriteView(pose, Modifier.size(104.dp), description = MoodEngine.describe(pet.name, reading.mood))
            Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pet.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(reading.caption)
                if (next != null) {
                    Text(
                        tr("Next: {0} {1} · {2}", next.first.task.kind.emoji, trName(next.first.task.title), relativeDay(next.second, app.now, app.repo.clock)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (urgent != null) {
                    val label = tr("Mark {0} done for {1}", trName(urgent.task.title), pet.name)
                    Button(onClick = { app.launch { app.repo.complete(urgent.task.id) } }, modifier = Modifier.semantics { contentDescription = label }) {
                        Text("${urgent.task.kind.emoji} ${tr(urgent.task.kind.verb)}")
                    }
                }
            }
        }
    }
}

/**
 * The widget is the heart of PawPixel (people who add a widget keep using an app far longer), so the
 * home screen offers it until one is added. Android can add it in one tap; iOS gets the steps.
 */
@Composable
private fun WidgetTip(app: AppScope, state: AppState) {
    val platform = app.repo.platform
    // Asks the system once per screen visit (and every few minutes), not on every clock tick.
    val first = remember { platform.widgetInstalled() }
    val installed by produceState(first, app.now / (5 * 60_000)) {
        value = withContext(Dispatchers.Default) { platform.widgetInstalled() }
    }
    var manual by remember { mutableStateOf(installed == null) }
    if (installed == true || state.settings.widgetTipDismissed) return
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Put your pet on your home screen"), fontWeight = FontWeight.Bold)
            Text(tr("See their mood at a glance and tap Done right from the widget."))
            if (manual) {
                Text(
                    if (installed == null) tr("Touch and hold your home screen, tap Edit → Add Widget, search PawPixel, and pick a size.")
                    else tr("Touch and hold your home screen, tap Widgets, find PawPixel, and drag it in."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!manual) Button(onClick = { if (!platform.pinWidget()) manual = true }) { Text(tr("Add widget")) }
                TextButton(onClick = { app.launch { app.repo.setSettings(state.settings.copy(widgetTipDismissed = true)) } }) {
                    Text(if (manual) tr("Got it") else tr("Not now"))
                }
            }
        }
    }
}
