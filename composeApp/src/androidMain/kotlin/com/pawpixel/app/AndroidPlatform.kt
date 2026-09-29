package com.pawpixel.app

import android.app.AlarmManager
import android.content.ClipData
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.pawpixel.core.ReminderDelivery
import com.pawpixel.i18n.tr
import com.pawpixel.map.Http
import com.pawpixel.map.HttpResponse
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
    override val isDebugBuild: Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

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

    override suspend fun encodeJpeg(image: PixelImage, quality: Int): ByteArray? = withContext(Dispatchers.Default) {
        runCatching {
            val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
            java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
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
        ensureChannels(context) // their names follow the app's language
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = reminderPrefs(context)
        prefs.getStringSet(KEY_IDS, emptySet())!!.forEach { id ->
            alarms.cancel(ReminderReceiver.pendingIntent(context, id.toInt(), null))
        }
        // Before replacing the list: did any of the last one never go off? (Phones that stop apps in the background.)
        val fired = prefs.getStringSet(KEY_FIRED, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        val lost = ReminderDelivery.lost(ReminderDelivery.decode(prefs.getString(KEY_SCHEDULED, null)), fired, nowMs())
        if (lost > 0) log("$lost reminder(s) were never delivered")
        // Android 12+ needs the owner's permission for exact alarms (off by default from Android 14).
        // Without it, "exact time" reminders still come, just possibly a few minutes late.
        val canExact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        for (r in reminders) {
            val pi = ReminderReceiver.pendingIntent(context, r.id, r)
            if (r.exact && canExact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.atMs, pi)
        }
        prefs.edit()
            .putStringSet(KEY_IDS, reminders.map { it.id.toString() }.toSet())
            .putString(KEY_SCHEDULED, ReminderDelivery.encode(reminders.map { it.id to it.atMs }))
            .putStringSet(KEY_FIRED, emptySet())
            .putInt(KEY_LOST, prefs.getInt(KEY_LOST, 0) + lost)
            .apply()
    }

    override fun requestNotificationPermission() {
        ensureChannels(context)
        permissionRequester?.invoke()
    }

    override fun backgroundTip(): BackgroundTip? {
        val prefs = reminderPrefs(context)
        if (prefs.getBoolean(KEY_TIP_DISMISSED, false) || prefs.getInt(KEY_LOST, 0) < ReminderDelivery.TIP_AFTER) return null
        val maker = PhoneMaker.of(Build.MANUFACTURER)
        val make = maker.label ?: Build.MANUFACTURER.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?.replaceFirstChar { it.uppercase() }
        return BackgroundTip(make, maker.steps())
    }

    override fun openBackgroundSettings() = PhoneMaker.of(Build.MANUFACTURER).openSettings(context)

    override fun dismissBackgroundTip() {
        reminderPrefs(context).edit().putBoolean(KEY_TIP_DISMISSED, true).apply()
    }

    // ---- Widgets ----

    override fun refreshWidgets(nextChangeMs: Long?) {
        appScope.launch {
            runCatching { PetWidget().updateAll(context) }
            PetWidget.updatePreview(context) // the widget picker shows your own pet (Android 15+)
        }
        // Wake up when the widget is due to change, and every few hours anyway to extend its timeline.
        // RTC (not _WAKEUP): a sleeping phone's screen is off, so it can wait until it wakes.
        val at = minOf(nextChangeMs ?: Long.MAX_VALUE, nowMs() + 6 * HOUR_MS)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, at, WidgetTickReceiver.pendingIntent(context))
    }

    override fun systemLanguage(): String = java.util.Locale.getDefault().language

    override fun widgetInstalled(): Boolean = runCatching {
        android.appwidget.AppWidgetManager.getInstance(context)
            .getAppWidgetIds(android.content.ComponentName(context, com.pawpixel.app.widget.PetWidgetReceiver::class.java)).isNotEmpty()
    }.getOrDefault(true)

    override fun pinWidget(): Boolean {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return runCatching {
            manager.requestPinAppWidget(android.content.ComponentName(context, com.pawpixel.app.widget.PetWidgetReceiver::class.java), null, null)
        }.getOrDefault(false)
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
            if (mimeType.startsWith("image/")) putExtra(Intent.EXTRA_TEXT, tr("Meet my pet in pixels! Made with PawPixel"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, tr("Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, tr("Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun log(message: String) { android.util.Log.w("PawPixel", message) }

    // ---- Pet map ----

    /** Set by [MainActivity]: asks for approximate location permission; true if granted. */
    var locationPermission: (suspend () -> Boolean)? = null
    /** The visible activity, for Google sign-in's account picker. */
    var activity: java.lang.ref.WeakReference<android.app.Activity>? = null

    override val http = Http { r ->
        withContext(Dispatchers.IO) {
            val c = (java.net.URL(r.url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = r.method
                connectTimeout = 15_000
                readTimeout = 20_000
                r.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (r.body != null) {
                    doOutput = true
                    outputStream.use { it.write(r.body!!.encodeToByteArray()) }
                }
            }
            try {
                val code = c.responseCode
                val stream = if (code in 200..399) c.inputStream else c.errorStream
                HttpResponse(code, stream?.use { it.readBytes().decodeToString() } ?: "")
            } finally {
                c.disconnect()
            }
        }
    }

    override suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("User-Agent", "PawPixel/1.0 (Android)")
            }
            try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
        }.getOrNull()
    }

    private fun hasCoarseLocation() =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun approximateLocation(): Pair<Double, Double>? {
        if (!hasCoarseLocation() && locationPermission?.invoke() != true) return null
        if (!hasCoarseLocation()) return null
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(android.location.LocationManager.FUSED_PROVIDER)
            add(android.location.LocationManager.NETWORK_PROVIDER)
            add(android.location.LocationManager.GPS_PROVIDER)
            add(android.location.LocationManager.PASSIVE_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (Build.VERSION.SDK_INT >= 30) {
            for (p in providers) {
                val fix = kotlinx.coroutines.withTimeoutOrNull(8_000) {
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        val cancel = android.os.CancellationSignal()
                        cont.invokeOnCancellation { cancel.cancel() }
                        runCatching { lm.getCurrentLocation(p, cancel, context.mainExecutor) { cont.resume(it) } }
                            .onFailure { cont.resume(null) }
                    }
                }
                if (fix != null) return fix.latitude to fix.longitude
            }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    }

    override val mapSignInLabel: String get() = tr("Sign in with Google")

    override suspend fun signInForMap(hashedNonce: String, googleWebClientId: String): MapIdentity? {
        if (googleWebClientId.isBlank()) throw IllegalStateException(tr("Google sign-in isn't set up in this build yet."))
        val act = activity?.get() ?: throw IllegalStateException(tr("Open PawPixel to sign in."))
        val option = com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(googleWebClientId)
            .setNonce(hashedNonce).build()
        val request = androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = androidx.credentials.CredentialManager.create(act).getCredential(act, request).credential
            if (credential is androidx.credentials.CustomCredential &&
                credential.type == com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                MapIdentity("google", com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                throw IllegalStateException(tr("Google sign-in returned something unexpected."))
            }
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            null
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            throw IllegalStateException(tr("Add a Google account to this phone to sign in."))
        } catch (e: androidx.credentials.exceptions.GetCredentialException) {
            throw IllegalStateException(tr("Google sign-in didn't work: {0}", e.message ?: e.type))
        }
    }

    override fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    companion object {
        /** Everyday care (feeding, walks...). */
        const val CHANNEL_ID = "care"
        /** Medicine and health care, so an owner can quiet everyday reminders but keep these. */
        const val CHANNEL_HEALTH = "health"

        /** Creates the channels, or renames them to the app's current language (Android keeps the owner's own settings). */
        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannels(listOf(
                NotificationChannel(CHANNEL_ID, tr("Care reminders"), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = tr("Feeding, walks, water and other everyday care.") },
                NotificationChannel(CHANNEL_HEALTH, tr("Medicine and health"), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = tr("Medicine doses, vaccines, deworming and vet visits.") },
            ))
        }

        private const val KEY_IDS = "ids"
        /** The scheduled reminders as "id@at", to notice any that never went off. */
        private const val KEY_SCHEDULED = "scheduled"
        /** Ids of reminders whose alarm went off since the list was last replaced. */
        private const val KEY_FIRED = "fired"
        /** Reminders never delivered, in all. */
        const val KEY_LOST = "lost"
        const val KEY_TIP_DISMISSED = "backgroundTipDismissed"

        fun reminderPrefs(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

        /** A reminder's alarm went off (whether or not it was still worth showing). */
        @Synchronized
        fun noteFired(context: Context, id: Int) {
            val prefs = reminderPrefs(context)
            prefs.edit().putStringSet(KEY_FIRED, prefs.getStringSet(KEY_FIRED, emptySet())!! + id.toString()).apply()
        }

        /** After a restart or a clock change, the old alarms are gone for reasons of their own: don't count them as lost. */
        fun forgetScheduled(context: Context) {
            reminderPrefs(context).edit().remove(KEY_SCHEDULED).apply()
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

    override fun writeBytes(path: String, bytes: ByteArray): Boolean = runCatching {
        val target = f(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        target.isFile && target.length() == bytes.size.toLong()
    }.getOrDefault(false)

    override fun delete(path: String) { f(path).deleteRecursively() }
    override fun exists(path: String): Boolean = f(path).exists()
    override fun rename(from: String, to: String): Boolean = !f(to).exists() && f(from).renameTo(f(to))
}
