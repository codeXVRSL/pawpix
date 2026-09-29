package com.pawpixel.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pawpixel.app.decodeImage
import com.pawpixel.app.toImageBitmap
import com.pawpixel.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a health photo comes from: its file (re-read when [revision] changes). */
class HealthPhoto(val key: String, val revision: Long, val read: () -> ByteArray?)

/** A small square preview of a health photo (decoded small, off the main thread). Tap to open it. */
@Composable
fun PhotoThumb(app: AppScope, photo: HealthPhoto, description: String, size: Dp = 56.dp, onClick: (() -> Unit)?) {
    val image by produceState<ImageBitmap?>(null, photo.key, photo.revision) {
        value = withContext(Dispatchers.Default) {
            runCatching { photo.read()?.let { app.repo.platform.decodePhoto(it, THUMB_SIDE)?.toImageBitmap() } }.getOrNull()
        }
    }
    val shape = MaterialTheme.shapes.small
    val base = Modifier.size(size).clip(shape).border(2.dp, MaterialTheme.colorScheme.outline, shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
    val box = if (onClick != null) base.clickable(onClickLabel = tr("Open photo"), role = Role.Image, onClick = onClick) else base
    // Described while it's still loading too (a bare "📷" would be read out as "camera").
    Box(box.semantics(mergeDescendants = true) { contentDescription = description }, contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text("📷", modifier = Modifier.padding(4.dp).clearAndSetSemantics {})
    }
}

/** A health photo full screen, readable: a vaccination card's small print is the point of keeping it. */
@Composable
fun PhotoViewer(photo: HealthPhoto, title: String, description: String, onClose: () -> Unit, onDelete: (() -> Unit)?) {
    val image by produceState<Result<ImageBitmap?>?>(null, photo.key, photo.revision) {
        value = withContext(Dispatchers.Default) { runCatching { photo.read()?.let { decodeImage(it) } } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Delete photo"), color = Color(0xFFFF8A9A)) }
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
            Text(
                tr("Kept only on this phone (and in your backup files)."), color = Color(0xFFCCCCCC),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private const val THUMB_SIDE = 192
