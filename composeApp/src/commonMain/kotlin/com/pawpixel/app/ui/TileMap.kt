package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle

import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawpixel.app.Platform
import com.pawpixel.app.decodeImage
import com.pawpixel.core.LocationGrid
import com.pawpixel.i18n.tr
import com.pawpixel.map.MapArea
import com.pawpixel.map.MapSettings
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.async
import com.pawpixel.core.Json
import com.pawpixel.map.Mvt
import com.pawpixel.map.VectorTile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.pawpixel.app.toImageBitmap
import com.pawpixel.map.MapStyle
import com.pawpixel.map.WebMercator
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val MIN_ZOOM = 11
private const val MAX_ZOOM = 17
private val Ink = PawColors.Ink
private val Berry = Color(0xFFE8374E)
private val LostRed = Color(0xFFD9574A)
private val Cream = Color(0xFFFFF4E0)
private val Sand = Color(0xFFF6E7CC)

/** 7x6 pixel paw drawn inside each area pin. */
private val PAW = listOf(".#...#.", "##.#.##", "...#...", ".#####.", "#######", ".##.##.")
/** 7x6 pixel flag drawn inside each walk pin. */
private val FLAG = listOf("#.....", "#####.", "######", "#####.", "#.....", "#.....")
private val Leaf = Color(0xFF5EA64C)

/** A walk on the map: its ~1 km area (never the venue) and its title. */
data class MapFlag(val lat: Double, val lng: Double, val title: String, val id: String = "")

/** Parsed zoom-14 vector tiles and the tile address, kept for the session so switching tabs doesn't fetch them again. */
object VectorTileCache {
    var template: String? = null
    val tiles = HashMap<String, VectorTile>()
    val order = ArrayDeque<String>()
}

/**
 * A street map drawn the pixel way: 256 px tiles scaled by a whole number with no smoothing, so
 * streets stay crisp and chunky. Areas with 3+ owners get a pixel paw pin with their pet count;
 * your own area is a dashed square. Drag to pan, pinch or +/− to zoom, tap a pin.
 */
