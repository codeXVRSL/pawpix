package com.pawpixel.app

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.pawpixel.sprite.PixelImage

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

actual fun PixelImage.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

@Composable
actual fun rememberPhotoPicker(onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The system photo picker needs no storage permission.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { onResult(null); return@rememberLauncherForActivityResult }
        scope.launch {
            // Cloud-backed photos can be slow to read: do it off the main thread.
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            onResult(bytes)
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
actual fun rememberFilePicker(onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The system file picker (Storage Access Framework): Drive, Downloads, etc. No permission needed.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onResult(null); return@rememberLauncherForActivityResult }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // A backup is small; refuse anything absurd rather than running out of memory.
                        val limit = com.pawpixel.core.Backup.MAX_BYTES + 1
                        val data = input.readNBytesCompat(limit)
                        if (data.size >= limit) null else data
                    }
                }.getOrNull()
            }
            onResult(bytes)
        }
    }
    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
