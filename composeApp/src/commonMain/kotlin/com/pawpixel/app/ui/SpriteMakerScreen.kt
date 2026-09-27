package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PetEvent
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Poses
import com.pawpixel.sprite.SpritePipeline
import com.pawpixel.sprite.SpriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private class Source(val photo: PixelImage, val mask: Mask?)

/**
 * Photo → sprite, with live tweaking, eye marking and a live preview. Used for new pets, and for
 * remaking an existing pet's sprite or just marking its eyes.
 */
@Composable
fun SpriteMakerScreen(app: AppScope, state: AppState, existingPetId: String?) {
    val existing = existingPetId?.let { state.pet(it) }
    var source by remember { mutableStateOf<Source?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(existing?.sprite ?: SpriteSettings()) }
    var result by remember { mutableStateOf(existing?.let { app.repo.storedResult(it.id) }) }
    var eyes by remember { mutableStateOf(existing?.eyes ?: emptyList()) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var species by remember { mutableStateOf(existing?.species ?: Species.DOG) }
    var saving by remember { mutableStateOf(false) }
    var reaction by remember { mutableStateOf<Reaction?>(null) }

    val pick = rememberPhotoPicker { bytes ->
        if (bytes == null) return@rememberPhotoPicker
        loading = true; error = null
        app.launch {
            val decoded = app.repo.platform.decodePhoto(bytes, SpritePipeline.WORKING_SIZE * 2)
            if (decoded == null) {
                error = "Couldn't open that photo. Try another one."
            } else {
                val mask = runCatching { app.repo.platform.segmentPet(decoded) }.getOrNull()
                eyes = emptyList() // new photo, new framing
                source = Source(decoded, mask)
            }
            loading = false
        }
    }

    // Re-generate whenever the photo or the settings change.
    LaunchedEffect(source, settings) {
        val src = source ?: return@LaunchedEffect
        loading = true
        try {
            val made = withContext(Dispatchers.Default) { runCatching { SpritePipeline.generate(src.photo, settings, src.mask) }.getOrNull() }
            if (made != null) result = made else error = "Couldn't make a sprite from that photo. Try another one."
        } finally {
            loading = false
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.pets.isNotEmpty() || existing != null) TextButton(onClick = app.back) { Text("‹ Back") }
            Text(
                if (existing != null) "Remake ${existing.name}" else "Make your pixel pet",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
        }

        val r = result
        if (r == null) {
            PixelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pick a photo of your pet", fontWeight = FontWeight.Bold)
                    Text("• Whole pet in the frame, facing the camera\n• Good light, plain background if you can\n• One pet per photo")
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
                    LivePet(
                        r.sprite, eyes, Mood.HAPPY, seed = 7, modifier = Modifier.fillMaxWidth(), reaction = reaction,
                    )
                    Text("Is that your pet? Tap to give pets.", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (m in listOf(Mood.HUNGRY, Mood.RESTLESS, Mood.SLEEPY, Mood.SAD)) {
                            val img = remember(r, m) { Poses.render(r.sprite, m) }
                            SpriteView(img, Modifier.size(56.dp), animate = false)
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

            Text("Eyes", fontWeight = FontWeight.Bold)
            Text(
                when (eyes.size) {
                    0 -> "Tap each of your pet's eyes so they can blink and close them to sleep."
                    1 -> "Now the other eye (or tap Done if only one shows)."
                    else -> "Eyes marked. Watch them blink above!"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EyeTapper(r.sprite, eyes, Modifier.width(180.dp)) { tap ->
                    eyes = if (eyes.size >= 2) listOf(tap) else eyes + tap
                    reaction = Reaction(PetEvent.Petted, (reaction?.nonce ?: 0) + 1)
                }
                if (eyes.isNotEmpty()) TextButton(onClick = { eyes = emptyList() }) { Text("Clear") }
            }

            if (source != null || existing == null) {
                Text("Size", fontWeight = FontWeight.Bold)
                ChipRow(listOf(32 to "Small", 40 to "Medium", 48 to "Large", 64 to "Detailed"), settings.size) { settings = settings.copy(size = it) }
                Text("Colours", fontWeight = FontWeight.Bold)
                ChipRow(listOf(6 to "6", 8 to "8", 12 to "12", 16 to "16"), settings.colors) { settings = settings.copy(colors = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Outline", Modifier.weight(1f))
                    Switch(settings.outline, { settings = settings.copy(outline = it) })
                }
            }
            OutlinedButton(onClick = pick) { Text(if (existing != null && source == null) "Use a new photo" else "Use a different photo") }

            if (existing == null) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("Pet's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Species.entries.forEach { sp -> FilterChip(species == sp, { species = sp }, label = { Text(sp.label) }) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !saving && !loading && (existing != null || name.isNotBlank()),
                onClick = {
                    saving = true
                    app.launch {
                        if (existing != null) {
                            app.repo.updateSprite(existing, settings, r, eyes)
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

/** The sprite, big and square, where the owner taps the eyes. Reports taps as fractions of sprite size. */
@Composable
private fun EyeTapper(sprite: PixelImage, eyes: List<Pair<Double, Double>>, modifier: Modifier, onTap: (Pair<Double, Double>) -> Unit) {
    val bitmap = remember(sprite) { sprite.toImageBitmap() }
    val marker = MaterialTheme.colorScheme.primary
    Canvas(
        modifier.aspectRatio(sprite.width.toFloat() / sprite.height)
            .pointerInput(sprite) {
                detectTapGestures { o ->
                    val fx = (o.x / size.width).toDouble().coerceIn(0.0, 0.999)
                    val fy = (o.y / size.height).toDouble().coerceIn(0.0, 0.999)
                    onTap(fx to fy)
                }
            },
    ) {
        drawRect(PawColors.Sand)
        val s = min(size.width / sprite.width, size.height / sprite.height)
        drawImage(
            bitmap, IntOffset.Zero, IntSize(sprite.width, sprite.height),
            IntOffset.Zero, IntSize((sprite.width * s).roundToInt(), (sprite.height * s).roundToInt()),
            filterQuality = FilterQuality.None,
        )
        for ((fx, fy) in eyes) {
            val c = Offset((fx * size.width).toFloat(), (fy * size.height).toFloat())
            drawCircle(Color.White, radius = s * 2.2f, center = c, style = Stroke(width = s * 0.9f))
            drawCircle(marker, radius = s * 2.2f, center = c, style = Stroke(width = s * 0.5f))
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
