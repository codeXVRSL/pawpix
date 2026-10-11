package com.pawpixel.sprite

import com.pawpixel.core.Sky
import kotlin.random.Random

/**
 * The pet's room, drawn in the pet's own pixels so it never looks pasted on.
 *
 * A 3/4 view, the way cozy games draw interiors: the papered back wall is seen straight on, the
 * floor is a grid of tiles that recede upward, and every piece of furniture has a lit top face, a
 * shaded front and a soft shadow on the floor, so flat pixels read as small boxes in a small room.
 * The light comes from the window side (top left), as pixel artists draw it. The owner decides what
 * stands where (a [RoomLayout]); the pet walks between the pieces, sleeps on the bed and eats at
 * the bowl.
 *
 * [scene] draws the backdrop (wall, wall pieces, floor, rugs, shadows) once, and hands back the
 * standing pieces as props with their floor baselines, so the view can draw the pet between them in
 * painter's order. [render] flattens everything for a still image.
 */
object Room {
    /** A floor tile on screen: 9 pixels wide, 6 deep (the foreshortened depth of a 3/4 view). */
    const val TILE_W = 9
    const val TILE_D = 6
    /** The tile row the pet's feet start on (rows 0 and 1 are behind that line of feet, the rest in front). */
    const val PET_ROW = 1

    /** The floor colour the page continues under the stage (so the floor runs on under a rounded sheet). */
    fun floorColor(phase: Sky.Phase): Int = palette(phase).floor

    /** Where the floor begins for a pet whose feet stand on [floorY]: [PET_ROW] rows of floor lie behind it. */
    fun floorLineFor(floorY: Int): Int = floorY - (PET_ROW + 1) * TILE_D

    /**
     * Pixel positions of the grid on a stage of [width] by [height] with the floor from [floorLine] down.
     * The grid is centred; a wider stage shows bare floor at the sides, a deeper one more rows in front.
     */
    class Geometry(val width: Int, val height: Int, val floorLine: Int) {
        val gridLeft: Int = (width - RoomLayout.COLS * TILE_W) / 2
        /** Rows of the grid that fit on this floor. */
        val rows: Int = ((height - floorLine) / TILE_D).coerceIn(0, RoomLayout.ROWS)
        /** Wall slots are [RoomLayout.WALL_STEP] apart, squeezed on a short wall so the window still shows. */
        val slotStep: Int = minOf(RoomLayout.WALL_STEP, maxOf(5, (floorLine - 26) / RoomLayout.WALL_SLOTS))

        fun tileX(col: Int): Int = gridLeft + col * TILE_W
        /** The y of a row's back edge. */
        fun tileTop(row: Int): Int = floorLine + row * TILE_D
        /** The y just below a row's front edge: where things standing on it are drawn to, and sorted by. */
        fun baseline(row: Int): Int = floorLine + (row + 1) * TILE_D
        /** The y of the bottom edge of a wall piece hung at [slot]. */
        fun wallBottom(slot: Int): Int = floorLine - 4 - slot * slotStep

        /** The floor tile under a stage pixel, or null when it's not on the grid. */
        fun tileAt(x: Int, y: Int): Pair<Int, Int>? {
            val col = Math.floorDiv(x - gridLeft, TILE_W); val row = Math.floorDiv(y - floorLine, TILE_D)
            return if (col in 0 until RoomLayout.COLS && row in 0 until rows) col to row else null
        }

        /** The wall column and slot under a stage pixel, or null when it isn't on the wall. */
        fun wallAt(x: Int, y: Int): Pair<Int, Int>? {
            if (y >= floorLine - 2) return null
            val col = Math.floorDiv(x - gridLeft, TILE_W)
            val slot = Math.floorDiv(floorLine - 4 - y + slotStep / 2, slotStep)
            return if (col in 0 until RoomLayout.COLS && slot in 0 until RoomLayout.WALL_SLOTS) col to slot else null
        }
    }

    /** A standing piece: its drawing, where it goes on the stage, and the floor baseline it is sorted by. */
    class Prop(val placed: Placed, val image: PixelImage, val x: Int, val y: Int, val baseline: Int)

    class Scene(val base: PixelImage, val props: List<Prop>, val geom: Geometry) {
        /** Everything drawn into one picture, in painter's order. */
        fun flatten(): PixelImage {
            val out = base.copy()
            for (p in props.sortedWith(compareBy({ it.baseline }, { it.x }))) out.draw(p.image, p.x, p.y)
            return out
        }
    }

    // ---------------------------------------------------------------- palette

    class Palette(
        val wall: Int, val wallDot: Int, val wainscot: Int, val wainscotLine: Int,
        val floor: Int, val plank: Int, val floorTop: Int, val tileLine: Int, val shadow: Int,
        val frame: Int, val sill: Int, val curtain: Int, val curtainDark: Int,
        val wood: Int, val woodLight: Int, val woodDark: Int,
        val pot: Int, val leaf: Int, val leafDark: Int,
        val cushion: Int, val cushionLight: Int, val cushionDark: Int,
        val rug: Int, val rugEdge: Int, val rugDot: Int,
        val bowl: Int, val bowlDark: Int, val kibble: Int, val lamp: Int, val shade: Int, val glow: Int?,
        val fabric: Int, val fabricLight: Int, val fabricDark: Int,
        val glass: Int, val water: Int, val fish: Int, val metal: Int, val metalDark: Int, val ink: Int,
    )

