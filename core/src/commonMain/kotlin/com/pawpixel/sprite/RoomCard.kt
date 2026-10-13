package com.pawpixel.sprite

import com.pawpixel.core.Sky
import kotlin.math.roundToInt

/**
 * A picture of the room to share: the whole room as the owner arranged it, the pet at home in it
 * (lit by the room, casting its shadow on the boards), the pet's name and a small PawPixel mark.
 * A 4:5 portrait, so it posts well; drawn in the room's own pixels and enlarged without smoothing,
 * with the diorama's soft edges top and bottom.
 */
object RoomCard {
    const val WIDTH = 104
    const val HEIGHT = 130
    const val SCALE = 8

    fun render(layout: RoomLayout, art: PetArt, petName: String, phase: Sky.Phase, season: Season = Season.NONE, seed: Int = 7): PixelImage {
        // The pet's feet on its home row, the front rows of floor below for the caption.
        val floorY = HEIGHT - 30
        val scene = Room.scene(WIDTH, HEIGHT, Room.floorLineFor(floorY), phase, seed, season, layout)
        val g = scene.geom
        val img = scene.base.copy()
        val size = g.size(Room.PET_ROW + 1.0)
        val still = art.still.let { it.resampled(maxOf(1, (it.width * size).roundToInt()), maxOf(1, (it.height * size).roundToInt())) }
        val body = Animator.opaqueBounds(still) ?: intArrayOf(0, 0, still.width, still.height)
        val petX = (g.x(Room.GRID_W / 2, Room.PET_ROW + 1.0) - (body[0] + body[2]) / 2.0).roundToInt()
        val petY = floorY - body[3]
        val petBaseline = floorY
        val sorted = scene.props.sortedWith(compareBy({ it.baseline }, { it.x }))
        for (p in sorted) if (p.baseline <= petBaseline) img.draw(p.image, p.x, p.y)
        // Its shadow across the boards, then the pet under the room's light.
        scene.castShadow(img, still, petX, petY, phase)
        img.draw(scene.lit(still, petX, petY), petX, petY)
        for (p in sorted) if (p.baseline > petBaseline) img.draw(p.image, p.x, p.y)
        val soft = Room.softFocus(img, 0.08, 0.10)
        // The caption on the floor's front edge: the name on the left, the mark on the right.
        val ink = if (phase.dark) 0xFFEADFCF.toInt() else 0xFF6B4E33.toInt()
        val name = PixelFont.fit(petName.uppercase(), 1, WIDTH / 2)
        PixelFont.draw(soft, name, 3, HEIGHT - PixelFont.H - 3, 1, ink)
        val mark = "PAWPIXEL"
        PixelFont.draw(soft, mark, WIDTH - PixelFont.textWidth(mark, 1) - 3, HEIGHT - PixelFont.H - 3, 1, ink)
        // Twice the resolution with rounded corners, then enlarged to the card's size.
        return soft.scale2x().scaled(SCALE / 2)
    }
}
