package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pawpixel.app.toImageBitmap
import com.pawpixel.core.AppState
import com.pawpixel.core.Milestones
import com.pawpixel.core.Pet
import com.pawpixel.core.Sky
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.Furniture
import com.pawpixel.sprite.Placed
import com.pawpixel.sprite.PixelIcons
import com.pawpixel.sprite.Room
import com.pawpixel.sprite.RoomLayout
import com.pawpixel.sprite.RoomTheme
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Decorate mode: the room, big, with its grid showing; tap a piece to pick it up, drag it to a tile
 * (a green ghost where it fits, red where it doesn't), flip it or put it away; a drawer of every
 * piece along the bottom, the ones not yet earned with the day they arrive. Every change is saved
 * at once (and shared with the household), with Undo and "Put everything back" always at hand.
 * No currency, no timers: the furniture is earned with days of care, like the outfits.
 */

@Composable
fun DecorateScreen(app: AppScope, state: AppState, pet: Pet) {
    val phase = phaseFor(app, state)
    var layout by remember(pet.id) { mutableStateOf(pet.layout) }
    val undo = remember(pet.id) { ArrayDeque<RoomLayout>() }
    var selected by remember(pet.id) { mutableStateOf<Placed?>(null) }
    val haptics = LocalHapticFeedback.current
    val earned = remember(pet.careDays.size) { Milestones.furniture(pet) }
    val days = Milestones.caredDays(pet)

    // What the pet makes of each change, said in a bubble over the room for a moment.
    var remark by remember { mutableStateOf<String?>(null) }
    var remarkAt by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(remarkAt) { if (remark != null) { kotlinx.coroutines.delay(3500); remark = null } }
    fun say(text: String) { remark = text; remarkAt++ }

    fun commit(next: RoomLayout, keep: Placed? = null) {
        if (next == layout) return
        undo.addLast(layout)
        if (undo.size > 30) undo.removeFirst()
        val before = layout
        layout = next
        selected = keep
        app.launch { app.repo.decorate(pet, next) }
        val added = next.items.filter { n -> before.items.none { it === n } }
        val gone = before.items.filter { o -> next.items.none { it === o } }
        when {
            next == RoomLayout.starter(next.theme) && next.theme == before.theme -> say(tr("{0} looks around: everything is where it was.", pet.name))
            next.theme != before.theme -> Unit
            gone.any { it.item == Furniture.BED } && added.none { it.item == Furniture.BED } -> say(tr("{0} will miss that bed.", pet.name))
            gone.any { it.item == Furniture.BOWL } && added.none { it.item == Furniture.BOWL } -> say(tr("{0} looks for the bowl.", pet.name))
            added.size == 1 && gone.size == 1 && added[0].item == gone[0].item -> say(tr("{0} likes the {1} there.", pet.name, tr(added[0].item.label).lowercase()))
            added.size == 1 && gone.isEmpty() -> say(tr("{0} sniffs the new {1}.", pet.name, tr(added[0].item.label).lowercase()))
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().background(Color(Room.floorColor(phase)))) {
        // Top: back, the title, undo and reset.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(PixelIcons.CHEVRON_LEFT, tr("Back")) { app.back() }
            Spacer(Modifier.weight(1f))
            GhostPill(tr("Undo"), enabled = undo.isNotEmpty()) { undo.removeLastOrNull()?.let { prev -> layout = prev; selected = null; app.launch { app.repo.decorate(pet, prev) } } }
            GhostPill(tr("Put everything back"), enabled = layout != RoomLayout.starter(layout.theme)) { commit(RoomLayout.starter(layout.theme)) }
        }

        // The room, with the grid showing, and the pet's remark over it.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RoomEditor(
                layout, selected, phase, Modifier.fillMaxSize(),
                onSelect = { selected = it },
                onMove = { from, to -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); commit(layout.replace(from, to), keep = to) },
            )
            remark?.let { SpeechBubble(it, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) }
        }

        // The selected piece's tools, or a hint.
        val sel = selected
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            if (sel == null) {
                Text(tr("Tap a piece to pick it up, then drag it where you like."), style = MaterialTheme.typography.labelMedium, color = PawColors.Ink, textAlign = TextAlign.Center)
            } else {
                ToyPanel(face = Paw.palette.sticker, lip = PawColors.StickerEdge, shape = Pill, padding = 0.dp) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr(sel.item.label), style = MaterialTheme.typography.labelLarge, color = PawColors.Ink)
                        TonalPill(tr("Flip")) { val flipped = sel.copy(flip = !sel.flip); commit(layout.replace(sel, flipped), keep = flipped) } // the same instance: the tools find it again
                        TonalPill(tr("Put away")) { commit(layout.without(sel)) }
                        PrimaryPill(tr("Done")) { selected = null }
                    }
                }
            }
        }

        // The drawer: the room to be in, then every piece that fits it, earned ones first.
        Column(Modifier.fillMaxWidth().background(Paw.palette.sticker).navigationBarsPadding().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(RoomTheme.entries, key = { it.name }) { t ->
                    ChoiceChip(layout.theme == t, {
                        if (layout.theme != t) {
                            // A room the owner hasn't arranged yet starts furnished; one they have keeps what fits.
                            val next = if (layout.items.isEmpty() || layout == RoomLayout.starter(layout.theme)) RoomLayout.starter(t) else layout.inTheme(t)
                            commit(next); say(tr("{0} looks around the {1}.", pet.name, tr(t.label).lowercase()))
                        }
                    }, tr(t.label))
                }
            }
            Text(tr("{0} earns furniture with days of care. Nothing to buy.", pet.name), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF6E6287))
            // Earned pieces not yet in the room first (something new to try), then the placed ones, then the locked ones by the day they arrive.
            // Earned before locked, not yet placed before placed, the room's own pieces before the ones every room shares, then by when they unlock.
            val pieces = Furniture.entries.filter { layout.allows(it) }.sortedWith(compareBy({ it !in earned }, { layout.items.any { p -> p.item == it } }, { it.theme == null && layout.theme != RoomTheme.COZY }, { it.unlockDays }))
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pieces, key = { it.name }) { item ->
                    val have = layout.items.count { it.item == item }
                    val unlocked = item in earned
                    val daysLeft = item.unlockDays - days
                    // Where one more would go, if it fits the room and the layout's code (which the save file and the server cap).
                    val spot = if (unlocked && have < item.maxInRoom) layout.freeSpot(item)?.takeIf { layout.with(Placed(item, it.first, it.second)).fits() } else null
                    val label = when {
                        !unlocked -> tr("{0} · in {1} days", tr(item.label), daysLeft)
                        spot == null -> tr("{0} · all placed", tr(item.label))
                        else -> tr("Add {0}", tr(item.label))
                    }
                    CataloguePiece(item, phase, layout.theme, enabled = spot != null, daysLeft = if (unlocked) null else daysLeft, count = have, label = label) {
                        if (spot == null) return@CataloguePiece
                        val placed = Placed(item, spot.first, spot.second)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        commit(layout.with(placed), keep = placed)
                    }
                }
            }
        }
    }
}