    private val DAY = Palette(
        wall = 0xFFF4E2D2.toInt(), wallDot = 0xFFEDD6C2.toInt(), wainscot = 0xFFE9CDB7.toInt(), wainscotLine = 0xFFDDBB9F.toInt(),
        floor = 0xFFD9B48C.toInt(), plank = 0xFFCDA57C.toInt(), floorTop = 0xFFB98E62.toInt(), tileLine = 0xFFC79F74.toInt(), shadow = 0x30362A4A,
        frame = 0xFFFFFDF8.toInt(), sill = 0xFFE6D5C3.toInt(), curtain = 0xFFE88A96.toInt(), curtainDark = 0xFFD06C7B.toInt(),
        wood = 0xFFB9865A.toInt(), woodLight = 0xFFD2A579.toInt(), woodDark = 0xFF8E6240.toInt(),
        pot = 0xFFC9744A.toInt(), leaf = 0xFF78B56F.toInt(), leafDark = 0xFF4F8F4C.toInt(),
        cushion = 0xFF9C8BE6.toInt(), cushionLight = 0xFFC2B6F2.toInt(), cushionDark = 0xFF7A69C4.toInt(),
        rug = 0xFFF0B9B2.toInt(), rugEdge = 0xFFD98C8C.toInt(), rugDot = 0xFFF6D3CE.toInt(),
        bowl = 0xFF4A90D9.toInt(), bowlDark = 0xFF2F6FB0.toInt(), kibble = 0xFF8B5A2B.toInt(), lamp = 0xFF8C6A48.toInt(), shade = 0xFFFFE2A8.toInt(), glow = null,
        fabric = 0xFF6FB3A8.toInt(), fabricLight = 0xFF9ED3C8.toInt(), fabricDark = 0xFF4E8F86.toInt(),
        glass = 0xFFBFE6F2.toInt(), water = 0xFF7CC4E6.toInt(), fish = 0xFFF28C28.toInt(), metal = 0xFFC9C3BE.toInt(), metalDark = 0xFF8E8780.toInt(), ink = 0xFF3A2E4A.toInt(),
    )

    private fun dim(c: Int, t: Double) = Argb.mix(c, 0xFF1B2440.toInt(), t)

    private val NIGHT = Palette(
        wall = dim(DAY.wall, 0.62), wallDot = dim(DAY.wallDot, 0.62), wainscot = dim(DAY.wainscot, 0.62), wainscotLine = dim(DAY.wainscotLine, 0.62),
        floor = dim(DAY.floor, 0.58), plank = dim(DAY.plank, 0.58), floorTop = dim(DAY.floorTop, 0.58), tileLine = dim(DAY.tileLine, 0.58), shadow = 0x40101830,
        frame = dim(DAY.frame, 0.55), sill = dim(DAY.sill, 0.55), curtain = dim(DAY.curtain, 0.5), curtainDark = dim(DAY.curtainDark, 0.5),
        wood = dim(DAY.wood, 0.5), woodLight = dim(DAY.woodLight, 0.5), woodDark = dim(DAY.woodDark, 0.5),
        pot = dim(DAY.pot, 0.5), leaf = dim(DAY.leaf, 0.5), leafDark = dim(DAY.leafDark, 0.5),
        cushion = dim(DAY.cushion, 0.5), cushionLight = dim(DAY.cushionLight, 0.5), cushionDark = dim(DAY.cushionDark, 0.5),
        rug = dim(DAY.rug, 0.55), rugEdge = dim(DAY.rugEdge, 0.55), rugDot = dim(DAY.rugDot, 0.55),
        bowl = dim(DAY.bowl, 0.5), bowlDark = dim(DAY.bowlDark, 0.5), kibble = dim(DAY.kibble, 0.5), lamp = dim(DAY.lamp, 0.4),
        shade = 0xFFFFE9B0.toInt(), glow = 0x2EFFD98A,
        fabric = dim(DAY.fabric, 0.5), fabricLight = dim(DAY.fabricLight, 0.5), fabricDark = dim(DAY.fabricDark, 0.5),
        glass = dim(DAY.glass, 0.45), water = dim(DAY.water, 0.4), fish = dim(DAY.fish, 0.3), metal = dim(DAY.metal, 0.5), metalDark = dim(DAY.metalDark, 0.5), ink = dim(DAY.ink, 0.3),
    )

    fun palette(phase: Sky.Phase): Palette = if (phase.dark) NIGHT else DAY

    // ---------------------------------------------------------------- the scene

    /** A still of the whole room, for cards, exports and the web. [floorY] is where a pet's feet would stand. */
    fun render(width: Int, height: Int, floorY: Int, phase: Sky.Phase, seed: Int = 7, season: Season = Season.NONE, layout: RoomLayout = RoomLayout.DEFAULT): PixelImage =
        scene(width, height, floorLineFor(floorY), phase, seed, season, layout).flatten()

