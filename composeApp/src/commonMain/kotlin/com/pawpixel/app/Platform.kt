package com.pawpixel.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.pawpixel.core.Reminder
import com.pawpixel.core.Species
import com.pawpixel.map.Http
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PixelImage

/**
 * Files under the app's shared folder. On iOS this is the App Group container, so the widget
 * extension reads the same `widget.json` and sprite PNGs. Paths are relative, e.g. "sprites/x/happy.png".
 */
interface FileStore {
    fun readText(path: String): String?
    /** Atomic: writes a temp file then renames, so a widget never reads half a file. False if it failed (e.g. disk full). */
    fun writeText(path: String, text: String): Boolean
    fun readBytes(path: String): ByteArray?
    fun writeBytes(path: String, bytes: ByteArray): Boolean
    /** Deletes a file or a folder recursively. Missing paths are ignored. */
    fun delete(path: String)
    fun exists(path: String): Boolean
    /** Moves a file or folder; [to] must not exist. False if it failed. */
    fun rename(from: String, to: String): Boolean
}

/** Everything the shared app needs from Android or iOS. */
interface Platform {
    val files: FileStore
    /** Debug/test builds: shows testing switches (like the Pro beta unlock) that release builds hide. */
    val isDebugBuild: Boolean
    /** The phone's language code ("en", "fil", "tl"...), for following it by default. */
    fun systemLanguage(): String = "en"
    /** The phone's country code ("PH", "US"...), for country-specific notes like noise nights. Blank if unknown. */
    fun systemCountry(): String = ""
    fun nowMs(): Long
    /** Offset from UTC for the device's time zone at [atMs] (DST-aware). */
    fun utcOffsetMs(atMs: Long): Long

    /** Decodes a picked photo (applying EXIF rotation), scaled so the longest side is at most [maxSide]. */
    suspend fun decodePhoto(bytes: ByteArray, maxSide: Int): PixelImage?
    /** JPEG bytes for a photo (vaccination cards). Re-encoding also drops EXIF, such as GPS. Null = unsupported. */
    suspend fun encodeJpeg(image: PixelImage, quality: Int = 82): ByteArray? = null
    /** Native pet cut-out (ML Kit / Vision). Null means "not available", and the core fallback is used. */
    suspend fun segmentPet(photo: PixelImage): Mask?
    /** Cat or dog, read from the photo on the device (ML Kit / Vision). Null when it can't tell: the owner is asked. */
    suspend fun classifyPet(photo: PixelImage): Species? = null

    // ---- Walks ----

    /** Starts counting steps for a walk (asks for permission first). False when this phone can't count steps. */
    suspend fun startSteps(): Boolean = false
    /** Steps since [startSteps], or null without a counter. */
    fun stepsSoFar(): Int? = null
    fun stopSteps() {}
    /** Keeps the screen on while a walk is being timed. */
    fun keepScreenOn(on: Boolean) {}

    /** Replaces all scheduled reminders with [reminders]. */
    fun scheduleReminders(reminders: List<Reminder>)
    fun requestNotificationPermission()
    /** Whether the system lets PawPixel show notifications; null when it can't say (yet). */
    fun notificationsAllowed(): Boolean? = null
    /** Reload widgets now, and again at [nextChangeMs] when the mood is due to change. */
    fun refreshWidgets(nextChangeMs: Long?)
    /** Whether a PawPixel widget is on the home screen; null when the system can't say (iOS). */
    fun widgetInstalled(): Boolean? = null
    /** Asks the launcher to add the widget (Android launchers that support it). False if it can't. */
    fun pinWidget(): Boolean = false
    /**
     * A one-time tip when this phone seems to stop PawPixel in the background (reminders that never
     * arrived, see [com.pawpixel.core.ReminderDelivery]); null when all is well or it was dismissed.
     */
    fun backgroundTip(): BackgroundTip? = null
    /** Opens the phone's setting for running PawPixel in the background (the app's details as a fallback). */
    fun openBackgroundSettings() {}
    fun dismissBackgroundTip() {}
    /** Opens the system share sheet for a file (PNG, GIF, or a JSON backup). */
    fun shareFile(bytes: ByteArray, fileName: String, mimeType: String)
    /** Opens the share sheet for a short text (a family invite). */
    fun shareText(text: String)
    fun openUrl(url: String)
    /** Diagnostic line in the system log (logcat / Console), never shown to the user. */
    fun log(message: String) {}

    // ---- Pet map (opt-in) ----

    /** HTTP for the map server. */
    val http: Http
    /** Downloads a map tile (or null on any failure). */
    suspend fun fetchBytes(url: String): ByteArray?
    /** Unpacks a gzip file (some tile servers send tiles that way); null when the platform can't. */
    fun gunzip(bytes: ByteArray): ByteArray? = null
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

/** What to tell the owner: the phone's make ("Xiaomi"; null if it doesn't say) and where its background setting is. */
data class BackgroundTip(val brand: String?, val steps: String)

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
