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
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.Ears
import com.pawpixel.core.Occasions
import com.pawpixel.core.Species
import com.pawpixel.core.WeatherAdvice
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
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Sky
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
    // A remembered pet's room is a quiet night: the lamp on, stars in the window.
    val phase = if (pet.remembered) Sky.Phase.NIGHT else phaseFor(app, state)
    val big = largeText()
    val celebrating = !pet.remembered && Milestones.toCelebrate(pet) != null
    // The bottom HUD's real height (two rows of meters at big fonts): the pet's floor sits above it.
    var hudHeight by remember { mutableStateOf(HUD_DEPTH) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(com.pawpixel.sprite.Room.floorColor(phase)))) {
        val screenHeight = maxHeight
        LivePet(
            art, pet.eyes, reading.mood, seed = pet.id.hashCode(), modifier = Modifier.fillMaxSize(), reaction = reaction,
            description = MoodEngine.describe(pet.name, reading.mood), phase = phase, keepAspect = false, floorDepth = hudHeight + 64.dp, zoom = 1.25f,
            onPetted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            season = seasonFor(app),
        )
        HeartBurst(burst, Modifier.matchParentSize())

        // A pal's pet dropping by (one a day, by the door), and a treat a pal sent, as the bubble.
        val palModel = lostModel(app)
        val today = clock.dayIndex(app.now)
        val visitor = remember(palModel?.pals, today, pet.remembered) { if (pet.remembered) null else palModel?.visitor(today) }
        var treatBubble by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(pet.id) {
            val fresh = runCatching { palModel?.newTreats() }.getOrNull().orEmpty().filter { it.toPetId == pet.id }
            fresh.firstOrNull()?.let { treatBubble = treatText(it.fromPet, pet.name, it.kind); burst = app.now }
        }
        LaunchedEffect(treatBubble) { if (treatBubble != null) { kotlinx.coroutines.delay(9_000); treatBubble = null } }
        // A birthday or gotcha day: bunting and confetti up top, and the pet says so.
        val occasion = remember(pet.id, pet.birthDay, clock.dayIndex(app.now)) { Occasions.today(pet, app.now, clock) }
        // The weather outside (for the owner's map area), as a chip and, when it matters, the pet's bubble for a moment.
        var weather by remember { mutableStateOf(app.repo.weather.fresh()) }
        var weatherBubble by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(pet.id) {
            val area = palModel?.myArea
            if (area != null) weather = runCatching { app.repo.weather.current(area.centerLat, area.centerLng) }.getOrNull() ?: weather
            val w = weather ?: return@LaunchedEffect
            if (!pet.remembered) WeatherAdvice.advice(w, pet.name, pet.species)?.let { weatherBubble = it; kotlinx.coroutines.delay(8_000); weatherBubble = null }
        }
        visitor?.let { v ->
            val species = Species.entries.firstOrNull { it.name == v.species } ?: Species.OTHER
            val img = remember(v.petId) { v.look?.let { PetArt(it, species, Ears.of(v.ears)).still } }
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = hudHeight + 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SpriteView(img, Modifier.size(64.dp), animate = false, description = tr("{0}, a pal's pet, is visiting", v.name))
                Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, PawColors.StickerEdge, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(tr("{0} is visiting", v.name), style = MaterialTheme.typography.labelSmall, color = PawColors.Ink)
                }
            }
        }

        // Top HUD: the name plate (and the pet switcher when there's more than one pet), the gear.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NamePlate(app, state, pet, reading.score)
                    weather?.let { w -> WeatherChip(w) }
                }
                Spacer(Modifier.weight(1f))
                GlassButton(PixelIcons.GEAR, tr("Settings")) { app.navigate(Screen.Settings) }
            }
            // A milestone pops up here, over the room, with confetti; the bubble moves under it then.
            if (!pet.remembered) Box(Modifier.padding(top = 10.dp)) { MilestoneBanner(app, pet) }
            occasion?.let { Box(Modifier.padding(top = 10.dp)) { OccasionBanner(it, pet) } }
            if (celebrating) SpeechBubble(reading.caption, Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally))
        }
        // What the pet is thinking: a bubble just over its head.
        if (!celebrating) SpeechBubble(treatBubble ?: weatherBubble ?: occasion?.bubble ?: reading.caption, Modifier.align(Alignment.TopCenter).padding(top = screenHeight * 0.27f))

        // Bottom HUD: Undo (for a few seconds after a tap), the need meters, the five keys. The floor follows the
        // meters and keys only: the passing Undo strip overlays the room instead of pushing it up and down.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            UndoStrip(app, pet, lastDone) { lastDone = null }
            Column(Modifier.fillMaxWidth().onSizeChanged { hudHeight = with(density) { it.height.toDp() } }, horizontalAlignment = Alignment.CenterHorizontally) {
            if (lostAlertFor(app, pet.id) != null) LostStrip(app, pet)
            if (pet.remembered) {
                MemoryStrip(app, state, pet)
            } else if (statuses.isEmpty()) {
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
                if (pet.remembered) DockItem(PixelIcons.CAMERA, tr("Album")) { app.navigate(Screen.PetSection(pet.id, "album")) }
                else DockItem(PixelIcons.PAW, tr("Care")) { app.navigate(Screen.PetSection(pet.id, "care")) },
                DockItem(PixelIcons.HEART, tr("Health")) { app.navigate(Screen.PetSection(pet.id, "health")) },
                DockItem(PixelIcons.SHIRT, tr("Wardrobe")) { app.navigate(Screen.PetSection(pet.id, "wardrobe")) },
                DockItem(PixelIcons.SHARE, tr("Share")) { app.navigate(Screen.PetSection(pet.id, "share")) },
                DockItem(PixelIcons.DOTS, tr("More")) { app.navigate(Screen.PetSection(pet.id, "more")) },
            )
            Box(Modifier.padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 10.dp)) { FloatingDock(keys, compact = true, onRoom = true) }
        }
    }
        }
}

