package com.pawpixel.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import com.pawpixel.map.MapStyle
import com.pawpixel.map.VectorFeature
import com.pawpixel.map.VectorTile
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A street or place name to draw crisp over the cartoon: position in tile pixels (0..side), angle in degrees. */
class MapLabel(val text: String, val x: Float, val y: Float, val angle: Float, val big: Boolean)

/**
 * Draws PawPixel's cartoon map from OpenMapTiles vector data (what OpenFreeMap serves): water,
 * parks, blocks, buildings and roads in the app's candy colours, painted at a low resolution
 * without smoothing, so that scaled up on screen it looks like the pet's room: chunky pixels.
 * Street and place names come back as [MapLabel]s and are drawn separately, sharp.
 *
 * Tiles only go down to zoom 14, so a closer zoom draws a quarter (or a sixteenth...) of the
 * zoom-14 tile, scaled up: [subX], [subY] and [subLevels] say which part.
 */
object CartoonTiles {
    /** The cartoon is painted this many map pixels across per tile, then scaled to the screen tile. */
    const val SIDE = 128

    class Drawn(val image: ImageBitmap, val labels: List<MapLabel>)

    fun draw(tile: VectorTile, zoom: Int, subX: Int = 0, subY: Int = 0, subLevels: Int = 0): Drawn {
        val image = ImageBitmap(SIDE, SIDE)
        val canvas = Canvas(image)
        val labels = ArrayList<MapLabel>()
        val fill = Paint().apply { isAntiAlias = false; style = PaintingStyle.Fill }
        val stroke = Paint().apply { isAntiAlias = false; style = PaintingStyle.Stroke; strokeCap = StrokeCap.Round; strokeJoin = StrokeJoin.Round }
        val factor = 1 shl subLevels
        // Everything the camera sees: more detail the closer the zoom.
        val detail = zoom + 0

        fun layer(name: String) = tile.layers[name]
        fun scale(extent: Int) = SIDE.toFloat() * factor / extent
        fun ox(extent: Int) = -subX * SIDE.toFloat()
        fun oy(extent: Int) = -subY * SIDE.toFloat()

        fun path(feature: VectorFeature, extent: Int): Path {
            val s = scale(extent); val dx = ox(extent); val dy = oy(extent)
            val p = Path().apply { fillType = PathFillType.EvenOdd }
            for (part in feature.geometry) {
                if (part.size < 4) continue
                p.moveTo(part[0] * s + dx, part[1] * s + dy)
                var i = 2
                while (i + 1 < part.size) { p.lineTo(part[i] * s + dx, part[i + 1] * s + dy); i += 2 }
                if (feature.isPolygon) p.close()
            }
            return p
        }

        fun fillLayer(name: String, color: (VectorFeature) -> Int?) {
            val l = layer(name) ?: return
            for (f in l.features) {
                if (!f.isPolygon) continue
                val c = color(f) ?: continue
                fill.color = Color(c)
                canvas.drawPath(path(f, l.extent), fill)
            }
        }
        fun strokeLayer(name: String, pass: (VectorFeature) -> Pair<Int, Float>?) {
            val l = layer(name) ?: return
            for (f in l.features) {
                if (!f.isLine) continue
                val (c, w) = pass(f) ?: continue
                stroke.color = Color(c); stroke.strokeWidth = w
                canvas.drawPath(path(f, l.extent), stroke)
            }
        }

        // Ground: open land, then built-up blocks, greenery, water.
        fill.color = Color(MapStyle.LAND)
        canvas.drawRect(0f, 0f, SIDE.toFloat(), SIDE.toFloat(), fill)
        fillLayer("landuse") { f ->
            when (f.str("class")) {
                "residential", "suburb", "neighbourhood", "commercial", "retail", "industrial" -> BLOCK
                "school", "university", "college", "hospital" -> CAMPUS
                "cemetery", "grass", "park", "pitch", "playground", "garden", "recreation_ground", "stadium" -> MapStyle.PARK
                else -> null
            }
        }
        fillLayer("landcover") { f -> if (f.str("class") == "wood" || f.str("subclass") == "forest") MapStyle.PARK_DEEP else MapStyle.PARK }
        fillLayer("park") { MapStyle.PARK }
        fillLayer("water") { MapStyle.WATER }
        strokeLayer("waterway") { f -> when (f.str("class")) { "river", "canal" -> MapStyle.WATER to 3f * factor.coerceAtMost(2); "stream" -> MapStyle.WATER to 1.5f; else -> null } }

        // Buildings, from zoom 14 up.
        if (detail >= 14) fillLayer("building") { MapStyle.BUILDING }

        // Roads: casing first, then the road on top, widest classes last so they sit over the small ones.
        fun roadWidth(cls: String?): Float = when (cls) {
            "motorway", "trunk" -> 6f
            "primary" -> 5f
            "secondary" -> 4f
            "tertiary" -> 3.5f
            "minor", "service", "raceway" -> 2.5f
            "path", "track", "pedestrian", "footway", "cycleway" -> 1f
            "rail", "transit" -> 1.5f
            else -> 0f
        } * (if (detail >= 16) 1.6f else if (detail >= 15) 1.3f else 1f)
        fun roadColors(cls: String?): Pair<Int, Int>? = when (cls) {
            "motorway", "trunk" -> MapStyle.HIGHWAY to MapStyle.HIGHWAY_EDGE
            "primary", "secondary" -> MapStyle.MAIN_ROAD to MapStyle.MAIN_ROAD_EDGE
            "tertiary", "minor", "service", "raceway" -> MapStyle.ROAD to MapStyle.ROAD_EDGE
            "path", "track", "pedestrian", "footway", "cycleway" -> if (detail >= 15) MapStyle.ROAD_EDGE to MapStyle.ROAD_EDGE else null
            "rail", "transit" -> MapStyle.RAIL to MapStyle.RAIL
            else -> null
        }
        val order = listOf("path", "track", "pedestrian", "footway", "cycleway", "rail", "transit", "service", "minor", "raceway", "tertiary", "secondary", "primary", "trunk", "motorway")
        val roads = layer("transportation")
        if (roads != null) {
            val byClass = roads.features.filter { it.isLine && it.str("brunnel") != "tunnel" }.groupBy { it.str("class") }
            for (pass in 0..1) for (cls in order) {
                val list = byClass[cls] ?: continue
                val colors = roadColors(cls) ?: continue
                val w = roadWidth(cls); if (w <= 0f) continue
                stroke.color = Color(if (pass == 0) colors.second else colors.first)
                stroke.strokeWidth = if (pass == 0) w + 1.5f else w
                for (f in list) canvas.drawPath(path(f, roads.extent), stroke)
            }
        }

        // Names: streets along their longest stretch (zoomed in), places at their point.
        val names = layer("transportation_name")
        if (names != null && detail >= 15) {
            val s = scale(names.extent); val dx = ox(names.extent); val dy = oy(names.extent)
            for (f in names.features) {
                val text = f.str("name") ?: continue
                if (!f.isLine) continue
                val cls = f.str("class")
                if (detail < 16 && cls !in setOf("motorway", "trunk", "primary", "secondary", "tertiary")) continue
                // The longest segment on this tile carries the name.
                var best = 0f; var bx = 0f; var by = 0f; var angle = 0f
                for (part in f.geometry) {
                    var i = 0
                    while (i + 3 < part.size) {
                        val x1 = part[i] * s + dx; val y1 = part[i + 1] * s + dy; val x2 = part[i + 2] * s + dx; val y2 = part[i + 3] * s + dy
                        val len = hypot(x2 - x1, y2 - y1)
                        val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
                        if (len > best && mx in 0f..SIDE.toFloat() && my in 0f..SIDE.toFloat()) {
                            best = len; bx = mx; by = my
                            angle = (atan2((y2 - y1).toDouble(), (x2 - x1).toDouble()) * 180.0 / PI).toFloat()
                            if (angle > 90f) angle -= 180f else if (angle < -90f) angle += 180f
                        }
                        i += 2
                    }
                }
                if (best >= SIDE / 5f) labels += MapLabel(text, bx, by, angle, big = false)
            }
        }
        val places = layer("place")
        if (places != null) {
            val s = scale(places.extent); val dx = ox(places.extent); val dy = oy(places.extent)
            for (f in places.features) {
                val text = f.str("name") ?: continue
                val cls = f.str("class")
                val show = when (cls) {
                    "city", "town" -> true
                    "village", "suburb", "quarter" -> detail >= 13
                    "neighbourhood", "hamlet" -> detail >= 15
                    else -> false
                }
                if (!show || !f.isPoint) continue
                val pt = f.geometry.firstOrNull() ?: continue
                val x = pt[0] * s + dx; val y = pt[1] * s + dy
                if (x in 0f..SIDE.toFloat() && y in 0f..SIDE.toFloat()) labels += MapLabel(text, x, y, 0f, big = cls == "city" || cls == "town")
            }
        }
        return Drawn(image, labels)
    }

    private const val BLOCK = 0xFFF9E6CC.toInt()
    private const val CAMPUS = 0xFFEFE3F4.toInt()

    @Suppress("unused") private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
}