@Composable
fun TileMap(
    settings: MapSettings,
    platform: Platform,
    centerLat: Double,
    centerLng: Double,
    areas: List<MapArea>,
    myArea: LocationGrid.Cell?,
    onAreaTap: (MapArea) -> Unit,
    modifier: Modifier = Modifier,
    /** Walks coming up, as flag pins on their areas. */
    walks: List<MapFlag> = emptyList(),
    onWalkTap: (MapFlag) -> Unit = {},
    /** A spot the owner is placing (a walk's meeting place). */
    marker: Pair<Double, Double>? = null,
    /** While set, a tap on the map picks a spot instead of a pin. */
    onMapTap: ((Double, Double) -> Unit)? = null,
    /** Lost pets, as red flags where they were last seen. */
    lost: List<MapFlag> = emptyList(),
    onLostTap: (MapFlag) -> Unit = {},
) {
    val density = LocalDensity.current.density
    // Each map pixel is a whole number of screen pixels: crisp pixel-art streets.
    val scale = max(2, density.roundToInt())
    var zoom by remember { mutableIntStateOf(15) }
    var cx by remember { mutableDoubleStateOf(WebMercator.x(centerLng, 15)) }
    var cy by remember { mutableDoubleStateOf(WebMercator.y(centerLat, 15)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    // Street and place names per screen tile, drawn crisp over the cartoon.
    val tileLabels = remember { mutableStateMapOf<String, List<MapLabel>>() }
    val order = remember { ArrayDeque<String>() }
    val pending = remember { HashSet<String>() }
    val failed = remember { HashSet<String>() }
    val scope = rememberCoroutineScope()
    val text = rememberTextMeasurer()
    var pinch by remember { mutableDoubleStateOf(1.0) }

    fun setZoom(z: Int) {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (nz == zoom) return
        val f = if (nz > zoom) (1 shl (nz - zoom)).toDouble() else 1.0 / (1 shl (zoom - nz))
        cx *= f; cy *= f; zoom = nz
    }

    fun recenter() {
        cx = WebMercator.x(myArea?.centerLng ?: centerLng, zoom)
        cy = WebMercator.y(myArea?.centerLat ?: centerLat, zoom)
    }
    // Vector tiles: the current tile URL from OpenFreeMap's TileJSON, and the parsed zoom-14 tiles.
    var vectorTemplate by remember { mutableStateOf(VectorTileCache.template) }
    val vectorTiles = VectorTileCache.tiles
    val vectorOrder = VectorTileCache.order
    val vectorPending = remember { HashMap<String, kotlinx.coroutines.Deferred<VectorTile?>>() }

    fun toScreen(lat: Double, lng: Double) = Offset(
        ((WebMercator.x(lng, zoom) - cx) * scale + size.width / 2.0).toFloat(),
        ((WebMercator.y(lat, zoom) - cy) * scale + size.height / 2.0).toFloat(),
    )

    LaunchedEffect(settings.usesVectorTiles) {
        if (!settings.usesVectorTiles || vectorTemplate != null) return@LaunchedEffect
        // One small request, once a session, names the tiles' current address ("tiles": ["https://.../{z}/{x}/{y}.pbf"]).
        vectorTemplate = platform.fetchBytes(MapSettings.VECTOR_TILEJSON)?.let { bytes ->
            runCatching { Json.parse(bytes.decodeToString())["tiles"].list.firstOrNull()?.str }.getOrNull()
        }?.takeIf { it.contains("{z}") }
        VectorTileCache.template = vectorTemplate
        if (vectorTemplate == null) platform.log("Map: couldn't read the vector tile address")
    }

    /** A zoom-14 vector tile, fetched and parsed once, shared by every screen tile cut from it. */
    suspend fun vectorTile(template: String, z: Int, x: Int, y: Int): VectorTile? {
        val vkey = "$z/$x/$y"
        vectorTiles[vkey]?.let { return it }
        val job = vectorPending.getOrPut(vkey) {
            scope.async(Dispatchers.Default) {
                val raw = platform.fetchBytes(WebMercator.tileUrl(template, z, x, y)) ?: return@async null
                val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) platform.gunzip(raw) ?: return@async null else raw
                runCatching { Mvt.decode(bytes) }.getOrNull()
            }
        }
        val tile = job.await()
        vectorPending.remove(vkey)
        if (tile != null) {
            vectorTiles[vkey] = tile
            vectorOrder.addLast(vkey)
            while (vectorOrder.size > 40) vectorTiles.remove(vectorOrder.removeFirst())
        }
        return tile
    }

    // Load the tiles on screen (plus one ring around), newest first; keep ~150 in memory.
    LaunchedEffect(zoom, cx.toInt() / 128, cy.toInt() / 128, size, settings.streetTileUrl, vectorTemplate) {
        if (size == IntSize.Zero) return@LaunchedEffect
        val template = if (settings.usesVectorTiles) vectorTemplate ?: return@LaunchedEffect else settings.streetTileUrl
        val n = 1 shl zoom
        val halfW = size.width / 2.0 / scale; val halfH = size.height / 2.0 / scale
        val tx0 = floor((cx - halfW) / 256).toInt() - 1; val tx1 = floor((cx + halfW) / 256).toInt() + 1
        val ty0 = max(0, floor((cy - halfH) / 256).toInt() - 1); val ty1 = minOf(n - 1, floor((cy + halfH) / 256).toInt() + 1)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val x = ((tx % n) + n) % n
            val key = "$zoom/$x/$ty"
            if (key in tiles || key in pending || key in failed) continue
            pending += key
            scope.launch {
                var found: List<MapLabel> = emptyList()
                val img = if (settings.usesVectorTiles) {
                    // Vector tiles end at zoom 14: closer views draw the right part of that tile, bigger.
                    val d = (zoom - MapSettings.VECTOR_MAX_ZOOM).coerceAtLeast(0)
                    val vt = vectorTile(template, zoom - d, x shr d, ty shr d)
                    vt?.let { withContext(Dispatchers.Default) { CartoonTiles.draw(it, zoom, x and ((1 shl d) - 1), ty and ((1 shl d) - 1), d) } }
                        ?.also { found = it.labels }?.image
                } else {
                    // Raster tiles from the build's own provider, repainted as the cartoon (off the main thread).
                    platform.fetchBytes(WebMercator.tileUrl(template, zoom, x, ty))?.let { bytes ->
                        withContext(Dispatchers.Default) { platform.decodePhoto(bytes, 512)?.let { MapStyle.cartoon(it).toImageBitmap() } }
                    }
                }
                pending -= key
                if (img == null) { failed += key; return@launch }
                tiles[key] = img
                tileLabels[key] = found
                order.addLast(key)
                while (order.size > 150) { val old = order.removeFirst(); tiles.remove(old); tileLabels.remove(old) }
            }
        }
    }

    val mapLabel = tr("Map around your area. Drag to move, pinch to zoom.")
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = mapLabel }
                .clipToBounds() // pins near the edge must not draw over the header
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        cx -= pan.x / scale; cy -= pan.y / scale
                        pinch *= gestureZoom
                        if (pinch > 1.6) { setZoom(zoom + 1); pinch = 1.0 }
                        if (pinch < 0.6) { setZoom(zoom - 1); pinch = 1.0 }
                    }
                }
                .pointerInput(areas, walks, lost, onMapTap) {
                    detectTapGestures(
                        onDoubleTap = { setZoom(zoom + 1) },
                        onTap = { tap ->
                            if (onMapTap != null) {
                                val wx = cx + (tap.x - size.width / 2.0) / scale
                                val wy = cy + (tap.y - size.height / 2.0) / scale
                                onMapTap(WebMercator.lat(wy, zoom).coerceIn(-85.0, 85.0), WebMercator.lng(wx, zoom).coerceIn(-180.0, 180.0))
                                return@detectTapGestures
                            }
                            val hit = areas.minByOrNull { (toScreen(it.lat, it.lng) - tap).getDistance() }
                            if (hit != null && (toScreen(hit.lat, hit.lng) - tap).getDistance() < 28 * density) { onAreaTap(hit); return@detectTapGestures }
                            val missing = lost.minByOrNull { (toScreen(it.lat, it.lng) - tap).getDistance() }
                            if (missing != null && (toScreen(missing.lat, missing.lng) - tap).getDistance() < 28 * density) { onLostTap(missing); return@detectTapGestures }
                            val walk = walks.minByOrNull { (toScreen(it.lat, it.lng) - tap).getDistance() }
                            if (walk != null && (toScreen(walk.lat, walk.lng) - tap).getDistance() < 28 * density) onWalkTap(walk)
                        },
                    )
                },
        ) {
            drawRect(Color(MapStyle.LAND))
            if (tiles.isEmpty()) drawPixelGrid(cx, cy, scale)
            drawTiles(tiles, zoom, cx, cy, scale)
            drawLabels(tileLabels, zoom, cx, cy, scale, density, text)
            myArea?.let { drawMyArea(it, ::toScreen, density) }
            // Walks sit a little to the right of the area pin, so both stay tappable in a shared area.
            for (w in walks) drawFlag(toScreen(w.lat, w.lng) + Offset(26 * density, 0f), density)
            for (a in areas) drawPin(toScreen(a.lat, a.lng), a.pets, density, text)
            for (l in lost) drawLostFlag(toScreen(l.lat, l.lng), density)
            marker?.let { (lat, lng) -> drawMarker(toScreen(lat, lng), density) }
        }
        val lostLabel = tr("Show the lost pet")
        for (l in lost) {
            val label = tr("Lost pet: {0}", l.title)
            Box(
                Modifier
                    .offset {
                        val p = toScreen(l.lat, l.lng)
                        val half = 24.dp.roundToPx()
                        IntOffset(p.x.roundToInt() - half, p.y.roundToInt() - 2 * half)
                    }
                    .size(48.dp)
                    .semantics { contentDescription = label; onClick(label = lostLabel) { onLostTap(l); true } },
            )
        }
        val walkLabel = tr("Show walks")
        for (w in walks) {
            val label = tr("Walk: {0}", w.title)
            Box(
                Modifier
                    .offset {
                        val p = toScreen(w.lat, w.lng)
                        val half = 24.dp.roundToPx()
                        IntOffset(p.x.roundToInt() - half + (26 * density).roundToInt(), p.y.roundToInt() - 2 * half)
                    }
                    .size(48.dp)
                    .semantics { contentDescription = label; onClick(label = walkLabel) { onWalkTap(w); true } },
            )
        }

        // The pins, for screen readers: drawn on the map above, listed here where they are, each
        // opening its pets. Semantics only, so touches still reach the map (drag, pinch, tap a pin).
        val showPets = tr("Show pets")
        for (a in areas) {
            val label = if (myArea?.id == a.cellId) tr("Your area: {0} pets", a.pets) else tr("An area near you: {0} pets", a.pets)
            Box(
                Modifier
                    .offset {
                        val p = toScreen(a.lat, a.lng)
                        val half = 24.dp.roundToPx()
                        IntOffset(p.x.roundToInt() - half, p.y.roundToInt() - 2 * half)
                    }
                    .size(48.dp)
                    .semantics { contentDescription = label; onClick(label = showPets) { onAreaTap(a); true } },
            )
        }

        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            MapButton("+", tr("Zoom in")) { setZoom(zoom + 1) }
            MapButton("−", tr("Zoom out")) { setZoom(zoom - 1) }
            MapButton("◎", tr("Back to your area")) { recenter() }
        }
        val credit = settings.streetAttribution
        if (credit.isNotBlank()) {
            Text(
                credit, style = MaterialTheme.typography.labelSmall, color = Ink,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun MapButton(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.padding(bottom = 4.dp).size(48.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp), shape = Pill,
        // Readable over any street map, light or dark mode.
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = Cream, contentColor = Ink),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

/** The cartoon tiles, whole pixels with no smoothing. */
private fun DrawScope.drawTiles(tiles: Map<String, ImageBitmap>, zoom: Int, cx: Double, cy: Double, scale: Int) {
    val n = 1 shl zoom
    val halfW = size.width / 2.0 / scale; val halfH = size.height / 2.0 / scale
    val tx0 = floor((cx - halfW) / 256).toInt(); val tx1 = floor((cx + halfW) / 256).toInt()
    val ty0 = max(0, floor((cy - halfH) / 256).toInt()); val ty1 = minOf(n - 1, floor((cy + halfH) / 256).toInt())
    val tilePx = 256 * scale
    for (ty in ty0..ty1) for (tx in tx0..tx1) {
        val img = tiles["$zoom/${((tx % n) + n) % n}/$ty"] ?: continue
        val left = ((tx * 256.0 - cx) * scale + size.width / 2).roundToInt()
        val top = ((ty * 256.0 - cy) * scale + size.height / 2).roundToInt()
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(left, top), IntSize(tilePx, tilePx),
            filterQuality = FilterQuality.None)
    }
}

/** Street and place names over the cartoon, each once, with a cream halo so they read on any paint. */
private fun DrawScope.drawLabels(
    labels: Map<String, List<MapLabel>>, zoom: Int, cx: Double, cy: Double, scale: Int, density: Float, text: androidx.compose.ui.text.TextMeasurer,
) {
    val n = 1 shl zoom
    val halfW = size.width / 2.0 / scale; val halfH = size.height / 2.0 / scale
    val tx0 = floor((cx - halfW) / 256).toInt(); val tx1 = floor((cx + halfW) / 256).toInt()
    val ty0 = max(0, floor((cy - halfH) / 256).toInt()); val ty1 = minOf(n - 1, floor((cy + halfH) / 256).toInt())
    val tilePx = 256f * scale
    val seen = HashSet<String>()
    for (ty in ty0..ty1) for (tx in tx0..tx1) {
        val list = labels["$zoom/${((tx % n) + n) % n}/$ty"] ?: continue
        val left = ((tx * 256.0 - cx) * scale + size.width / 2).toFloat()
        val top = ((ty * 256.0 - cy) * scale + size.height / 2).toFloat()
        for (l in list) {
            if (!seen.add(l.text)) continue
            val px = left + l.x * tilePx / CartoonTiles.SIDE; val py = top + l.y * tilePx / CartoonTiles.SIDE
            val measured = text.measure(l.text, TextStyle(color = Ink, fontSize = if (l.big) 14.sp else 11.sp, fontWeight = FontWeight.Black))
            val halo = text.measure(l.text, TextStyle(color = Cream, fontSize = if (l.big) 14.sp else 11.sp, fontWeight = FontWeight.Black))
            val at = Offset(px - measured.size.width / 2f, py - measured.size.height / 2f)
            rotate(l.angle, pivot = Offset(px, py)) {
                val h = density.coerceAtLeast(1f)
                for ((ox, oy) in listOf(-h to 0f, h to 0f, 0f to -h, 0f to h)) drawText(halo, topLeft = at + Offset(ox, oy))
                drawText(measured, topLeft = at)
            }
        }
    }
}

/** Without a tile provider: a plain pixel grid, so the map still works (pins, your area). */
private fun DrawScope.drawPixelGrid(cx: Double, cy: Double, scale: Int) {
    val step = 32.0 * scale
    val ox = (-(cx * scale) % step + size.width / 2 % step).toFloat()
    val oy = (-(cy * scale) % step + size.height / 2 % step).toFloat()
    var x = ox - step.toFloat()
    while (x < size.width) { drawRect(Color(0x22000000), Offset(x, 0f), Size(scale.toFloat(), size.height)); x += step.toFloat() }
    var y = oy - step.toFloat()
    while (y < size.height) { drawRect(Color(0x22000000), Offset(0f, y), Size(size.width, scale.toFloat())); y += step.toFloat() }
}

/** Your ~1 km square, dashed, so it's clear the map only knows your rough area. */
private fun DrawScope.drawMyArea(cell: LocationGrid.Cell, toScreen: (Double, Double) -> Offset, density: Float) {
    val latStep = cell.cellKm / 111.32
    val lngStep = latStep / max(cos(cell.centerLat * PI / 180.0), 0.01)
    val tl = toScreen(cell.centerLat + latStep / 2, cell.centerLng - lngStep / 2)
    val br = toScreen(cell.centerLat - latStep / 2, cell.centerLng + lngStep / 2)
    drawRect(Berry.copy(alpha = 0.12f), tl, Size(br.x - tl.x, br.y - tl.y))
    drawRect(Berry, tl, Size(br.x - tl.x, br.y - tl.y),
        style = Stroke(width = 2 * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * density, 6 * density))))
}

