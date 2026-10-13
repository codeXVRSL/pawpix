package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
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
import com.pawpixel.sprite.PetBrain
import com.pawpixel.sprite.Furniture
import com.pawpixel.sprite.StageLayout
import com.pawpixel.sprite.Room
import com.pawpixel.sprite.RoomLayout
import com.pawpixel.sprite.Places
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
    /** Draw the pet this much bigger than the stage's own scale (the room that fills a phone); it then wanders the width that is on screen. */
    zoom: Float = 1f,
    onPetted: () -> Unit = {},
    /** The calendar's decorations (Halloween, Christmas, Valentine's). */
    season: com.pawpixel.sprite.Season = com.pawpixel.sprite.Season.NONE,
    /** The room's furniture, as the owner arranged it. */
    room: RoomLayout = RoomLayout.DEFAULT,
) {
    if (art == null) return
    // Drawing every frame of the pet takes a budget phone a noticeable moment: do it off the main
    // thread (the screen opens at once), keeping what's shown until the new drawings are ready.
    val built by produceState<Built?>(null, art, eyes) {
        value = withContext(Dispatchers.Default) {
            val set = Chibi.build(art)
            val layout = StageLayout(set)
            // The frames that are drawn: the same pet at twice the resolution (every curve, ear and outline finer), see Chibi.compose.
            val fineSet = Chibi.build(art, fine = DrawnScene.FINE)
            Built(set, fineSet, layout, EffectKind.entries.associateWith { Icons.forEffect(it).scaled(layout.iconScale).toImageBitmap() })
        }
    }
    val drawn by produceState<MoodFrames?>(null, built, mood) {
        val b = built ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val moodSet = b.fineSet.forMood(mood)
            MoodFrames(b.fineSet, Frame.entries.associateWith { moodSet[it].toImageBitmap() })
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
    val petted by rememberUpdatedState(onPetted)
    val currentMood by rememberUpdatedState(mood)

    // One clock for the lifetime of this pet view, independent of brain rebuilds. A plain holder:
    // reading it (taps, reactions) must not redraw anything.
    val clock = remember { longArrayOf(-1L, 0L) } // start, now
    BoxWithConstraints(modifier.let { if (keepAspect) it.aspectRatio(layout.stageWidth.toFloat() / layout.stageHeight) else it }) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    val floorDepthPx = with(density) { floorDepth.toPx() }
    // The scale of a room pixel, and how many of them fit across. On a tablet or a phone on its side
    // the pet keeps a phone's size (capped by a phone's width and by the height), and the room gets
    // wider instead of the pet getting huge.
    val byWidth = pixelScale(with(density) { minOf(maxWidth, MAX_STAGE_WIDTH).toPx() } * zoom, layout.stageWidth)
    // The stage sits on top of the floor kept free for the buttons, so only the height above it counts.
    // A stage that keeps its aspect gets its height from the width, so only a free-height stage (the room) is capped by it.
    val byHeight = if (!keepAspect && constraints.hasBoundedHeight) {
        pixelScale((constraints.maxHeight - floorDepthPx).coerceAtLeast(1f) * MAX_STAGE_HEIGHT_SHARE, layout.stageHeight)
    } else Float.MAX_VALUE
    // An even number of screen pixels per room pixel, so the fine (2x) drawings land on whole pixels too.
    val px = minOf(byWidth, byHeight).let { if (it >= 2f) (floor(it / 2) * 2) else it }
    // The room's grid for this stage: the floor rows the pet may walk, where its bed and bowl stand, what's in the way.
    // The pet walks the back wall's width in room units; the box projects it nearer and bigger on the front rows.
    val depthRows = kotlin.math.ceil(floorDepthPx / px).toInt()
    val roomW = kotlin.math.ceil(widthPx / px).toInt().coerceAtLeast(layout.stageWidth / 2)
    val places = remember(layout, depthRows, room, art?.species) { placesFor(room, layout, depthRows, art?.species ?: com.pawpixel.core.Species.OTHER) }
    val brain = remember(layout, seed, places) { PetBrain(seed, Room.GRID_W, layout.set.width, layout.body, places) }
    /**
     * The pose on screen. The pet thinks every frame, but the stage is redrawn only when what it
     * shows changes by a pet pixel (a few times a second), not 60 times a second: a budget phone
     * keeps its time for scrolling and its battery.
     */
    var shown by remember(brain) { mutableStateOf<PetPose?>(null) }
    // The room's own slow life: dust drifting in the sunbeam by day, the lamp breathing at night.
    // A few beats a second, enough for motes to drift and a glow to swell, not a frame rate.
    var ambientBeat by remember { mutableStateOf(0L) }
    val ambientStep = if (phase.dark) 160L else 90L
    LaunchedEffect(brain, ambientStep) {
        while (true) withFrameMillis { t ->
            if (clock[0] < 0) clock[0] = t
            clock[1] = t - clock[0]
            val pose = brain.pose(clock[1], currentMood).onPixelGrid()
            if (pose != shown) shown = pose
            val beat = clock[1] / ambientStep
            if (beat != ambientBeat) ambientBeat = beat
        }
    }
    // Keyed on the reaction alone: a rebuilt brain (a new outfit, a resized stage) doesn't replay the last one.
    val currentBrain by androidx.compose.runtime.rememberUpdatedState(brain)
    LaunchedEffect(reaction) { reaction?.let { currentBrain.react(it.event, clock[1]) } }

    // The room behind the pet, drawn once per size, time of day and arrangement (it is tiny: stage pixels).
    val roomCache = remember { HashMap<String, DrawnScene>() }
    // When the time of day turns (dawn, dusk, night), the old room fades into the new over a minute and a half rather than snapping.
    val fade = remember { PhaseFade() }
    val roomCode = remember(room) { room.encode() }
    // Where the pet was last drawn on screen (left, top, right, bottom), for taps: written by the draw, read by the hit-test.
    val petBox = remember { FloatArray(4) }
    val petLabel = tr("Give pets")
    Canvas(
        Modifier.matchParentSize()
            // Its own layer: the pet redraws a few times a second, and only this stage repaints, never the whole page.
            .graphicsLayer()
            // A picture only shows the mood: say it, and let screen-reader users give pets too.
            .semantics {
                if (description != null) contentDescription = description
                onClick(label = petLabel) { brain.react(PetEvent.Petted, clock[1]); petted(); true }
            }
            .pointerInput(brain) {
                detectTapGestures { tap ->
                    val pad = px * 4
                    if (tap.x >= petBox[0] - pad && tap.x <= petBox[2] + pad && tap.y >= petBox[1] - pad * 2 && tap.y <= petBox[3] + pad / 2) {
                        brain.react(PetEvent.Petted, clock[1])
                        petted()
                    }
                }
            },
    ) {
        val pose = shown ?: return@Canvas // read here, so a new pose redraws the stage without recomposing
        // Bottom-aligned above the extra floor: any spare height becomes wall, not floor.
        val depthRows = kotlin.math.ceil(floorDepthPx / px).toInt()

        // The room: as many rows as the stage is tall, the pet's home row of floor where its feet are.
        val rows = kotlin.math.ceil(size.height / px).toInt().coerceAtLeast(layout.stageHeight + depthRows)
        val floorRow = rows - (layout.stageHeight + depthRows) + layout.floorY
        val key = "$roomW:$rows:$floorRow:${phase.key}:${season.name}:$roomCode"
        val scene = roomCache[key] ?: Room.scene(roomW, rows, Room.floorLineFor(floorRow), phase, seed, season, room).let { sc ->
            // Back to front, so two pieces that overlap are drawn in the right order (as Scene.flatten does).
            // The room and its pieces at twice their resolution too (DrawnScene.FINE pixels per room pixel), corners rounded.
            DrawnScene(sc.fineBase.toImageBitmap(), sc.flattenFine().toImageBitmap(), sc.props.sortedWith(compareBy({ it.baseline }, { it.x })).map { DrawnProp(it.fine.toImageBitmap(), it.x, it.y, it.baseline, it.placed.item == Furniture.LAMP) }, sc.geom, sc.light, sc.windows, sc.lamps)
        }.also {
            if (roomCache.size >= 4) roomCache.clear() // a few sizes at most: a resizing stage doesn't keep a bitmap per frame
            roomCache[key] = it
        }
        val g = scene.geom
        val roomLeft = ((size.width - px * roomW) / 2).roundToInt()
        val roomTop = (size.height - px * rows).roundToInt()
        fun sx(v: Double) = roomLeft + (v * px).toFloat()
        fun sy(v: Double) = roomTop + (v * px).toFloat()
        val fine = DrawnScene.FINE
        val blend = fade.progress(scene, phase, clock[1]) // 1 once the new room is fully in
        if (blend < 1f) fade.from?.let { old -> drawImage(old.soft, IntOffset.Zero, IntSize(roomW * fine, rows * fine), IntOffset(roomLeft, roomTop), IntSize((roomW * px).roundToInt(), (rows * px).roundToInt()), filterQuality = FilterQuality.None) }
        drawImage(scene.base, IntOffset.Zero, IntSize(roomW * fine, rows * fine), IntOffset(roomLeft, roomTop), IntSize((roomW * px).roundToInt(), (rows * px).roundToInt()), filterQuality = FilterQuality.None, alpha = blend)
        fun drawProp(p: DrawnProp) = drawImage(p.image, IntOffset.Zero, IntSize(p.image.width, p.image.height), IntOffset(roomLeft + (p.x * px).roundToInt(), roomTop + (p.y * px).roundToInt()), IntSize((p.image.width * px / fine).roundToInt(), (p.image.height * px / fine).roundToInt()), filterQuality = FilterQuality.None, alpha = blend)

        // Where the pet stands: its row's depth sets how big it is drawn and where its row is on the box's floor.
        val depth = Room.PET_ROW + 1.0 + pose.dy.toDouble() / Room.TILE_D
        // Whole screen pixels per fine pixel of the pet, so its pixels stay even; petPx is the size of one of its own (coarse) pixels.
        val finePx = (g.size(depth) * px / fine).roundToInt().coerceAtLeast(1)
        val petPx = finePx * fine
        val bodyMid = (layout.body[0] + layout.body[2]) / 2.0
        val feetRoomY = floorRow + pose.dy
        val centreX = sx(g.x(pose.x + bodyMid, depth))
        val feetY = sy(feetRoomY.toDouble())
        val frames = drawn?.takeIf { it.set === current.fineSet }?.frames
        val img = frames?.getValue(pose.frame)
        val canvasW = layout.set.width; val canvasH = layout.set.height
        val ix = (centreX - bodyMid * petPx).roundToInt()
        val iy = (feetY - (layout.body[3] - pose.lift) * petPx).roundToInt()
        val iw = canvasW * petPx; val ih = canvasH * petPx
        petBox[0] = ix + layout.body[0] * petPx.toFloat(); petBox[1] = iy + layout.body[1] * petPx.toFloat()
        petBox[2] = ix + layout.body[2] * petPx.toFloat(); petBox[3] = feetY

        // The lamp breathes at night: its pool swells and settles over a few seconds, like a flame settling.
        val ambientMs = ambientBeat * ambientStep
        if (phase.dark) for (lamp in scene.lamps) {
            val breath = 0.5f + 0.5f * kotlin.math.sin(ambientMs / 950.0 + lamp[0]).toFloat() // a full swell and settle every six seconds
            val r = (26 + 4 * breath) * px
            drawCircle(
                Brush.radialGradient(0f to Color(0xFFFFC978).copy(alpha = 0.10f + 0.07f * breath), 1f to Color.Transparent, center = Offset(sx(lamp[0].toDouble()), sy(lamp[1] + 6.0)), radius = r),
                radius = r, center = Offset(sx(lamp[0].toDouble()), sy(lamp[1] + 6.0)),
            )
        }

        // Painter's order: what stands behind the pet's feet first, then the pet, then what stands in front.
        val petBaseline = feetRoomY
        for (p in scene.props) if (p.baseline <= petBaseline) drawProp(p)

        if (img != null) {
            // Its shadow: the pet flattened onto the boards at its feet, pushed away from the window (or the lamp at night), smaller while airborne.
            val lamp = scene.props.firstOrNull { it.lamp }
            val away = if (phase.dark && lamp != null) ((centreX - sx(lamp.x + lamp.image.width / 2.0)) * 0.15f).coerceIn(-5 * px, 5 * px) else 3 * px
            val squash = (1.0 + pose.lift / 40.0).coerceIn(0.4, 1.0)
            val shH = (ih / 3.6 * squash).roundToInt().coerceAtLeast(1)
            drawImage(
                img, IntOffset.Zero, IntSize(img.width, img.height),
                IntOffset((ix + away).roundToInt(), (feetY + 2 * px - shH).roundToInt()), IntSize(iw, shH),
                filterQuality = FilterQuality.None, alpha = if (phase.dark) 0.5f else 0.38f,
                colorFilter = ColorFilter.tint(Color(0xFF14102A), BlendMode.SrcIn),
            )
            // The pet under the room's light where it stands (the lamp's pool at night, the shade of a corner).
            val l = scene.light.at(g.x(pose.x + bodyMid, depth).roundToInt(), feetRoomY - (layout.body[3] - layout.body[1]) / 2)
            val lighting = ColorFilter.lighting(
                Color(l[0].coerceIn(0f, 1f), l[1].coerceIn(0f, 1f), l[2].coerceIn(0f, 1f)),
                Color(((l[0] - 1f).coerceIn(0f, 0.6f) * 0.35f), ((l[1] - 1f).coerceIn(0f, 0.6f) * 0.35f), ((l[2] - 1f).coerceIn(0f, 0.6f) * 0.35f)),
            )
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(ix, iy), IntSize(iw, ih), filterQuality = FilterQuality.None, colorFilter = lighting)
        }
        for (p in scene.props) if (p.baseline > petBaseline) drawProp(p)

        // A diorama photographed up close: the top and bottom edges of the room fall softly out of focus.
        // The smooth copy is the same picture drawn with filtering, in strips that fade in toward the edge.
        val stageH = rows * px
        fun softBand(fromY: Float, toY: Float, strips: Int, topEdge: Boolean) {
            val h = toY - fromY
            for (i in 0 until strips) {
                val y0 = fromY + h * i / strips; val y1 = fromY + h * (i + 1) / strips
                val t = if (topEdge) 1f - (i + 0.5f) / strips else (i + 0.5f) / strips
                val alpha = t * kotlin.math.sqrt(t)
                val srcY0 = ((y0 - roomTop) / px).toInt().coerceIn(0, rows - 1); val srcY1 = ((y1 - roomTop) / px).toInt().coerceIn(srcY0 + 1, rows)
                drawImage(
                    scene.soft, IntOffset(0, srcY0 * fine), IntSize(roomW * fine, (srcY1 - srcY0) * fine),
                    IntOffset(roomLeft, roomTop + (srcY0 * px).roundToInt()), IntSize((roomW * px).roundToInt(), ((srcY1 - srcY0) * px).roundToInt()),
                    filterQuality = FilterQuality.Medium, alpha = alpha,
                )
            }
        }
        // Dust drifting down the sunbeam by day: a few specks that catch the light, each on its own slow fall.
        if (!phase.dark) for (win in scene.windows) {
            val f = (roomW / 100f).coerceAtLeast(0.5f)
            val wx = win[0] + win[2] / 2.0; val wy = (win[1] + win[3]).toDouble()
            val fall = (g.floorLine - wy + 10 * f).coerceAtLeast(12.0)
            for (i in 0 until 9) {
                val period = 9000.0 + 1700.0 * ((i * 7) % 5)
                val t = ((ambientMs + i * 1310L) % period.toLong()) / period
                val sway = kotlin.math.sin(ambientMs / 1400.0 + i * 1.9) * (2.0 + 4.0 * t) * f
                val lane = ((i * 37) % 11 - 5) * 1.6 * f
                val y = wy + 2 + t * fall
                val x = wx + (y - wy) * 0.45 + lane * (0.4 + t) + sway
                val glow = (kotlin.math.sin(t * kotlin.math.PI) * (0.55 + 0.45 * kotlin.math.sin(ambientMs / 700.0 + i * 2.3))).toFloat().coerceIn(0f, 1f)
                if (glow < 0.08f) continue
                drawRect(Color(0xFFFFF3C8).copy(alpha = 0.75f * glow), Offset(sx(x), sy(y)), androidx.compose.ui.geometry.Size(px, px))
            }
        }
        // Fireflies over the garden at night: a few soft green-gold points wandering above the hedge, each lighting up on its own slow pulse.
        if (phase.dark && room.theme == com.pawpixel.sprite.RoomTheme.GARDEN) {
            val f = (roomW / 100f).coerceAtLeast(0.5f)
            for (i in 0 until 7) {
                val x0 = g.backLeft + (g.backRight - g.backLeft) * (((i * 53) % 17) / 17.0 + 0.03)
                val y0 = g.floorLine - (10 + (i * 29) % 26) * f
                val x = x0 + kotlin.math.sin(ambientMs / 2300.0 + i * 1.1) * 7 * f + kotlin.math.sin(ambientMs / 700.0 + i) * 1.5 * f
                val y = y0 + kotlin.math.cos(ambientMs / 1900.0 + i * 1.7) * 4 * f
                val pulse = kotlin.math.sin(ambientMs / 1100.0 + i * 2.4).coerceAtLeast(0.0)
                val glow = (pulse * pulse * pulse).toFloat()
                if (glow < 0.05f) continue
                val r = (2.2f + 1.3f * glow) * px
                drawCircle(Brush.radialGradient(0f to Color(0xFFE4FF9A).copy(alpha = 0.55f * glow), 1f to Color.Transparent, center = Offset(sx(x), sy(y)), radius = r), radius = r, center = Offset(sx(x), sy(y)))
                drawRect(Color(0xFFF4FFC2).copy(alpha = 0.9f * glow), Offset(sx(x) - px / 2, sy(y) - px / 2), androidx.compose.ui.geometry.Size(px, px))
            }
        }
        softBand(roomTop.toFloat(), roomTop + stageH * 0.09f, 5, topEdge = true)
        softBand(roomTop + stageH * 0.88f, roomTop + stageH, 5, topEdge = false)

        if (img == null) return@Canvas
        // Effects (mirrored with the pet)
        for (e in pose.effects) {
            val icon = icons.getValue(e.kind)
            val baseH = Icons.forEffect(e.kind).height
            val ex = if (pose.flip) canvasW - e.x - icon.width else e.x
            val ey = e.y - (icon.height - baseH)
            drawImage(
                icon, IntOffset.Zero, IntSize(icon.width, icon.height),
                IntOffset((ix + ex * petPx).toInt(), (iy + ey * petPx).toInt()),
                IntSize((icon.width * px).roundToInt(), (icon.height * px).roundToInt()),
                filterQuality = FilterQuality.None,
            )
        }
    }
    }
}

