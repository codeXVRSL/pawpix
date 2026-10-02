package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AdaptiveTiming
import com.pawpixel.core.AppState
import com.pawpixel.core.CareEngine
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Mood
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.core.StateOps
import com.pawpixel.core.TaskStatus
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(app: AppScope, state: AppState) {
    var showProDialog by remember { mutableStateOf(false) }
    val clock = app.repo.clock
    val minute = clock.minuteOfDay(app.now)
    Box(Modifier.fillMaxSize().background(heroGlow())) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Wordmark and the day's greeting. (The main places live in the dock below.)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 10.dp)) {
                Text("PawPixel", style = MaterialTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.semantics { heading() })
                Hint("${greeting(minute)} · ${LocalClock.shortDate(clock.dayIndex(app.now))}")
            }

            if (state.pets.isEmpty()) {
                Box(Modifier.weight(1f).padding(horizontal = 16.dp)) { EmptyHome(app) }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    items(state.pets, key = { it.id }) { pet -> PetCard(app, state, pet, minute) }
                    item {
                        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GhostPill(tr("+ Add another pet")) {
                                if (StateOps.canAddPet(state)) app.navigate(Screen.CreatePet) else showProDialog = true
                            }
                            if (app.repo.family.household == null) JoinHouseholdLink(app)
                        }
                    }
                    item { WidgetTip(app, state) }
                }
            }

            // The dock: the main places, one thumb away. (Its words are what the end-to-end tests tap.)
            val doors = buildList {
                add(DockItem("🐾", tr("Pets"), selected = true) {})
                if (state.pets.isNotEmpty()) add(DockItem("🗺️", tr("Pet map")) { app.navigate(Screen.PetMap) })
                add(DockItem("👪", tr("Family")) { app.navigate(Screen.Family()) })
                add(DockItem("⚙️", tr("Settings")) { app.navigate(Screen.Settings) })
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { FloatingDock(doors) }
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

/** No pets yet: what PawPixel does, and the one button that matters. */
@Composable
private fun EmptyHome(app: AppScope) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 22.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // A little stage with nobody in it yet.
                val scene = sceneFor(app.repo.clock.minuteOfDay(app.now), 22 * 60, 6 * 60)
                Box(
                    Modifier.fillMaxWidth().height(120.dp).clip(MaterialTheme.shapes.medium)
                        .background(Brush.verticalGradient(0f to scene.skyTop, 0.78f to scene.skyBottom, 0.78f to scene.floor, 1f to scene.floor)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🐾", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(tr("Turn your pet into pixel art"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    tr("Your pixel pet lives on your home screen and gets hungry, restless or sleepy based on the real care you give. Done a task? Tap it and watch them cheer up."),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
                PrimaryPill(tr("Choose a photo"), big = true) { app.navigate(Screen.CreatePet) }
            }
        }
        JoinHouseholdLink(app)
    }
}

/** For someone whose partner already has the pet on PawPixel: join their household instead of making it again. */
@Composable
fun JoinHouseholdLink(app: AppScope) {
    LinkButton(tr("👪 Join a household")) { app.navigate(Screen.Family(join = true)) }
}

fun statusesFor(app: AppScope, state: AppState, petId: String): List<TaskStatus> =
    state.tasksFor(petId).map { t ->
        CareEngine.status(t, state.completions, app.now, app.repo.clock, AdaptiveTiming.effectiveSlots(t, state.completions, app.now, app.repo.clock), state.pet(petId))
    }

