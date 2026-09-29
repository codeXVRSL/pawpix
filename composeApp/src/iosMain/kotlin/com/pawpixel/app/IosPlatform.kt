package com.pawpixel.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import com.pawpixel.app.ui.App
import com.pawpixel.core.Json
import com.pawpixel.core.Reminder
import com.pawpixel.map.Http
import com.pawpixel.map.HttpResponse
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Png
import com.pawpixel.sprite.RawImage
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.Foundation.*  // Foundation categories (e.g. NSURLSession's dataTaskWithRequest) are extensions
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTimeZone
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.UIKit.UIViewController
import platform.posix.memcpy
import kotlin.coroutines.resume

/** Result callback for Swift. A plain interface avoids Kotlin-lambda/KotlinUnit bridging questions. */
interface DataCallback {
    fun onResult(data: NSData?)
}

/**
 * Implemented in Swift (iosApp/iosApp/IosHost.swift). Apple-framework work (PhotosUI, Vision,
 * UserNotifications, WidgetKit, share sheet) lives there, where Apple's APIs are native.
 */
interface IosHost {
    /** Absolute path of the App Group folder shared with the widget extension. */
    fun sharedContainerPath(): String
    fun pickPhoto(completion: DataCallback)
    /** Document picker for restoring a backup (a .json file from Files, iCloud Drive, Google Drive...). */
    fun pickFile(completion: DataCallback)
    /** Decodes and orientation-corrects a photo; returns [RawImage] bytes, longest side <= maxSide. */
    fun decodePhoto(data: NSData, maxSide: Int): NSData?
    /** Takes [RawImage] bytes, returns JPEG bytes (no EXIF), or null. */
    fun encodeJpeg(rawImage: NSData, quality: Double): NSData?
    /** Takes [RawImage] bytes, returns a Float32 little-endian mask (width*height), or null if Vision can't. */
    fun segmentPet(rawImage: NSData, completion: DataCallback)
    /** JSON array of {id, taskId, at (epoch seconds), title, body}. Replaces all pending reminders. */
    fun scheduleReminders(json: String)
    fun requestNotificationPermission()
    fun reloadWidgets()
    fun shareFile(data: NSData, fileName: String)
    fun shareText(text: String)
    fun openUrl(url: String)
    /** Approximate location (asks permission; reduced accuracy is fine). */
    fun approximateLocation(completion: LocationCallback)
    /** Sign in with Apple; the request carries [hashedNonce]. Token null + error null = cancelled. */
    fun signInWithApple(hashedNonce: String, completion: TokenCallback)
}

/** Swift's notification completion handler, called when a notification's Done is saved. */
interface NotificationDone {
    fun finished()
}

interface LocationCallback {
    fun onResult(found: Boolean, lat: Double, lng: Double)
}

interface TokenCallback {
    fun onResult(token: String?, error: String?)
}

/** Entry points for Swift. */
object IosGraph {
    private lateinit var host: IosHost
    val repo: PawRepository by lazy { PawRepository(IosPlatform(host)) }

    fun start(host: IosHost) { this.host = host }

    /** Call when the app comes to the foreground: applies widget taps and refreshes everything. */
    fun onForeground() { MainScope().launch { repo.ingestWidgetTaps(); repo.publish(); repo.family.requestSync() } }

    /** "Done" tapped on a notification. */
    /** "Done" in the owner's language, for the notification button (loads the app's settings first). */
    fun doneLabel(): String { repo.state; return com.pawpixel.i18n.tr("Done") }

    /**
     * Done on a notification: [refs] is "task@slot" pairs (several for a bundled reminder). Calls
     * [done] once it's saved and sent to the household (or that timed out), so iOS keeps the app awake until then.
     */
    fun completeTask(refs: String, done: NotificationDone) {
        MainScope().launch {
            try { repo.completeInBackground { completeFromReminder(com.pawpixel.core.ReminderRef.decodeAll(refs)) } } finally { done.finished() }
        }
    }

    internal fun host() = host
}

// Screens pad themselves above the keyboard (imePadding), so don't also pan the whole view.
fun MainViewController(): UIViewController =
    ComposeUIViewController(configure = { onFocusBehavior = OnFocusBehavior.DoNothing }) { App(IosGraph.repo) }

class IosPlatform(private val host: IosHost) : Platform {
    override val files: FileStore = IosFileStore(host.sharedContainerPath())
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    override val isDebugBuild: Boolean = kotlin.native.Platform.isDebugBinary