/** How many of a piece a room takes: one bed, bowl, window or clock; a few of anything else. */
private val Furniture.maxInRoom: Int get() = when (this) { Furniture.BED, Furniture.BOWL, Furniture.WINDOW, Furniture.CLOCK -> 1; else -> 3 }

/** One piece in the drawer: a little picture of it in the room, its count, and a lock until it's earned ([daysLeft] until then). */
@Composable
private fun CataloguePiece(item: Furniture, phase: Sky.Phase, theme: RoomTheme, enabled: Boolean, daysLeft: Int?, count: Int, label: String, onClick: () -> Unit) {
    val locked = daysLeft != null
    val thumb = remember(item, phase, theme) { catalogueThumb(item, phase, theme).toImageBitmap() }
    Pressable(
        Modifier.semantics { contentDescription = label }, face = if (locked) Color(0xFFF3EEE8) else Color.White, lip = PawColors.StickerEdge, outline = PawColors.StickerEdge,
        shape = RoundedCornerShape(14.dp), role = Role.Button, enabled = enabled, onClickLabel = label, onClick = if (enabled) onClick else null,
        contentPadding = PaddingValues(6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(64.dp, 52.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    // The thumbnail holds two fine pixels per room pixel: a whole number of screen pixels per fine pixel.
                    val scale = floor(minOf(size.width / thumb.width, size.height / thumb.height)).coerceAtLeast(1f)
                    val w = (thumb.width * scale).roundToInt(); val h = (thumb.height * scale).roundToInt()
                    drawImage(thumb, IntOffset.Zero, IntSize(thumb.width, thumb.height), IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()), IntSize(w, h), filterQuality = FilterQuality.None, alpha = if (locked) 0.45f else 1f)
                }
                if (locked) Box(Modifier.align(Alignment.TopEnd).size(18.dp).background(Color.White, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { PixelIcon(PixelIcons.LOCK, tint = PawColors.Ink, size = 10.dp) }
                else if (count > 0) Box(Modifier.align(Alignment.TopEnd).size(18.dp).background(PawColors.Coral, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) { Text("$count", style = MaterialTheme.typography.labelSmall, color = Color.White) }
            }
            Text(
                if (daysLeft != null) tr("in {0} days", daysLeft) else tr(item.label),
                style = MaterialTheme.typography.labelSmall, color = if (locked) Color(0xFF6E6287) else PawColors.Ink, maxLines = 1,
            )
        }
    }
}

/** The empty drawer room per phase and theme: every thumbnail is cut against the same one. */
private val emptyThumbRooms = HashMap<Pair<Sky.Phase, RoomTheme>, com.pawpixel.sprite.PixelImage>()

/** The piece alone, cut out of a room, for the drawer. */
private fun catalogueThumb(item: Furniture, phase: Sky.Phase, theme: RoomTheme): com.pawpixel.sprite.PixelImage {
    val roomW = RoomLayout.COLS * Room.TILE_W + 20; val h = 64
    val x = 2; val y = if (item.onWall) 1 else 1
    // Cut from the fine picture (twice the resolution), so the thumbnail is as fine as the room.
    val scene = Room.scene(roomW, h, Room.floorLineFor(h - 10), phase, layout = RoomLayout(listOf(Placed(item, x, y)), theme))
    val with = scene.flattenFine()
    val fw = with.width; val fh = with.height
    var l = fw; var t = fh; var r = -1; var b = -1
    val standing = scene.props.firstOrNull()
    if (standing != null) {
        // A standing piece is cut to its own drawing (a lamp's light pool would otherwise widen the thumbnail to most of the room).
        l = standing.x * Room.FINE; t = standing.y * Room.FINE; r = l + standing.fine.width - 1; b = t + standing.fine.height - 1
    } else {
        val without = emptyThumbRooms.getOrPut(phase to theme) { Room.scene(roomW, h, Room.floorLineFor(h - 10), phase, layout = RoomLayout(emptyList(), theme)).flattenFine() }
        for (yy in 0 until fh) for (xx in 0 until fw) if (with[xx, yy] != without[xx, yy]) { if (xx < l) l = xx; if (xx > r) r = xx; if (yy < t) t = yy; if (yy > b) b = yy }
    }
    if (r < 0) return com.pawpixel.sprite.PixelImage(8, 8)
    l = (l - 4).coerceAtLeast(0); t = (t - 4).coerceAtLeast(0); r = (r + 4).coerceAtMost(fw - 1); b = (b + 4).coerceAtMost(fh - 1)
    val out = com.pawpixel.sprite.PixelImage(r - l + 1, b - t + 1)
    for (yy in t..b) for (xx in l..r) out[xx - l, yy - t] = with[xx, yy]
    return out
}

/**
 * The room in Decorate mode: the scene at a fixed scale with its grid drawn over the floor and the
 * wall, the selected piece outlined, and a green or red ghost while a piece is dragged.
 */
@Composable
private fun RoomEditor(layout: RoomLayout, selected: Placed?, phase: Sky.Phase, modifier: Modifier, onSelect: (Placed?) -> Unit, onMove: (Placed, Placed) -> Unit) {
    var drag by remember { mutableStateOf<Drag?>(null) }
    val cache = remember { HashMap<String, Drawn>() }
    val ghosts = remember { HashMap<Placed, ImageBitmap>() }
    val layoutState by androidx.compose.runtime.rememberUpdatedState(layout)
    val selectedState by androidx.compose.runtime.rememberUpdatedState(selected)
    val desc = tr("The room: {0} pieces of furniture", layout.items.size)
    // The lit scene is built off the main thread after every change (a few dozen milliseconds of pixel work);
    // until the new one is ready the previous picture stays up, so a drop never stutters the editor.
    var size by remember { mutableStateOf(IntSize.Zero) }
    var drawn by remember { mutableStateOf<Drawn?>(null) }
    androidx.compose.runtime.LaunchedEffect(layout, phase, size) {
        val d = cache[drawnKey(layout, phase, size.width, size.height)]
            ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { drawnFor(cache, layout, phase, size.width, size.height) }
        if (d != null) drawn = d
    }
    Canvas(
        modifier.semantics { contentDescription = desc }
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val d = drawn ?: return@detectTapGestures
                    onSelect(d.hit(tap))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { start ->
                        val d = drawn ?: return@detectDragGestures
                        val hit = d.hit(start) ?: return@detectDragGestures
                        onSelect(hit)
                        val (gx, gy) = d.cell(start, hit.item.onWall) ?: (hit.x to hit.y)
                        drag = Drag(hit, gx - hit.x, gy - hit.y, hit, true, start)
                    },
                    onDrag = { change, _ ->
                        val cur = drag ?: return@detectDragGestures
                        val d = drawn ?: return@detectDragGestures
                        val cell = d.cell(change.position, cur.piece.item.onWall)
                        val ghost = if (cell == null) cur.ghost else cur.piece.copy(x = cell.first - cur.grabX, y = cell.second - cur.grabY)
                        drag = cur.copy(ghost = ghost, valid = cell != null && layoutState.canPlace(ghost.item, ghost.x, ghost.y, except = cur.piece), at = change.position)
                    },
                    onDragEnd = {
                        val cur = drag; drag = null
                        if (cur != null && cur.valid && (cur.ghost.x != cur.piece.x || cur.ghost.y != cur.piece.y)) onMove(cur.piece, cur.ghost)
                    },
                    onDragCancel = { drag = null },
                )
            },
    ) {
        val d = drawn ?: return@Canvas
        val px = d.px.toFloat()
        // The bitmaps hold two fine pixels per room pixel (PixelImage.scale2x), as the live room does.
        drawImage(d.base, IntOffset.Zero, IntSize(d.base.width, d.base.height), IntOffset(d.left, d.top), IntSize((d.base.width * px / 2).roundToInt(), (d.base.height * px / 2).roundToInt()), filterQuality = FilterQuality.None)
        // The grid: tile rows across the floor, columns converging toward the back wall, slot lines on the wall, faint.
        val g = d.geom
        val gridColor = Color(0x3AFFFFFF)
        for (row in 0..g.rows) { val y = d.top + g.tileTop(row) * px; val depth = row.toDouble(); drawLine(gridColor, Offset(d.left + g.x(0.0, depth).toFloat() * px, y), Offset(d.left + g.x(Room.GRID_W, depth).toFloat() * px, y), 1f) }
        for (col in 0..RoomLayout.COLS) { val u = col * Room.TILE_W.toDouble(); drawLine(gridColor, Offset(d.left + g.x(u, 0.0).toFloat() * px, d.top + g.floorLine * px), Offset(d.left + g.x(u, g.rows.toDouble()).toFloat() * px, d.top + g.tileTop(g.rows) * px), 1f) }
        for (slot in 0 until RoomLayout.WALL_SLOTS) { val y = d.top + g.wallBottom(slot) * px; if (y > d.top) drawLine(Color(0x22000000), Offset(d.left + g.backLeft * px, y), Offset(d.left + g.backRight * px, y), 1f) }
        // Standing pieces in painter's order; the dragged one lifted and faded.
        val cur = drag
        for (p in d.props) {
            val lifted = cur != null && p.placed === cur.piece
            drawImage(p.image, IntOffset.Zero, IntSize(p.image.width, p.image.height), IntOffset(d.left + (p.x * px).roundToInt(), d.top + (p.y * px).roundToInt()), IntSize((p.image.width * px / 2).roundToInt(), (p.image.height * px / 2).roundToInt()), filterQuality = FilterQuality.None, alpha = if (lifted) 0.35f else 1f)
        }
        // The selection outline and the ghost.
        val sel = cur?.piece ?: selected
        if (sel != null) footprint(this, d, sel, Color(0xFFD9364F), fill = null)
        if (cur != null) {
            val tint = if (cur.valid) Color(0x5560C080) else Color(0x55E04848)
            footprint(this, d, cur.ghost, if (cur.valid) Color(0xFF2E8B5A) else Color(0xFFD9364F), fill = tint)
            d.prop(cur.ghost)?.let { pr ->
                val img = ghosts.getOrPut(cur.ghost) { pr.fine.toImageBitmap() }
                drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(d.left + (pr.x * px).roundToInt(), d.top + (pr.y * px).roundToInt()), IntSize((img.width * px / 2).roundToInt(), (img.height * px / 2).roundToInt()), filterQuality = FilterQuality.None, alpha = 0.75f)
            }
        }
    }
}

