package com.pawpixel.sprite

import com.pawpixel.core.Sky

/**
 * A picture of the room to share: the whole room as the owner arranged it, the pet at home in it,
 * the pet's name and a small PawPixel mark. Square-ish, so it posts well; drawn in the room's own
 * pixels and enlarged without smoothing.
 */
object RoomCard {
    const val WIDTH = 150
    const val HEIGHT = 128
    const val SCALE = 6

    fun render(layout: RoomLayout, art: PetArt, petName: String, phase: Sky.Phase, season: Season = Season.NONE, seed: Int = 7): PixelImage {
        // The pet's feet on its home row, the front rows of floor below for the caption.
        val floorY = HEIGHT - 30
        val scene = Room.scene(WIDTH, HEIGHT, Room.floorLineFor(floorY), phase, seed, season, layout)
        val img = scene.base.copy()
        val still = art.still
        val body = Animator.opaqueBounds(still) ?: intArrayOf(0, 0, still.width, still.height)
        val petX = (WIDTH - (body[0] + body[2])) / 2
        val petBaseline = floorY
        val sorted = scene.props.sortedWith(compareBy({ it.baseline }, { it.x }))
        for (p in sorted) if (p.baseline <= petBaseline) img.draw(p.image, p.x, p.y)
        // A soft shadow, then the pet standing on its row.
        val cx = petX + (body[0] + body[2]) / 2; val half = (body[2] - body[0]) * 0.4
        for (x in (cx - half).toInt()..(cx + half).toInt()) for (dy in 0..1) if (img.inBounds(x, floorY - 1 + dy)) img[x, floorY - 1 + dy] = Argb.blend(img[x, floorY - 1 + dy], 0x40000000)
        img.draw(still, petX, floorY - body[3])
        for (p in sorted) if (p.baseline > petBaseline) img.draw(p.image, p.x, p.y)
        // The caption on the floor's front edge: the name on the left, the mark on the right.
        val ink = if (phase.dark) 0xFFEADFCF.toInt() else 0xFF6B4E33.toInt()
        val name = PixelFont.fit(petName.uppercase(), 1, WIDTH / 2)
        PixelFont.draw(img, name, 3, HEIGHT - PixelFont.H - 3, 1, ink)
        val mark = "PAWPIXEL"
        PixelFont.draw(img, mark, WIDTH - PixelFont.textWidth(mark, 1) - 3, HEIGHT - PixelFont.H - 3, 1, ink)
        return img.scaled(SCALE)
    }
}