/** A walk's pin: a leaf-green box with a flag, and a pointer below. */
private fun DrawScope.drawFlag(at: Offset, density: Float) {
    val px = (2 * density).roundToInt().toFloat().coerceAtLeast(2f)
    val w = px * 9; val h = px * 9
    val left = (at.x - w / 2).roundToInt().toFloat(); val top = (at.y - h - px * 3).roundToInt().toFloat()
    drawRect(Ink, Offset(left - px, top - px), Size(w + 2 * px, h + 2 * px))
    drawRect(Leaf, Offset(left, top), Size(w, h))
    drawRect(Color(0xFF9BD98A), Offset(left, top), Size(w, px))
    for ((r, row) in FLAG.withIndex()) for ((c, ch) in row.withIndex()) {
        if (ch == '#') drawRect(Cream, Offset(left + px * (1.5f + c), top + px * (1.5f + r)), Size(px, px))
    }
    drawRect(Ink, Offset(at.x - px * 1.5f, top + h + px), Size(px * 3, px * 2))
    drawRect(Leaf, Offset(at.x - px / 2, top + h), Size(px, px * 2))
}

/** A lost pet's pin: a coral box with a "!" where the pet was last seen. */
private fun DrawScope.drawLostFlag(at: Offset, density: Float) {
    val px = (2 * density).roundToInt().toFloat().coerceAtLeast(2f)
    val w = px * 9; val h = px * 9
    val left = (at.x - w / 2).roundToInt().toFloat(); val top = (at.y - h - px * 3).roundToInt().toFloat()
    drawRect(Ink, Offset(left - px, top - px), Size(w + 2 * px, h + 2 * px))
    drawRect(LostRed, Offset(left, top), Size(w, h))
    drawRect(Color(0xFFFFB3A8), Offset(left, top), Size(w, px))
    // "!"
    drawRect(Cream, Offset(left + px * 4, top + px * 2), Size(px, px * 4))
    drawRect(Cream, Offset(left + px * 4, top + px * 7), Size(px, px))
    drawRect(Ink, Offset(at.x - px * 1.5f, top + h + px), Size(px * 3, px * 2))
    drawRect(LostRed, Offset(at.x - px / 2, top + h), Size(px, px * 2))
}