/** A piece being dragged: the one picked up, where the finger took hold of it (in cells), and where it would land. */
private data class Drag(val piece: Placed, val grabX: Int, val grabY: Int, val ghost: Placed, val valid: Boolean, val at: Offset)

/** The scene drawn for the editor at one size: scale, offsets, bitmaps, and hit-testing. */
private class Drawn(val px: Int, val left: Int, val top: Int, val geom: Room.Geometry, val base: ImageBitmap, val props: List<EditorProp>, val layout: RoomLayout, val phase: Sky.Phase) {
    private val sprites = HashMap<Placed, Room.Prop>()
    /** A standing piece as it would be drawn at [p]'s place (unlit: a ghost), or null for a rug or a wall piece. */
    fun prop(p: Placed): Room.Prop? = if (p.item.onWall || p.item.flat) null else sprites.getOrPut(p) { Room.prop(p, geom, Room.palette(phase, layout.theme), phase) }

    /** Screen point to room pixel. */
    private fun room(at: Offset): Pair<Int, Int> = floor((at.x - left) / px).toInt() to floor((at.y - top) / px).toInt()

    /** The floor tile or wall cell under a screen point. */
    fun cell(at: Offset, onWall: Boolean): Pair<Int, Int>? { val (x, y) = room(at); return if (onWall) geom.wallAt(x, y) else geom.tileAt(x, y) }

