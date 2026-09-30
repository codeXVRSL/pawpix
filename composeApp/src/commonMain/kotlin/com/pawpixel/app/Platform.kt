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
    /** Debug/test builds: shows testing switches (like the pretend store) that release builds hide. */
    val isDebugBuild: Boolean
    /** The app store's in-app purchases (Google Play Billing / StoreKit 2), for PawPixel Pro. */
    val store: Store get() = Store.None
    /** The phone's language code ("en", "fil", "tl"...), for following it by default. */
    fun systemLanguage(): String = "en"
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

/**
 * PawPixel Pro through the phone's app store. The store is the source of truth; the app keeps a copy
 * (see [com.pawpixel.core.ProEntitlement]) so Pro works offline. Prices always come from here.
 */
interface Store {
    /** Pro's price as the store shows it to this owner (like "₱249.00"). Throws [StoreException]. */
    suspend fun price(): String

    /** Opens the store's purchase sheet and waits for the answer. Later changes arrive through [listen]. */
    suspend fun buy(): BuyResult

    /**
     * What this account owns (restore). [sync]: the owner tapped Restore, so the store may check
     * with its server and ask them to sign in (iOS); otherwise it answers quietly. Throws [StoreException].
     */
    suspend fun owned(sync: Boolean = false): com.pawpixel.core.Ownership

    /** Changes that happen outside a purchase: a cash payment that arrived, a refund. Set once. */
    fun listen(onEvent: (com.pawpixel.core.StoreEvent) -> Unit)

    /** Test builds only: a pretend store to walk through buying without paying. Null in release builds. */
    val test: TestStore? get() = null

    /** No store on this device (or not wired up): everything says it's unavailable. */
    object None : Store {
        override suspend fun price(): String = throw StoreException(StoreProblem.UNAVAILABLE)
        override suspend fun buy(): BuyResult = BuyResult.Failed(StoreProblem.UNAVAILABLE)
        override suspend fun owned(sync: Boolean): com.pawpixel.core.Ownership = throw StoreException(StoreProblem.UNAVAILABLE)
        override fun listen(onEvent: (com.pawpixel.core.StoreEvent) -> Unit) {}
    }
}

/** How a purchase went. */
sealed interface BuyResult {
    /** Bought ([com.pawpixel.core.Ownership.OWNED]), or waiting for the payment ([com.pawpixel.core.Ownership.PENDING]). */
    data class Done(val ownership: com.pawpixel.core.Ownership) : BuyResult
    /** The owner closed the purchase sheet. Nothing was charged. */
    data object Cancelled : BuyResult
    data class Failed(val problem: StoreProblem) : BuyResult
}

enum class StoreProblem {
    /** No connection. */
    OFFLINE,
    /** No store on this phone, purchases turned off, or Pro isn't set up in the store yet. */
    UNAVAILABLE,
    /** Anything else. */
    ERROR,
}

class StoreException(val problem: StoreProblem) : Exception("store: $problem")

/** Test builds: controls for the pretend store (Settings → the test build's switches, and the end-to-end test). */
interface TestStore {
    /** Use the pretend store instead of the real one. */
    var enabled: Boolean
    /** The next purchase is paid later, like cash at 7-Eleven. */
    var payLater: Boolean
    /** The pretend cash payment arrives. */
    fun paymentArrives()
    /** The pretend purchase is refunded. */
    fun refund()
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