    override fun systemLanguage(): String = (NSLocale.preferredLanguages.firstOrNull() as? String) ?: "en"
    override fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    override fun utcOffsetMs(atMs: Long): Long =
        NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(atMs / 1000.0)) * 1000L

    // Decoding a full-size photo is slow; keep it off the main thread.
    override suspend fun decodePhoto(bytes: ByteArray, maxSide: Int): PixelImage? = withContext(Dispatchers.Default) {
        host.decodePhoto(bytes.toNSData(), maxSide)?.toByteArray()?.let(RawImage::decode)
    }

    override suspend fun encodeJpeg(image: PixelImage, quality: Int): ByteArray? = withContext(Dispatchers.Default) {
        host.encodeJpeg(RawImage.encode(image).toNSData(), quality / 100.0)?.toByteArray()
    }

    override suspend fun segmentPet(photo: PixelImage): Mask? = suspendCancellableCoroutine { cont ->
        host.segmentPet(RawImage.encode(photo).toNSData(), callback { data ->
            val bytes = data?.toByteArray()
            val n = photo.width * photo.height
            val mask = if (bytes == null || bytes.size != n * 4) null else {
                val values = FloatArray(n) { i ->
                    val o = i * 4
                    Float.fromBits(
                        (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8) or
                            ((bytes[o + 2].toInt() and 0xff) shl 16) or ((bytes[o + 3].toInt() and 0xff) shl 24),
                    )
                }
                Mask(photo.width, photo.height, values)
            }
            if (cont.isActive) cont.resume(mask)
        })
    }

    override fun scheduleReminders(reminders: List<Reminder>) {
        val json = Json.arr(reminders.map {
            Json.obj("id" to it.id.toString(), "taskId" to it.taskId, "at" to it.atMs / 1000, "title" to it.title, "body" to it.body, "quickDone" to it.quickDone,
                "taskIds" to com.pawpixel.core.ReminderRef.encodeAll(it.refs))
        }).stringify()
        host.scheduleReminders(json)
    }

    override fun requestNotificationPermission() = host.requestNotificationPermission()
    // WidgetKit timelines already contain future mood changes, so only a reload is needed.
    override fun refreshWidgets(nextChangeMs: Long?) = host.reloadWidgets()
    override fun shareFile(bytes: ByteArray, fileName: String, mimeType: String) = host.shareFile(bytes.toNSData(), fileName)
    override fun shareText(text: String) = host.shareText(text)
    override fun openUrl(url: String) = host.openUrl(url)
    // Only the format argument is bridged to NSString: passing a Kotlin String through NSLog's
    // variadic "%@" crashes (EXC_BAD_ACCESS). So the message is the format, with % escaped.
    override fun log(message: String) = platform.Foundation.NSLog("PawPixel: " + message.replace("%", "%%"))

    // ---- Pet map ----

    override val http = Http { r ->
        suspendCancellableCoroutine { cont ->
            val url = platform.Foundation.NSURL.URLWithString(r.url)
            if (url == null) { cont.resumeWith(Result.failure(IllegalArgumentException("bad url"))); return@suspendCancellableCoroutine }
            val req = NSMutableURLRequest.requestWithURL(url)
            req.setHTTPMethod(r.method)
            req.setTimeoutInterval(20.0)
            r.headers.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
            r.body?.let { req.setHTTPBody(it.encodeToByteArray().toNSData()) }
            val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data: NSData?, response: NSURLResponse?, error: NSError? ->
                if (error != null || response == null) {
                    cont.resumeWith(Result.failure(IllegalStateException(error?.localizedDescription ?: "no response")))
                } else {
                    val code = (response as platform.Foundation.NSHTTPURLResponse).statusCode.toInt()
                    cont.resumeWith(Result.success(HttpResponse(code, data?.toByteArray()?.decodeToString() ?: "")))
                }
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    override suspend fun fetchBytes(url: String): ByteArray? = suspendCancellableCoroutine { cont ->
        val u = platform.Foundation.NSURL.URLWithString(url)
        if (u == null) { cont.resume(null); return@suspendCancellableCoroutine }
        val req = NSMutableURLRequest.requestWithURL(u)
        req.setValue("PawPixel/1.0 (iOS)", forHTTPHeaderField = "User-Agent")
        val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data: NSData?, response: NSURLResponse?, _: NSError? ->
            val ok = (response as? platform.Foundation.NSHTTPURLResponse)?.statusCode?.toInt() == 200
            cont.resume(if (ok) data?.toByteArray() else null)
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    override suspend fun approximateLocation(): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        host.approximateLocation(object : LocationCallback {
            override fun onResult(found: Boolean, lat: Double, lng: Double) {
                if (cont.isActive) cont.resume(if (found) lat to lng else null)
            }
        })
    }

    override val mapSignInLabel = "Sign in with Apple"

    override suspend fun signInForMap(hashedNonce: String, googleWebClientId: String): MapIdentity? =
        suspendCancellableCoroutine { cont ->
            host.signInWithApple(hashedNonce, object : TokenCallback {
                override fun onResult(token: String?, error: String?) {
                    if (!cont.isActive) return
                    when {
                        token != null -> cont.resume(MapIdentity("apple", token))
                        error != null -> cont.resumeWith(Result.failure(IllegalStateException(error)))
                        else -> cont.resume(null)
                    }
                }
            })
        }
}

class IosFileStore(private val root: String) : FileStore {
    private val fm = NSFileManager.defaultManager
    private fun p(path: String) = "$root/$path"

    override fun readBytes(path: String): ByteArray? = NSData.dataWithContentsOfFile(p(path))?.toByteArray()
    override fun readText(path: String): String? = readBytes(path)?.decodeToString()
    override fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    @OptIn(ExperimentalForeignApi::class)
    override fun writeBytes(path: String, bytes: ByteArray): Boolean {
        val full = p(path)
        val dir = full.substringBeforeLast('/')
        fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return bytes.toNSData().writeToFile(full, atomically = true)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun delete(path: String) {
        fm.removeItemAtPath(p(path), error = null)
    }

    override fun exists(path: String): Boolean = fm.fileExistsAtPath(p(path))

    @OptIn(ExperimentalForeignApi::class)
    override fun rename(from: String, to: String): Boolean =
        !fm.fileExistsAtPath(p(to)) && fm.moveItemAtPath(p(from), toPath = p(to), error = null)
}

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun PixelImage.toImageBitmap(): ImageBitmap = Image.makeFromEncoded(Png.encode(this)).toComposeImageBitmap()

@Composable
actual fun rememberPhotoPicker(onResult: (ByteArray?) -> Unit): () -> Unit = {
    IosGraph.host().pickPhoto(callback { data -> onResult(data?.toByteArray()) })
}

@Composable
actual fun rememberFilePicker(onResult: (ByteArray?) -> Unit): () -> Unit = {
    IosGraph.host().pickFile(callback { data -> onResult(data?.toByteArray()) })
}

private fun callback(block: (NSData?) -> Unit) = object : DataCallback {
    override fun onResult(data: NSData?) = block(data)
}

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
