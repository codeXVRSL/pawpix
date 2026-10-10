package com.pawpixel.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.pawpixel.core.MoodEngine

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pawpixel.app.rememberPhotoPicker
import com.pawpixel.app.toImageBitmap
import com.pawpixel.core.AppState
import com.pawpixel.core.Mood
import com.pawpixel.core.Species
import com.pawpixel.core.SpriteSettings
import com.pawpixel.core.StateOps
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcons
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.FaceBox
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Poses
import com.pawpixel.sprite.SpritePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.math.roundToInt

private class Source(val photo: PixelImage, val mask: Mask?) {
    /** Small copy for showing the photo while framing the face. */
    val preview: PixelImage = photo.fitWithin(360)
}

/**
 * Photo → full-body pixel pet. The owner picks a photo, checks the face square (its colours and
 * markings go on the pet), chooses dog or cat body and ear shape, and sees the pet come alive.
 * Also used to edit an existing pet's look.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpriteMakerScreen(app: AppScope, state: AppState, existingPetId: String?) {
    val existing = existingPetId?.let { state.pet(it) }
    var source by remember { mutableStateOf<Source?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(existing?.sprite ?: SpriteSettings()) }
    var face by remember { mutableStateOf<FaceBox?>(null) }
    var result by remember { mutableStateOf(existing?.let { app.repo.storedResult(it) }) }
    var ears by remember { mutableStateOf(Ears.of(existing?.ears)) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    // Cat or dog: read from the photo on the phone, or chosen by the owner; never assumed.
    var species by remember { mutableStateOf(existing?.species?.takeIf { it != Species.OTHER }) }
    var detected by remember { mutableStateOf<Species?>(null) }
    var detecting by remember { mutableStateOf(false) }
    /** New pets: an optional birthday, for puppy and kitten care. */
    var birthDay by remember { mutableStateOf<Long?>(null) }
    var askBirthday by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var reaction by remember { mutableStateOf<Reaction?>(null) }
    /** Which inputs the current [result] was made from; eyes and Save wait until it matches. */
    var madeFor by remember { mutableStateOf<Any?>(null) }

    val pick = rememberPhotoPicker { bytes ->
        if (bytes == null) return@rememberPhotoPicker
        loading = true; error = null
        app.launch {
            val decoded = app.repo.platform.decodePhoto(bytes, SpritePipeline.WORKING_SIZE * 2)
            if (decoded == null) {
                error = tr("Couldn't open that photo. Try another one.")
            } else {
                // The native cut-out can stall (e.g. its model still downloading): fall back after a while.
                val mask = runCatching { withTimeoutOrNull(12_000) { app.repo.platform.segmentPet(decoded) } }.getOrNull()
                face = null        // let PawPixel find the face first
                source = Source(decoded, mask)
                // What is it? The phone's own classifier says cat or dog; the owner can still change it.
                detecting = true
                detected = runCatching { withTimeoutOrNull(8_000) { app.repo.platform.classifyPet(decoded) } }.getOrNull()
                detecting = false
                if (detected != null) species = detected
            }
            loading = false
        }
    }

    // Re-generate when the photo, settings or face square change (short pause = smooth dragging).
    LaunchedEffect(source, settings, face) {
        val src = source ?: return@LaunchedEffect
        if (face != null) delay(150)
        loading = true
        try {
            val made = withContext(Dispatchers.Default) {
                runCatching { SpritePipeline.generate(src.photo, settings, src.mask, face) }.getOrNull()
            }
            if (made != null) { result = made; madeFor = Triple(src, settings, face); error = null }
            else error = tr("Couldn't make a pet from that photo. Try another one.")
        } finally {
            loading = false
        }
    }

    val r = result
    val shownSpecies = species ?: detected ?: Species.CAT
    val art = remember(r, shownSpecies, ears) { r?.let { PetArt(it.look, shownSpecies, ears) } }
    // The reveal: the first time a new pet appears, its stage springs in under a little confetti.
    var revealed by remember { mutableStateOf<Int?>(null) }
    val entrance = remember { Animatable(if (existing != null) 1f else 0.88f) }
    LaunchedEffect(r != null) {
        if (r != null && revealed == null && existing == null) {
            revealed = r.hashCode()
            entrance.animateTo(1f, defaultSpatial())
        }
    }
    val upToDate = source == null || madeFor == Triple(source, settings, face)

    val canSave = r != null && art != null && upToDate && !saving && !loading && species != null && (existing != null || name.isNotBlank())
    val save: () -> Unit = save@{
        val made = r ?: return@save
        saving = true
        app.launch {
            try {
                if (existing != null) {
                    val latest = app.repo.state.value.pet(existing.id) ?: existing
                    app.repo.updateSprite(latest.copy(species = species ?: latest.species), settings, made, ears)
                    app.back()
                } else if (StateOps.canAddPet(app.repo.state.value)) {
                    // Notifications are offered on the pet's page, next to the care they're for.
                    val pet = app.repo.addPet(name, species ?: Species.CAT, settings, made, ears, birthDay)
                    app.showPet(pet.id) // home, showing the new pet's room (from wherever "add a pet" was tapped)
                } else {
                    error = tr("Your first pet is free forever. Extra pets are part of PawPixel Pro, which is coming soon.")
                }
            } finally {
                saving = false
            }
        }
    }

    val phase = phaseFor(app, state)
    Column(
        Modifier.fillMaxSize().background(heroGlow()).statusBarsPadding().navigationBarsPadding()
            .imePadding() // keeps the focused field and buttons above the keyboard
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Save lives in the header too, so naming and saving never need scrolling past the keyboard.
        TopBar(
            app.takeIf { state.pets.isNotEmpty() || existing != null },
            if (existing != null) tr("Edit {0}'s look", existing.name) else tr("Make your pixel pet"),
        ) {
            if (r != null) PrimaryPill(tr("Save"), enabled = canSave, onClick = save)
        }

        if (r == null || art == null) {
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    // The photo goes here: an empty room, waiting for a pet.
                    RoomBackdrop(phase, Modifier.fillMaxWidth().height(160.dp).clip(MaterialTheme.shapes.medium)) { floor ->
                        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
                        else PixelIcon(PixelIcons.CAMERA, tint = PawColors.Ink.copy(alpha = 0.4f), size = 40.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = floor + 8.dp))
                    }
                    Text(tr("Pick a photo of your pet"), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Text(
                        tr("• Your pet's face clearly visible, looking at the camera\n• Good light; one pet per photo\n• A close-up or a full-body photo both work"),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(),
                    )
                    if (!loading) PrimaryPill(tr("Choose a photo"), big = true, onClick = pick)
                    Hint(tr("Your photo stays on this phone. PawPixel keeps only a small crop for your before/after card."), align = TextAlign.Center)
                }
            }
            if (existing == null && state.pets.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Hint(tr("Someone at home already has your pet on PawPixel?"))
                    JoinHouseholdLink(app)
                }
            }
        } else {
            SoftCard(Modifier.fillMaxWidth().graphicsLayer { scaleX = entrance.value; scaleY = entrance.value }, tone = Tone.Surface, padding = 0.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val shownName = name.trim().ifEmpty { existing?.name ?: tr("your pet") }
                    Box(Modifier.fillMaxWidth()) {
                        LivePet(
                            art, emptyList(), Mood.HAPPY, seed = 7, modifier = Modifier.fillMaxWidth(), reaction = reaction,
                            description = MoodEngine.describe(shownName, Mood.HAPPY), phase = phase,
                        )
                        if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp), strokeWidth = 3.dp)
                        Confetti(revealed, Modifier.matchParentSize())
                    }
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("Is that your pet? Tap to give pets."), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (m in listOf(Mood.HUNGRY, Mood.RESTLESS, Mood.SLEEPY, Mood.SAD)) {
                                val img = remember(art, m) {
                                    if (m == Mood.SLEEPY) Poses.render(Chibi.sleeping(art), m)
                                    else Poses.render(art.still, m)
                                }
                                Box(Modifier.size(64.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                                    SpriteView(img, Modifier.size(56.dp), animate = false, description = MoodEngine.describe(shownName, m))
                                }
                            }
                        }
                        if (!r.backgroundRemoved) {
                            // Also happens with close-ups, where the pet fills the photo: a tip, not an error.
                            Hint(tr("Colours look off? Move the face square below, or try a photo with a plainer background."), align = TextAlign.Center)
                        }
                    }
                }
            }

            if (existing == null) {
                OutlinedTextField(
                    name, { name = it.take(24) }, label = { Text(tr("Pet's name")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
                )
                BirthdayRow(birthDay, app.repo.clock.dayIndex(app.now)) { askBirthday = true }
            }

            // Cat, dog or rabbit, first: a cat drawn as a dog is the one mistake that spoils everything.
            SoftCard(Modifier.fillMaxWidth(), tone = if (species == null) Tone.Accent else Tone.Surface) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            detecting -> tr("Looking at the photo…")
                            species == null -> tr("Cat, dog or rabbit? Tap one.")
                            detected == species && detected == Species.CAT -> tr("Looks like a cat. Not right? Tap Dog.")
                            detected == species && detected == Species.DOG -> tr("Looks like a dog. Not right? Tap Cat.")
                            detected == species && detected == Species.RABBIT -> tr("Looks like a rabbit. Not right? Tap Cat or Dog.")
                            species == Species.CAT -> tr("Drawn as a cat.")
                            species == Species.RABBIT -> tr("Drawn as a rabbit.")
                            else -> tr("Drawn as a dog.")
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Species.CAT to "Cat body", Species.DOG to "Dog body", Species.RABBIT to "Rabbit body").forEach { (sp, label) ->
                            ChoiceChip(species == sp, { species = sp }, tr(label))
                        }
                    }
                }
            }
            GroupLabel(tr("Ears"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A rabbit's ears stand up or hang down (a lop).
                Ears.entries.forEach { e ->
                    val label = if (art.species != Species.RABBIT) e.label else if (e == Ears.POINTY) "Upright ears" else "Lop ears"
                    ChoiceChip(art.ears == e, { ears = e }, tr(label))
                }
            }

            source?.takeIf { upToDate }?.let { src ->
                GroupLabel(tr("Face"))
                Hint(tr("Drag the square over your pet's face. Its colours and markings go on your pixel pet."))
                FaceFramer(src.preview, face ?: r.face, Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)) { moved -> face = moved.fitIn(src.preview.width, src.preview.height) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val smaller = tr("Make the face square smaller")
                    val bigger = tr("Make the face square bigger")
                    GhostPill(tr("Smaller"), modifier = Modifier.semantics { contentDescription = smaller }) {
                        face = resize(face ?: r.face, 0.88).fitIn(src.preview.width, src.preview.height)
                    }
                    GhostPill(tr("Bigger"), modifier = Modifier.semantics { contentDescription = bigger }) {
                        face = resize(face ?: r.face, 1.12).fitIn(src.preview.width, src.preview.height)
                    }
                }
            }

            GhostPill(if (existing != null && source == null) tr("Use a new photo") else tr("Use a different photo"), onClick = pick)

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryPill(
                if (existing != null) tr("Save") else if (name.isBlank()) tr("Save pet") else tr("Save {0}", name),
                enabled = canSave, big = true, modifier = Modifier.fillMaxWidth(), onClick = save,
            )
        }
        error?.takeIf { r == null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
    }
    if (askBirthday) {
        BirthdayDialog(
            name.ifBlank { tr("your pet") }, birthDay, app.repo.clock.dayIndex(app.now), skipLabel = tr("Cancel"),
            onSkip = { askBirthday = false }, onSave = { birthDay = it; askBirthday = false },
        )
    }
}