    fun scene(width: Int, height: Int, floorLine0: Int, phase: Sky.Phase, seed: Int = 7, season: Season = Season.NONE, layout: RoomLayout = RoomLayout.DEFAULT): Scene {
        val p = palette(phase)
        val img = PixelImage(width, height)
        val floorLine = floorLine0.coerceIn(10, height)
        val g = Geometry(width, height, floorLine)

        // Wall: paper with a quiet dot pattern, and a wainscot band above the floor.
        for (y in 0 until floorLine) for (x in 0 until width) img[x, y] = if ((x + y * 3) % 10 == 0 && (y % 5 == 2)) p.wallDot else p.wall
        val wainscotTop = floorLine - 7
        for (y in wainscotTop until floorLine) for (x in 0 until width) img[x, y] = p.wainscot
        for (x in 0 until width) { img[x, wainscotTop] = p.wainscotLine; if (x % 8 == 4) for (y in wainscotTop + 2 until floorLine - 1) img[x, y] = p.wainscotLine }

        // Floor: boards running across, two tile rows to a board, with a faint joint here and there: the lines recede toward the wall.
        val seam = Argb.mix(p.floor, p.tileLine, 0.55)
        for (y in floorLine until height) for (x in 0 until width) {
            val d = y - floorLine; val row = d / TILE_D
            val joint = row % 2 == 1 && ((x - g.gridLeft) + (row / 2) * 17).mod(41) == 0 && d % TILE_D != 0
            img[x, y] = when {
                d == 0 -> p.floorTop
                d % (TILE_D * 2) == 0 -> seam
                joint -> seam
                else -> p.floor
            }
        }

        // The season's garland along the top of a tall wall.
        if (floorLine > 60) when (season) {
            Season.CHRISTMAS -> lights(img, 4, p)
            Season.VALENTINES -> hearts(img, 4, p)
            Season.HALLOWEEN -> bunting(img, 4, p, intArrayOf(0xFFF28C28.toInt(), 0xFF2B2135.toInt(), 0xFF9C6BD6.toInt()))
            Season.NONE -> Unit
        }

        // Wall pieces, flat on the wall.
        for (pl in layout.items.filter { it.item.onWall }.sortedBy { it.y }) {
            val bottom = g.wallBottom(pl.y)
            val x = g.tileX(pl.x)
            when (pl.item) {
                Furniture.WINDOW -> window(img, x + 1, bottom - 24, TILE_W * 3 - 2, 22, phase, p, seed)
                Furniture.SHELF -> shelf(img, x, bottom, p, pl.flip)
                Furniture.FRAME -> frame(img, x, bottom - 8, p)
                Furniture.CLOCK -> clock(img, x + 4, bottom - 5, p)
                Furniture.HANGING_PLANT -> hanging(img, x + 4, bottom - 18, p)
                Furniture.POSTER -> poster(img, x, bottom - 12, p)
                Furniture.STRING_LIGHTS -> stringLights(img, x, bottom - 6, TILE_W * 4, p, phase)
                Furniture.PAW_BANNER -> banner(img, x + 1, bottom - 14, p)
                else -> Unit
            }
        }

        // By day the window lays a patch of sun on the floor below it, slanting to the right as the light does.
        if (!phase.dark) for (pl in layout.items.filter { it.item == Furniture.WINDOW }) {
            val x0 = g.tileX(pl.x) + 2
            for (row in 0 until minOf(g.rows, 3)) for (yy in g.tileTop(row) until g.baseline(row)) for (xx in x0 + row * 3 until x0 + TILE_W * 3 - 2 + row * 3) {
                if (img.inBounds(xx, yy) && yy > floorLine) img[xx, yy] = Argb.blend(img[xx, yy], 0x2AFFF0B8)
            }
        }

        // Rugs lie flat on the floor, under everything.
        for (pl in layout.items.filter { it.item.flat }) {
            if (pl.y + pl.item.rows > g.rows + 1) continue
            when (pl.item) {
                Furniture.RUG -> rug(img, g.tileX(pl.x), g.tileTop(pl.y), TILE_W * 3, TILE_D * 2, p)
                Furniture.ROUND_RUG -> roundRug(img, g.tileX(pl.x), g.tileTop(pl.y), p)
                else -> Unit
            }
        }

        // Standing pieces: a soft shadow on the floor (in the backdrop), the piece itself as a prop.
        val props = ArrayList<Prop>()
        // The owner's pieces, then the season's stand-in (a pumpkin or a tree on a free tile, never saved).
        val standing = layout.items.filter { !it.item.onWall && !it.item.flat }.map { it to false } + seasonal(season, layout, g).map { it to true }
        for ((pl, isSeasonal) in standing) {
            if (pl.y + pl.item.rows > g.rows + 1) continue
            val x0 = g.tileX(pl.x); val back = g.tileTop(pl.y); val front = g.baseline(pl.y + pl.item.rows - 1)
            shadow(img, x0 + 1, back + 2, pl.item.cols * TILE_W, front - back, p)
            val sprite = piece(pl, p, phase, seed, seasonal = isSeasonal)
            // The drawing's bottom sits on the front edge; its left on the tile's left.
            props += Prop(pl, sprite, x0, front - sprite.height, front)
        }
        // The lamp lights the floor around it at night (into the backdrop, under the props).
        p.glow?.let { glow -> for ((pl, _) in standing) if (pl.item == Furniture.LAMP) lampGlow(img, g.tileX(pl.x) + 4, g.baseline(pl.y) - 24, glow) }
        return Scene(img, props, g)
    }