    /** The piece under a screen point: a standing piece by its drawing (front first), else a rug by its tile, else a wall piece by its cell. */
    fun hit(at: Offset): Placed? {
        val (x, y) = room(at)
        for (p in props.asReversed()) if (x in p.x until p.x + p.w && y in p.y until p.y + p.h) return p.placed
        geom.tileAt(x, y)?.let { (c, r) -> layout.at(c, r, onWall = false)?.let { return it } }
        geom.wallAt(x, y)?.let { (c, s) -> layout.at(c, s, onWall = true)?.let { return it } }
        return null
    }
}

/** A standing piece in the editor: its fine (2x) bitmap, and its place and size in room pixels. */
private class EditorProp(val placed: Placed, val image: ImageBitmap, val x: Int, val y: Int, val w: Int, val h: Int)

/** The editor's drawing of [layout] at a canvas size, cached per layout and size. */
private fun drawnKey(layout: RoomLayout, phase: Sky.Phase, width: Int, height: Int) = "$width:$height:${phase.key}:${layout.encode()}"

private fun drawnFor(cache: HashMap<String, Drawn>, layout: RoomLayout, phase: Sky.Phase, width: Int, height: Int): Drawn? {
    if (width <= 0 || height <= 0) return null
    val key = drawnKey(layout, phase, width, height)
    cache[key]?.let { return it }
    // The grid plus a margin must fit across; the floor's rows plus a margin must fit below the wall.
    val px = (floor(width / 100f).toInt() / 2 * 2).coerceAtLeast(2) // even, so the fine drawing lands on whole pixels
    val roomW = kotlin.math.ceil(width / px.toFloat()).toInt()
    val rows = kotlin.math.ceil(height / px.toFloat()).toInt()
    val floorLine = (rows - RoomLayout.ROWS * Room.TILE_D - 4).coerceAtLeast(20)
    val scene = Room.scene(roomW, rows, floorLine, phase, layout = layout)
    val props = scene.props.sortedWith(compareBy({ it.baseline }, { it.x })).map { EditorProp(it.placed, it.fine.toImageBitmap(), it.x, it.y, it.image.width, it.image.height) }
    val drawn = Drawn(px, (width - roomW * px) / 2, height - rows * px, scene.geom, scene.fineBase.toImageBitmap(), props, layout, phase)
    if (cache.size > 6) cache.clear()
    cache[key] = drawn
    return drawn
}