private fun resize(f: FaceBox, by: Double) = f.copy(side = (f.side * by).coerceIn(0.15, 1.0))

/** The photo with a draggable square marking the face. */
@Composable
private fun FaceFramer(photo: PixelImage, face: FaceBox, modifier: Modifier, onMove: (FaceBox) -> Unit) {
    val bitmap = remember(photo) { photo.toImageBitmap() }
    val current by rememberUpdatedState(face)
    val move by rememberUpdatedState(onMove)
    val accent = MaterialTheme.colorScheme.primary
    val described = tr("Your photo, with a square on your pet's face. Drag it to move it; the Smaller and Bigger buttons resize it.")
    Canvas(
        modifier.aspectRatio(photo.width.toFloat() / photo.height)
            .semantics { contentDescription = described }
            .clipToBounds()
            .pointerInput(photo) {
                // Accumulate within a gesture so fast moves between redraws aren't lost.
                var f = current
                detectDragGestures(onDragStart = { _: Offset -> f = current }) { change, drag ->
                    change.consume()
                    f = f.copy(
                        cx = (f.cx + drag.x / size.width).coerceIn(0.0, 1.0),
                        cy = (f.cy + drag.y / size.height).coerceIn(0.0, 1.0),
                    )
                    move(f)
                }
            },
    ) {
        drawImage(bitmap, IntOffset.Zero, IntSize(photo.width, photo.height), IntOffset.Zero,
            IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
        val side = (face.side * min(size.width, size.height)).toFloat()
        val tl = Offset((face.cx * size.width).toFloat() - side / 2, (face.cy * size.height).toFloat() - side / 2)
        drawRect(Color.White, tl, Size(side, side), style = Stroke(width = 6f))
        drawRect(accent, tl, Size(side, side), style = Stroke(width = 3f))
    }
}
