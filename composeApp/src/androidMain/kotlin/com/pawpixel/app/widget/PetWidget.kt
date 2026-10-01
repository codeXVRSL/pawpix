package com.pawpixel.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.widget.RemoteViews
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pawpixel.app.MainActivity
import com.pawpixel.app.PawPixelApplication
import com.pawpixel.app.R
import com.pawpixel.core.Json
import com.pawpixel.core.Sky
import com.pawpixel.core.WidgetFace
import com.pawpixel.core.WidgetSnapshot
import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Home-screen pet. Reads only `widget.json` + pre-rendered PNGs (see [WidgetSnapshot]), so it's cheap
 * and stays right for two days even if the app never runs. Each widget shows the pet chosen for it
 * ([PetPickerActivity]), under the sky of the hour (or the sky the owner picked), and it is alive:
 * the mood's idle animation plays on the home screen itself (see [PetSprite]).
 *
 * Layouts: a strip one cell high (pet, name and Done when wide enough), a square (the pet big, its
 * mood, and Done when tall enough), and wide (pet beside name, mood, next care and Done). The
 * system's corner radius on Android 12+, 48dp touch targets.
 */
class PetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(SIZES)
    override val previewSizeMode = SizeMode.Responsive(SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = PawPixelApplication.repo(context)
        // Read before the first frame, so the widget never flashes the "make your pet" state.
        val prefs = runCatching { getAppWidgetState(context, PreferencesGlanceStateDefinition, id) }.getOrNull()
        val first = withContext(Dispatchers.IO) { Shown.load(context, prefs?.get(PET), System.currentTimeMillis()) }
        provideContent {
            val choice = currentState(PET)
            val theme = Theme.of(currentState(THEME))
            val showName = currentState(SHOW_NAME) ?: true
            // Re-read widget.json whenever the app publishes (e.g. after a Done tap on this widget).
            val revision by repo.widgetRevision.collectAsState()
            val shown by produceState(first, revision, choice) {
                value = withContext(Dispatchers.IO) { Shown.load(context, choice, System.currentTimeMillis()) }
            }
            WidgetTheme { Content(shown ?: Shown.EMPTY, theme, showName) }
        }
    }

    /** The widget picker (Android 15+) shows the owner's own first pet, or a sample one. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val shown = withContext(Dispatchers.IO) { Shown.load(context, null, System.currentTimeMillis()) }?.takeIf { it.face != null }
            ?: Shown.sample(context)
        provideContent { WidgetTheme { Content(shown, Theme.AUTO, showName = true) } }
    }

    /** The widget's look: the sky of the hour, a fixed sky, or the app's plain paper. */
    enum class Theme(val key: String) {
        AUTO("auto"), DAY("day"), NIGHT("night"), PAPER("paper");

        companion object {
            fun of(key: String?): Theme = entries.firstOrNull { it.key == key } ?: AUTO
        }
    }

    companion object {
        /** This widget's pet: an id, [WidgetSnapshot.MOST_IN_NEED], or unset for the first pet. */
        val PET = stringPreferencesKey("pet")
        /** A [Theme.key]; unset = [Theme.AUTO]. */
        val THEME = stringPreferencesKey("theme")
        /** Whether the pet's name is written on the widget (default yes). */
        val SHOW_NAME = booleanPreferencesKey("showName")

        val SIZES = setOf(
            DpSize(110.dp, 50.dp), DpSize(250.dp, 50.dp), // one cell high
            DpSize(110.dp, 110.dp), DpSize(110.dp, 180.dp), // squares and tall
            DpSize(180.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp), // wide
        )

        /**
         * Publishes the picker preview with the owner's pet when it changes (Android 15+). The system
         * allows only a couple of these an hour, so it's sent only when the first pet's look changes.
         */
        suspend fun updatePreview(context: Context) {
            if (Build.VERSION.SDK_INT < 35) return
            val pet = PawPixelApplication.repo(context).state.value.pets.firstOrNull()
            val key = "${pet?.id}:${pet?.spriteVersion}:${pet?.accessory}:${pet?.name}:${com.pawpixel.i18n.I18n.lang}"
            val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
            if (prefs.getString("previewKey", null) == key) return
            val result = runCatching { GlanceAppWidgetManager(context).setWidgetPreviews(PetWidgetReceiver::class) }.getOrNull()
            if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) prefs.edit().putString("previewKey", key).apply()
        }
    }
}

