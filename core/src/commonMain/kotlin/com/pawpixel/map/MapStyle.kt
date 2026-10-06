package com.pawpixel.map

import com.pawpixel.sprite.PixelImage

/**
 * Turns a street-map tile into PawPixel's own cartoon: every pixel is sorted by what it shows
 * (water, greenery, a road, a building, open land) and painted in the app's candy palette, so the
 * real streets of Naga come out looking like the pet's room, not like a sheet of paper. The map
 * engine draws the result with no smoothing at a whole-number scale, which gives it chunky pixels.
 *
 * Works on any light, muted raster style (CARTO Voyager, OpenStreetMap, MapTiler Streets): the
 * sorting goes by hue, saturation and lightness, not by exact colours.
 */
object MapStyle {
    // The cartoon's paints. Same family as the app's rooms and keys (see composeApp Toy.kt).
    const val WATER = 0xFF9BD1F2.toInt()
    const val WATER_EDGE = 0xFF6FB4E0.toInt()
    const val PARK = 0xFFB9E7A4.toInt()
    const val PARK_DEEP = 0xFF8FD47A.toInt()
    const val LAND = 0xFFFFF1DA.toInt()
    const val BUILDING = 0xFFF2DCC2.toInt()
    const val BUILDING_EDGE = 0xFFD9BFA0.toInt()
    const val ROAD = 0xFFFFFBF2.toInt()
    const val ROAD_EDGE = 0xFFE8D6BE.toInt()
    const val MAIN_ROAD = 0xFFFFE08A.toInt()
    const val MAIN_ROAD_EDGE = 0xFFE6B84A.toInt()
    const val HIGHWAY = 0xFFFFB38A.toInt()
    const val HIGHWAY_EDGE = 0xFFE08A5A.toInt()
    const val INK = 0xFF8C7BA8.toInt()
    const val RAIL = 0xFFC9BBD6.toInt()

    /** The cartoon version of [tile] (a new image; the tile is left alone). */
    fun cartoon(tile: PixelImage): PixelImage {
        val out = PixelImage(tile.width, tile.height)
        val px = tile.pixels
        val o = out.pixels
        for (i in px.indices) o[i] = paint(px[i])
        return out
    }

    /** What one map pixel becomes. */
    fun paint(argb: Int): Int {
        val a = argb ushr 24
        if (a < 0x80) return 0 // transparent (label tiles, edges)
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2f
        // Chroma, not HSL saturation: near-white pixels have tiny chroma but wild "saturation".
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f).let { if (it < 0) it + 6f else it }
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return when {
            // Coloured things first: water (blues), greenery (greens), big roads (yellows, oranges, reds).
            d >= 0.08f && h in 175f..260f -> if (l < 0.72f) WATER_EDGE else WATER
            d >= 0.06f && h in 65f..170f -> if (l < 0.70f) PARK_DEEP else PARK
            d >= 0.14f && h in 38f..64f -> if (l < 0.72f) MAIN_ROAD_EDGE else MAIN_ROAD
            d >= 0.14f && (h < 38f || h > 330f) -> if (l < 0.70f) HIGHWAY_EDGE else HIGHWAY
            // Purples: railways and transit lines.
            d >= 0.08f && h in 260f..330f -> RAIL
            // The rest is cream, beige and grey, sorted by brightness: roads are the brightest, then
            // open land, then built-up blocks and buildings, then outlines and text.
            l >= 0.975f -> ROAD
            l >= 0.925f -> LAND
            l >= 0.85f -> BUILDING
            l >= 0.74f -> if (d < 0.03f) BUILDING_EDGE else ROAD_EDGE
            else -> INK
        }
    }
}
