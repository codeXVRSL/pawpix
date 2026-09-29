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
import com.pawpixel.map.MapArea
import com.pawpixel.map.MapSettings
import com.pawpixel.map.WebMercator
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val MIN_ZOOM = 11
private const val MAX_ZOOM = 17
private val Ink = Color(0xFF2B2135)
private val Berry = Color(0xFFE8374E)
private val Cream = Color(0xFFFFF4E0)
private val Sand = Color(0xFFF6E7CC)

/** 7x6 pixel paw drawn inside each area pin. */
private val PAW = listOf(".#...#.", "##.#.##", "...#...", ".#####.", "#######", ".##.##.")

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
) {
    val density = LocalDensity.current.density
    // Each map pixel is a whole number of screen pixels: crisp pixel-art streets.
    val scale = max(2, density.roundToInt())
    var zoom by remember { mutableIntStateOf(15) }
    var cx by remember { mutableDoubleStateOf(WebMercator.x(centerLng, 15)) }
    var cy by remember { mutableDoubleStateOf(WebMercator.y(centerLat, 15)) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
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

    fun toScreen(lat: Double, lng: Double) = Offset(
        ((WebMercator.x(lng, zoom) - cx) * scale + size.width / 2.0).toFloat(),
        ((WebMercator.y(lat, zoom) - cy) * scale + size.height / 2.0).toFloat(),
    )

    // Load the tiles on screen (plus one ring around), newest first; keep ~150 in memory.
    LaunchedEffect(zoom, cx.toInt() / 128, cy.toInt() / 128, size, settings.tileUrl) {
        if (!settings.hasTiles || size == IntSize.Zero) return@LaunchedEffect
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
                val img = platform.fetchBytes(WebMercator.tileUrl(settings.tileUrl, zoom, x, ty))?.let(::decodeImage)
                pending -= key
                if (img == null) { failed += key; return@launch }
                tiles[key] = img
                order.addLast(key)
                while (order.size > 150) tiles.remove(order.removeFirst())
            }
        }
    }

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize()
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
                .pointerInput(areas) {
                    detectTapGestures(
                        onDoubleTap = { setZoom(zoom + 1) },
                        onTap = { tap ->
                            val hit = areas.minByOrNull { (toScreen(it.lat, it.lng) - tap).getDistance() }
                            if (hit != null && (toScreen(hit.lat, hit.lng) - tap).getDistance() < 28 * density) onAreaTap(hit)
                        },
                    )
                },
        ) {
            drawRect(Sand)
            if (settings.hasTiles) drawTiles(tiles, zoom, cx, cy, scale) else drawPixelGrid(cx, cy, scale)
            myArea?.let { drawMyArea(it, ::toScreen, density) }
            for (a in areas) drawPin(toScreen(a.lat, a.lng), a.pets, density, text)
        }

        Column(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            MapButton("+") { setZoom(zoom + 1) }
            MapButton("−") { setZoom(zoom - 1) }
            MapButton("◎") { recenter() }
        }
        val credit = if (settings.hasTiles) settings.tileAttribution else "Street map not set up in this build"
        if (credit.isNotBlank()) {
            Text(
                credit, style = MaterialTheme.typography.labelSmall, color = Ink,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(bottom = 4.dp).size(44.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

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