/** Material You colours where the phone has them (Android 12+), PawPixel's own warm paper otherwise. */
@Composable
private fun WidgetTheme(content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT >= 31) GlanceTheme(content = content) else GlanceTheme(colors = PAW_COLORS, content = content)
}

/** The app's palette (see the shared Theme.kt): warm cream and coral by day, deep plum by night. */
private val PAW_COLORS = ColorProviders(
    light = lightColorScheme(
        primary = Color(0xFFD9364F), onPrimary = Color.White,
        surface = Color(0xFFFFF7EC), onSurface = Color(0xFF2B2135),
        surfaceVariant = Color(0xFFFFE9CF), onSurfaceVariant = Color(0xFF6A5C78),
        background = Color(0xFFFFF7EC), onBackground = Color(0xFF2B2135),
    ),
    dark = darkColorScheme(
        primary = Color(0xFFFF8093), onPrimary = Color(0xFF4A0A18),
        surface = Color(0xFF1F1930), onSurface = Color(0xFFF7EEE4),
        surfaceVariant = Color(0xFF2B2340), onSurfaceVariant = Color(0xFFC9BDD6),
        background = Color(0xFF1F1930), onBackground = Color(0xFFF7EEE4),
    ),
)

/** The colours one widget draws with: its sky (null for paper) and the ink that reads on it. */
private class Look(val sky: Sky.Phase?, val ink: ColorProvider, val soft: ColorProvider, val pill: GlanceModifier)

@Composable
private fun lookFor(theme: PetWidget.Theme, shown: Shown, size: DpSize): Look {
    val phase = when (theme) {
        PetWidget.Theme.PAPER -> null
        PetWidget.Theme.DAY -> Sky.Phase.DAY
        PetWidget.Theme.NIGHT -> Sky.Phase.NIGHT
        PetWidget.Theme.AUTO -> {
            val c = Calendar.getInstance()
            Sky.phase(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), shown.nightStart, shown.nightEnd)
        }
    }
    if (phase == null) {
        val pill = if (Build.VERSION.SDK_INT >= 31) GlanceModifier.background(GlanceTheme.colors.surfaceVariant).cornerRadius(14.dp)
        else GlanceModifier.background(ImageProvider(R.drawable.widget_pill))
        return Look(null, GlanceTheme.colors.onSurface, GlanceTheme.colors.onSurfaceVariant, pill)
    }
    val night = phase.dark
    val ink = ColorProvider(if (night) Color(0xFFF7EEE4) else Color(0xFF2B2135))
    val soft = ColorProvider(if (night) Color(0xFFC9BDD6) else Color(0xFF5A4C68))
    val pill = if (Build.VERSION.SDK_INT >= 31) GlanceModifier.background(ColorProvider(if (night) Color(0x59000000) else Color(0xBFFFFFFF))).cornerRadius(14.dp)
    else GlanceModifier.background(ImageProvider(if (night) R.drawable.widget_pill_night else R.drawable.widget_pill))
    return Look(phase, ink, soft, pill)
}