    /** The season's piece on the floor, on a free tile by the right wall: a jack-o'-lantern or a little tree. */
    private fun seasonal(season: Season, layout: RoomLayout, g: Geometry): List<Placed> {
        val item = when (season) { Season.HALLOWEEN -> Furniture.TOY_BOX; Season.CHRISTMAS -> Furniture.BIG_PLANT; else -> return emptyList() }
        // Borrow the catalogue's footprint; the piece is drawn as the season's own thing (see [piece]).
        for (y in 0 until minOf(g.rows, 2)) for (x in RoomLayout.COLS - 1 downTo 0) if (layout.canPlace(item, x, y)) return listOf(Placed(item, x, y, flip = true))
        return emptyList()
    }

    // ---------------------------------------------------------------- pieces

    /**
     * The drawing of a standing piece: [item.cols] tiles wide, its top face [item.rows] tiles deep, and its
     * height below that. [seasonal] draws the season's stand-in on that footprint (a pumpkin, a tree).
     */
    fun piece(pl: Placed, p: Palette, phase: Sky.Phase, seed: Int = 7, seasonal: Boolean = false): PixelImage {
        val w = pl.item.cols * TILE_W; val d = pl.item.rows * TILE_D
        val img = when (pl.item) {
            Furniture.BED -> bed(w, d, p)
            Furniture.BOWL -> bowl(p)
            Furniture.LAMP -> lamp(p)
            Furniture.PLANT -> plant(p, big = false)
            Furniture.BIG_PLANT -> if (seasonal) tree(p, phase) else plant(p, big = true)
            Furniture.TABLE -> table(w, d, p)
            Furniture.CHAIR -> chair(p)
            Furniture.TOY_BOX -> if (seasonal) pumpkin(phase) else toyBox(p)
            Furniture.BOOKCASE -> bookcase(w, d, p)
            Furniture.SCRATCH_POST -> scratchPost(p)
            Furniture.FISH_TANK -> fishTank(w, d, p, phase)
            Furniture.RADIO -> radio(p)
            Furniture.TEA_SET -> teaSet(p)
            else -> PixelImage(w, d)
        }
        return if (pl.flip) StageRenderer.mirror(img) else img
    }

    /**
     * A box in 3/4 view: a lit top face [d] deep, a front face [h] tall, a darker right edge and a
     * one-pixel outline in the deep tone. Everything "furniture" starts as one of these.
     */
    private fun box(img: PixelImage, x: Int, yTop: Int, w: Int, d: Int, h: Int, top: Int, front: Int, dark: Int, outline: Int) {
        for (yy in yTop until yTop + d) for (xx in x until x + w) set(img, xx, yy, top)
        for (yy in yTop + d until yTop + d + h) for (xx in x until x + w) set(img, xx, yy, if (xx >= x + w - 2) dark else front)
        // The top's front lip, and the outline.
        for (xx in x until x + w) { set(img, xx, yTop + d, Argb.mix(top, outline, 0.35)); set(img, xx, yTop, outline); set(img, xx, yTop + d + h - 1, outline) }
        for (yy in yTop until yTop + d + h) { set(img, x, yy, outline); set(img, x + w - 1, yy, outline) }
    }