/** The room's backdrop, its smooth copy for the soft edges, and its standing pieces (in room pixels), ready to draw. */
/**
 * The slow cross-fade between the room by one time of day and the next: the old picture stays
 * underneath while the new one comes in over [FADE_MS]. Read only while drawing.
 */
private class PhaseFade {
    private var last: DrawnScene? = null
    private var lastPhase: Sky.Phase? = null
    private var start = 0L
    /** The room being faded out, while a fade runs. */
    var from: DrawnScene? = null; private set

    /** How far the new [scene] is in (0 to 1) at [nowMs]; a change of [phase] starts a fade, any other change of scene (a resize, a new piece) just swaps. */
    fun progress(scene: DrawnScene, phase: Sky.Phase, nowMs: Long): Float {
        if (scene !== last) {
            val old = last
            if (old != null && lastPhase != null && lastPhase != phase && old.geom.width == scene.geom.width && old.geom.height == scene.geom.height) { from = old; start = nowMs }
            else from = null
            last = scene; lastPhase = phase
        }
        val f = from ?: return 1f
        val t = (nowMs - start).toFloat() / FADE_MS
        if (t >= 1f) { from = null; return 1f }
        return t.coerceAtLeast(0f)
    }

    companion object { const val FADE_MS = 90_000f }
}

