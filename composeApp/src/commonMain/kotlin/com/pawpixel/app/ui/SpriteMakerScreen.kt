package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import com.pawpixel.sprite.Animator
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.FaceBox
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetEvent
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Poses
import com.pawpixel.sprite.SpritePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private class Source(val photo: PixelImage, val mask: Mask?) {
    /** Small copy for showing the photo while framing the face. */
    val preview: PixelImage = photo.fitWithin(360)
}

/**
 * Photo → full-body pixel pet. The owner picks a photo, checks the face square, chooses dog or cat
 * body, taps the eyes, and sees the pet come alive. Also used to edit an existing pet's look.
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
    var eyes by remember { mutableStateOf(existing?.eyes ?: emptyList()) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var species by remember { mutableStateOf(existing?.species ?: Species.DOG) }
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
                error = "Couldn't open that photo. Try another one."
            } else {
                val mask = runCatching { app.repo.platform.segmentPet(decoded) }.getOrNull()
                eyes = emptyList() // new photo, new face
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
            else error = "Couldn't make a pet from that photo. Try another one."
        } finally {
            loading = false
        }
    }

    val r = result
    val art = remember(r, species) { r?.let { PetArt(it.head, species) } }
    val upToDate = source == null || madeFor == Triple(source, settings, face)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.pets.isNotEmpty() || existing != null) TextButton(onClick = app.back) { Text("‹ Back") }
            Text(
                if (existing != null) "Edit ${existing.name}'s look" else "Make your pixel pet",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
        }

        if (r == null || art == null) {
            PixelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pick a photo of your pet", fontWeight = FontWeight.Bold)
                    Text("• Your pet's face clearly visible, looking at the camera\n• Good light; one pet per photo\n• A close-up or a full-body photo both work")
                    Text(
                        "Your photo stays on this phone. PawPixel keeps only a small crop for your before/after card.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (loading) CircularProgressIndicator() else Button(onClick = pick) { Text("Choose a photo") }
                }
            }
        } else {
            PixelCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LivePet(art, eyes, Mood.HAPPY, seed = 7, modifier = Modifier.fillMaxWidth(), reaction = reaction)
                    Text("Is that your pet? Tap to give pets.", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (m in listOf(Mood.HUNGRY, Mood.RESTLESS, Mood.SLEEPY, Mood.SAD)) {
                            val img = remember(art, m, eyes) {
                                if (m == Mood.SLEEPY) Poses.render(Chibi.sleeping(art, Animator.eyePixels(art.head, eyes)), m)
                                else Poses.render(art.still, m)
                            }
                            SpriteView(img, Modifier.size(64.dp), animate = false)
                        }
                    }
                    if (!r.backgroundRemoved) {
                        Text(
                            "Couldn't separate your pet from the background. A photo with a plainer background will look better.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (loading) CircularProgressIndicator(Modifier.padding(top = 8.dp))
                }
            }

            Text("Body", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Species.DOG to "Dog body", Species.CAT to "Cat body").forEach { (sp, label) ->
                    FilterChip(species == sp || (sp == Species.DOG && species == Species.OTHER), { species = sp }, label = { Text(label) })
                }
            }

            source?.takeIf { upToDate }?.let { src ->
                Text("Face", fontWeight = FontWeight.Bold)
                Text("Drag the square over your pet's face, ears included.", style = MaterialTheme.typography.bodySmall)
                FaceFramer(src.preview, face ?: r.face, Modifier.fillMaxWidth()) { moved ->
                    face = moved
                    eyes = emptyList()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { face = resize(face ?: r.face, 0.88); eyes = emptyList() }) { Text("Smaller") }
                    OutlinedButton(onClick = { face = resize(face ?: r.face, 1.12); eyes = emptyList() }) { Text("Bigger") }
                }
            }

            Text("Eyes", fontWeight = FontWeight.Bold)
            Text(
                when (eyes.size) {
                    0 -> "Tap each of your pet's eyes so they can blink and close them to sleep."
                    1 -> "Now the other eye (skip it if only one shows)."
                    else -> "Eyes marked. Watch them blink above!"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EyeTapper(r.head, eyes, Modifier.width(180.dp)) { tap ->
                    if (!upToDate) return@EyeTapper // the face is being redrawn
                    eyes = if (eyes.size >= 2) listOf(tap) else eyes + tap
                    reaction = Reaction(PetEvent.Petted, (reaction?.nonce ?: 0) + 1)
                }
                if (eyes.isNotEmpty()) TextButton(onClick = { eyes = emptyList() }) { Text("Clear") }
            }

            if (source != null) {
                Text("Detail", fontWeight = FontWeight.Bold)
                ChipRow(listOf(32 to "Small", 40 to "Medium", 48 to "Large", 64 to "Detailed"), settings.size) { settings = settings.copy(size = it); eyes = emptyList() }
                Text("Colours", fontWeight = FontWeight.Bold)
                ChipRow(listOf(6 to "6", 8 to "8", 12 to "12", 16 to "16"), settings.colors) { settings = settings.copy(colors = it) }
            }
            OutlinedButton(onClick = pick) { Text(if (existing != null && source == null) "Use a new photo" else "Use a different photo") }

            if (existing == null) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Pet's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = upToDate && !saving && !loading && (existing != null || name.isNotBlank()),
                onClick = {
                    saving = true
                    app.launch {
                        if (existing != null) {
                            val latest = app.repo.state.value.pet(existing.id) ?: existing
                            app.repo.updateSprite(latest.copy(species = species), settings, r, eyes)
                            app.back()
                        } else if (StateOps.canAddPet(app.repo.state.value)) {
                            val pet = app.repo.addPet(name, species, settings, r, eyes)
                            app.repo.platform.requestNotificationPermission()
                            app.back()
                            app.navigate(Screen.PetDetail(pet.id))
                        } else {
                            error = "Your first pet is free. More pets come with PawPixel Pro (coming soon)."
                        }
                        saving = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (existing != null) "Save" else "Save ${name.ifBlank { "pet" }}") }
        }
        error?.takeIf { r == null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
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

/** The pet's face, big and square, where the owner taps the eyes. Reports taps as fractions of the face image. */
@Composable
private fun EyeTapper(head: PixelImage, eyes: List<Pair<Double, Double>>, modifier: Modifier, onTap: (Pair<Double, Double>) -> Unit) {
    val bitmap = remember(head) { head.toImageBitmap() }
    val marker = MaterialTheme.colorScheme.primary
    Canvas(
        modifier.aspectRatio(head.width.toFloat() / head.height)
            .pointerInput(head) {
                detectTapGestures { o ->
                    val fx = (o.x / size.width).toDouble().coerceIn(0.0, 0.999)
                    val fy = (o.y / size.height).toDouble().coerceIn(0.0, 0.999)
                    onTap(fx to fy)
                }
            },
    ) {
        drawRect(PawColors.Sand)
        val s = min(size.width / head.width, size.height / head.height)
        drawImage(
            bitmap, IntOffset.Zero, IntSize(head.width, head.height),
            IntOffset.Zero, IntSize((head.width * s).roundToInt(), (head.height * s).roundToInt()),
            filterQuality = FilterQuality.None,
        )
        for ((fx, fy) in eyes) {
            val c = Offset((fx * size.width).toFloat(), (fy * size.height).toFloat())
            drawCircle(Color.White, radius = s * 1.8f, center = c, style = Stroke(width = s * 0.8f))
            drawCircle(marker, radius = s * 1.8f, center = c, style = Stroke(width = s * 0.45f))
        }
        drawRect(Color(0x33000000), size = Size(size.width, size.height), style = Stroke(width = 2f))
    }
}

@Composable
private fun ChipRow(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) -> FilterChip(selected == v, { onSelect(v) }, label = { Text(label) }) }
    }
}
