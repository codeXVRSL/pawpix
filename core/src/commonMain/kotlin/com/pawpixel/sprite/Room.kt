package com.pawpixel.sprite

import com.pawpixel.core.Sky
import kotlin.random.Random

/**
 * The pet's room, drawn in the pet's own pixels so it never looks pasted on: a papered wall with a
 * window onto the sky of the hour, a shelf with a plant and a framed paw, a floor lamp that glows
 * at night, a wooden floor with a rug, a cushion and a bowl. Pet games stage their pets in a room
 * (Pou, Finch, every Tamagotchi since the LCD got a backdrop) because a bare gradient reads as
 * empty; a room says "someone lives here".
 *
 * [render] draws the whole backdrop at stage resolution: [width] by [height] pixels with the
 * pet's feet on [floorY]. Everything is placed relative to the floor, so a taller stage simply
 * shows more wall.
 */
object Room {
    private class Palette(
        val wall: Int, val wallDot: Int, val wainscot: Int, val wainscotLine: Int,
        val floor: Int, val plank: Int, val floorTop: Int,
        val frame: Int, val sill: Int, val curtain: Int, val curtainDark: Int,
        val shelf: Int, val pot: Int, val leaf: Int, val leafDark: Int,
        val cushion: Int, val cushionLight: Int, val rug: Int, val rugEdge: Int, val rugDot: Int,
        val bowl: Int, val bowlDark: Int, val kibble: Int, val lamp: Int, val shade: Int, val glow: Int?,
    )

    private val DAY = Palette(
        wall = 0xFFF4E2D2.toInt(), wallDot = 0xFFEDD6C2.toInt(), wainscot = 0xFFE9CDB7.toInt(), wainscotLine = 0xFFDDBB9F.toInt(),
        floor = 0xFFD9B48C.toInt(), plank = 0xFFC79F74.toInt(), floorTop = 0xFFB98E62.toInt(),
        frame = 0xFFFFFDF8.toInt(), sill = 0xFFE6D5C3.toInt(), curtain = 0xFFE88A96.toInt(), curtainDark = 0xFFD06C7B.toInt(),
        shelf = 0xFFB9865A.toInt(), pot = 0xFFC9744A.toInt(), leaf = 0xFF78B56F.toInt(), leafDark = 0xFF4F8F4C.toInt(),
        cushion = 0xFF9C8BE6.toInt(), cushionLight = 0xFFC2B6F2.toInt(), rug = 0xFFF0B9B2.toInt(), rugEdge = 0xFFD98C8C.toInt(), rugDot = 0xFFF6D3CE.toInt(),
        bowl = 0xFF4A90D9.toInt(), bowlDark = 0xFF2F6FB0.toInt(), kibble = 0xFF8B5A2B.toInt(), lamp = 0xFF8C6A48.toInt(), shade = 0xFFFFE2A8.toInt(), glow = null,
    )

    private fun dim(c: Int, t: Double) = Argb.mix(c, 0xFF1B2440.toInt(), t)

    private val NIGHT = Palette(
        wall = dim(DAY.wall, 0.62), wallDot = dim(DAY.wallDot, 0.62), wainscot = dim(DAY.wainscot, 0.62), wainscotLine = dim(DAY.wainscotLine, 0.62),
        floor = dim(DAY.floor, 0.58), plank = dim(DAY.plank, 0.58), floorTop = dim(DAY.floorTop, 0.58),
        frame = dim(DAY.frame, 0.55), sill = dim(DAY.sill, 0.55), curtain = dim(DAY.curtain, 0.5), curtainDark = dim(DAY.curtainDark, 0.5),
        shelf = dim(DAY.shelf, 0.55), pot = dim(DAY.pot, 0.5), leaf = dim(DAY.leaf, 0.5), leafDark = dim(DAY.leafDark, 0.5),
        cushion = dim(DAY.cushion, 0.5), cushionLight = dim(DAY.cushionLight, 0.5), rug = dim(DAY.rug, 0.55), rugEdge = dim(DAY.rugEdge, 0.55), rugDot = dim(DAY.rugDot, 0.55),
        bowl = dim(DAY.bowl, 0.5), bowlDark = dim(DAY.bowlDark, 0.5), kibble = dim(DAY.kibble, 0.5), lamp = dim(DAY.lamp, 0.4),
        shade = 0xFFFFE9B0.toInt(), glow = 0x2EFFD98A,
    )

    /** The floor colour the page continues under the stage (so the floor runs on under a rounded sheet). */
    fun floorColor(phase: Sky.Phase): Int = (if (phase.dark) NIGHT else DAY).floor

