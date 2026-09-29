package com.pawpixel.sprite

import com.pawpixel.core.Mood

/**
 * A square card for a care milestone ("100 DAYS OF CARE"): the pixel pet in its happy pose.
 * Shared from the pet page; like the before/after card, it carries a small PawPixel watermark.
 */
object MilestoneCard {
    private const val SIZE = 640
    private const val MARGIN = 32
    private const val TEXT = 5
    private val BG = 0xFFFFF4E0.toInt()
    private val INK = 0xFF2B2135.toInt()
    private val ACCENT = 0xFFE8374E.toInt()
    private val PANEL_BG = 0xFFFFE3B8.toInt()

    fun render(sprite: PixelImage, petName: String, title: String): PixelImage {
        val card = PixelImage(SIZE, SIZE).fill(BG)
        for (i in 0 until SIZE) for (t in 0 until 8) { card[i, t] = INK; card[i, SIZE - 1 - t] = INK; card[t, i] = INK; card[SIZE - 1 - t, i] = INK }

        val head = PixelFont.fit(title.uppercase(), TEXT, SIZE - 2 * MARGIN)
        PixelFont.draw(card, head, (SIZE - PixelFont.textWidth(head, TEXT)) / 2, MARGIN + 8, TEXT, ACCENT)

        val panelTop = MARGIN + 8 + PixelFont.H * TEXT + 24
        val panelH = SIZE - panelTop - (MARGIN + PixelFont.H * 4 * 2 + 40)
        val panel = PixelImage(SIZE - 2 * MARGIN, panelH).fill(PANEL_BG)
        val full = Poses.render(sprite, Mood.HAPPY)
        val bb = Animator.opaqueBounds(full) ?: intArrayOf(0, 0, full.width, full.height)
        val pose = PixelImage(bb[2] - bb[0] + 4, bb[3] - bb[1] + 4).also { it.draw(full, 2 - bb[0], 2 - bb[1]) }
        val f = maxOf(1, minOf((panel.width - 40) / pose.width, (panel.height - 40) / pose.height))
        val big = pose.scaled(f)
        panel.draw(big, (panel.width - big.width) / 2, (panel.height - big.height) / 2)
        card.draw(panel, MARGIN, panelTop)

        val name = PixelFont.fit(petName.trim().ifEmpty { "My pet" }.uppercase(), 4, SIZE - 2 * MARGIN)
        val ny = panelTop + panelH + 20
        PixelFont.draw(card, name, (SIZE - PixelFont.textWidth(name, 4)) / 2, ny, 4, INK)
        val footer = com.pawpixel.i18n.tr("MADE WITH PAWPIXEL")
        PixelFont.draw(card, footer, (SIZE - PixelFont.textWidth(footer, 3)) / 2, ny + PixelFont.H * 4 + 16, 3, ACCENT)
        return card
    }
}
