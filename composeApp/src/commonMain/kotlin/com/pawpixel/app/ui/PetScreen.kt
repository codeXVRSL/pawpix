package com.pawpixel.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.HealthPlan
import com.pawpixel.core.Milestones
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.core.TaskStatus
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName
import com.pawpixel.sprite.PetEvent
import com.pawpixel.sprite.PixelIcons
import kotlinx.coroutines.delay

/*
 * The pet's room is the game screen: it fills the phone, and everything else is a HUD laid over
 * it, the way Pou, My Talking Tom and Finch do it. Top: a name plate with hearts and a gear.
 * Middle: the pet, with a speech bubble for its mood. Bottom: one tappable need meter per care
 * task (tap it to log the care; the pet reacts), and a bar of five keys to the pet's panels.
 * Nothing scrolls, nothing is a form.
 */

/** How tall the HUD at the bottom of the room is: the pet's floor sits well above it, on the rug. */
private val HUD_DEPTH = 130.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetScreen(app: AppScope, state: AppState, pet: Pet) {
    val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
    val art = remember(pet.lookKey) { app.repo.art(pet) }
    // Everyday care here; health care (a vaccine, a vet visit) lives in the Health panel.
    val statuses = statusesFor(app, state, pet.id).filter { !it.task.kind.health }
    var reaction by remember { mutableStateOf<Reaction?>(null) }
    var burst by remember { mutableStateOf<Long?>(null) }
    var lastDone by remember { mutableStateOf<TaskStatus?>(null) }
    val haptics = LocalHapticFeedback.current
    fun react(event: PetEvent) {
        reaction = Reaction(event, (reaction?.nonce ?: 0) + 1)
        if (event is PetEvent.Cared) { burst = app.now + reaction!!.nonce; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    val clock = app.repo.clock
    val phase = phaseFor(app, state)
    val big = largeText()
    val celebrating = Milestones.toCelebrate(pet) != null
    // The bottom HUD's real height (two rows of meters at big fonts): the pet's floor sits above it.
    var hudHeight by remember { mutableStateOf(HUD_DEPTH) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(com.pawpixel.sprite.Room.floorColor(phase)))) {
        val screenHeight = maxHeight
        LivePet(
            art, pet.eyes, reading.mood, seed = pet.id.hashCode(), modifier = Modifier.fillMaxSize(), reaction = reaction,
            description = MoodEngine.describe(pet.name, reading.mood), phase = phase, keepAspect = false, floorDepth = hudHeight + 64.dp, zoom = 1.25f,
            onPetted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
        HeartBurst(burst, Modifier.matchParentSize())

        // Top HUD: the name plate (and the pet switcher when there's more than one pet), the gear.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                NamePlate(app, state, pet, reading.score)
                Spacer(Modifier.weight(1f))
                GlassButton(PixelIcons.GEAR, tr("Settings")) { app.navigate(Screen.Settings) }
            }
            // A milestone pops up here, over the room, with confetti; the bubble moves under it then.
            Box(Modifier.padding(top = 10.dp)) { MilestoneBanner(app, pet) }
            if (celebrating) SpeechBubble(reading.caption, Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally))
        }
        // What the pet is thinking: a bubble just over its head.
        if (!celebrating) SpeechBubble(reading.caption, Modifier.align(Alignment.TopCenter).padding(top = screenHeight * 0.27f))

        // Bottom HUD: Undo (for a few seconds after a tap), the need meters, the five keys.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .onSizeChanged { hudHeight = with(density) { it.height.toDp() } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            UndoStrip(app, pet, lastDone) { lastDone = null }
            if (statuses.isEmpty()) {
                EmptyNeeds(app, pet)
            } else {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = 5,
                ) {
                    statuses.forEach { s ->
                        NeedTile(app, state, pet, s, big) {
                            react(PetEvent.Cared(s.task.kind))
                            lastDone = s
                            app.launch { app.repo.complete(s.task.id) }
                        }
                    }
                }
            }
            val keys = listOf(
                DockItem(PixelIcons.PAW, tr("Care")) { app.navigate(Screen.PetSection(pet.id, "care")) },
                DockItem(PixelIcons.HEART, tr("Health")) { app.navigate(Screen.PetSection(pet.id, "health")) },
                DockItem(PixelIcons.SHIRT, tr("Wardrobe")) { app.navigate(Screen.PetSection(pet.id, "wardrobe")) },
                DockItem(PixelIcons.SHARE, tr("Share")) { app.navigate(Screen.PetSection(pet.id, "share")) },
                DockItem(PixelIcons.DOTS, tr("More")) { app.navigate(Screen.PetSection(pet.id, "more")) },
            )
            Box(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp)) { FloatingDock(keys, compact = true, onRoom = true) }
        }
    }
}

