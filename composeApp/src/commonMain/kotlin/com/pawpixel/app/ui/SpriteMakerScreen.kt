package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
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
    var species by remember { mutableStateOf(existing?.species ?: Species.DOG) }
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
    val art = remember(r, species, ears) { r?.let { PetArt(it.head, species, ears) } }
    val upToDate = source == null || madeFor == Triple(source, settings, face)

    val canSave = r != null && art != null && upToDate && !saving && !loading && (existing != null || name.isNotBlank())
    val save: () -> Unit = save@{
        val made = r ?: return@save
        saving = true
        app.launch {
            if (existing != null) {
                val latest = app.repo.state.value.pet(existing.id) ?: existing
                app.repo.updateSprite(latest.copy(species = species), settings, made, ears)
                app.back()
            } else if (StateOps.canAddPet(app.repo.state.value)) {
                val pet = app.repo.addPet(name, species, settings, made, ears, birthDay)
                app.repo.platform.requestNotificationPermission()
                app.back()
                app.navigate(Screen.PetDetail(pet.id))
            } else {
                error = tr("Your first pet is free. More pets come with PawPixel Pro (coming soon).")
            }
            saving = false
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .imePadding() // keeps the focused field and buttons above the keyboard
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Save lives in the header too, so naming and saving never need scrolling past the keyboard.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.pets.isNotEmpty() || existing != null) TextButton(onClick = app.back) { Text(tr("‹ Back")) }
            Text(
                if (existing != null) tr("Edit {0}'s look", existing.name) else tr("Make your pixel pet"),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
            )
            if (r != null) Button(enabled = canSave, onClick = save) { Text(tr("Save")) }
        }

        if (r == null || art == null) {
            PixelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Pick a photo of your pet"), fontWeight = FontWeight.Bold)
                    Text(tr("• Your pet's face clearly visible, looking at the camera\n• Good light; one pet per photo\n• A close-up or a full-body photo both work"))
                    Text(
                        tr("Your photo stays on this phone. PawPixel keeps only a small crop for your before/after card."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (loading) CircularProgressIndicator() else Button(onClick = pick) { Text(tr("Choose a photo")) }
                }
            }
        } else {
            PixelCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LivePet(art, emptyList(), Mood.HAPPY, seed = 7, modifier = Modifier.fillMaxWidth(), reaction = reaction)
                    Text(tr("Is that your pet? Tap to give pets."), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (m in listOf(Mood.HUNGRY, Mood.RESTLESS, Mood.SLEEPY, Mood.SAD)) {
                            val img = remember(art, m) {
                                if (m == Mood.SLEEPY) Poses.render(Chibi.sleeping(art), m)
                                else Poses.render(art.still, m)
                            }
                            SpriteView(img, Modifier.size(64.dp), animate = false)
                        }
                    }
                    if (!r.backgroundRemoved) {
                        // Also happens with close-ups, where the pet fills the photo: a tip, not an error.
                        Text(
                            tr("Colours look off? Move the face square below, or try a photo with a plainer background."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (loading) CircularProgressIndicator(Modifier.padding(top = 8.dp))
                }
            }

            if (existing == null) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text(tr("Pet's name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BirthdayRow(birthDay, app.repo.clock.dayIndex(app.now)) { askBirthday = true }
            }

            Text(tr("Body"), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Species.DOG to "Dog body", Species.CAT to "Cat body").forEach { (sp, label) ->
                    FilterChip(species == sp || (sp == Species.DOG && species == Species.OTHER), { species = sp }, label = { Text(tr(label)) })
                }
            }
            Text(tr("Ears"), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ears.entries.forEach { e -> FilterChip(art.ears == e, { ears = e }, label = { Text(tr(e.label)) }) }
            }

            source?.takeIf { upToDate }?.let { src ->
                Text(tr("Face"), fontWeight = FontWeight.Bold)
                Text(tr("Drag the square over your pet's face. Its colours and markings go on your pixel pet."), style = MaterialTheme.typography.bodySmall)
                FaceFramer(src.preview, face ?: r.face, Modifier.fillMaxWidth()) { moved -> face = moved.fitIn(src.preview.width, src.preview.height) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { face = resize(face ?: r.face, 0.88).fitIn(src.preview.width, src.preview.height) }) { Text(tr("Smaller")) }
                    OutlinedButton(onClick = { face = resize(face ?: r.face, 1.12).fitIn(src.preview.width, src.preview.height) }) { Text(tr("Bigger")) }
                }
            }

            OutlinedButton(onClick = pick) { Text(if (existing != null && source == null) tr("Use a new photo") else tr("Use a different photo")) }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = canSave, onClick = save, modifier = Modifier.fillMaxWidth()) {
                Text(if (existing != null) tr("Save") else if (name.isBlank()) tr("Save pet") else tr("Save {0}", name))
            }
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
    Canvas(
        modifier.aspectRatio(photo.width.toFloat() / photo.height)
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
