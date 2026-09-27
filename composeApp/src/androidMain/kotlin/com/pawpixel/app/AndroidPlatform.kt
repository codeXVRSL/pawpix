package com.pawpixel.app

import android.app.AlarmManager
import android.content.ClipData
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.glance.appwidget.updateAll
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.pawpixel.app.widget.PetWidget
import com.pawpixel.core.HOUR_MS
import com.pawpixel.core.Reminder
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PixelImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.max

class AndroidPlatform(private val context: Context) : Platform {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val files: FileStore = AndroidFileStore(File(context.filesDir, "pawpixel"))

    /** Set by [MainActivity] so the shared UI can ask for notification permission. */
    var permissionRequester: (() -> Unit)? = null

    override fun nowMs() = System.currentTimeMillis()
    override fun utcOffsetMs(atMs: Long) = TimeZone.getDefault().getOffset(atMs).toLong()

    override suspend fun decodePhoto(bytes: ByteArray, maxSide: Int): PixelImage? = withContext(Dispatchers.Default) {
        runCatching {
            val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder applies EXIF rotation for us.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxSide) {
                        val f = maxSide.toFloat() / longest
                        decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            bitmap.toPixelImage().fitWithin(maxSide)
        }.getOrNull()
    }

    override suspend fun segmentPet(photo: PixelImage): Mask? {
        val bitmap = Bitmap.createBitmap(photo.pixels, photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
        return try {
            suspendCancellableCoroutine { cont ->
                segmenter.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        val buf = result.foregroundConfidenceMask
                        if (buf == null) { cont.resume(null); return@addOnSuccessListener }
                        buf.rewind()
                        val values = FloatArray(photo.width * photo.height)
                        buf.get(values)
                        cont.resume(Mask(photo.width, photo.height, values))
                    }
                    // Model not downloaded yet, old Play services, etc. The core fallback takes over.
                    .addOnFailureListener { cont.resume(null) }
            }
        } finally {
            segmenter.close()
        }
    }

    // ---- Reminders ----

    @Synchronized
    override fun scheduleReminders(reminders: List<Reminder>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        prefs.getStringSet("ids", emptySet())!!.forEach { id ->
            alarms.cancel(ReminderReceiver.pendingIntent(context, id.toInt(), null))
        }
        val canExact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        for (r in reminders) {
            val pi = ReminderReceiver.pendingIntent(context, r.id, r)
            if (r.exact && canExact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pi)
        }
        prefs.edit().putStringSet("ids", reminders.map { it.id.toString() }.toSet()).apply()
    }

    override fun requestNotificationPermission() {
        ensureChannel(context)
        permissionRequester?.invoke()
    }

    // ---- Widgets ----

    override fun refreshWidgets(nextChangeMs: Long?) {
        appScope.launch { runCatching { PetWidget().updateAll(context) } }
        // Wake up when the mood is due to change (or in a few hours, to extend the timeline).
        val at = nextChangeMs ?: (nowMs() + 6 * HOUR_MS)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, WidgetTickReceiver.pendingIntent(context))
    }

    // ---- Sharing ----

    override fun shareFile(bytes: ByteArray, fileName: String, mimeType: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri) // lets the share sheet show a preview
            putExtra(Intent.EXTRA_TEXT, "Meet my pet in pixels! Made with PawPixel")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        const val CHANNEL_ID = "care"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_care), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }
    }
}

fun Bitmap.toPixelImage(): PixelImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return PixelImage(width, height, px)
}

class AndroidFileStore(private val root: File) : FileStore {
    private fun f(path: String) = File(root, path)

    override fun readText(path: String): String? = f(path).takeIf { it.isFile }?.readText()
    override fun readBytes(path: String): ByteArray? = f(path).takeIf { it.isFile }?.readBytes()
    override fun writeText(path: String, text: String) = writeBytes(path, text.encodeToByteArray())

    override fun writeBytes(path: String, bytes: ByteArray) {
        val target = f(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    override fun delete(path: String) { f(path).deleteRecursively() }
}