/** The spot being placed: a berry cross with a dot, exactly where the tap was. */
private fun DrawScope.drawMarker(at: Offset, density: Float) {
    val px = (2 * density).roundToInt().toFloat().coerceAtLeast(2f)
    drawRect(Ink, Offset(at.x - px * 6, at.y - px), Size(px * 12, px * 2))
    drawRect(Ink, Offset(at.x - px, at.y - px * 6), Size(px * 2, px * 12))
    drawRect(Berry, Offset(at.x - px * 5, at.y - px / 2), Size(px * 10, px))
    drawRect(Berry, Offset(at.x - px / 2, at.y - px * 5), Size(px, px * 10))
    drawRect(Cream, Offset(at.x - px, at.y - px), Size(px * 2, px * 2))
}

/** A chunky pixel pin: outlined berry box with a paw and the pet count, and a little pointer below. */
private fun DrawScope.drawPin(at: Offset, pets: Int, density: Float, text: androidx.compose.ui.text.TextMeasurer) {
    val px = (2 * density).roundToInt().toFloat().coerceAtLeast(2f)   // one "pixel" of the pin
    val label = text.measure(pets.toString(), TextStyle(color = Cream, fontSize = 13.sp, fontWeight = FontWeight.Black))
    val w = px * 10 + label.size.width
    val h = px * 11
    val left = (at.x - w / 2).roundToInt().toFloat(); val top = (at.y - h - px * 3).roundToInt().toFloat()
    drawRect(Ink, Offset(left - px, top - px), Size(w + 2 * px, h + 2 * px))
    drawRect(Berry, Offset(left, top), Size(w, h))
    drawRect(Color(0xFFFF6B7E), Offset(left, top), Size(w, px))            // highlight
    for ((r, row) in PAW.withIndex()) for ((c, ch) in row.withIndex()) {
        if (ch == '#') drawRect(Cream, Offset(left + px * (1 + c), top + px * (2 + r) + px / 2), Size(px, px))
    }
    drawText(label, topLeft = Offset(left + px * 9, top + (h - label.size.height) / 2))
    // pointer
    drawRect(Ink, Offset(at.x - px * 1.5f, top + h + px), Size(px * 3, px * 2))
    drawRect(Berry, Offset(at.x - px / 2, top + h), Size(px, px * 2))
}
