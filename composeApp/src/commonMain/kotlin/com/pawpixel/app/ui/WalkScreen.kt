package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.app.toImageBitmap
import com.pawpixel.core.AppState
import com.pawpixel.core.Mood
import com.pawpixel.core.Pet
import com.pawpixel.core.Units
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.Frame
import com.pawpixel.sprite.PixelIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A walk, timed with the app open: the pixel pet trots along while the clock runs and the phone
 * counts steps (where it can). Ending the walk logs it as care, with the steps and a rough
 * distance. Nothing about the walk leaves the phone; no location is used.
 */
@Composable
fun WalkScreen(app: AppScope, state: AppState, pet: Pet) {
    val platform = app.repo.platform
    val startMs = androidx.compose.runtime.saveable.rememberSaveable { app.repo.now() } // a rotation doesn't restart the walk
    var elapsed by remember { mutableIntStateOf(0) }
    var steps by remember { mutableStateOf<Int?>(null) }
    var counting by remember { mutableStateOf<com.pawpixel.app.StepStart?>(null) }
    var ending by remember { mutableStateOf(false) }
    val art = remember(pet.lookKey) { app.repo.art(pet) }
    // The pet trots: the four walk frames, uneven timing, never mirrored.
    val frames by produceState<List<androidx.compose.ui.graphics.ImageBitmap>?>(null, art) {
        val a = art ?: return@produceState
        value = withContext(Dispatchers.Default) { val set = Chibi.build(a).forMood(Mood.HAPPY); Frame.WALK.map { set[it].toImageBitmap() } }
    }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        platform.keepScreenOn(true)
        counting = platform.startSteps()
        while (true) {
            delay(1_000)
            elapsed = ((app.repo.now() - startMs) / 1000).toInt()
            if (counting == com.pawpixel.app.StepStart.COUNTING) {
                val s = platform.stepsSoFar()
                if (s == null) counting = com.pawpixel.app.StepStart.DENIED else steps = s // null while counting: the phone said no after the prompt
            }
        }
    }
    LaunchedEffect(frames) { if (frames != null) while (true) { delay(if (frame % 2 == 0) 260L else 160L); frame = (frame + 1) % 4 } }
    // Leaving by the back key (or the system back) still stops the step sensor.
    DisposableEffect(Unit) { onDispose { platform.keepScreenOn(false); platform.stopSteps() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(app, tr("Walk with {0}", pet.name), Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                val f = frames
                if (f != null) androidx.compose.foundation.Image(
                    f[frame], contentDescription = tr("{0} trotting along", pet.name), filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                    modifier = Modifier.size(180.dp),
                )
            }
            val m = elapsed / 60; val sec = elapsed % 60
            val clockText = "$m:" + sec.toString().padStart(2, '0')
            Text(clockText, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, modifier = Modifier.semantics { contentDescription = tr("{0} minutes", m) })
            val st = steps
            Text(
                when {
                    counting == com.pawpixel.app.StepStart.NO_SENSOR -> tr("This phone has no step counter; the walk is timed.")
                    counting == com.pawpixel.app.StepStart.DENIED -> tr("Steps need the physical activity permission (Settings → Apps → PawPixel). The walk is timed meanwhile.")
                    st == null -> tr("Counting steps…")
                    else -> tr("{0} steps · about {1}", st, Units.distance(st * 0.0007))
                },
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Hint(tr("Keep the app open. Steps stay on your phone; no location is used."))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryPill(if (ending) tr("Saving…") else tr("End walk"), enabled = !ending, big = true, icon = PixelIcons.CHECK) {
                    ending = true
                    app.launch {
                        try {
                            app.repo.endWalk(pet.id, startMs, if (counting == com.pawpixel.app.StepStart.COUNTING) platform.stepsSoFar() else null)
                            app.back()
                        } finally { ending = false } // a failed save leaves End walk ready to try again
                    }
                }
                GhostPill(tr("Cancel")) { platform.stopSteps(); app.back() }
            }
        }
    }
}
