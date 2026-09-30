package com.pawpixel.sprite

/**
 * Little pixel outfits. Most are earned with care: each unlocks after a number of days of care
 * (see [com.pawpixel.core.Milestones]), and nothing is ever taken away. A few Filipino ones
 * ([pro]) come with PawPixel Pro, the one-time purchase, instead.
 *
 * Each is a tiny hand-drawn grid placed relative to the head, so it follows every pose (walking,
 * eating, looking around, lying down). Letters are colours; '.' is see-through; 'o' is the outline.
 */
enum class Accessory(
    val label: String,
    val unlockDays: Int,
    private val rows: List<String>,
    private val anchor: Anchor,
    /** Comes with PawPixel Pro rather than days of care. */
    val pro: Boolean = false,
) {
    BANDANA("Bandana", 3, listOf(
        "ooooooooo",
        "orrrrrrro",
        ".orrwrro.",
        "..orrro..",
        "...oro...",
        "....o....",
    ), Anchor.NECK),
    FLOWER("Flower", 7, listOf(
        ".opo.",
        "opypo",
        ".opo.",
        "..g..",
    ), Anchor.EAR),
    BOW_TIE("Bow tie", 14, listOf(
        "oo...oo",
        "obooobo",
        "obbobbo",
        "obooobo",
        "oo...oo",
    ), Anchor.NECK),
    PARTY_HAT("Party hat", 30, listOf(
        "...y...",
        "...o...",
        "..obo..",
        "..oyo..",
        ".obbbo.",
        ".oyyyo.",
        "obbbbbo",
        "ooooooo",
    ), Anchor.TOP),
    SUNGLASSES("Sunglasses", 50, listOf(
        "fffff......fffff",
        "fkkwffffffffkkwf",
        "fkkkf......fkkkf",
        ".fff........fff.",
    ), Anchor.EYES),
    CROWN("Crown", 100, listOf(
        ".o..o..o.",
        "oyooyooyo",
        "oyyyyyyyo",
        "oyryyybyo",
        "ooooooooo",
    ), Anchor.TOP),

    // ---- Pro outfits ----
    SALAKOT("Salakot", 0, listOf(
        "..........o..........",
        ".........oyo.........",
        "........onnno........",
        "......oonnnndoo......",
        "....oonndnnnnddoo....",
        "..oonnnnnnndnnnddoo..",
        "oonndnnnnnnnnndnnddoo",
        "odddddddddddddddddddo",
        ".ooooooooooooooooooo.",
    ), Anchor.TOP, pro = true),
    SAMPAGUITA("Sampaguita", 0, listOf(
        "wo.........ow",
        "owo.......owo",
        ".owgo...ogwo.",
        "..owwowowwo..",
        "...ogwwwgo...",
        "....orrro....",
        ".....oro.....",
    ), Anchor.NECK, pro = true),
    PAROL("Parol", 0, listOf(
        "..ooo..",
        ".oyryo.",
        "oyrrryo",
        ".oyryo.",
        "..ooo..",
        "..y.y..",
        "..r.r..",
    ), Anchor.EAR, pro = true);

    enum class Anchor { NECK, EAR, TOP, EYES }

    val width: Int get() = rows[0].length
    val height: Int get() = rows.size

    /** Top-left corner for a head centred at ([hcx], [hcy]) with eyes' top row [eyeTop]. */
    fun origin(hcx: Double, hcy: Double, eyeTop: Int): Pair<Int, Int> = when (anchor) {
        Anchor.NECK -> kotlin.math.round(hcx - width / 2.0).toInt() to kotlin.math.round(hcy + 6.6).toInt()
        Anchor.EAR -> kotlin.math.round(hcx + 4.5).toInt() to kotlin.math.round(hcy - 8.4).toInt()
        // Clamped to the canvas: in bobbing walk frames the hat sits a pixel lower rather than losing its tip.
        Anchor.TOP -> kotlin.math.round(hcx - width / 2.0).toInt() to maxOf(0, kotlin.math.round(hcy - 12.6).toInt())
        Anchor.EYES -> kotlin.math.round(hcx - 7.5).toInt() to eyeTop - 1
    }

    /** Draws it onto [img] (over the pet, including its outline). */
    fun drawOn(img: PixelImage, hcx: Double, hcy: Double, eyeTop: Int) {
        val (x0, y0) = origin(hcx, hcy, eyeTop)
        for ((dy, row) in rows.withIndex()) for ((dx, ch) in row.withIndex()) {
            val c = PALETTE[ch] ?: continue
            val x = x0 + dx; val y = y0 + dy
            if (img.inBounds(x, y)) img[x, y] = c
        }
    }

    companion object {
        fun of(name: String?): Accessory? = entries.firstOrNull { it.name == name }

        private val PALETTE = mapOf(
            'o' to 0xFF2B2135.toInt(), // outline, the app's ink
            'r' to 0xFFE8374E.toInt(), // PawPixel red
            'w' to 0xFFFFFFFF.toInt(),
            'y' to 0xFFF6C744.toInt(),
            'p' to 0xFFF59AB8.toInt(),
            'g' to 0xFF5DAE5B.toInt(),
            'b' to 0xFF3E7BD6.toInt(),
            'k' to 0xFF1D1B26.toInt(),
            'f' to 0xFF8A7FA3.toInt(), // a light frame, so glasses show on black fur too
            'n' to 0xFFE2B66C.toInt(), // woven straw (salakot)
            'd' to 0xFFB07A3E.toInt(), // straw in shade
        )
    }
}
