package com.pawpixel.sprite

import com.pawpixel.core.Mood

/** 3x5 pixel font (original), enough for names and labels. Accents fold to their base letter. */
object PixelFont {
    const val W = 3
    const val H = 5
    private val GLYPHS: Map<Char, List<String>> = mapOf(
        'A' to listOf(".#.", "#.#", "###", "#.#", "#.#"), 'B' to listOf("##.", "#.#", "##.", "#.#", "##."),
        'C' to listOf("###", "#..", "#..", "#..", "###"), 'D' to listOf("##.", "#.#", "#.#", "#.#", "##."),
        'E' to listOf("###", "#..", "##.", "#..", "###"), 'F' to listOf("###", "#..", "##.", "#..", "#.."),
        'G' to listOf("###", "#..", "#.#", "#.#", "###"), 'H' to listOf("#.#", "#.#", "###", "#.#", "#.#"),
        'I' to listOf("###", ".#.", ".#.", ".#.", "###"), 'J' to listOf("..#", "..#", "..#", "#.#", "###"),
        'K' to listOf("#.#", "#.#", "##.", "#.#", "#.#"), 'L' to listOf("#..", "#..", "#..", "#..", "###"),
        'M' to listOf("#.#", "###", "#.#", "#.#", "#.#"), 'N' to listOf("##.", "#.#", "#.#", "#.#", "#.#"),
        'O' to listOf("###", "#.#", "#.#", "#.#", "###"), 'P' to listOf("###", "#.#", "###", "#..", "#.."),
        'Q' to listOf("###", "#.#", "#.#", "###", "..#"), 'R' to listOf("##.", "#.#", "##.", "#.#", "#.#"),
        'S' to listOf("###", "#..", "###", "..#", "###"), 'T' to listOf("###", ".#.", ".#.", ".#.", ".#."),
        'U' to listOf("#.#", "#.#", "#.#", "#.#", "###"), 'V' to listOf("#.#", "#.#", "#.#", "#.#", ".#."),
        'W' to listOf("#.#", "#.#", "#.#", "###", "#.#"), 'X' to listOf("#.#", "#.#", ".#.", "#.#", "#.#"),
        'Y' to listOf("#.#", "#.#", ".#.", ".#.", ".#."), 'Z' to listOf("###", "..#", ".#.", "#..", "###"),
        '0' to listOf("###", "#.#", "#.#", "#.#", "###"), '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to listOf("###", "..#", "###", "#..", "###"), '3' to listOf("###", "..#", ".##", "..#", "###"),
        '4' to listOf("#.#", "#.#", "###", "..#", "..#"), '5' to listOf("###", "#..", "###", "..#", "###"),
        '6' to listOf("###", "#..", "###", "#.#", "###"), '7' to listOf("###", "..#", ".#.", ".#.", ".#."),
        '8' to listOf("###", "#.#", "###", "#.#", "###"), '9' to listOf("###", "#.#", "###", "..#", "###"),
        ' ' to listOf("...", "...", "...", "...", "..."), '!' to listOf(".#.", ".#.", ".#.", "...", ".#."),
        '?' to listOf("###", "..#", ".#.", "...", ".#."), '.' to listOf("...", "...", "...", "...", ".#."),
        '-' to listOf("...", "...", "###", "...", "..."), '\'' to listOf(".#.", ".#.", "...", "...", "..."),
        '&' to listOf(".#.", "#.#", ".#.", "#.#", ".##"), ':' to listOf("...", ".#.", "...", ".#.", "..."),
        '+' to listOf("...", ".#.", "###", ".#.", "..."), '/' to listOf("..#", "..#", ".#.", "#..", "#.."),
        '♥' to listOf("#.#", "###", "###", ".#.", "..."),
    )
    private const val FOLD_FROM = "ÀÁÂÃÄÅÈÉÊËÌÍÎÏÒÓÔÕÖÙÚÛÜÑÇÝ"
    private const val FOLD_TO = "AAAAAAEEEEIIIIOOOOOUUUUNCY"

    fun normalize(c: Char): Char {
        val u = c.uppercaseChar()
        val i = FOLD_FROM.indexOf(u)
        val f = if (i >= 0) FOLD_TO[i] else u
        return if (GLYPHS.containsKey(f)) f else '?'
    }

    fun textWidth(text: String, scale: Int) = if (text.isEmpty()) 0 else (text.length * (W + 1) - 1) * scale

    fun draw(img: PixelImage, text: String, x: Int, y: Int, scale: Int, color: Int) {
        var cx = x
        for (ch in text) {
            val g = GLYPHS.getValue(normalize(ch))
            for (gy in 0 until H) for (gx in 0 until W) if (g[gy][gx] == '#') {
                for (sy in 0 until scale) for (sx in 0 until scale) {
                    val px = cx + gx * scale + sx; val py = y + gy * scale + sy
                    if (img.inBounds(px, py)) img[px, py] = color
                }
            }
            cx += (W + 1) * scale
        }
    }