private class DrawnScene(val base: ImageBitmap, val soft: ImageBitmap, val props: List<DrawnProp>, val geom: Room.Geometry, val light: Room.Light, val windows: List<IntArray>, val lamps: List<FloatArray>) {
    companion object {
        /** The bitmaps hold this many fine pixels per room pixel (they are drawn through [com.pawpixel.sprite.PixelImage.scale2x]). */
        const val FINE = Room.FINE
    }
}
private class DrawnProp(val image: ImageBitmap, val x: Int, val y: Int, val baseline: Int, val lamp: Boolean)

/**
 * The room as the pet's brain sees it: which rows it can walk (those on screen above the controls),
 * its bed and bowl as positions across the room (in room units, the back wall [Room.GRID_W] wide),
 * and the tiles furniture takes.
 */
private fun placesFor(room: RoomLayout, layout: StageLayout, depthRows: Int, species: com.pawpixel.core.Species): Places {
    // Rows in front of the home row that still show above the controls (the home row's feet are [depthRows] above them).
    val front = ((depthRows - 6) / Room.TILE_D).coerceAtLeast(0)
    val rows = (Room.PET_ROW + 1 + front).coerceAtMost(RoomLayout.ROWS)
    val bodyMid = (layout.body[0] + layout.body[2]) / 2.0
    // The bed's front row must be one the pet can walk to; a bed beyond the walkable rows would be drawn over the sleeping pet.
    // Standing on a piece: centred on it, on its front row (a bed beyond the walkable rows would be drawn over the sleeping pet).
    fun on(p: com.pawpixel.sprite.Placed?): Pair<Double, Int>? = p?.takeIf { it.y + it.item.rows - 1 < rows }?.let { b -> (b.x + b.item.cols / 2.0) * Room.TILE_W - bodyMid to b.y + b.item.rows - 1 }
    val bed = on(room.bedFor(species))
    val play = on(room.toyFor(species))
    val bowl = room.items.firstOrNull { it.item.use == Furniture.Use.EAT }?.let { b ->
        // Standing just left of the bowl, facing it.
        b.x * Room.TILE_W - layout.body[2] - 1.0 to b.y.coerceAtMost(rows - 1)
    }
    val blocked = room.items.filter { !it.item.onWall && !it.item.flat && it.item.use == Furniture.Use.NONE }.flatMap { it.tiles() }.toSet()
    return Places(rows, bed, bowl, play) { x, row -> (floor(x / Room.TILE_W).toInt() to row) !in blocked }
}

