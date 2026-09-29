package com.pawpixel.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.pawpixel.core.Reminder
import com.pawpixel.map.Http
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
    /** Debug/test builds: shows testing switches (like the Pro beta unlock) that release builds hide. */
    val isDebugBuild: Boolean
    fun nowMs(): Long
    /** Offset from UTC for the device's time zone at [atMs] (DST-aware). */
    fun utcOffsetMs(atMs: Long): Long

    /** Decodes a picked photo (applying EXIF rotation), scaled so the longest side is at most [maxSide]. */
    suspend fun decodePhoto(bytes: ByteArray, maxSide: Int): PixelImage?
    /** JPEG bytes for a photo (vaccination cards). Re-encoding also drops EXIF, such as GPS. Null = unsupported. */
    suspend fun encodeJpeg(image: PixelImage, quality: Int = 82): ByteArray? = null
    /** Native pet cut-out (ML Kit / Vision). Null means "not available", and the core fallback is used. */
    suspend fun segmentPet(photo: PixelImage): Mask?

    /** Replaces all scheduled reminders with [reminders]. */
    fun scheduleReminders(reminders: List<Reminder>)
    fun requestNotificationPermission()
    /** Reload widgets now, and again at [nextChangeMs] when the mood is due to change. */
    fun refreshWidgets(nextChangeMs: Long?)
    /** Whether a PawPixel widget is on the home screen; null when the system can't say (iOS). */
    fun widgetInstalled(): Boolean? = null
    /** Asks the launcher to add the widget (Android launchers that support it). False if it can't. */
    fun pinWidget(): Boolean = false
    /** Opens the system share sheet for a file (PNG, GIF, or a JSON backup). */
    fun shareFile(bytes: ByteArray, fileName: String, mimeType: String)
    fun openUrl(url: String)
    /** Diagnostic line in the system log (logcat / Console), never shown to the user. */
    fun log(message: String) {}

    // ---- Pet map (opt-in) ----

    /** HTTP for the map server. */
    val http: Http
    /** Downloads a map tile (or null on any failure). */
    suspend fun fetchBytes(url: String): ByteArray?
    /** Approximate location, asking permission first. Null if refused or unavailable. Snapped to a grid by the caller. */
    suspend fun approximateLocation(): Pair<Double, Double>?
    /** "Sign in with Google" (Android) / "Sign in with Apple" (iOS). */
    val mapSignInLabel: String
    /**
     * Google (Android) or Apple (iOS) sign-in. [hashedNonce] is SHA-256 of the raw nonce the server
     * checks. Returns null if the owner cancels; throws with a readable message on errors.
     */
    suspend fun signInForMap(hashedNonce: String, googleWebClientId: String): MapIdentity?
}

/** An identity token from Google or Apple, exchanged for a map session by the server. */
data class MapIdentity(val provider: String, val idToken: String)

/** Decodes a PNG/JPEG (map tiles) for drawing. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

expect fun PixelImage.toImageBitmap(): ImageBitmap

/** Returns a launcher that opens the system photo picker and reports the chosen image's bytes. */
@Composable
expect fun rememberPhotoPicker(onResult: (ByteArray?) -> Unit): () -> Unit

/** Returns a launcher that opens the system file picker (for restoring a backup) and reports the file's bytes. */
@Composable
expect fun rememberFilePicker(onResult: (ByteArray?) -> Unit): () -> Unit