/** The tiles (or wall cells) a piece covers, outlined and optionally filled: a trapezoid on the floor (the box's perspective), a rectangle on the wall. */
private fun footprint(scope: androidx.compose.ui.graphics.drawscope.DrawScope, d: Drawn, p: Placed, outline: Color, fill: Color?) {
    val px = d.px.toFloat(); val g = d.geom
    val path = androidx.compose.ui.graphics.Path()
    if (p.item.onWall) {
        val bottom = g.wallBottom(p.y)
        val x0 = d.left + g.x(p.x * Room.TILE_W.toDouble(), 0.0).toFloat() * px; val x1 = d.left + g.x((p.x + p.item.cols) * Room.TILE_W.toDouble(), 0.0).toFloat() * px
        val y0 = d.top + (bottom - g.slotStep + 2) * px; val y1 = y0 + g.slotStep * px
        path.moveTo(x0, y0); path.lineTo(x1, y0); path.lineTo(x1, y1); path.lineTo(x0, y1); path.close()
    } else {
        val u0 = p.x * Room.TILE_W.toDouble(); val u1 = (p.x + p.item.cols) * Room.TILE_W.toDouble()
        val back = p.y.toDouble(); val front = (p.y + p.item.rows).toDouble()
        val yb = d.top + g.tileTop(p.y) * px; val yf = d.top + g.tileTop(p.y + p.item.rows) * px
        path.moveTo(d.left + g.x(u0, back).toFloat() * px, yb); path.lineTo(d.left + g.x(u1, back).toFloat() * px, yb)
        path.lineTo(d.left + g.x(u1, front).toFloat() * px, yf); path.lineTo(d.left + g.x(u0, front).toFloat() * px, yf); path.close()
    }
    if (fill != null) scope.drawPath(path, fill)
    scope.drawPath(path, outline, style = Stroke(width = maxOf(2f, px / 2f)))
}