/** The pet's name on a sticker, its hearts, and its age: the game's character plate. */
@Composable
private fun NamePlate(app: AppScope, state: AppState, pet: Pet, score: Int) {
    val many = state.pets.size > 1
    val label = if (many) tr("Switch pet") else null
    // Age when the birthday is known; otherwise the days you've been together (never a streak to break).
    val together = if (pet.createdAtMs > 0) ((app.now - pet.createdAtMs) / com.pawpixel.core.DAY_MS).toInt() else 0
    val birthDay = pet.birthDay
    val age = when {
        pet.remembered -> tr("Forever in your heart")
        birthDay != null -> HealthPlan.ageLabel(birthDay, app.repo.clock.dayIndex(app.now))
        together >= 1 -> tr("{0} days together", together)
        else -> null
    }
    ToyPanel(
        Modifier.widthIn(max = 230.dp), face = Color.White, lip = PawColors.StickerEdge, shape = RoundedCornerShape(16.dp), padding = 0.dp,
        onClick = if (many) ({ app.navigate(Screen.PetSection(pet.id, "pets")) }) else null, onClickLabel = label,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    pet.name, style = MaterialTheme.typography.headlineSmall, color = PawColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
    val ink = PawColors.Ink
    val edge = PawColors.StickerEdge
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
    val fraction = s.meterFraction(app.now)
    val label = when {
        done -> tr("{0}: all done today", name)
        else -> tr("Mark {0} done for {1}", name, pet.name)
    }
    // Overdue: a slow bob, like a pet pawing at its bowl. Only an overdue tile runs the animation, and the
    // offset is read in the layout lambda, so the rest of the room doesn't recompose on every frame.
    val lift: androidx.compose.runtime.State<Float> =
        if (s.isOverdue && !state.isAway(app.now)) rememberInfiniteTransition(label = "bob").animateFloat(0f, -4f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "lift")
        else remember { mutableStateOf(0f) }
    Pressable(
        Modifier.offset { androidx.compose.ui.unit.IntOffset(0, lift.value.dp.roundToPx()) }.semantics { contentDescription = label },
        face = Color.White, lip = PawColors.StickerEdge, outline = if (s.isOverdue) toy.lip else PawColors.StickerEdge,
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
    // The last task stays for the exit slide, after `last` has already gone back to null.
    var shown by remember { mutableStateOf(last) }
    if (last != null) shown = last
    AnimatedVisibility(last != null, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically { it / 2 } + fadeOut()) {
        val t = shown?.task ?: return@AnimatedVisibility
        val name = trName(t.title)
        val undoLabel = tr("Undo {0} for {1}", name, pet.name)
        ToyPanel(Modifier.padding(bottom = 6.dp), face = Color.White, lip = PawColors.StickerEdge, shape = Pill, padding = 0.dp) {
            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelIcon(PixelIcons.CHECK, tint = Paw.palette.good, size = 14.dp)
                Text(tr("{0} done", name), style = MaterialTheme.typography.labelMedium, color = PawColors.Ink)
                LinkButton(tr("Undo"), modifier = Modifier.semantics { contentDescription = undoLabel }) { onGone(); app.launch { app.repo.undo(t.id) } }
            }
        }
    }
}

/** A remembered pet: in place of its needs, its dates and the door to its album. */
@Composable
private fun MemoryStrip(app: AppScope, state: AppState, pet: Pet) {
    val photos = state.albumFor(pet.id).size
    val since = LocalClock.shortDate(pet.rememberedDay ?: 0)
    ToyPanel(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), face = Color.White, lip = PawColors.StickerEdge, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CandyTile(PixelIcons.STAR, Candy.Lavender, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("In loving memory"), style = MaterialTheme.typography.titleSmall, color = PawColors.Ink)
                Text(
                    if (photos == 0) since else tr("{0} · {1} photos", since, photos),
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF6E6287),
                )
            }
            PrimaryPill(tr("Album"), icon = PixelIcons.CAMERA) { app.navigate(Screen.PetSection(pet.id, "album")) }
        }
    }
}

