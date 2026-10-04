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
import com.pawpixel.sprite.PixelIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home is the pet's room (the shown pet's, or the first): the game starts in the world, not on a
 * list. With no pet yet, an empty room and the one key that matters.
 */
@Composable
fun HomeScreen(app: AppScope, state: AppState) {
    val pet = state.pet(app.shownPetId ?: "") ?: state.pets.firstOrNull()
    if (pet != null) PetScreen(app, state, pet) else EmptyHome(app, state)
}

/** No pets yet: an empty room, what PawPixel does, and the one key that matters. */
@Composable
private fun EmptyHome(app: AppScope, state: AppState) {
    val phase = phaseFor(app, state)
    Box(Modifier.fillMaxSize()) {
        RoomBackdrop(phase, Modifier.fillMaxSize(), pixel = 4.dp, floorDepth = 150.dp) { floor ->
            PixelIcon(PixelIcons.PAW, tint = Color(0x662B2135), size = 48.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = floor + 8.dp))
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Spacer(Modifier.weight(1f))
            GlassButton(PixelIcons.GEAR, tr("Settings")) { app.navigate(Screen.Settings) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToyPanel(Modifier.fillMaxWidth(), face = Color.White, lip = Color(0xFFE6D5C3), padding = 20.dp) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("Turn your pet into pixel art"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = Color(0xFF2B2135), modifier = Modifier.semantics { heading() })
                    Text(
                        tr("Your pixel pet lives on your home screen and gets hungry, restless or sleepy based on the real care you give. Done a task? Tap it and watch them cheer up."),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF2B2135),
                    )
                    PrimaryPill(tr("Choose a photo"), big = true) { app.navigate(Screen.CreatePet) }
                    JoinHouseholdLink(app)
                }
            }
        }
    }
}

/** For someone whose partner already has the pet on PawPixel: join their household instead of making it again. */
@Composable
fun JoinHouseholdLink(app: AppScope) {
    LinkButton(tr("Join a household"), icon = PixelIcons.PEOPLE) { app.navigate(Screen.Family(join = true)) }
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
                    if (!manual) PrimaryPill(tr("Add widget"), icon = PixelIcons.PHONE) { if (!platform.pinWidget()) manual = true }
                    LinkButton(if (manual) tr("Got it") else tr("Not now"), color = MaterialTheme.colorScheme.onPrimaryContainer) {
                        app.launch { app.repo.setSettings(state.settings.copy(widgetTipDismissed = true)) }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            IconTile(PixelIcons.PHONE, tone = Tone.Surface, size = 44.dp)
        }
    }
}