    fun render(width: Int, height: Int, floorY: Int, phase: Sky.Phase, seed: Int = 7, season: Season = Season.NONE): PixelImage {
        val p = if (phase.dark) NIGHT else DAY
        val img = PixelImage(width, height)
        val floorLine = (floorY - 2).coerceIn(10, height) // the top edge of the floor (the pet stands two pixels into it); never above the wainscot's rows

        // Wall: paper with a quiet dot pattern, and a wainscot band above the floor.
        for (y in 0 until floorLine) for (x in 0 until width) {
            img[x, y] = if ((x + y * 3) % 10 == 0 && (y % 5 == 2)) p.wallDot else p.wall
        }
        val wainscotTop = floorLine - 9
        for (y in wainscotTop until floorLine) for (x in 0 until width) img[x, y] = p.wainscot
        for (x in 0 until width) { img[x, wainscotTop] = p.wainscotLine; if (x % 8 == 4) for (y in wainscotTop + 2 until floorLine - 1) img[x, y] = p.wainscotLine }

        // Floor: planks seen from the front, a darker top edge.
        for (y in floorLine until height) for (x in 0 until width) {
            val plankEdge = (x + (y - floorLine) * 7) % 23 == 0
            img[x, y] = if (y == floorLine) p.floorTop else if (plankEdge) p.plank else p.floor
        }

        // Window onto the sky, left of centre, with curtains.
        // A short wall (a small card) gets a shorter window, hung lower, and no clock. A tall wall
        // (the room filling a phone screen) gets a bigger window hung at eye height, with the wall
        // above it left for the clock and a second picture.
        val tall = wainscotTop > width * 0.9
        val wx = (width * 0.12).toInt(); val ww = (width * (if (tall) 0.36 else 0.3)).toInt().coerceAtLeast(24)
        val wh = minOf((ww * (if (tall) 1.0 else 0.78)).toInt(), wainscotTop - 10).coerceAtLeast(10)
        val spare = wainscotTop - wh
        val wy = (if (tall) spare - 14 else spare - 16).coerceIn(4, (spare - 6).coerceAtLeast(4))
        window(img, wx, wy, ww, wh, phase, p, seed)

        // A shelf on the right with a plant, a book stack and a framed paw.
        val sx = (width * 0.64).toInt(); val sw = (width * 0.26).toInt()
        val sy = wy + wh / 2 - 2
        for (x in sx until sx + sw) { img[x, sy] = p.shelf; img[x, sy + 1] = p.plank }
        // A round wall clock between the window and the shelf, high on the wall (when there is wall).
        if (wy >= 9) clock(img, (wx + ww + sx) / 2 + 2, wy - 2, p)
        img[sx + 1, sy + 2] = p.plank; img[sx + sw - 2, sy + 2] = p.plank
        plant(img, sx + 2, sy - 1, p, small = true)
        books(img, sx + 9, sy - 1, p)
        frame(img, sx + sw - 8, sy - 10, p)
        // On a tall wall: bunting across the top, a hanging plant by the window and a second
        // picture over the shelf.
        if (tall && wy >= 22) {
            when (season) {
                Season.CHRISTMAS -> lights(img, 4, p)
                Season.VALENTINES -> hearts(img, 4, p)
                Season.HALLOWEEN -> bunting(img, 4, p, intArrayOf(0xFFF28C28.toInt(), 0xFF2B2135.toInt(), 0xFF9C6BD6.toInt()))
                Season.NONE -> bunting(img, 4, p)
            }
            frame(img, sx + 4, wy - 14, p)
            hanging(img, wx + ww + 10, 8, p)
        }

        // A floor lamp at the far right, glowing at night.
        val lx = width - 7
        lamp(img, lx, floorLine, p)

        // On the floor: a rug in the middle, a cushion on the left, a bowl on the right.
        rug(img, width / 2, floorLine + 3, (width * 0.34).toInt(), height - floorLine - 3, p)
        cushion(img, 2, floorLine - 1, p)
        bowl(img, width - 26, floorLine + 1, p)
        // The season's piece on the floor: a jack-o'-lantern or a little tree by the lamp.
        when (season) {
            Season.HALLOWEEN -> pumpkin(img, width - 16, floorLine + 1, phase)
            Season.CHRISTMAS -> { tree(img, width - 19, floorLine + 1, p, phase); bowl(img, width - 26, floorLine + 1, p) } // the bowl stays in front of the tree
            else -> {}
        }
        return img
    }

