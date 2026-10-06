package com.pawpixel.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pawpixel.app.decodeImage
import com.pawpixel.app.rememberPhotoPicker
import com.pawpixel.core.AlbumPhoto
import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pet's album: real photos the owner keeps with the pet, on this phone and in backups, never
 * uploaded. It outlives everything else about the pet: a remembered pet's page opens on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlbumPanel(app: AppScope, state: AppState, pet: Pet) {
    val photos = state.albumFor(pet.id)
    val revision by app.repo.cardRevision.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var captionFor by remember { mutableStateOf<AlbumPhoto?>(null) }
    var open by remember { mutableStateOf<AlbumPhoto?>(null) }
    val pick = rememberPhotoPicker { bytes ->
        if (bytes == null) return@rememberPhotoPicker
        adding = true; error = null
        app.launch {
            try {
                val added = app.repo.addAlbumPhoto(pet.id, bytes)
                if (added == null) error = tr("Couldn't read that photo. Try another one.") else captionFor = added
            } finally { adding = false }
        }
    }
    val clock = app.repo.clock

    if (pet.remembered) MemoryCard(app, pet)
    Hint(
        if (photos.isEmpty()) tr("Photos of {0} stay on this phone and in your backups. Add the first one.", pet.name)
        else tr("{0} photos, kept on this phone and in your backups. Nothing is uploaded.", photos.size),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryPill(if (adding) tr("Adding…") else tr("Add photo"), enabled = !adding, icon = PixelIcons.CAMERA) { pick() }
        if (photos.size >= AppState.MAX_ALBUM_PHOTOS_PER_PET) {
            Text(tr("The album is full: the oldest photo makes room."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (photos.isEmpty()) {
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("No photos yet"), style = MaterialTheme.typography.titleMedium)
                Text(tr("A photo from today, the day you brought {0} home, a funny face: this album is for the real {0}.", pet.name), style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = (maxWidth - 16.dp) / 3
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                photos.forEach { photo ->
                    val date = LocalClock.shortDate(clock.dayIndex(photo.atMs))
                    val description = if (photo.caption.isBlank()) tr("Photo of {0}, {1}", pet.name, date) else tr("Photo of {0}: {1}", pet.name, photo.caption)
                    Column(Modifier.width(side), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        PhotoThumb(app, HealthPhoto(photo.id, revision) { app.repo.albumPhoto(photo) }, description, size = side) { open = photo }
                        Text(
                            photo.caption.ifBlank { date }, style = MaterialTheme.typography.labelSmall,
                            color = if (photo.caption.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    captionFor?.let { photo ->
        CaptionDialog(photo.caption, onClose = { captionFor = null }) { text ->
            captionFor = null
            app.launch { app.repo.setAlbumCaption(photo.id, text) }
        }
    }
    open?.let { photo ->
        // The album may have changed under the viewer (a caption saved): show the current entry.
        val current = state.album.firstOrNull { it.id == photo.id } ?: photo
        AlbumViewer(
            app, pet, current, revision,
            onClose = { open = null },
            onCaption = { captionFor = current },
            onDelete = { open = null; app.launch { app.repo.deleteAlbumPhoto(current) } },
        )
    }
}

/** "In loving memory": the remembered pet's dates, at the top of its album. */
@Composable
private fun MemoryCard(app: AppScope, pet: Pet) {
    val day = pet.rememberedDay ?: return
    val born = pet.birthDay?.let { LocalClock.shortDate(it) }
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CandyTile(PixelIcons.STAR, Candy.Lavender)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("In loving memory of {0}", pet.name), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (born != null) "$born – ${LocalClock.shortDate(day)}" else LocalClock.shortDate(day),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CaptionDialog(initial: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Caption")) },
        text = {
            OutlinedTextField(
                text, { text = it.take(AppState.MAX_CAPTION) }, label = { Text(tr("A few words (optional)")) },
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, maxLines = 3,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
}

/** One album photo, full screen, with its caption and date; edit the caption or delete it here. */
@Composable
private fun AlbumViewer(app: AppScope, pet: Pet, photo: AlbumPhoto, revision: Long, onClose: () -> Unit, onCaption: () -> Unit, onDelete: () -> Unit) {
    val image by produceState<Result<ImageBitmap?>?>(null, photo.id, revision) {
        value = withContext(Dispatchers.Default) { runCatching { app.repo.albumPhoto(photo)?.let { decodeImage(it) } } }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val date = LocalClock.shortDate(app.repo.clock.dayIndex(photo.atMs))
    val description = if (photo.caption.isBlank()) tr("Photo of {0}, {1}", pet.name, date) else tr("Photo of {0}: {1}", pet.name, photo.caption)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pet.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(tr("Close"), color = Color.White) }
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp), contentAlignment = Alignment.Center) {
                val loaded = image
                when {
                    loaded == null -> Text(tr("Opening…"), color = Color.White)
                    loaded.getOrNull() != null ->
                        Image(loaded.getOrNull()!!, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else -> Text(tr("The photo couldn't be opened."), color = Color.White)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (photo.caption.isNotBlank()) Text(photo.caption, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Text(date, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCaption) { Text(if (photo.caption.isBlank()) tr("Add caption") else tr("Edit caption"), color = Color.White) }
                    TextButton(onClick = { confirmDelete = true }) { Text(tr("Delete photo"), color = Color(0xFFFF8A9A)) }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete this photo?")) },
            text = { Text(tr("It's removed from {0}'s album on this phone. Backups you already saved still have it.", pet.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
}

/**
 * "Remember {pet}": the pet passed away. Its reminders and needs stop and its room goes quiet;
 * the album, the care history and the weight log stay. One more dialog undoes it.
 */
@Composable
fun RememberDialog(app: AppScope, pet: Pet, onClose: () -> Unit) {
    if (pet.remembered) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(tr("Care for {0} again?", pet.name)) },
            text = { Text(tr("{0}'s reminders and needs come back as they were.", pet.name)) },
            confirmButton = { TextButton(onClick = { onClose(); app.launch { app.repo.rememberPet(pet.id, null) } }) { Text(tr("Bring back")) } },
            dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(tr("Remember {0}?", pet.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("If {0} has passed away, their page becomes a quiet room: no more reminders or needs.", pet.name))
                    Text(tr("The album, the care history and the weight log stay for as long as you want them. You can undo this any time."))
                }
            },
            confirmButton = { TextButton(onClick = { onClose(); app.launch { app.repo.rememberPet(pet.id) } }) { Text(tr("Remember {0}", pet.name)) } },
            dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
        )
    }
}
