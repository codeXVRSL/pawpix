package com.pawpixel.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/** Web Mercator maths for 256-pixel map tiles ("world pixels" at a zoom level). */
object WebMercator {
    const val TILE = 256

    fun worldSize(zoom: Int): Double = TILE.toDouble() * (1 shl zoom)

    fun x(lng: Double, zoom: Int): Double = (lng + 180.0) / 360.0 * worldSize(zoom)

    fun y(lat: Double, zoom: Int): Double {
        val r = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * worldSize(zoom)
    }

    fun lng(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360.0 - 180.0

    fun lat(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /** Fills a tile URL template like `https://.../{z}/{x}/{y}.png?key=...`. */
    fun tileUrl(template: String, z: Int, x: Int, y: Int): String =
        template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}