/** A pet's drawings for one look: its animation frames for every mood, stage and effect icons. */
private class Built(val set: AnimationSet, val fineSet: AnimationSet, val layout: StageLayout, val icons: Map<EffectKind, ImageBitmap>)

/** The frames of one mood, and which drawings they came from. */
private class MoodFrames(val set: AnimationSet, val frames: Map<Frame, ImageBitmap>)

/** Width / height of the stage for most looks (between 1.53 and 1.60). */
private const val PLACEHOLDER_ASPECT = 1.55f

/** The widest a stage draws its pet for (a large phone); wider screens show more room around it. */
private val MAX_STAGE_WIDTH = 480.dp
/** The most of the height above the floor the stage's rows may take, so a phone on its side still shows the whole pet. */
private const val MAX_STAGE_HEIGHT_SHARE = 0.95f

/** Whole-number scale when there's room, so every sprite pixel is the same size on screen. */
private fun pixelScale(widthPx: Float, stageWidth: Int): Float {
    val s = widthPx / stageWidth
    return if (s >= 1f) floor(s) else s
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
fun RoomBackdrop(phase: Sky.Phase, modifier: Modifier = Modifier, pixel: Dp = 3.dp, floorDepth: Dp = 18.dp, season: com.pawpixel.sprite.Season = com.pawpixel.sprite.Season.NONE, room: RoomLayout = RoomLayout.DEFAULT, content: @Composable BoxScope.(floor: Dp) -> Unit) {
    val cache = remember { HashMap<String, ImageBitmap>() }
    // A whole, even number of screen pixels per room pixel: every pixel the same size, the fine drawing on whole pixels too.
    val pxDp = with(LocalDensity.current) { pixel.toPx() }.let { if (it >= 2f) floor(it / 2) * 2 else it }
    val depthPx = with(LocalDensity.current) { floorDepth.toPx() }
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val cols = kotlin.math.ceil(size.width / pxDp).toInt().coerceAtLeast(40)
            val rows = kotlin.math.ceil(size.height / pxDp).toInt().coerceAtLeast(30)
            val floorRow = rows - kotlin.math.ceil(depthPx / pxDp).toInt() + 2
            val key = "$cols:$rows:$floorRow:${phase.key}:${season.name}:${room.encode()}"
            val img = cache.getOrPut(key) { Room.scene(cols, rows, Room.floorLineFor(floorRow), phase, season = season, layout = room).flattenFine().toImageBitmap() }
            drawImage(img, IntOffset.Zero, IntSize(cols * 2, rows * 2), IntOffset(0, (size.height - rows * pxDp).roundToInt()), IntSize((cols * pxDp).roundToInt(), (rows * pxDp).roundToInt()), filterQuality = FilterQuality.None)
        }
        content(floorDepth)
    }
}