    private fun bed(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 5)
        // A soft cushion: a rounded box with a lighter top and a dent in the middle.
        box(img, 0, 0, w, d, 4, p.cushionLight, p.cushion, p.cushionDark, p.cushionDark)
        img[0, 0] = 0; img[w - 1, 0] = 0; img[0, d + 4] = 0; img[w - 1, d + 4] = 0
        for (xx in 4 until w - 4) set(img, xx, 2, p.cushion)
        for (xx in 5 until w - 5) set(img, xx, 3, p.cushion)
        return img
    }

    private fun bowl(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 3)
        val rows = listOf("..kkkk...", ".kkkkkk..", "bbbbbbbb.", "dbbbbbbd.", ".dddddd..", "..dddd...")
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, j, 3 + i, when (c) { 'k' -> p.kibble; 'b' -> p.bowl; else -> p.bowlDark }) } }
        return img
    }

    private fun lamp(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, 31)
        val x = 4; val floor = 30
        for (yy in 8 until floor) set(img, x, yy, p.lamp)
        for (dx in -2..2) set(img, x + dx, floor - 1, p.lamp); for (dx in -3..3) set(img, x + dx, floor, p.lamp)
        // Shade, lit from the left.
        for (dy in 0..6) for (dx in -(2 + dy / 2)..(2 + dy / 2)) set(img, x + dx, dy, if (dy == 6 || dy == 0) p.lamp else if (dx > 1 + dy / 2) Argb.mix(p.shade, p.lamp, 0.35) else p.shade)
        return img
    }

    private fun lampGlow(img: PixelImage, x: Int, top: Int, glow: Int) {
        for (dy in -3..20) for (dx in -14..14) { val xx = x + dx; val yy = top + 4 + dy; if (img.inBounds(xx, yy) && dx * dx / 2 + dy * dy / 3 < 70) img[xx, yy] = Argb.blend(img[xx, yy], glow) }
    }

    private fun plant(p: Palette, big: Boolean): PixelImage {
        val img = PixelImage(TILE_W, if (big) 22 else 14)
        val base = img.height - 1; val x = 2
        // Pot: a little box with a lit rim.
        for (dy in 0..3) for (dx in dy / 2..4 - dy / 2) set(img, x + dx, base - dy, if (dx >= 4 - dy / 2) Argb.mix(p.pot, p.woodDark, 0.4) else p.pot)
        for (dx in 0..4) set(img, x + dx, base - 4, Argb.mix(p.pot, p.frame, 0.3))
        val rows = if (big) listOf("...x...", "..xox..", ".xoxox.", "xoxoxox", ".xoxox.", "..xox..", "...x...", "...x...")
                   else listOf("..x..", ".xox.", "xoxox", ".xox.", "..x..")
        val top = base - 5 - rows.size
        val off = if (big) 1 else 2
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, off + j, top + i, if (c == 'o') p.leaf else p.leafDark) } }
        set(img, x + 2, base - 5, p.leafDark)
        return img
    }

    private fun table(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 9)
        // Legs first, then the top as a thin box over them.
        for (lx in listOf(1, w - 3)) for (yy in d + 2 until d + 9) { set(img, lx, yy, p.woodDark); set(img, lx + 1, yy, p.wood) }
        box(img, 0, 0, w, d, 2, p.woodLight, p.wood, p.woodDark, p.woodDark)
        // A cup on it.
        for ((dx, dy) in listOf(3 to 1, 4 to 1, 5 to 1, 3 to 2, 4 to 2, 5 to 2)) set(img, dx, dy, p.frame)
        set(img, 6, 2, p.frame); set(img, 4, 1, p.bowl)
        return img
    }

    private fun chair(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 11)
        // Back rest (a tall thin box), then the seat in front of it.
        box(img, 0, 0, TILE_W, 2, 7, p.fabricLight, p.fabric, p.fabricDark, p.fabricDark)
        box(img, 0, 7, TILE_W, TILE_D, 4, p.fabricLight, p.fabric, p.fabricDark, p.fabricDark)
        // Arms.
        for (yy in 6..13) { set(img, 0, yy, p.fabricDark); set(img, 1, yy, p.fabricLight); set(img, TILE_W - 2, yy, p.fabric); set(img, TILE_W - 1, yy, p.fabricDark) }
        return img
    }

    private fun toyBox(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 7)
        box(img, 0, 2, TILE_W, TILE_D - 1, 6, p.curtain, p.curtainDark, Argb.mix(p.curtainDark, p.ink, 0.3), Argb.mix(p.curtainDark, p.ink, 0.45))
        // A ball and a bone peeking out of the top.
        set(img, 2, 1, p.bowl); set(img, 3, 1, p.bowl); set(img, 2, 2, p.bowl); set(img, 3, 2, p.bowlDark)
        for (dx in 5..7) set(img, dx, 1, p.frame); set(img, 5, 0, p.frame); set(img, 7, 0, p.frame)
        return img
    }

    private fun bookcase(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 24)
        box(img, 0, 0, w, d, 24, p.woodLight, p.wood, p.woodDark, p.woodDark)
        val colours = listOf(p.curtain, p.bowl, p.leafDark, p.cushion, p.fish, p.fabric)
        for (shelf in 0..2) {
            val top = d + 1 + shelf * 8
            for (xx in 1 until w - 1) set(img, xx, top + 6, p.woodDark)
            var x = 2; var i = shelf
            while (x < w - 3) {
                val h = 4 + (i % 2); val bw = 1 + (i % 3 == 0).compareTo(false)
                for (dx in 0 until bw) for (dy in 0 until h) set(img, x + dx, top + 5 - dy, colours[i % colours.size])
                x += bw + 1; i++
            }
        }
        return img
    }

    private fun scratchPost(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 19)
        // Base, post wrapped in rope, a carpeted platform on top.
        box(img, 1, TILE_D + 16, TILE_W - 2, 2, 1, p.woodLight, p.wood, p.woodDark, p.woodDark)
        for (yy in 5 until TILE_D + 16) { set(img, 3, yy, if (yy % 2 == 0) p.woodLight else p.wood); set(img, 4, yy, if (yy % 2 == 0) p.wood else p.woodLight); set(img, 5, yy, p.woodDark) }
        box(img, 0, 0, TILE_W, 3, 2, p.rug, p.rugEdge, Argb.mix(p.rugEdge, p.ink, 0.3), Argb.mix(p.rugEdge, p.ink, 0.4))
        return img
    }

    private fun fishTank(w: Int, d: Int, p: Palette, phase: Sky.Phase): PixelImage {
        val img = PixelImage(w, d + 11)
        box(img, 0, d + 8, w, 1, 2, p.woodLight, p.wood, p.woodDark, p.woodDark)
        // Glass: water with a lighter surface, a fish, some weed.
        for (yy in 0 until d + 8) for (xx in 0 until w) set(img, xx, yy, if (yy < 2) p.glass else p.water)
        for (xx in 0 until w) { set(img, xx, 0, p.metal); set(img, xx, d + 7, p.metalDark) }
        for (yy in 0 until d + 8) { set(img, 0, yy, p.metal); set(img, w - 1, yy, p.metalDark) }
        for ((dx, dy) in listOf(5 to 4, 6 to 4, 7 to 4, 8 to 3, 8 to 5, 4 to 4)) set(img, dx, dy, p.fish)
        for (yy in 3 until d + 7) set(img, w - 4, yy, if (yy % 2 == 0) p.leaf else p.leafDark)
        if (phase.dark) for (xx in 2 until w - 2) set(img, xx, 1, Argb.mix(p.glass, 0xFFFFFFFF.toInt(), 0.3))
        return img
    }

    private fun radio(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 4)
        box(img, 0, 1, TILE_W, TILE_D - 2, 5, p.woodLight, p.wood, p.woodDark, p.woodDark)
        for (yy in 6..8) for (xx in 2..4) set(img, xx, yy, p.metalDark)
        set(img, 6, 7, p.metal); set(img, 7, 7, p.metal); set(img, 7, 0, p.metalDark); set(img, 8, 0, p.metalDark)
        return img
    }

    private fun teaSet(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 2)
        // A tray with a teapot and a cup.
        box(img, 0, 3, TILE_W, 3, 1, p.woodLight, p.wood, p.woodDark, p.woodDark)
        for ((dx, dy) in listOf(2 to 1, 3 to 1, 4 to 1, 1 to 2, 2 to 2, 3 to 2, 4 to 2, 5 to 2, 3 to 0)) set(img, dx, dy, p.frame)
        set(img, 0, 2, p.frame); set(img, 7, 2, p.frame); set(img, 7, 1, p.curtain); set(img, 8, 2, p.frame)
        return img
    }

    // ---- the season's stand-ins ----

    private fun pumpkin(phase: Sky.Phase): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 5)
        val orange = if (phase.dark) 0xFFB8651E.toInt() else 0xFFF28C28.toInt()
        val dark = if (phase.dark) 0xFF8A4A14.toInt() else 0xFFC96F1E.toInt()
        val face = if (phase.dark) 0xFFFFE27A.toInt() else 0xFF2B2135.toInt()
        val rows = listOf("...gg....", "..xxxxx..", ".xxxxxxx.", "xxxxxxxxx", "xxxxxxxxx", "xxxxxxxxx", ".xxxxxxx.", "..xxxxx..")
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> when (ch) { 'x' -> set(img, c, 3 + r, if (c == 2 || c == 6) dark else orange); 'g' -> set(img, c, 3 + r, 0xFF5EA64C.toInt()) } } }
        set(img, 2, 6, face); set(img, 6, 6, face)
        for (c in 2..6) set(img, c, 8, face); set(img, 3, 9, face); set(img, 5, 9, face)
        return img
    }

    private fun tree(p: Palette, phase: Sky.Phase): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 18)
        val green = if (phase.dark) 0xFF3F7A3C.toInt() else 0xFF4F8F4C.toInt()
        val light = if (phase.dark) 0xFF5E9B55.toInt() else 0xFF78B56F.toInt()
        val baubles = intArrayOf(0xFFE8374E.toInt(), 0xFFFFCF5C.toInt(), 0xFF4A90D9.toInt(), 0xFFFFA9C9.toInt())
        val x = 4; val baseY = img.height - 2
        var row = 0
        for (dy in 0 until 14) {
            val half = (dy * 4) / 13 + 0
            for (dx in -half..half) set(img, x + dx, baseY - 15 + dy, if ((dx + dy) % 3 == 0) light else green)
            if (dy % 3 == 2) { set(img, x + (if (row % 2 == 0) -half + 1 else half - 1), baseY - 15 + dy, baubles[row % baubles.size]); row++ }
        }
        for (dy in 0..1) for (dx in -1..1) set(img, x + dx, baseY - dy, p.wood)
        set(img, x, baseY - 16, 0xFFFFCF5C.toInt()); set(img, x - 1, baseY - 15, 0xFFFFCF5C.toInt()); set(img, x + 1, baseY - 15, 0xFFFFCF5C.toInt())
        return img
    }

    // ---------------------------------------------------------------- floor and wall decor

    /** The shadow a piece casts on the floor: a darker patch under and just right of it. */
    private fun shadow(img: PixelImage, x: Int, y: Int, w: Int, h: Int, p: Palette) {
        for (yy in y until y + h) for (xx in x until x + w + 1) if (img.inBounds(xx, yy)) img[xx, yy] = Argb.blend(img[xx, yy], p.shadow)
    }

    private fun rug(img: PixelImage, x: Int, y: Int, w: Int, h: Int, p: Palette) {
        for (dy in 0 until h) for (dx in 0 until w) {
            val edge = dy == 0 || dy == h - 1 || dx == 0 || dx == w - 1
            val corner = (dx == 0 || dx == w - 1) && (dy == 0 || dy == h - 1)
            if (!corner) set(img, x + dx, y + dy, if (edge) p.rugEdge else if ((dx + dy) % 5 == 0 && dy % 2 == 1) p.rugDot else p.rug)
        }
    }

    private fun roundRug(img: PixelImage, x: Int, y: Int, p: Palette) {
        val w = TILE_W * 2; val h = TILE_D * 2
        for (dy in 0 until h) for (dx in 0 until w) {
            val nx = (dx + 0.5 - w / 2.0) / (w / 2.0); val ny = (dy + 0.5 - h / 2.0) / (h / 2.0)
            val r = nx * nx + ny * ny
            if (r <= 1.0) set(img, x + dx, y + dy, if (r > 0.72) p.rugEdge else if ((dx + dy) % 4 == 0) p.rugDot else p.rug)
        }
    }

    private fun window(img: PixelImage, x: Int, y: Int, w: Int, h: Int, phase: Sky.Phase, p: Palette, seed: Int) {
        for (yy in y until y + h) for (xx in x until x + w) set(img, xx, yy, Argb.mix(phase.top, phase.bottom, (yy - y).toDouble() / (h - 1)))
        val r = Random(seed)
        if (phase.stars) {
            repeat(w * h / 30) { val sx = x + 1 + r.nextInt(w - 2); val sy = y + 1 + r.nextInt((h * 0.7).toInt()); set(img, sx, sy, 0xFFFFF6D5.toInt()) }
            disc(img, x + w - 7, y + 6, 3.2, 0xFFFFF1C9.toInt())
            disc(img, x + w - 5, y + 5, 2.6, Argb.mix(phase.top, phase.bottom, 0.15))
        } else {
            val sun = if (phase == Sky.Phase.DAY) 0xFFFFF4C2.toInt() else 0xFFFFD98A.toInt()
            disc(img, x + w - 7, y + 6, 4.0, Argb.mix(sun, Argb.mix(phase.top, phase.bottom, 0.3), 0.5))
            disc(img, x + w - 7, y + 6, 2.6, sun)
            cloud(img, x + 3, y + 4, 0xCCFFFFFF.toInt())
            cloud(img, x + w / 2 - 2, y + h / 2, 0x99FFFFFF.toInt())
        }
        for (xx in x - 1..x + w) { set(img, xx, y - 1, p.frame); set(img, xx, y + h, p.frame) }
        for (yy in y - 1..y + h) { set(img, x - 1, yy, p.frame); set(img, x + w, yy, p.frame) }
        for (yy in y until y + h) set(img, x + w / 2, yy, p.frame)
        for (xx in x until x + w) set(img, xx, y + h / 2, p.frame)
        for (xx in x - 2..x + w + 1) { set(img, xx, y + h + 1, p.sill); set(img, xx, y + h + 2, p.woodDark) }
        for (yy in y - 2..y + h) {
            val wide = if (yy < y + 4 || yy > y + h - 5) 3 else 2
            for (i in 0 until wide) { set(img, x - 2 - i, yy, if (i == wide - 1) p.curtainDark else p.curtain); set(img, x + w + 1 + i, yy, if (i == wide - 1) p.curtainDark else p.curtain) }
        }
        for (xx in x - 5..x + w + 4) set(img, xx, y - 3, p.wood)
    }

    /** A shelf with a plant, a stack of books and a little framed paw. */
    private fun shelf(img: PixelImage, x: Int, bottom: Int, p: Palette, flip: Boolean) {
        val w = TILE_W * 3
        for (xx in x until x + w) { set(img, xx, bottom, p.wood); set(img, xx, bottom + 1, p.woodDark) }
        set(img, x + 1, bottom + 2, p.woodDark); set(img, x + w - 2, bottom + 2, p.woodDark)
        val sprite = PixelImage(w, 12)
        val pot = plant(p, big = false)
        sprite.draw(pot, 0, 12 - pot.height + 1)
        val colours = listOf(p.curtain, p.bowl, p.leafDark, p.cushion)
        colours.forEachIndexed { i, c -> for (dy in 0 until 5 + (i % 2)) set(sprite, 10 + i, 11 - dy, c) }
        val fr = PixelImage(8, 8); frame(fr, 0, 0, p); sprite.draw(fr, w - 9, 3)
        img.draw(if (flip) StageRenderer.mirror(sprite) else sprite, x, bottom - 12)
    }

    private fun frame(img: PixelImage, x: Int, y: Int, p: Palette) {
        for (dy in 0..7) for (dx in 0..7) set(img, x + dx, y + dy, if (dx == 0 || dy == 0 || dx == 7 || dy == 7) p.wood else p.frame)
        for ((dx, dy) in listOf(2 to 2, 4 to 2, 1 to 3, 5 to 3, 3 to 4, 2 to 5, 4 to 5, 3 to 5)) set(img, x + dx, y + dy, p.curtainDark)
    }

    private fun poster(img: PixelImage, x: Int, y: Int, p: Palette) {
        // A poster of a sunset over the sea, with a pin at each top corner.
        for (dy in 0 until 12) for (dx in 0 until 8) set(img, x + dx, y + dy, if (dy < 7) Argb.mix(0xFFFFC49A.toInt(), p.wall, 0.15) else p.bowl)
        disc(img, x + 4, y + 5, 2.0, 0xFFFFD98A.toInt())
        for (dx in 0 until 8) set(img, x + dx, y + 7, p.bowlDark)
        set(img, x, y, p.curtainDark); set(img, x + 7, y, p.curtainDark)
    }

    private fun clock(img: PixelImage, cx: Int, cy: Int, p: Palette) {
        disc(img, cx, cy, 4.6, p.wood)
        disc(img, cx, cy, 3.6, p.frame)
        for (dy in -2..0) set(img, cx, cy + dy, p.lamp)
        for (dx in 0..1) set(img, cx + dx, cy, p.lamp)
        set(img, cx, cy - 3, p.curtainDark); set(img, cx, cy + 3, p.curtainDark); set(img, cx - 3, cy, p.curtainDark); set(img, cx + 3, cy, p.curtainDark)
    }

    private fun hanging(img: PixelImage, x: Int, top: Int, p: Palette) {
        for (yy in top until top + 8) set(img, x, yy, p.wood)
        for (dx in -3..3) set(img, x + dx, top + 8, p.pot)
        for (dx in -2..2) { set(img, x + dx, top + 9, p.pot); set(img, x + dx, top + 10, p.pot) }
        for (dx in -1..1) set(img, x + dx, top + 11, p.pot)
        val rows = listOf("..x.o.x..", ".o.x.x.o.", "x...o...x", "o.......o", "x.......x")
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, x - 4 + j, top + 10 + i, if (c == 'o') p.leaf else p.leafDark) } }
    }

    private fun banner(img: PixelImage, x: Int, top: Int, p: Palette) {
        // A pennant hanging from a rod, with a paw.
        for (xx in x - 1..x + 16) set(img, xx, top, p.wood)
        for (dy in 1..12) {
            val inset = if (dy > 9) dy - 9 else 0
            for (dx in inset..15 - inset) set(img, x + dx, top + dy, if (dx == inset || dx == 15 - inset) p.fabricDark else p.fabric)
        }
        for ((dx, dy) in listOf(6 to 4, 9 to 4, 5 to 5, 10 to 5, 7 to 6, 8 to 6, 6 to 7, 7 to 7, 8 to 7, 9 to 7, 7 to 8, 8 to 8)) set(img, x + dx, top + dy, p.fabricLight)
    }

    private fun stringLights(img: PixelImage, x: Int, y: Int, w: Int, p: Palette, phase: Sky.Phase) {
        val bulbs = intArrayOf(0xFFE8374E.toInt(), 0xFF5EA64C.toInt(), 0xFFFFCF5C.toInt(), 0xFF4A90D9.toInt())
        var i = 0; var xx = x
        while (xx < x + w - 2) {
            val t = (xx - x - w / 2.0) / (w / 2.0)
            val yy = y + (2 * (1 - t * t)).toInt()
            for (k in xx until minOf(xx + 6, x + w)) set(img, k, yy, p.woodDark)
            set(img, xx + 3, yy + 1, p.woodDark)
            val c = bulbs[i % bulbs.size]
            set(img, xx + 3, yy + 2, c); set(img, xx + 2, yy + 3, c); set(img, xx + 3, yy + 3, c); set(img, xx + 4, yy + 3, c); set(img, xx + 3, yy + 4, c)
            if (phase.dark) for ((dx, dy) in listOf(1 to 3, 5 to 3, 3 to 1, 3 to 5)) if (img.inBounds(xx + dx, yy + dy)) img[xx + dx, yy + dy] = Argb.blend(img[xx + dx, yy + dy], (c and 0xFFFFFF) or (0x60 shl 24))
            i++; xx += 6
        }
    }

    /** A string of lights along the top: a dark wire with bulbs in turn red, green and gold. */
    private fun lights(img: PixelImage, top: Int, p: Palette) = stringLights(img, 2, top, img.width - 4, p, Sky.Phase.NIGHT)

    /** A garland of little hearts. */
    private fun hearts(img: PixelImage, top: Int, p: Palette) {
        val w = img.width
        val colours = intArrayOf(0xFFE8374E.toInt(), 0xFFFFA9C9.toInt())
        var i = 0; var x = 2
        while (x + 6 < w) {
            val t = (x + 3 - w / 2.0) / (w / 2.0)
            val y = top + (3 * (1 - t * t)).toInt()
            for (xx in x until x + 8) set(img, xx, y, p.wood)
            val c = colours[i % colours.size]
            val rows = listOf(".xx.xx.", "xxxxxxx", "xxxxxxx", ".xxxxx.", "..xxx..", "...x...")
            rows.forEachIndexed { r, row -> row.forEachIndexed { cc, ch -> if (ch == 'x') set(img, x + cc, y + 1 + r, c) } }
            i++; x += 8
        }
    }

    /** A string of little flags across the top of the wall, sagging a little in the middle. */
    private fun bunting(img: PixelImage, top: Int, p: Palette, colours: IntArray) {
        val w = img.width
        var i = 0; var x = 2
        while (x + 6 < w) {
            val t = (x + 3 - w / 2.0) / (w / 2.0)
            val y = top + (3 * (1 - t * t)).toInt()
            for (xx in x until x + 8) set(img, xx, y, p.wood)
            val c = colours[i % colours.size]
            for (dy in 0..4) for (dx in dy..6 - dy) set(img, x + 1 + dx, y + 1 + dy, c)
            i++; x += 8
        }
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