@Composable
private fun Content(shown: Shown, theme: PetWidget.Theme, showName: Boolean) {
    val size = LocalSize.current
    val context = LocalContext.current
    val face = shown.face
    val look = lookFor(theme, shown, size)
    val open = Intent(context, MainActivity::class.java)
        .setData(Uri.parse(if (face != null) "pawpixel://pet/${face.petId}" else "pawpixel://new"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    // Fills its bounds with the sky (or paper) and the system's corner radius (Android 12+), or a rounded card before.
    val frame = GlanceModifier.fillMaxSize().appWidgetBackground().let {
        val sky = look.sky
        when {
            sky != null -> it.background(ImageProvider(WidgetSky.bitmap(sky, size.width.value / size.height.value)), contentScale = ContentScale.Crop)
            Build.VERSION.SDK_INT >= 31 -> it.background(GlanceTheme.colors.widgetBackground)
            else -> it.background(ImageProvider(R.drawable.widget_background))
        }
    }.let { if (Build.VERSION.SDK_INT >= 31) it.cornerRadius(android.R.dimen.system_app_widget_background_radius) else it }
        .clickable(actionStartActivity(open))
    val ink = look.ink
    val soft = look.soft

    if (face == null) {
        // Nothing yet: an invitation, not an error.
        Column(frame.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            if (size.height >= 100.dp) {
                Image(ImageProvider(R.drawable.widget_sample_pet), contentDescription = null, modifier = GlanceModifier.defaultWeight().fillMaxWidth())
            }
            Text(tr("Make your pixel pet"), maxLines = 2, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        }
        return
    }
    val sprite: @Composable (GlanceModifier) -> Unit = { modifier -> PetSprite(shown, modifier) }
    val next = shown.nextLine(context)
    val title = if (showName) face.name else face.caption

    when {
        // One cell high (4x1, 2x1): pet, name and mood; Done when there's room beside them.
        size.height < 100.dp -> Row(frame.padding(horizontal = if (size.width < 180.dp) 6.dp else 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            sprite(GlanceModifier.width(if (size.width < 180.dp) size.height - 8.dp else size.height * 1.2f).fillMaxHeight())
            Column(GlanceModifier.defaultWeight().padding(start = 4.dp)) {
                Text(title, maxLines = 1, style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                // A two-cell strip only has room for one line; the picture shows the mood.
                if (size.width >= 180.dp && showName) Text(face.caption, maxLines = 1, style = TextStyle(color = soft, fontSize = 12.sp))
            }
            if (face.actionTaskId != null && size.width >= 250.dp) DoneButton(face)
        }
        // Wide (3x2, 4x2 and up): pet beside its name, mood, what's next, and Done.
        size.width >= 180.dp -> Row(frame.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            sprite(GlanceModifier.width(minOf(size.height - 24.dp, size.width * 0.42f)).fillMaxHeight())
            Column(GlanceModifier.defaultWeight().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showName) Text(face.name, maxLines = 1, style = TextStyle(color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                Box(look.pill.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(face.caption, maxLines = 2, style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                }
                if (face.actionTaskId != null) {
                    Spacer(GlanceModifier.height(8.dp))
                    DoneButton(face)
                } else if (next != null) {
                    Spacer(GlanceModifier.height(6.dp))
                    Text(next, maxLines = 2, style = TextStyle(color = soft, fontSize = 12.sp))
                }
            }
        }
        // Square or tall (2x2, 2x3): the pet big, its mood, and Done when tall enough.
        else -> Column(frame.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            sprite(GlanceModifier.defaultWeight().fillMaxWidth())
            Box(look.pill.padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(
                    if (size.width >= 150.dp || !showName) face.caption else face.name, maxLines = 1,
                    style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                )
            }
            if (face.actionTaskId != null && size.height >= 170.dp) {
                Spacer(GlanceModifier.height(6.dp))
                DoneButton(face, GlanceModifier.fillMaxWidth())
            } else if (next != null && size.height >= 170.dp) {
                Spacer(GlanceModifier.height(4.dp))
                Text(next, maxLines = 2, style = TextStyle(color = soft, fontSize = 11.sp, textAlign = TextAlign.Center))
            }
        }
    }
}

/**
 * The pet, alive: the mood's idle animation (breathing, a blink, a glance, a hop; the icons bobbing
 * along) plays on the home screen itself. Launchers only draw what RemoteViews can describe, and
 * animated images don't play there; a ViewFlipper does, flipping through its frames on its own.
 * So the widget hands it the frames the app pre-rendered, in order, and the interval. Falls back to
 * the still pose when the frames aren't there yet.
 */
@Composable
private fun PetSprite(shown: Shown, modifier: GlanceModifier) {
    val context = LocalContext.current
    val face = shown.face ?: return
    val description = "${face.name}: ${face.caption}"
    if (shown.frames.size < 2) {
        val image = shown.bitmap?.let { ImageProvider(it) } ?: ImageProvider(R.drawable.widget_sample_pet)
        Image(image, contentDescription = description, modifier = modifier)
        return
    }
    val flipper = RemoteViews(context.packageName, R.layout.widget_pet_flipper)
    flipper.setInt(R.id.pet_flipper, "setFlipInterval", shown.frameMs)
    flipper.setContentDescription(R.id.pet_flipper, description)
    flipper.removeAllViews(R.id.pet_flipper)
    for (bitmap in shown.frames) {
        // Each step is its own child; a repeated picture is the same Bitmap, so it travels once.
        val frame = RemoteViews(context.packageName, R.layout.widget_pet_frame)
        frame.setImageViewBitmap(R.id.pet_frame, bitmap)
        flipper.addView(R.id.pet_flipper, frame)
    }
    AndroidRemoteViews(flipper, modifier)
}

/**
 * "🍖 Done": one tap logs the care that's due; the widget redraws with the happy pet as soon as it's
 * saved. Drawn by hand rather than Glance's Button, which some launchers show in capitals. 48dp high.
 */
@Composable
private fun DoneButton(face: WidgetFace, modifier: GlanceModifier = GlanceModifier) {
    val label = "${face.actionEmoji ?: ""} ${tr("Done")}".trim()
    val shape = if (Build.VERSION.SDK_INT >= 31) GlanceModifier.background(GlanceTheme.colors.primary).cornerRadius(24.dp)
    else GlanceModifier.background(ImageProvider(R.drawable.widget_button))
    Box(
        modifier.height(48.dp).then(shape).padding(horizontal = 14.dp)
            .clickable(actionRunCallback<DoneAction>(actionParametersOf(DoneAction.TASK to face.actionTaskId!!)))
            .semantics { contentDescription = "${face.actionTitle ?: ""}: ${tr("Done")}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

class PetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PetWidget()
}

/** One-tap "Done" from the home screen. */
class DoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val taskId = parameters[TASK] ?: return
        // Saves and refreshes every widget, then tells the household.
        PawPixelApplication.repo(context).completeInBackground { complete(taskId) }
    }

    companion object {
        val TASK = ActionParameters.Key<String>("taskId")
    }
}

/**
 * What a widget draws: the moment's [WidgetFace], its still pose and the frames of its idle
 * animation (in order; repeats share a Bitmap), read from the files the app wrote.
 */
internal class Shown(
    val face: WidgetFace?,
    val bitmap: Bitmap?,
    val frames: List<Bitmap> = emptyList(),
    val frameMs: Int = com.pawpixel.sprite.Poses.FRAME_MS,
    private val nextTemplate: String? = null,
    /** The owner's night (minutes of the day), for the sky. */
    val nightStart: Int = 22 * 60,
    val nightEnd: Int = 6 * 60,
) {
    /** "Next: 🍖 Feed · 5:30 PM" (in the phone's 12/24-hour style). */
    fun nextLine(context: Context): String? {
        val f = face ?: return null
        val at = f.nextAtMs ?: return null
        val time = DateFormat.getTimeFormat(context).format(java.util.Date(at))
        return (nextTemplate ?: "Next: {0} {1} · {2}").replace("{0}", f.nextEmoji ?: "").replace("{1}", f.nextTitle ?: "").replace("{2}", time)
    }

    companion object {
        val EMPTY = Shown(null, null)

        fun load(context: Context, choice: String?, now: Long): Shown? {
            val root = File(context.filesDir, "pawpixel")
            val text = File(root, WidgetSnapshot.FILE_NAME).takeIf { it.isFile }?.readText() ?: return null
            val snap = runCatching { Json.parse(text) }.getOrNull() ?: return null
            val face = WidgetSnapshot.face(snap, now, choice, utcOffsetNowMs = TimeZone.getDefault().getOffset(now).toLong())
            // Not scaled by density: the poses are pre-scaled pixel art, and the widget scales them without smoothing.
            fun decode(path: String): Bitmap? = File(root, path).takeIf { it.isFile }
                ?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inScaled = false }) }
            val bitmap = face?.sprite?.let(::decode)
            val distinct = HashMap<String, Bitmap?>()
            val frames = face?.frames.orEmpty().map { path -> distinct.getOrPut(path) { decode(path) } }
            return Shown(
                face, bitmap,
                frames = if (frames.isNotEmpty() && frames.all { it != null }) frames.map { it!! } else emptyList(),
                frameMs = snap["frameMs"].long?.toInt()?.coerceIn(100, 2000) ?: com.pawpixel.sprite.Poses.FRAME_MS,
                nextTemplate = snap["labels"]["next"].str,
                nightStart = snap["night"]["start"].long?.toInt() ?: 22 * 60,
                nightEnd = snap["night"]["end"].long?.toInt() ?: 6 * 60,
            )
        }

        /** A happy sample pet, for the widget picker before the owner has made one. */
        fun sample(context: Context): Shown {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.widget_sample_pet)
            val face = WidgetFace(
                petId = "", name = "Mochi", mood = com.pawpixel.core.Mood.HAPPY, caption = tr("{0} is happy!", "Mochi"),
                sprite = null, actionTaskId = null, actionEmoji = null, actionTitle = null,
                nextEmoji = "🍖", nextTitle = trName("Feed"), nextAtMs = null,
            )
            return Shown(face, bitmap, nextTemplate = tr("Next: {0} {1} · {2}"))
        }
    }
}
