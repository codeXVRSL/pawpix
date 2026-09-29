package com.pawpixel.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pawpixel.app.MainActivity
import com.pawpixel.app.PawPixelApplication
import com.pawpixel.core.Json
import com.pawpixel.core.Mood
import com.pawpixel.core.WidgetSnapshot
import com.pawpixel.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Home-screen pet. Reads only `widget.json` + pre-rendered PNGs, so it's cheap and never runs the
 * mood engine. With several pets it shows whichever needs attention most.
 */
class PetWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(
        DpSize(110.dp, 60.dp), DpSize(250.dp, 60.dp), // one row high
        DpSize(110.dp, 110.dp), DpSize(180.dp, 180.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp),
    ))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = PawPixelApplication.repo(context)
        provideContent {
            // Re-read widget.json whenever the app publishes (e.g. after a Done tap on this widget).
            val revision by repo.widgetRevision.collectAsState()
            val data by produceState<WidgetData?>(null, revision) {
                value = withContext(Dispatchers.IO) { WidgetData.load(context, System.currentTimeMillis()) }
            }
            Content(data)
        }
    }

    @Composable
    private fun Content(data: WidgetData?) {
        val size = LocalSize.current
        // Warm paper in the day, a soft night version when the phone is in dark mode.
        val bg = androidx.glance.color.ColorProvider(day = Color(0xFFFFF4E0), night = Color(0xFF2A2433))
        val ink = androidx.glance.color.ColorProvider(day = Color(0xFF2B2135), night = Color(0xFFF3EAF7))
        // Wide and short (4x1): pet on the left, mood and the Done button beside it.
        if (data != null && (size.height < 100.dp || (size.width >= 240.dp && size.height < 150.dp))) {
            val short = size.height < 100.dp
            androidx.glance.layout.Row(
                GlanceModifier.fillMaxSize().background(bg).cornerRadius(18.dp).padding(8.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                data.sprite?.let {
                    Image(ImageProvider(it), contentDescription = "${data.name}: ${data.caption}",
                        modifier = GlanceModifier.width(size.height - 16.dp).fillMaxHeight())
                }
                Column(GlanceModifier.defaultWeight().padding(start = 8.dp)) {
                    Text(data.name, maxLines = 1, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    if (size.width >= 240.dp || !short) Text(data.caption, maxLines = if (short) 1 else 2, style = TextStyle(color = ink, fontSize = 12.sp))
                    // A one-row widget has room for the button only when it's wide.
                    if (data.actionTaskId != null && (!short || size.width >= 240.dp)) {
                        Spacer(GlanceModifier.height(4.dp))
                        Button(
                            text = data.actionLabel ?: tr("Done"),
                            onClick = actionRunCallback<DoneAction>(actionParametersOf(DoneAction.TASK to data.actionTaskId)),
                            colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color(0xFFE8374E)), contentColor = ColorProvider(Color.White)),
                        )
                    }
                }
            }
            return
        }
        Column(
            GlanceModifier.fillMaxSize().background(bg).cornerRadius(18.dp).padding(8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (data == null) {
                Text(
                    tr("Open PawPixel to make your pixel pet"),
                    style = TextStyle(color = ink, fontSize = 13.sp, textAlign = TextAlign.Center),
                )
                return@Column
            }
            data.sprite?.let {
                Image(ImageProvider(it), contentDescription = "${data.name}: ${data.caption}", modifier = GlanceModifier.defaultWeight().fillMaxWidth())
            }
            Text(
                if (size.width >= 170.dp) data.caption else data.name,
                maxLines = 1,
                style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
            if (data.actionTaskId != null && size.height >= 150.dp) {
                Spacer(GlanceModifier.height(4.dp))
                Button(
                    text = data.actionLabel ?: tr("Done"),
                    onClick = actionRunCallback<DoneAction>(actionParametersOf(DoneAction.TASK to data.actionTaskId)),
                    colors = ButtonDefaults.buttonColors(backgroundColor = ColorProvider(Color(0xFFE8374E)), contentColor = ColorProvider(Color.White)),
                )
            }
        }
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

internal class WidgetData(
    val name: String,
    val caption: String,
    val sprite: Bitmap?,
    val actionTaskId: String?,
    val actionLabel: String?,
) {
    companion object {
        /** Worst first: the widget shows the pet that needs you most. */
        private val PRIORITY = listOf(Mood.SAD, Mood.NEEDS_MEDS, Mood.HUNGRY, Mood.RESTLESS, Mood.CONTENT, Mood.HAPPY, Mood.SLEEPY)

        fun load(context: Context, now: Long): WidgetData? {
            val root = File(context.filesDir, "pawpixel")
            val text = File(root, WidgetSnapshot.FILE_NAME).takeIf { it.isFile }?.readText() ?: return null
            val snap = runCatching { Json.parse(text) }.getOrNull() ?: return null
            val pets = snap["pets"].list
            if (pets.isEmpty()) return null
            val withEntry = pets.map { p -> p to current(p["timeline"].list, now) }
            val (pet, entry) = withEntry.minBy { (_, e) -> PRIORITY.indexOf(Mood.fromKey(e?.get("mood")?.str ?: "content")) }
            val mood = Mood.fromKey(entry?.get("mood")?.str ?: "content")
            val spriteFile = File(root, pet["sprites"][mood.key].str ?: "")
            val bitmap = if (spriteFile.isFile) BitmapFactory.decodeFile(spriteFile.path, BitmapFactory.Options().apply { inScaled = false }) else null
            val action = pet["action"]
            return WidgetData(
                name = pet["name"].str ?: "",
                caption = entry?.get("caption")?.str ?: "",
                sprite = bitmap,
                actionTaskId = action["taskId"].str,
                actionLabel = action["label"].str,
            )
        }

        private fun current(timeline: List<Json>, now: Long): Json? =
            timeline.lastOrNull { (it["at"].long ?: 0) <= now } ?: timeline.firstOrNull()
    }
}