/** Today's decorations, from the phone's calendar. */
fun seasonFor(app: AppScope): com.pawpixel.sprite.Season {
    val (_, m, d) = LocalClock.civil(app.repo.clock.dayIndex(app.now))
    return com.pawpixel.sprite.Season.forDate(m, d)
}

/** The day's bunting: a birthday or gotcha day card with confetti, up by the name plate. */
@Composable
private fun OccasionBanner(occasion: com.pawpixel.core.Occasion, pet: Pet) {
    val party = remember(pet.id, occasion) { "${pet.id}:$occasion" }
    Box(Modifier.fillMaxWidth()) {
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelIcon(PixelIcons.PARTY, size = 20.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(occasion.title + "!", style = MaterialTheme.typography.titleMedium)
                    Text(tr("Extra treats today. The room is decorated for it."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Confetti(party, Modifier.matchParentSize())
    }
}

/** "31° · sunny": the weather outside, from Open-Meteo, for the owner's area. */
@Composable
private fun WeatherChip(w: com.pawpixel.core.Weather) {
    val label = tr("Weather outside: {0}", WeatherAdvice.chip(w))
    Box(
        Modifier.background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, PawColors.StickerEdge, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp).semantics { contentDescription = label },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PixelIcon(if (w.rain || w.storm) PixelIcons.DROP else if (w.isDay) PixelIcons.SPARKLE else PixelIcons.MOON, tint = Color(0xFF6E6287), size = 12.dp)
            Text(WeatherAdvice.chip(w), style = MaterialTheme.typography.labelSmall, color = PawColors.Ink)
        }
    }
}

/** While a lost alert is on: the way to its sightings, right on the room. */
@Composable
private fun LostStrip(app: AppScope, pet: Pet) {
    ToyPanel(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), face = Color(0xFFFFE9E4), lip = Color(0xFFD9574A), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CandyTile(PixelIcons.BELL, Candy.Coral, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Reported lost"), style = MaterialTheme.typography.titleSmall, color = PawColors.Ink)
                Text(tr("Owners nearby are looking. Sightings show in the alert."), style = MaterialTheme.typography.labelSmall, color = Color(0xFF6E6287))
            }
            PrimaryPill(tr("Alert"), icon = PixelIcons.BELL) { app.navigate(Screen.PetSection(pet.id, "lost")) }
        }
    }
}

/** No care tasks yet: one sticker that says what to do. */
@Composable
private fun EmptyNeeds(app: AppScope, pet: Pet) {
    ToyPanel(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), face = Color.White, lip = PawColors.StickerEdge, padding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care.", pet.name), style = MaterialTheme.typography.bodySmall, color = PawColors.Ink)
            PrimaryPill(tr("+ Add care task")) { app.navigate(Screen.EditTask(pet.id, null)) }
        }
    }
}
