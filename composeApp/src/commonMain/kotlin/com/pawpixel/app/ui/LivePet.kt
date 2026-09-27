package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pawpixel.app.toImageBitmap
import com.pawpixel.core.Mood
import com.pawpixel.sprite.Animator
import com.pawpixel.sprite.EffectKind
import com.pawpixel.sprite.Frame
import com.pawpixel.sprite.Icons
import com.pawpixel.sprite.PetEvent
import com.pawpixel.sprite.PetPose
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.StageLayout
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
    onPetted: () -> Unit = {},
) {
    if (art == null) return
    val set = remember(art, eyes) { Chibi.build(art, Animator.eyePixels(art.head, eyes)) }
    val layout = remember(set) { StageLayout(set) }
    val moodSet = remember(set, mood) { set.forMood(mood) }
    val frames: Map<Frame, ImageBitmap> = remember(moodSet) { Frame.entries.associateWith { moodSet[it].toImageBitmap() } }
    val icons: Map<EffectKind, ImageBitmap> = remember(layout) {
        EffectKind.entries.associateWith { Icons.forEffect(it).scaled(layout.iconScale).toImageBitmap() }
    }
    val brain = remember(layout, seed) { layout.brain(seed) }
    /** Last pose drawn, for tap hit-testing (plain holder: must not trigger recomposition). */
    val lastPose = remember(brain) { arrayOfNulls<PetPose>(1) }
    val petted by rememberUpdatedState(onPetted)

    // One clock for the lifetime of this pet view, independent of brain rebuilds.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now = it - start }
    }
    LaunchedEffect(reaction, brain) { reaction?.let { brain.react(it.event, now) } }

    val floorColor = PawColors.Sand
    val floorLine = Color(0xFFE9C99A)
    Canvas(
        modifier
            .aspectRatio(layout.stageWidth.toFloat() / layout.stageHeight)
            .pointerInput(brain) {
                detectTapGestures { tap ->
                    val pose = lastPose[0] ?: return@detectTapGestures
                    val px = pixelScale(size.width.toFloat(), layout.stageWidth)
                    val left = (size.width - px * layout.stageWidth) / 2
                    val top = size.height - px * layout.stageHeight
                    val sx = (tap.x - left) / px
                    val sy = (tap.y - top) / px
                    val w = layout.set.width
                    val (b0, b2) = if (pose.flip) (w - layout.body[2]) to (w - layout.body[0]) else layout.body[0] to layout.body[2]
                    val petTopY = layout.petTop + pose.lift + layout.body[1]
                    if (sx >= pose.x + b0 - 4 && sx <= pose.x + b2 + 4 && sy >= petTopY - 8 && sy <= layout.floorY + 2) {
                        brain.react(PetEvent.Petted, now)
                        petted()
                    }
                }
            },
    ) {
        val t = now // read state here so only drawing (not composition) reruns each frame
        val pose = brain.pose(t, mood)
        lastPose[0] = pose
        val px = pixelScale(size.width, layout.stageWidth)
        val left = ((size.width - px * layout.stageWidth) / 2).roundToInt().toFloat()
        // Bottom-aligned: any spare height becomes sky, not floor.
        val top = (size.height - px * layout.stageHeight).roundToInt().toFloat()
        fun sx(v: Double) = left + (v * px).toFloat()
        fun sy(v: Double) = top + (v * px).toFloat()

        // Floor
        drawRect(floorLine, Offset(0f, sy(layout.floorY - 2.0)), Size(size.width, px))
        drawRect(floorColor, Offset(0f, sy(layout.floorY - 1.0)), Size(size.width, size.height - sy(layout.floorY - 1.0)))


        // Shadow, smaller while airborne
        val bodyW = layout.body[2] - layout.body[0]
        val half = bodyW * 0.4 * (1.0 + pose.lift / 40.0).coerceIn(0.4, 1.0)
        val cx = pose.x + (layout.body[0] + layout.body[2]) / 2.0
        drawOval(Color(0x40000000), Offset(sx(cx - half), sy(layout.floorY - 1.0)), Size((half * 2 * px).toFloat(), 2 * px))

        // Pet
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

/** Whole-number scale when there's room, so every sprite pixel is the same size on screen. */
private fun pixelScale(widthPx: Float, stageWidth: Int): Float {
    val s = widthPx / stageWidth
    return if (s >= 1f) floor(s) else s
}

private inline fun DrawScope.withFlip(flip: Boolean, pivotX: Float, block: DrawScope.() -> Unit) {
    if (flip) scale(-1f, 1f, pivot = Offset(pivotX, 0f)) { block() } else block()
}