    /** A string of lights along the top: a dark wire with bulbs in turn red, green and gold. */
    private fun lights(img: PixelImage, top: Int, p: Palette) {
        val w = img.width
        val bulbs = intArrayOf(0xFFE8374E.toInt(), 0xFF5EA64C.toInt(), 0xFFFFCF5C.toInt(), 0xFF4A90D9.toInt())
        var i = 0; var x = 2
        while (x < w - 2) {
            val t = (x - w / 2.0) / (w / 2.0)
            val y = top + (3 * (1 - t * t)).toInt()
            for (xx in x until minOf(x + 6, w)) set(img, xx, y, p.shelf)
            set(img, x + 3, y + 1, p.shelf)
            val c = bulbs[i % bulbs.size]
            set(img, x + 3, y + 2, c); set(img, x + 2, y + 3, c); set(img, x + 3, y + 3, c); set(img, x + 4, y + 3, c); set(img, x + 3, y + 4, c)
            i++; x += 6
        }
    }

    /** A garland of little hearts. */
    private fun hearts(img: PixelImage, top: Int, p: Palette) {
        val w = img.width
        val colours = intArrayOf(0xFFE8374E.toInt(), 0xFFFFA9C9.toInt())
        var i = 0; var x = 2
        while (x + 6 < w) {
            val t = (x + 3 - w / 2.0) / (w / 2.0)
            val y = top + (3 * (1 - t * t)).toInt()
            for (xx in x until x + 8) set(img, xx, y, p.shelf)
            val c = colours[i % colours.size]
            val rows = listOf(".xx.xx.", "xxxxxxx", "xxxxxxx", ".xxxxx.", "..xxx..", "...x...")
            rows.forEachIndexed { r, row -> row.forEachIndexed { cc, ch -> if (ch == 'x') set(img, x + cc, y + 1 + r, c) } }
            i++; x += 8
        }
    }

