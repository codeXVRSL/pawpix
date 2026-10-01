package com.pawpixel.app.widget

import android.graphics.Bitmap
import com.pawpixel.core.Sky
import com.pawpixel.sprite.Argb
import com.pawpixel.sprite.PixelImage
import kotlin.random.Random

/**
 * The sky behind the widget's pet, drawn as pixel art in the app's own colours ([Sky]): a gradient
 * with a soft sun by day, a moon and stars at night, and a strip of ground for the pet to stand on.
 * Drawn once per phase and shape, then shared by every widget size (one bitmap in the update).
 */
internal object WidgetSky {
    /** Pixel-art scale: the picture is drawn small and blown up without smoothing, like the pet. */
    private const val SCALE = 3
    private val cache = HashMap<String, Bitmap>()

    /** [aspect] is width over height of the widget cell, coarsely (so a 4x1 strip gets a wide sky). */
    fun bitmap(phase: Sky.Phase, aspect: Float): Bitmap = synchronized(cache) {
        val wide = aspect >= 1.6f
        val tall = aspect <= 0.7f
        val key = "${phase.key}:${if (wide) "wide" else if (tall) "tall" else "square"}"
        cache.getOrPut(key) {
            val w = if (wide) 96 else 48
            val h = if (tall) 80 else 48
            toBitmap(draw(phase, w, h).scaled(SCALE))
        }
    }

    private fun draw(phase: Sky.Phase, w: Int, h: Int): PixelImage {
        val img = PixelImage(w, h)
        val groundTop = h - (h * 0.16).toInt().coerceAtLeast(5)
        // Sky: a vertical gradient, with light ordered dithering so bands never show.
        for (y in 0 until groundTop) {
            val t = y.toDouble() / (groundTop - 1)
            for (x in 0 until w) {
                val d = (((x + y) and 1) - 0.5) * 0.04
                img[x, y] = Argb.mix(phase.top, phase.bottom, (t + d).coerceIn(0.0, 1.0))
            }
        }
        // Ground, with its line, and a few darker tufts of grass.
        for (y in groundTop until h) for (x in 0 until w) img[x, y] = if (y == groundTop) phase.floorLine else phase.floor
        val r = Random(phase.ordinal * 31 + w)
        repeat(w / 6) { val x = r.nextInt(w); val y = groundTop + 2 + r.nextInt((h - groundTop - 2).coerceAtLeast(1)); if (img.inBounds(x, y)) img[x, y] = phase.floorLine }
        if (phase.stars) {
            // Stars, a couple of them twinkling bigger, and a crescent moon top right.
            repeat(w / 3) {
                val x = r.nextInt(w); val y = r.nextInt((groundTop * 0.8).toInt())
                img[x, y] = 0xCCFFF6D5.toInt()
                if (r.nextInt(5) == 0) for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) if (img.inBounds(x + dx, y + dy)) img[x + dx, y + dy] = 0x88FFF6D5.toInt()
            }
            disc(img, w - 11, 8, 5.0, 0xFFFFF1C9.toInt())
            disc(img, w - 9, 7, 4.2, Argb.mix(phase.top, phase.bottom, 0.12))
        } else {
            // A soft sun, warmer at dawn and dusk, with a glow ring.
            val sun = if (phase == Sky.Phase.DAY) 0xFFFFF4C2.toInt() else 0xFFFFD98A.toInt()
            disc(img, w - 10, 8, 6.5, Argb.mix(sun, img[w - 10, 8], 0.55))
            disc(img, w - 10, 8, 4.5, sun)
            // Two small clouds.
            cloud(img, 6, 7, 0xDDFFFFFF.toInt())
            cloud(img, w / 2 - 2, 13, 0xBBFFFFFF.toInt())
        }
        return img
    }

    private fun disc(img: PixelImage, cx: Int, cy: Int, r: Double, color: Int) {
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            val dx = x - cx + 0.5; val dy = y - cy + 0.5
            if (dx * dx + dy * dy <= r * r && img.inBounds(x, y)) img[x, y] = Argb.blend(img[x, y], color)
        }
    }

    private fun cloud(img: PixelImage, x0: Int, y0: Int, color: Int) {
        val rows = listOf("..####..", ".######.", "########")
        rows.forEachIndexed { dy, row -> row.forEachIndexed { dx, c -> if (c == '#' && img.inBounds(x0 + dx, y0 + dy)) img[x0 + dx, y0 + dy] = Argb.blend(img[x0 + dx, y0 + dy], color) } }
    }

    private fun toBitmap(img: PixelImage): Bitmap = Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888)
}
