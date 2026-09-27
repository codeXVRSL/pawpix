package com.pawpixel.sprite

import com.pawpixel.core.Mood
import kotlin.math.roundToInt

/**
 * Builds one image per [Mood] from the pet sprite. Moods are shown with posture (hop, droop),
 * lighting (night tint, grey when sad) and small original pixel icons, never by redrawing the
 * pet's face, so every pose stays faithful to the real pet.
 */
object Poses {
    /** Icons and margins grow with the sprite so effects stay readable on a widget. */
    fun iconScale(sprite: PixelImage) = if (sprite.width >= 36) 2 else 1

    fun canvasSize(sprite: PixelImage): Pair<Int, Int> {
        val s = iconScale(sprite)
        return (sprite.width + 2 * 7 * s) to (sprite.height + 10 * s + 3)
    }

    /** All poses; [eyesClosed] (if the owner marked the eyes) is used for the sleeping pose. */
    fun renderAll(sprite: PixelImage, eyesClosed: PixelImage? = null): Map<Mood, PixelImage> =
        Mood.entries.associateWith { render(if (it == Mood.SLEEPY && eyesClosed != null) eyesClosed else sprite, it) }

    fun render(sprite: PixelImage, mood: Mood): PixelImage {
        val s = iconScale(sprite)
        val (w, h) = canvasSize(sprite)
        val canvas = PixelImage(w, h)
        val baseX = 7 * s
        val baseY = 10 * s
        val lift = when (mood) { Mood.HAPPY -> -2 * s; Mood.SAD -> s; else -> 0 }

        drawShadow(canvas, sprite, baseX, h - 3, if (mood == Mood.HAPPY) 0.6 else 0.8)
        val body = when (mood) {
            Mood.SLEEPY -> tint(sprite, 0xFF1D2B53.toInt(), 0.35)
            Mood.SAD -> desaturate(sprite, 0.65, 0.85)
            Mood.NEEDS_MEDS -> desaturate(sprite, 0.3, 0.95)
            Mood.HAPPY -> brighten(sprite, 1.05)
            else -> sprite
        }
        canvas.draw(body, baseX, baseY + lift)

        fun icon(img: PixelImage) = img.scaled(s)
        fun atRight(img: PixelImage, fromRight: Int, y: Int) { val i = icon(img); canvas.draw(i, w - 1 - fromRight * s - i.width, y * s) }
        fun atBottom(img: PixelImage, x: Int, right: Boolean) {
            val i = icon(img)
            canvas.draw(i, if (right) w - 1 - x * s - i.width else x * s, h - i.height)
        }
        when (mood) {
            Mood.HAPPY -> {
                atRight(Icons.HEART, 0, 0)
                atRight(Icons.HEART_SMALL, 8, 4)
                canvas.draw(icon(Icons.SPARKLE), s, 3 * s)
            }
            Mood.HUNGRY -> {
                atBottom(Icons.BOWL, 0, right = true)
                atRight(Icons.QUESTION, 0, 0)
            }
            Mood.RESTLESS -> {
                atBottom(Icons.BALL, 0, right = false)
                atRight(Icons.EXCLAIM, 0, 0)
                atRight(Icons.EXCLAIM, 3, 0)
            }
            Mood.NEEDS_MEDS -> atRight(Icons.PILL, 0, 1)
            Mood.SLEEPY -> {
                atRight(Icons.Z_BIG, 0, 3)
                atRight(Icons.Z_SMALL, 5, 0)
            }
            Mood.SAD -> { val c = icon(Icons.RAIN_CLOUD); canvas.draw(c, (w - c.width) / 2, 0) }
            Mood.CONTENT -> Unit
        }
        return canvas
    }

    private fun drawShadow(canvas: PixelImage, sprite: PixelImage, x0: Int, y: Int, widthFactor: Double) {
        var minX = sprite.width; var maxX = -1
        for (yy in 0 until sprite.height) for (xx in 0 until sprite.width) if (Argb.alpha(sprite[xx, yy]) > 0) {
            if (xx < minX) minX = xx; if (xx > maxX) maxX = xx
        }
        if (maxX < 0) return
        val cx = x0 + (minX + maxX) / 2.0
        val half = (maxX - minX + 1) * widthFactor / 2
        val shadow = 0x40000000
        for (row in 0..1) {
            val hw = if (row == 0) half else half * 0.7
            for (x in (cx - hw).roundToInt()..(cx + hw).roundToInt()) {
                val yy = y + row
                if (canvas.inBounds(x, yy)) canvas[x, yy] = Argb.blend(canvas[x, yy], shadow)
            }
        }
    }

