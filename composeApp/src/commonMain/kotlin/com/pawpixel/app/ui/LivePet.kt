package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import com.pawpixel.sprite.AnimationSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.pawpixel.i18n.tr
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pawpixel.app.toImageBitmap
import com.pawpixel.core.Mood
import com.pawpixel.sprite.EffectKind
import com.pawpixel.sprite.Frame
import com.pawpixel.sprite.Icons
import com.pawpixel.sprite.PetEvent
import com.pawpixel.sprite.PetPose
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.StageLayout
import com.pawpixel.sprite.Room
import com.pawpixel.core.Sky
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/** A reaction to play, with a nonce so the same event can be sent twice in a row. */
data class Reaction(val event: PetEvent, val nonce: Long)

/**
 * The pet, alive: it breathes, blinks, wanders, reacts to taps and to care you log.
 * Rendered pixel-perfect on a Canvas; frames are pre-built once per sprite and mood.
 */
@Composable
fun LivePet(
    art: PetArt?,
    eyes: List<Pair<Double, Double>>,
    mood: Mood,
    seed: Int,
    modifier: Modifier = Modifier,
    reaction: Reaction? = null,
    /** What a screen reader says: the pet's name and mood (see [com.pawpixel.core.MoodEngine.describe]). */
    description: String? = null,
    /** The time of day: the room's window shows that sky and the lamp comes on at night. */
    phase: Sky.Phase = Sky.Phase.DAY,
    /** Keep the stage's own proportions (false when the caller gives it a height: a hero stage, more wall above). */
    keepAspect: Boolean = true,
    /** Extra floor below the pet's feet (a hero room has a deep floor with a rug). */
    floorDepth: Dp = 0.dp,
    onPetted: () -> Unit = {},
) {
    if (art == null) return
    // Drawing every frame of the pet takes a budget phone a noticeable moment: do it off the main
    // thread (the screen opens at once), keeping what's shown until the new drawings are ready.
    val built by produceState<Built?>(null, art, eyes) {
        value = withContext(Dispatchers.Default) {
            val set = Chibi.build(art)
            val layout = StageLayout(set)
            Built(set, layout, EffectKind.entries.associateWith { Icons.forEffect(it).scaled(layout.iconScale).toImageBitmap() })
        }
    }
    val drawn by produceState<MoodFrames?>(null, built, mood) {
        val b = built ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val moodSet = b.set.forMood(mood)
            MoodFrames(b.set, Frame.entries.associateWith { moodSet[it].toImageBitmap() })
        }
    }
    val current = built
    if (current == null) {
        // The stage's usual shape, so nothing jumps when the pet appears.
        Box(modifier.let { if (keepAspect) it.aspectRatio(PLACEHOLDER_ASPECT) else it }.semantics { if (description != null) contentDescription = description })
        return
    }
    val layout = current.layout
    val icons = current.icons
    val brain = remember(layout, seed) { layout.brain(seed) }
    val petted by rememberUpdatedState(onPetted)
    val currentMood by rememberUpdatedState(mood)

    // One clock for the lifetime of this pet view, independent of brain rebuilds. A plain holder:
    // reading it (taps, reactions) must not redraw anything.
    val clock = remember { longArrayOf(-1L, 0L) } // start, now
    /**
     * The pose on screen. The pet thinks every frame, but the stage is redrawn only when what it
     * shows changes by a pet pixel (a few times a second), not 60 times a second: a budget phone
     * keeps its time for scrolling and its battery.
     */
    var shown by remember(brain) { mutableStateOf<PetPose?>(null) }
    LaunchedEffect(brain) {
        while (true) withFrameMillis { t ->
            if (clock[0] < 0) clock[0] = t
            clock[1] = t - clock[0]
            val pose = brain.pose(clock[1], currentMood).onPixelGrid()
            if (pose != shown) shown = pose
        }
    }
    LaunchedEffect(reaction, brain) { reaction?.let { brain.react(it.event, clock[1]) } }

    // The room behind the pet, drawn once per size and time of day (it is tiny: stage pixels).
    val roomCache = remember { HashMap<String, ImageBitmap>() }
    val floorDepthPx = with(LocalDensity.current) { floorDepth.toPx() }
    val petLabel = tr("Give pets")
    Canvas(
        modifier
            .let { if (keepAspect) it.aspectRatio(layout.stageWidth.toFloat() / layout.stageHeight) else it }
            // Its own layer: the pet redraws a few times a second, and only this stage repaints, never the whole page.
            .graphicsLayer()
            // A picture only shows the mood: say it, and let screen-reader users give pets too.
            .semantics {
                if (description != null) contentDescription = description
                onClick(label = petLabel) { brain.react(PetEvent.Petted, clock[1]); petted(); true }
            }
            .pointerInput(brain) {
                detectTapGestures { tap ->
                    val pose = shown ?: return@detectTapGestures
                    val px = pixelScale(size.width.toFloat(), layout.stageWidth)
                    val left = (size.width - px * layout.stageWidth) / 2
                    val top = size.height - px * (layout.stageHeight + kotlin.math.ceil(floorDepthPx / px).toInt())
                    val sx = (tap.x - left) / px
                    val sy = (tap.y - top) / px
                    val w = layout.set.width
                    val (b0, b2) = if (pose.flip) (w - layout.body[2]) to (w - layout.body[0]) else layout.body[0] to layout.body[2]
                    val petTopY = layout.petTop + pose.lift + layout.body[1]
                    if (sx >= pose.x + b0 - 4 && sx <= pose.x + b2 + 4 && sy >= petTopY - 8 && sy <= layout.floorY + 2) {
                        brain.react(PetEvent.Petted, clock[1])
                        petted()
                    }
                }
            },
    ) {
        val pose = shown ?: return@Canvas // read here, so a new pose redraws the stage without recomposing
        val px = pixelScale(size.width, layout.stageWidth)
        val left = ((size.width - px * layout.stageWidth) / 2).roundToInt().toFloat()
        // Bottom-aligned above the extra floor: any spare height becomes wall, not floor.
        val depthRows = kotlin.math.ceil(floorDepthPx / px).toInt()
        val top = (size.height - px * (layout.stageHeight + depthRows)).roundToInt().toFloat()
        fun sx(v: Double) = left + (v * px).toFloat()
        fun sy(v: Double) = top + (v * px).toFloat()

        // The room: as many rows as the stage is tall, the floor where the pet's feet are.
        val rows = kotlin.math.ceil(size.height / px).toInt().coerceAtLeast(layout.stageHeight + depthRows)
        val roomW = kotlin.math.ceil(size.width / px).toInt().coerceAtLeast(layout.stageWidth)
        val floorRow = rows - (layout.stageHeight + depthRows) + layout.floorY
        val key = "$roomW:$rows:$floorRow:${phase.key}"
        val room = roomCache.getOrPut(key) { Room.render(roomW, rows, floorRow, phase, seed).toImageBitmap() }
        val roomLeft = ((size.width - px * roomW) / 2).roundToInt()
        val roomTop = (size.height - px * rows).roundToInt()
        drawImage(room, IntOffset.Zero, IntSize(roomW, rows), IntOffset(roomLeft, roomTop), IntSize((roomW * px).roundToInt(), (rows * px).roundToInt()), filterQuality = FilterQuality.None)


        // Shadow, smaller while airborne
        val bodyW = layout.body[2] - layout.body[0]
        val half = bodyW * 0.4 * (1.0 + pose.lift / 40.0).coerceIn(0.4, 1.0)
        val cx = pose.x + (layout.body[0] + layout.body[2]) / 2.0
        drawOval(Color(0x40000000), Offset(sx(cx - half), sy(layout.floorY - 1.0)), Size((half * 2 * px).toFloat(), 2 * px))

        // Pet (once its drawings for this look and mood are ready)
        val frames = drawn?.takeIf { it.set === current.set }?.frames ?: return@Canvas
        val img = frames.getValue(pose.frame)
        val ix = sx(pose.x).roundToInt()
        val iy = sy(layout.petTop + pose.lift.toDouble()).roundToInt()
        val iw = (img.width * px).roundToInt()
        val ih = (img.height * px).roundToInt()
        withFlip(pose.flip, ix + iw / 2f) {
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(ix, iy), IntSize(iw, ih), filterQuality = FilterQuality.None)
        }

        // Effects (mirrored with the pet)
        for (e in pose.effects) {
            val icon = icons.getValue(e.kind)
            val baseH = Icons.forEffect(e.kind).height
            val ex = if (pose.flip) img.width - e.x - icon.width else e.x
            val ey = layout.petTop + pose.lift + e.y - (icon.height - baseH)
            drawImage(
                icon, IntOffset.Zero, IntSize(icon.width, icon.height),
                IntOffset(sx(pose.x + ex).roundToInt(), sy(ey).roundToInt()),
                IntSize((icon.width * px).roundToInt(), (icon.height * px).roundToInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/** A pet's drawings for one look: its animation frames for every mood, stage and effect icons. */
private class Built(val set: AnimationSet, val layout: StageLayout, val icons: Map<EffectKind, ImageBitmap>)

/** The frames of one mood, and which drawings they came from. */
private class MoodFrames(val set: AnimationSet, val frames: Map<Frame, ImageBitmap>)

/** Width / height of the stage for most looks (between 1.53 and 1.60). */
private const val PLACEHOLDER_ASPECT = 1.55f

/** Whole-number scale when there's room, so every sprite pixel is the same size on screen. */
private fun pixelScale(widthPx: Float, stageWidth: Int): Float {
    val s = widthPx / stageWidth
    return if (s >= 1f) floor(s) else s
}

private inline fun DrawScope.withFlip(flip: Boolean, pivotX: Float, block: DrawScope.() -> Unit) {
    if (flip) scale(-1f, 1f, pivot = Offset(pivotX, 0f)) { block() } else block()
}

/** The time of day for the pet's room: the owner's night makes it night. */
@Composable
fun phaseFor(app: AppScope, state: com.pawpixel.core.AppState): Sky.Phase =
    Sky.phase(app.repo.clock.minuteOfDay(app.now), state.settings.nightStart, state.settings.nightEnd)

/**
 * The pet's room as a backdrop for a card (the home card, an empty stage), drawn at [pixel] per
 * room pixel. [content] is placed over it; its bottom padding is the floor's depth, so a sprite
 * aligned to the bottom stands on the floor.
 */
@Composable
fun RoomBackdrop(phase: Sky.Phase, modifier: Modifier = Modifier, pixel: Dp = 3.dp, floorDepth: Dp = 18.dp, content: @Composable BoxScope.(floor: Dp) -> Unit) {
    val cache = remember { HashMap<String, ImageBitmap>() }
    val pxDp = with(LocalDensity.current) { pixel.toPx() }
    val depthPx = with(LocalDensity.current) { floorDepth.toPx() }
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val cols = kotlin.math.ceil(size.width / pxDp).toInt().coerceAtLeast(40)
            val rows = kotlin.math.ceil(size.height / pxDp).toInt().coerceAtLeast(30)
            val floorRow = rows - kotlin.math.ceil(depthPx / pxDp).toInt() + 2
            val key = "$cols:$rows:$floorRow:${phase.key}"
            val img = cache.getOrPut(key) { Room.render(cols, rows, floorRow, phase).toImageBitmap() }
            drawImage(img, IntOffset.Zero, IntSize(cols, rows), IntOffset(0, (size.height - rows * pxDp).roundToInt()), IntSize((cols * pxDp).roundToInt(), (rows * pxDp).roundToInt()), filterQuality = FilterQuality.None)
        }
        content(floorDepth)
    }
}