/** The pet's name on a sticker, its hearts, and its age: the game's character plate. */
@Composable
private fun NamePlate(app: AppScope, state: AppState, pet: Pet, score: Int) {
    val many = state.pets.size > 1
    val label = if (many) tr("Switch pet") else null
    val age = pet.birthDay?.let { HealthPlan.ageLabel(it, app.repo.clock.dayIndex(app.now)) }
    ToyPanel(
        Modifier.widthIn(max = 230.dp), face = Color.White, lip = Color(0xFFE6D5C3), shape = RoundedCornerShape(16.dp), padding = 0.dp,
        onClick = if (many) ({ app.navigate(Screen.PetSection(pet.id, "pets")) }) else null, onClickLabel = label,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    pet.name, style = MaterialTheme.typography.headlineSmall, color = Color(0xFF2B2135), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (many) PixelIcon(PixelIcons.CHEVRON_RIGHT, tint = Color(0xFF8C7BA8), size = 12.dp)
            }
            Hearts(score, heart = 13.dp, description = tr("Happiness {0} of 5", (score + 10) / 20))
            if (age != null) Text(age, style = MaterialTheme.typography.labelSmall, color = Color(0xFF6E6287))
        }
    }
}

/** A pixel speech bubble: a white sticker with a little tail underneath. */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    val ink = Color(0xFF2B2135)
    val edge = Color(0xFFE6D5C3)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.background(Color.White, RoundedCornerShape(14.dp)).border(2.dp, edge, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium, color = ink, textAlign = TextAlign.Center)
        }
        Canvas(Modifier.size(16.dp, 9.dp).offset(y = (-1).dp)) {
            val path = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }
            drawPath(path, edge)
            val inner = Path().apply { moveTo(3f, 0f); lineTo(size.width - 3f, 0f); lineTo(size.width / 2, size.height - 4f); close() }
            drawPath(inner, Color.White)
        }
    }
}

/**
 * One need, the way a virtual pet shows hunger: the care's candy sticker over a meter of how much
 * of its interval is left. Tap it to log the care (the pet eats, drinks, plays). Overdue, it bobs
 * for attention; done, it wears a check.
 */
@Composable
private fun NeedTile(app: AppScope, state: AppState, pet: Pet, s: TaskStatus, big: Boolean, onDone: () -> Unit) {
    val t = s.task
    val name = trName(t.title)
    val toy = Candy.forKind(t.kind)
    val done = s.allDoneThisCycle
    val interval = if (t.everyDays > 1) t.everyDays * com.pawpixel.core.DAY_MS else com.pawpixel.core.DAY_MS / t.slots.size.coerceAtLeast(1)
    val nextDue = s.nextDueMs
    val fraction = when {
        s.isOverdue -> 0f
        done -> 1f
        nextDue != null -> ((nextDue - app.now).toFloat() / interval).coerceIn(0.1f, 1f)
        else -> 1f
    }
    val label = when {
        done -> tr("{0}: all done today", name)
        else -> tr("Mark {0} done for {1}", name, pet.name)
    }
    // Overdue: a slow bob, like a pet pawing at its bowl.
    val bob = rememberInfiniteTransition(label = "bob")
    val lift by bob.animateFloat(0f, if (s.isOverdue && !state.isAway(app.now)) -4f else 0f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "lift")
    Pressable(
        Modifier.offset(y = lift.dp).semantics { contentDescription = label },
        face = Color.White, lip = Color(0xFFE6D5C3), outline = if (s.isOverdue) toy.lip else Color(0xFFE6D5C3),
        shape = RoundedCornerShape(16.dp), role = Role.Button, enabled = !done, onClickLabel = label,
        onClick = if (done) null else onDone, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(Modifier.width(if (big) 56.dp else 50.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                CandyTile(PixelIcons.forKind(t.kind), toy, size = 40.dp)
                if (done) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(16.dp).background(Paw.palette.good, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { PixelIcon(PixelIcons.CHECK, tint = Color.White, size = 9.dp) }
            }
            Meter(fraction, toy, Modifier.width(44.dp), segments = 5, height = 8.dp)
        }
    }
}

/** "Fed Chelsea · Undo", for a few seconds after a tap on a need: a mistaken tap is one tap back. */
@Composable
private fun UndoStrip(app: AppScope, pet: Pet, last: TaskStatus?, onGone: () -> Unit) {
    LaunchedEffect(last) { if (last != null) { delay(8_000); onGone() } }
    AnimatedVisibility(last != null, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
        val t = last?.task ?: return@AnimatedVisibility
        val name = trName(t.title)
        val undoLabel = tr("Undo {0} for {1}", name, pet.name)
        ToyPanel(Modifier.padding(bottom = 6.dp), face = Color.White, lip = Color(0xFFE6D5C3), shape = Pill, padding = 0.dp) {
            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelIcon(PixelIcons.CHECK, tint = Paw.palette.good, size = 14.dp)
                Text(tr("{0} done", name), style = MaterialTheme.typography.labelMedium, color = Color(0xFF2B2135))
                LinkButton(tr("Undo"), modifier = Modifier.semantics { contentDescription = undoLabel }) { onGone(); app.launch { app.repo.undo(t.id) } }
            }
        }
    }
}

/** No care tasks yet: one sticker that says what to do. */
@Composable
private fun EmptyNeeds(app: AppScope, pet: Pet) {
    ToyPanel(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), face = Color.White, lip = Color(0xFFE6D5C3), padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care.", pet.name), style = MaterialTheme.typography.bodySmall, color = Color(0xFF2B2135))
            PrimaryPill(tr("+ Add care task")) { app.navigate(Screen.EditTask(pet.id, null)) }
        }
    }
}