    private fun map(img: PixelImage, f: (Int) -> Int): PixelImage {
        val out = img.copy()
        for (i in out.pixels.indices) if (Argb.alpha(out.pixels[i]) > 0) out.pixels[i] = f(out.pixels[i])
        return out
    }

    fun tint(img: PixelImage, color: Int, t: Double) = map(img) { Argb.mix(it, color, t) }

    fun desaturate(img: PixelImage, amount: Double, light: Double) = map(img) {
        val p = Lab.fromArgb(it)
        Lab(p.l * light, p.a * (1 - amount), p.b * (1 - amount)).toArgb(Argb.alpha(it))
    }

    fun brighten(img: PixelImage, f: Double) = map(img) {
        val p = Lab.fromArgb(it)
        Lab((p.l * f).coerceAtMost(1.0), p.a, p.b).toArgb(Argb.alpha(it))
    }
}

/** Tiny original pixel icons, drawn from text grids. '.' = transparent. */
object Icons {
    private val COLORS = mapOf(
        'o' to 0xFF1A1423.toInt(), // outline
        'r' to 0xFFE8374E.toInt(), // red
        'p' to 0xFFFF8FA3.toInt(), // pink highlight
        'w' to 0xFFFFFFFF.toInt(),
        'g' to 0xFFB8C2CC.toInt(), // light grey
        'c' to 0xFF3E8ED0.toInt(), // bowl blue
        'C' to 0xFF2B6AA3.toInt(), // bowl shade
        'y' to 0xFFF6D743.toInt(), // yellow
        'Y' to 0xFFC9A227.toInt(), // yellow shade
        'd' to 0xFF6FB6FF.toInt(), // rain drop
        'z' to 0xFFE6ECFF.toInt(), // sleepy Z
        'q' to 0xFFFFC857.toInt(), // question mark / exclaim
        'b' to 0xFF8B5A2B.toInt(), // kibble
    )

    fun grid(vararg rows: String): PixelImage {
        val w = rows.maxOf { it.length }
        val img = PixelImage(w, rows.size)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> COLORS[ch]?.let { img[x, y] = it } } }
        return img
    }

    val HEART = grid(
        ".oo.oo.",
        "oprorro",
        "orrrrro",
        ".orrro.",
        "..oro..",
        "...o...",
    )
    val HEART_SMALL = grid(
        "o.o",
        "rrr",
        ".r.",
    )
    val SPARKLE = grid(
        ".y.",
        "ywy",
        ".y.",
    )
    val BOWL = grid(
        "o.......o",
        "occccccco",
        ".oCCCCCo.",
        "..ooooo..",
    )
    val BOWL_FOOD = grid(
        "..bbbbb..",
        "obbbbbbbo",
        "occccccco",
        ".oCCCCCo.",
        "..ooooo..",
    )
    val DROPS = grid(
        "d...d",
        "d...d",
        ".....",
        "..d..",
        "..d..",
    )

    /** Icon for a [EffectKind] from [PetBrain]. */
    fun forEffect(kind: EffectKind): PixelImage = when (kind) {
        EffectKind.HEART -> HEART
        EffectKind.SPARKLE -> SPARKLE
        EffectKind.QUESTION -> QUESTION
        EffectKind.EXCLAIM -> EXCLAIM
        EffectKind.ZZZ -> Z_BIG
        EffectKind.RAIN -> RAIN_CLOUD
        EffectKind.PILL -> PILL
        EffectKind.BOWL -> BOWL_FOOD
        EffectKind.DROPS -> DROPS
    }

    val QUESTION = grid(
        ".qq.",
        "q..q",
        "..q.",
        ".q..",
        "....",
        ".q..",
    )
    val EXCLAIM = grid(
        "q", "q", "q", ".", "q",
    )
    val BALL = grid(
        ".ooo.",
        "oyyyo",
        "owwwo",
        "oyYyo",
        ".ooo.",
    )
    val PILL = grid(
        ".oooooo.",
        "orrrwwwo",
        "oprrwwgo",
        ".oooooo.",
    )
    val Z_BIG = grid(
        "zzzz",
        "..z.",
        ".z..",
        "zzzz",
    )
    val Z_SMALL = grid(
        "zzz",
        ".z.",
        "zzz",
    )
    val RAIN_CLOUD = grid(
        "....ooo......",
        "..oogggoo....",
        ".oggggggooo..",
        "ogggggggggggo",
        ".ooooooooooo.",
        "..d...d...d..",
        ".d...d...d...",
    )
}
