package com.pawpixel.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.pawpixel.core.Reminder
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PixelImage

/**
 * Files under the app's shared folder. On iOS this is the App Group container, so the widget
 * extension reads the same `widget.json` and sprite PNGs. Paths are relative, e.g. "sprites/x/happy.png".
 */
interface FileStore {
    fun readText(path: String): String?
    /** Atomic: writes a temp file then renames, so a widget never reads half a file. */
    fun writeText(path: String, text: String)
    fun readBytes(path: String): ByteArray?
    fun writeBytes(path: String, bytes: ByteArray)
    /** Deletes a file or a folder recursively. Missing paths are ignored. */
    fun delete(path: String)
}

/** Everything the shared app needs from Android or iOS. */
interface Platform {
    val files: FileStore
    fun nowMs(): Long
    /** Offset from UTC for the device's time zone at [atMs] (DST-aware). */
    fun utcOffsetMs(atMs: Long): Long

    /** Decodes a picked photo (applying EXIF rotation), scaled so the longest side is at most [maxSide]. */
    suspend fun decodePhoto(bytes: ByteArray, maxSide: Int): PixelImage?
    /** Native pet cut-out (ML Kit / Vision). Null means "not available", and the core fallback is used. */
    suspend fun segmentPet(photo: PixelImage): Mask?

    /** Replaces all scheduled reminders with [reminders]. */
    fun scheduleReminders(reminders: List<Reminder>)
    fun requestNotificationPermission()
    /** Reload widgets now, and again at [nextChangeMs] when the mood is due to change. */
    fun refreshWidgets(nextChangeMs: Long?)
    /** Opens the system share sheet for an image (PNG or GIF). */
    fun shareFile(bytes: ByteArray, fileName: String, mimeType: String)
    fun openUrl(url: String)
}

expect fun PixelImage.toImageBitmap(): ImageBitmap

/** Returns a launcher that opens the system photo picker and reports the chosen image's bytes. */
@Composable
expect fun rememberPhotoPicker(onResult: (ByteArray?) -> Unit): () -> Unit