    /** A jack-o'-lantern on the floor, lit at night. */
    private fun pumpkin(img: PixelImage, x: Int, baseY: Int, phase: Sky.Phase) {
        val orange = if (phase.dark) 0xFFB8651E.toInt() else 0xFFF28C28.toInt()
        val dark = if (phase.dark) 0xFF8A4A14.toInt() else 0xFFC96F1E.toInt()
        val face = if (phase.dark) 0xFFFFE27A.toInt() else 0xFF2B2135.toInt()
        val rows = listOf("...gg....", "..xxxxx..", ".xxxxxxx.", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", ".xxxxxxx.", "..xxxxx..")
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> when (ch) { 'x' -> set(img, x + c, baseY - 7 + r, if (c == 2 || c == 6) dark else orange); 'g' -> set(img, x + c, baseY - 7 + r, 0xFF5EA64C.toInt()) } } }
        set(img, x + 2, baseY - 4, face); set(img, x + 6, baseY - 4, face)
        for (c in 2..6) set(img, x + c, baseY - 2, face); set(img, x + 3, baseY - 1, face); set(img, x + 5, baseY - 1, face)
    }

    /** A small tree with baubles and a star, by the lamp. */
    private fun tree(img: PixelImage, x: Int, baseY: Int, p: Palette, phase: Sky.Phase) {
        val green = if (phase.dark) 0xFF3F7A3C.toInt() else 0xFF4F8F4C.toInt()
        val light = if (phase.dark) 0xFF5E9B55.toInt() else 0xFF78B56F.toInt()
        val baubles = intArrayOf(0xFFE8374E.toInt(), 0xFFFFCF5C.toInt(), 0xFF4A90D9.toInt(), 0xFFFFA9C9.toInt())
        var row = 0
        for (dy in 0 until 14) {
            val half = (dy * 5) / 13 + 1
            for (dx in -half..half) set(img, x + dx, baseY - 15 + dy, if ((dx + dy) % 3 == 0) light else green)
            if (dy % 3 == 2) { set(img, x + (if (row % 2 == 0) -half + 1 else half - 1), baseY - 15 + dy, baubles[row % baubles.size]); row++ }
        }
        for (dy in 0..1) for (dx in -1..1) set(img, x + dx, baseY - dy, p.shelf)
        set(img, x, baseY - 16, 0xFFFFCF5C.toInt()); set(img, x - 1, baseY - 15, 0xFFFFCF5C.toInt()); set(img, x + 1, baseY - 15, 0xFFFFCF5C.toInt())
    }

    private fun window(img: PixelImage, x: Int, y: Int, w: Int, h: Int, phase: Sky.Phase, p: Palette, seed: Int) {
        // Sky inside the frame.
        for (yy in y until y + h) for (xx in x until x + w) {
            val t = (yy - y).toDouble() / (h - 1)
            img[xx, yy] = Argb.mix(phase.top, phase.bottom, t)
        }
        val r = Random(seed)
        if (phase.stars) {
            repeat(w * h / 30) { val sx = x + 1 + r.nextInt(w - 2); val sy = y + 1 + r.nextInt((h * 0.7).toInt()); img[sx, sy] = 0xFFFFF6D5.toInt() }
            disc(img, x + w - 7, y + 6, 3.2, 0xFFFFF1C9.toInt())
            disc(img, x + w - 5, y + 5, 2.6, Argb.mix(phase.top, phase.bottom, 0.15))
        } else {
            val sun = if (phase == Sky.Phase.DAY) 0xFFFFF4C2.toInt() else 0xFFFFD98A.toInt()
            disc(img, x + w - 7, y + 6, 4.0, Argb.mix(sun, img[x + w - 7, y + 6], 0.5))
            disc(img, x + w - 7, y + 6, 2.6, sun)
            cloud(img, x + 3, y + 4, 0xCCFFFFFF.toInt())
            cloud(img, x + w / 2 - 2, y + h / 2, 0x99FFFFFF.toInt())
        }
        // Frame: outer edge, a cross bar, and a sill.
        for (xx in x - 1..x + w) { set(img, xx, y - 1, p.frame); set(img, xx, y + h, p.frame) }
        for (yy in y - 1..y + h) { set(img, x - 1, yy, p.frame); set(img, x + w, yy, p.frame) }
        for (yy in y until y + h) set(img, x + w / 2, yy, p.frame)
        for (xx in x until x + w) set(img, xx, y + h / 2, p.frame)
        for (xx in x - 3..x + w + 2) { set(img, xx, y + h + 1, p.sill); set(img, xx, y + h + 2, p.plank) }
        // Curtains, tied back.
        for (yy in y - 2..y + h) {
            val wide = if (yy < y + 4 || yy > y + h - 5) 4 else 3
            for (i in 0 until wide) { set(img, x - 2 - i, yy, if (i == wide - 1) p.curtainDark else p.curtain); set(img, x + w + 1 + i, yy, if (i == wide - 1) p.curtainDark else p.curtain) }
        }
        // Curtain rod.
        for (xx in x - 7..x + w + 6) set(img, xx, y - 3, p.shelf)
    }

    /** A string of little flags across the top of the wall, sagging a little in the middle. */
    private fun bunting(img: PixelImage, top: Int, p: Palette, colours: IntArray = intArrayOf(p.curtain, p.leaf, p.shade, p.cushion)) {
        val w = img.width
        var i = 0
        var x = 2
        while (x + 6 < w) {
            // The string dips towards the middle.
            val t = (x + 3 - w / 2.0) / (w / 2.0)
            val y = top + (3 * (1 - t * t)).toInt()
            for (xx in x until x + 8) set(img, xx, y, p.shelf)
            val c = colours[i % colours.size]
            for (dy in 0..4) for (dx in dy..6 - dy) set(img, x + 1 + dx, y + 1 + dy, c)
            i++
            x += 8
        }
    }

    /** A plant hanging from the ceiling on a string, trailing leaves. */
    private fun hanging(img: PixelImage, x: Int, top: Int, p: Palette) {
        for (yy in top until top + 10) set(img, x, yy, p.shelf)
        for (dx in -3..3) set(img, x + dx, top + 10, p.pot)
        for (dx in -2..2) { set(img, x + dx, top + 11, p.pot); set(img, x + dx, top + 12, p.pot) }
        for (dx in -1..1) set(img, x + dx, top + 13, p.pot)
        val rows = listOf("..x.o.x..", ".o.x.x.o.", "x...o...x", "o.......o", "x.......x")
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, x - 4 + j, top + 12 + i, if (c == 'o') p.leaf else p.leafDark) } }
    }

    private fun plant(img: PixelImage, x: Int, baseY: Int, p: Palette, small: Boolean) {
        // Pot
        for (dy in 0..2) for (dx in dy / 2..4 - dy / 2) set(img, x + dx, baseY - dy, p.pot)
        set(img, x, baseY - 3, p.pot); set(img, x + 4, baseY - 3, p.pot)
        for (dx in 0..4) set(img, x + dx, baseY - 3, p.pot)
        // Leaves
        val rows = listOf("..x..", ".xox.", "xoxox", ".xox.", "..x..")
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, x + j, baseY - 8 + i, if (c == 'o') p.leaf else p.leafDark) } }
        set(img, x + 2, baseY - 4, p.leafDark)
    }

    private fun clock(img: PixelImage, cx: Int, cy: Int, p: Palette) {
        disc(img, cx, cy, 4.6, p.shelf)
        disc(img, cx, cy, 3.6, p.frame)
        for (dy in -2..0) set(img, cx, cy + dy, p.lamp)
        for (dx in 0..1) set(img, cx + dx, cy, p.lamp)
        set(img, cx, cy - 3, p.curtainDark); set(img, cx, cy + 3, p.curtainDark); set(img, cx - 3, cy, p.curtainDark); set(img, cx + 3, cy, p.curtainDark)
    }

    private fun books(img: PixelImage, x: Int, baseY: Int, p: Palette) {
        val colours = listOf(p.curtain, p.bowl, p.leafDark, p.cushion)
        colours.forEachIndexed { i, c -> for (dy in 0 until 5 + (i % 2)) set(img, x + i, baseY - dy, c) }
    }

    private fun frame(img: PixelImage, x: Int, y: Int, p: Palette) {
        for (dy in 0..7) for (dx in 0..7) set(img, x + dx, y + dy, if (dx == 0 || dy == 0 || dx == 7 || dy == 7) p.shelf else p.frame)
        // a tiny paw
        for ((dx, dy) in listOf(2 to 2, 4 to 2, 1 to 3, 5 to 3, 3 to 4, 2 to 5, 4 to 5, 3 to 5)) set(img, x + dx, y + dy, p.curtainDark)
    }

    private fun lamp(img: PixelImage, x: Int, floorLine: Int, p: Palette) {
        val top = floorLine - 30
        for (yy in top + 7 until floorLine) set(img, x, yy, p.lamp)
        for (dx in -2..2) set(img, x + dx, floorLine - 1, p.lamp)
        // Shade
        for (dy in 0..6) for (dx in -(2 + dy / 2)..(2 + dy / 2)) set(img, x + dx, top + dy, if (dy == 6 || dy == 0) p.lamp else p.shade)
        // Glow at night
        p.glow?.let { g -> for (dy in -3..18) for (dx in -14..14) { val xx = x + dx; val yy = top + 4 + dy; if (img.inBounds(xx, yy) && dx * dx / 2 + dy * dy / 3 < 70) img[xx, yy] = Argb.blend(img[xx, yy], g) } }
    }

    private fun rug(img: PixelImage, cx: Int, top: Int, halfW: Int, h: Int, p: Palette) {
        val rows = h.coerceAtMost(9)
        for (dy in 0 until rows) {
            val hw = halfW - (if (dy == 0 || dy == rows - 1) 2 else 0)
            for (xx in cx - hw..cx + hw) {
                val edge = dy == 0 || dy == rows - 1 || xx == cx - hw || xx == cx + hw
                set(img, xx, top + dy, if (edge) p.rugEdge else if ((xx + dy) % 5 == 0 && dy % 2 == 1) p.rugDot else p.rug)
            }
        }
    }

    private fun cushion(img: PixelImage, x: Int, floorLine: Int, p: Palette) {
        val rows = listOf(".xxxxxxxxxxxxxxx.", "xooooooooooooooox", "xooooooooooooooox", ".xxxxxxxxxxxxxxx.") // 17 wide, outlined both sides
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, x + j, floorLine - 1 + i, if (c == 'o') p.cushionLight else p.cushion) } }
        // a dent where the pet sleeps
        for (j in 5..11) set(img, x + j, floorLine, p.cushion)
    }

    private fun bowl(img: PixelImage, x: Int, floorLine: Int, p: Palette) {
        val rows = listOf("..kkkk..", ".kkkkkk.", "bbbbbbbb", ".dddddd.", "..dddd..")
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, x + j, floorLine - 2 + i, when (c) { 'k' -> p.kibble; 'b' -> p.bowl; else -> p.bowlDark }) } }
    }

    private fun disc(img: PixelImage, cx: Int, cy: Int, r: Double, color: Int) {
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            val dx = x - cx + 0.5; val dy = y - cy + 0.5
            if (dx * dx + dy * dy <= r * r && img.inBounds(x, y)) img[x, y] = Argb.blend(img[x, y], color)
        }
    }

    private fun cloud(img: PixelImage, x0: Int, y0: Int, color: Int) {
        listOf("..####..", ".######.", "########").forEachIndexed { dy, row -> row.forEachIndexed { dx, c -> if (c == '#' && img.inBounds(x0 + dx, y0 + dy)) img[x0 + dx, y0 + dy] = Argb.blend(img[x0 + dx, y0 + dy], color) } }
    }

    private fun set(img: PixelImage, x: Int, y: Int, c: Int) { if (img.inBounds(x, y)) img[x, y] = c }
}