/** The colour of a mood's dot: coral when the pet needs something, green when all is well, lavender asleep. */
@Composable
fun moodColor(mood: Mood): Color = when (mood) {
    Mood.HAPPY, Mood.CONTENT -> Paw.palette.good
    Mood.SLEEPY -> Paw.palette.calm
    else -> MaterialTheme.colorScheme.primary
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PetCard(app: AppScope, state: AppState, pet: Pet, minute: Int) {
    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val pose = remember(pet.lookKey, reading.mood) { app.repo.pose(pet, reading.mood) }
    // Quick Done is for daily care; health care (a vaccine, a vet visit) is recorded on the pet's page.
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    val urgent = statuses.filter { it.isOverdue }.takeIf { !state.isAway(app.now) }
        ?.maxByOrNull { MoodEngine.penalty(it, app.now, state.settings.awayUntilMs) }
    val next = statuses.filter { !it.isOverdue }.mapNotNull { s -> s.nextDueMs?.let { s to it } }.minByOrNull { it.second }
    val scene = sceneFor(minute, state.settings.nightStart, state.settings.nightEnd)
    var burst by remember { mutableStateOf<Long?>(null) }

    SoftCard(
        Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 0.dp,
        onClick = { app.navigate(Screen.PetDetail(pet.id)) }, onClickLabel = tr("Open {0}'s page", pet.name),
    ) {
        Column {
            // A window onto the pet's little world: the sky of the hour, the pet standing on its floor.
            Box(
                Modifier.fillMaxWidth().height(164.dp)
                    .background(Brush.verticalGradient(0f to scene.skyTop, 0.8f to scene.skyBottom, 0.8f to scene.floor, 1f to scene.floor)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                SpriteView(pose, Modifier.size(120.dp).padding(bottom = 20.dp), description = MoodEngine.describe(pet.name, reading.mood))
                StatusPill(
                    reading.caption, moodColor(reading.mood), tone = Tone.Surface,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
                HeartBurst(burst, Modifier.fillMaxSize())
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pet.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (urgent != null) {
                        val label = tr("Mark {0} done for {1}", trName(urgent.task.title), pet.name)
                        PrimaryPill(
                            "${urgent.task.kind.emoji} ${tr(urgent.task.kind.verb)}",
                            modifier = Modifier.semantics { contentDescription = label },
                        ) { burst = app.now; app.launch { app.repo.complete(urgent.task.id) } }
                    }
                }
                if (urgent != null) {
                    Hint(tr("Waiting since {0}", formatTime(urgent.overdueSinceMs!!, app.repo.clock)))
                } else if (next != null) {
                    Text(
                        tr("Next: {0} {1} · {2}", next.first.task.kind.emoji, trName(next.first.task.title), relativeDay(next.second, app.now, app.repo.clock)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Hint(tr("No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care.", pet.name))
                }
            }
        }
    }
}

/**
 * The widget is the heart of PawPixel (people who add a widget keep using an app far longer), so the
 * home screen offers it until one is added, and so does a pet's page (where a new owner lands after
 * making their pet). Android can add it in one tap; iOS gets the steps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetTip(app: AppScope, state: AppState, pet: Pet? = null) {
    val platform = app.repo.platform
    // Asks the system once per screen visit (and every few minutes), not on every clock tick.
    val first = remember { platform.widgetInstalled() }
    val installed by produceState(first, app.now / (5 * 60_000)) {
        value = withContext(Dispatchers.Default) { platform.widgetInstalled() }
    }
    var manual by remember { mutableStateOf(installed == null) }
    if (installed == true || state.settings.widgetTipDismissed) return
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pet?.let { tr("Put {0} on your home screen", it.name) } ?: tr("Put your pet on your home screen"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(tr("See their mood at a glance and tap Done right from the widget."), style = MaterialTheme.typography.bodyMedium)
                if (manual) {
                    Text(
                        if (installed == null) tr("Touch and hold your home screen, tap Edit → Add Widget, search PawPixel, and pick a size.")
                        else tr("Touch and hold your home screen, tap Widgets, find PawPixel, and drag it in."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!manual) PrimaryPill(tr("Add widget")) { if (!platform.pinWidget()) manual = true }
                    LinkButton(if (manual) tr("Got it") else tr("Not now"), color = MaterialTheme.colorScheme.onPrimaryContainer) {
                        app.launch { app.repo.setSettings(state.settings.copy(widgetTipDismissed = true)) }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text("📱", style = MaterialTheme.typography.displaySmall)
        }
    }
}