    /** Shortens [text] so it fits in [maxWidth]. */
    fun fit(text: String, scale: Int, maxWidth: Int): String {
        var t = text
        while (t.length > 1 && textWidth(t, scale) > maxWidth) t = t.dropLast(1)
        return if (t.length < text.length && t.length > 2) t.dropLast(1) + "." else t
    }
}

/**
 * The shareable before/after image: real photo → pixel pet, with a PawPixel watermark.
 * Every share is an ad, which is how the app is meant to grow.
 */
object RevealCard {
    private const val PANEL = 320
    private const val MARGIN = 24
    private const val GAP = 56
    private const val TEXT_SCALE = 4

    private val BG = 0xFFFFF4E0.toInt()
    private val INK = 0xFF2B2135.toInt()
    private val ACCENT = 0xFFE8374E.toInt()
    private val PANEL_BG = 0xFFFFE3B8.toInt()

    fun render(photoCrop: PixelImage, sprite: PixelImage, petName: String): PixelImage {
        val w = MARGIN * 2 + PANEL * 2 + GAP
        val titleH = PixelFont.H * TEXT_SCALE
        val top = MARGIN + titleH + 20
        val h = top + PANEL + 20 + titleH + MARGIN
        val card = PixelImage(w, h).fill(BG)

        // Frame
        for (x in 0 until w) for (t in 0 until 6) { card[x, t] = INK; card[x, h - 1 - t] = INK }
        for (y in 0 until h) for (t in 0 until 6) { card[t, y] = INK; card[w - 1 - t, y] = INK }

        val title = com.pawpixel.i18n.tr("MEET {0}", petName.trim().ifEmpty { com.pawpixel.i18n.tr("MY PET") })
        val fitted = PixelFont.fit(title, TEXT_SCALE, w - 2 * MARGIN)
        PixelFont.draw(card, fitted, (w - PixelFont.textWidth(fitted, TEXT_SCALE)) / 2, MARGIN, TEXT_SCALE, INK)

        // Left: photo
        val leftX = MARGIN
        val photo = photoCrop.resampleArea(0.0, 0.0, photoCrop.width.toDouble(), photoCrop.height.toDouble(), PANEL, PANEL)
        for (i in photo.pixels.indices) photo.pixels[i] = Argb.withAlpha(photo.pixels[i], 255)
        card.draw(photo, leftX, top)
        border(card, leftX, top, PANEL, PANEL)

        // Arrow
        val arrow = Icons.grid(
            "..o...",
            "..oo..",
            "ooooo.",
            "oooooo",
            "ooooo.",
            "..oo..",
            "..o...",
        ).scaled(6)
        card.draw(recolor(arrow, ACCENT), leftX + PANEL + (GAP - arrow.width) / 2, top + (PANEL - arrow.height) / 2)

        // Right: sprite (happy pose), integer-scaled for crisp pixels
        val rightX = leftX + PANEL + GAP
        val panel = PixelImage(PANEL, PANEL).fill(PANEL_BG)
        val full = Poses.render(sprite, Mood.CONTENT)
        // Crop to the pet itself so it fills the panel; hearts are added on top below.
        val bb = Animator.opaqueBounds(full) ?: intArrayOf(0, 0, full.width, full.height)
        val pose = PixelImage(bb[2] - bb[0] + 4, bb[3] - bb[1] + 4).also { it.draw(full, 2 - bb[0], 2 - bb[1]) }
        val f = maxOf(1, minOf((PANEL - 24) / pose.width, (PANEL - 24) / pose.height))
        val big = pose.scaled(f)
        panel.draw(big, (PANEL - big.width) / 2, (PANEL - big.height) / 2)
        val heart = Icons.HEART.scaled(maxOf(3, f - 1))
        panel.draw(heart, PANEL - heart.width - 16, 16)
        panel.draw(Icons.HEART_SMALL.scaled(maxOf(3, f - 1)), PANEL - heart.width - 16 - Icons.HEART_SMALL.width * maxOf(3, f - 1) - 8, 16 + heart.height / 2)
        card.draw(panel, rightX, top)
        border(card, rightX, top, PANEL, PANEL)

        val footer = com.pawpixel.i18n.tr("MADE WITH PAWPIXEL")
        val fy = top + PANEL + 20
        PixelFont.draw(card, footer, (w - PixelFont.textWidth(footer, TEXT_SCALE)) / 2, fy, TEXT_SCALE, ACCENT)
        return card
    }

    private fun border(img: PixelImage, x: Int, y: Int, w: Int, h: Int) {
        for (t in 0 until 4) {
            for (i in -t until w + t) { safe(img, x + i, y - 1 - t); safe(img, x + i, y + h + t) }
            for (i in -t until h + t) { safe(img, x - 1 - t, y + i); safe(img, x + w + t, y + i) }
        }
    }

    private fun safe(img: PixelImage, x: Int, y: Int) { if (img.inBounds(x, y)) img[x, y] = INK }

    private fun recolor(img: PixelImage, c: Int): PixelImage {
        val out = img.copy()
        for (i in out.pixels.indices) if (Argb.alpha(out.pixels[i]) > 0) out.pixels[i] = c
        return out
    }
}
