package com.pawpixel.sprite

import com.pawpixel.core.Sky
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The pet's room, drawn in the pet's own pixels so it never looks pasted on.
 *
 * A dollhouse seen through its open front, the way HD-2D games (Octopath Traveler) show pixel art
 * in a world with depth: the back wall is narrower than the front edge, the side walls lean in,
 * the floorboards converge, and every piece of furniture is drawn a little smaller the further
 * back it stands. Then the room is lit: a daylight beam slants in from the window, the lamp pools
 * warm light on the floor at night, the corners fall off into shade, each piece casts a soft
 * shadow across the boards, and the brightest spots bloom. The pixels stay crisp; only the light
 * is smooth. The owner decides what stands where (a [RoomLayout]); the pet walks between the
 * pieces, sleeps on the bed and eats at the bowl.
 *
 * [scene] draws the backdrop once (walls, floor, wall pieces, rugs, shadows, light) and hands back
 * the standing pieces as props, already lit, with their floor baselines, so a view can draw the
 * pet between them in painter's order. [render] flattens everything for a still image.
 */
object Room {
    /** A floor tile is [TILE_W] room units wide; the back wall is [RoomLayout.COLS] tiles, [GRID_W] units. */
    const val TILE_W = 9
    const val GRID_W = TILE_W * RoomLayout.COLS.toDouble()
    /** A tile row is this deep on screen: the same for every row (a dollhouse seen from a little above). */
    const val TILE_D = 8
    /** The tile row the pet's feet start on (rows 0 and 1 are behind that line of feet, the rest in front). */
    const val PET_ROW = 1
    /** How much wider the room's front edge is than its back wall: the strength of the perspective. */
    const val DEPTH = 1.35
    /** Screen pixels per room unit at the front edge of a phone-wide (96 px) stage: pieces are drawn at their own size there. */
    private const val FRONT = (96 + 4) / GRID_W

    /** The floor colour the page continues under the stage (so the floor runs on under a rounded sheet). */
    fun floorColor(phase: Sky.Phase): Int { val a = ambient(phase); return Light.lit(palette(phase).floor, a[0], a[1], a[2]) }

    /** Where the floor begins for a pet whose feet stand on [floorY]: [PET_ROW] rows of floor lie behind it. */
    fun floorLineFor(floorY: Int): Int = floorY - (PET_ROW + 1) * TILE_D

    /**
     * Where everything is on a stage of [width] by [height] with the back wall's foot at [floorLine].
     * Positions across the room are in room units ([TILE_W] a tile) and a depth in tile rows from the
     * back wall; the box projects them to stage pixels: one unit is [scale] pixels, more at the front.
     */
    class Geometry(val width: Int, val height: Int, val floorLine: Int) {
        /** Pixels per unit at the back wall: the front edge is [DEPTH] times wider and runs just off the stage. */
        val back: Double = (width + 4) / DEPTH / GRID_W
        val cx: Double = width / 2.0
        /** Rows of the grid that fit on this floor. */
        val rows: Int = ((height - floorLine) / TILE_D).coerceIn(0, RoomLayout.ROWS)
        /** Wall slots are [RoomLayout.WALL_STEP] apart, squeezed on a short wall so the window still shows. */
        val slotStep: Int = minOf(RoomLayout.WALL_STEP, maxOf(5, (floorLine - 26) / RoomLayout.WALL_SLOTS))
        /** The back wall's edges, and its top (a very tall stage shows a strip of ceiling above it). */
        val backLeft: Int = x(0.0, 0.0).roundToInt()
        val backRight: Int = x(GRID_W, 0.0).roundToInt()
        val wallTop: Int = maxOf(0, floorLine - (GRID_W * back * 1.3).roundToInt())

        /** Pixels per unit at [depth] rows in front of the back wall. */
        fun scale(depth: Double): Double = back * (1 + (DEPTH - 1) * depth / RoomLayout.ROWS)
        /** The stage x of a point [u] units from the left wall at [depth]. */
        fun x(u: Double, depth: Double): Double = cx + (u - GRID_W / 2) * scale(depth)
        fun u(x: Double, depth: Double): Double = GRID_W / 2 + (x - cx) / scale(depth)
        /** The depth of a floor pixel's row. */
        fun depth(y: Int): Double = (y + 0.5 - floorLine) / TILE_D
        /** How big a piece or the pet is drawn at [depth]: its own size at the front of a phone-wide room, smaller behind. */
        fun size(depth: Double): Double = scale(depth) / FRONT

        fun tileX(col: Int, depth: Double = 0.0): Int = x(col * TILE_W.toDouble(), depth).roundToInt()
        /** The y of a row's back edge. */
        fun tileTop(row: Int): Int = floorLine + row * TILE_D
        /** The y just below a row's front edge: where things standing on it are drawn to, and sorted by. */
        fun baseline(row: Int): Int = floorLine + (row + 1) * TILE_D
        /** The y of the bottom edge of a wall piece hung at [slot]. */
        fun wallBottom(slot: Int): Int = floorLine - 4 - slot * slotStep

        /** The floor tile under a stage pixel, or null when it's not on the grid. */
        fun tileAt(x: Int, y: Int): Pair<Int, Int>? {
            val row = (y - floorLine).floorDiv(TILE_D)
            if (row !in 0 until rows) return null
            val col = floor(u(x + 0.5, depth(y)) / TILE_W).toInt()
            return if (col in 0 until RoomLayout.COLS) col to row else null
        }

        /** The wall column and slot under a stage pixel, or null when it isn't on the wall. */
        fun wallAt(x: Int, y: Int): Pair<Int, Int>? {
            if (y >= floorLine - 2) return null
            val col = floor(u(x + 0.5, 0.0) / TILE_W).toInt()
            val slot = (floorLine - 4 - y + slotStep / 2).floorDiv(slotStep)
            return if (col in 0 until RoomLayout.COLS && slot in 0 until RoomLayout.WALL_SLOTS) col to slot else null
        }
    }

    /** A standing piece: its drawing (scaled for its depth and lit where it stands), where it goes on the stage, and the floor baseline it is sorted by. */
    class Prop(val placed: Placed, val image: PixelImage, val x: Int, val y: Int, val baseline: Int)

    /** The light over the stage: a multiplier per channel for every pixel (1.0 = the palette's own colour, more in a beam, less in the shade). */
    class Light(val width: Int, val height: Int) {
        val r = FloatArray(width * height) { 1f }; val g = FloatArray(width * height) { 1f }; val b = FloatArray(width * height) { 1f }
        fun at(x: Int, y: Int): FloatArray { val i = y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1); return floatArrayOf(r[i], g[i], b[i]) }
        /** [c] under the light at ([x], [y]); off the stage, as it is. */
        fun apply(c: Int, x: Int, y: Int): Int {
            if (x !in 0 until width || y !in 0 until height) return c
            val i = y * width + x
            return lit(c, r[i], g[i], b[i])
        }
        companion object {
            fun lit(c: Int, lr: Float, lg: Float, lb: Float): Int {
                val a = c ushr 24
                if (a == 0) return c
                fun ch(v: Int, l: Float) = (v * l).roundToInt().coerceIn(0, 255)
                return Argb.of(a, ch(Argb.r(c), lr), ch(Argb.g(c), lg), ch(Argb.b(c), lb))
            }
        }
    }

    class Scene(
        val base: PixelImage, val props: List<Prop>, val geom: Geometry, val light: Light,
        /** Each window's glass on the stage (x, y, width, height): the sun's beam starts at its sill. */
        val windows: List<IntArray> = emptyList(),
        /** Each lamp's shade on the stage (x, y): where its light pools from. */
        val lamps: List<FloatArray> = emptyList(),
    ) {
        /** Everything drawn into one picture, in painter's order. */
        fun flatten(): PixelImage {
            val out = base.copy()
            for (p in props.sortedWith(compareBy({ it.baseline }, { it.x }))) out.draw(p.image, p.x, p.y)
            return out
        }
        /** [sprite] as it looks under the room's light with its top-left at ([x], [y]). */
        fun lit(sprite: PixelImage, x: Int, y: Int): PixelImage = litSprite(sprite, x, y, light)
        /** The soft shadow [sprite] casts on the floor, standing with its top-left at ([x], [y]), drawn into [into]. */
        fun castShadow(into: PixelImage, sprite: PixelImage, x: Int, y: Int, phase: Sky.Phase) {
            val mask = FloatArray(into.width * into.height)
            shadowOf(mask, into.width, into.height, sprite, x, y, lampX = lampAt(props, geom))
            drawShadows(into, mask, phase)
        }
    }

    // ---------------------------------------------------------------- palette

    class Palette(
        val wall: Int, val wallDot: Int, val wainscot: Int, val wainscotLine: Int, val side: Int, val ceiling: Int,
        val floor: Int, val plank: Int, val floorTop: Int, val tileLine: Int,
        val frame: Int, val sill: Int, val curtain: Int, val curtainDark: Int,
        val wood: Int, val woodLight: Int, val woodDark: Int,
        val pot: Int, val leaf: Int, val leafDark: Int,
        val cushion: Int, val cushionLight: Int, val cushionDark: Int,
        val rug: Int, val rugEdge: Int, val rugDot: Int,
        val bowl: Int, val bowlDark: Int, val kibble: Int, val lamp: Int, val shade: Int,
        val fabric: Int, val fabricLight: Int, val fabricDark: Int,
        val glass: Int, val water: Int, val fish: Int, val metal: Int, val metalDark: Int, val ink: Int,
    )

    private val DAY = Palette(
        wall = 0xFFF4E2D2.toInt(), wallDot = 0xFFEDD6C2.toInt(), wainscot = 0xFFE9CDB7.toInt(), wainscotLine = 0xFFDDBB9F.toInt(), side = 0xFFE8D4C2.toInt(), ceiling = 0xFFEFE3D8.toInt(),
        floor = 0xFFD9B48C.toInt(), plank = 0xFFCDA57C.toInt(), floorTop = 0xFFB98E62.toInt(), tileLine = 0xFFC79F74.toInt(),
        frame = 0xFFFFFDF8.toInt(), sill = 0xFFE6D5C3.toInt(), curtain = 0xFFE88A96.toInt(), curtainDark = 0xFFD06C7B.toInt(),
        wood = 0xFFB9865A.toInt(), woodLight = 0xFFD2A579.toInt(), woodDark = 0xFF8E6240.toInt(),
        pot = 0xFFC9744A.toInt(), leaf = 0xFF78B56F.toInt(), leafDark = 0xFF4F8F4C.toInt(),
        cushion = 0xFF9C8BE6.toInt(), cushionLight = 0xFFC2B6F2.toInt(), cushionDark = 0xFF7A69C4.toInt(),
        rug = 0xFFF0B9B2.toInt(), rugEdge = 0xFFD98C8C.toInt(), rugDot = 0xFFF6D3CE.toInt(),
        bowl = 0xFF4A90D9.toInt(), bowlDark = 0xFF2F6FB0.toInt(), kibble = 0xFF8B5A2B.toInt(), lamp = 0xFF8C6A48.toInt(), shade = 0xFFFFE2A8.toInt(),
        fabric = 0xFF6FB3A8.toInt(), fabricLight = 0xFF9ED3C8.toInt(), fabricDark = 0xFF4E8F86.toInt(),
        glass = 0xFFBFE6F2.toInt(), water = 0xFF7CC4E6.toInt(), fish = 0xFFF28C28.toInt(), metal = 0xFFC9C3BE.toInt(), metalDark = 0xFF8E8780.toInt(), ink = 0xFF3A2E4A.toInt(),
    )

    private fun dim(c: Int, t: Double) = Argb.mix(c, 0xFF1B2440.toInt(), t)

    private val NIGHT = Palette(
        wall = dim(DAY.wall, 0.62), wallDot = dim(DAY.wallDot, 0.62), wainscot = dim(DAY.wainscot, 0.62), wainscotLine = dim(DAY.wainscotLine, 0.62), side = dim(DAY.side, 0.62), ceiling = dim(DAY.ceiling, 0.65),
        floor = dim(DAY.floor, 0.58), plank = dim(DAY.plank, 0.58), floorTop = dim(DAY.floorTop, 0.58), tileLine = dim(DAY.tileLine, 0.58),
        frame = dim(DAY.frame, 0.55), sill = dim(DAY.sill, 0.55), curtain = dim(DAY.curtain, 0.5), curtainDark = dim(DAY.curtainDark, 0.5),
        wood = dim(DAY.wood, 0.5), woodLight = dim(DAY.woodLight, 0.5), woodDark = dim(DAY.woodDark, 0.5),
        pot = dim(DAY.pot, 0.5), leaf = dim(DAY.leaf, 0.5), leafDark = dim(DAY.leafDark, 0.5),
        cushion = dim(DAY.cushion, 0.5), cushionLight = dim(DAY.cushionLight, 0.5), cushionDark = dim(DAY.cushionDark, 0.5),
        rug = dim(DAY.rug, 0.55), rugEdge = dim(DAY.rugEdge, 0.55), rugDot = dim(DAY.rugDot, 0.55),
        bowl = dim(DAY.bowl, 0.5), bowlDark = dim(DAY.bowlDark, 0.5), kibble = dim(DAY.kibble, 0.5), lamp = dim(DAY.lamp, 0.4),
        shade = 0xFFFFE9B0.toInt(),
        fabric = dim(DAY.fabric, 0.5), fabricLight = dim(DAY.fabricLight, 0.5), fabricDark = dim(DAY.fabricDark, 0.5),
        glass = dim(DAY.glass, 0.45), water = dim(DAY.water, 0.4), fish = dim(DAY.fish, 0.3), metal = dim(DAY.metal, 0.5), metalDark = dim(DAY.metalDark, 0.5), ink = dim(DAY.ink, 0.3),
    )

    fun palette(phase: Sky.Phase): Palette = if (phase.dark) NIGHT else DAY

    /** The light in the room with nothing else lit: cool and low at night, warm by day, pink at dawn and dusk. */
    private fun ambient(phase: Sky.Phase): FloatArray = when (phase) {
        Sky.Phase.NIGHT -> floatArrayOf(0.44f, 0.46f, 0.64f)
        Sky.Phase.DAWN -> floatArrayOf(0.90f, 0.84f, 0.82f)
        Sky.Phase.DUSK -> floatArrayOf(0.90f, 0.82f, 0.80f)
        Sky.Phase.DAY -> floatArrayOf(0.88f, 0.86f, 0.82f)
    }

    // ---------------------------------------------------------------- the scene

    /** A still of the whole room, for cards, exports and the web. [floorY] is where a pet's feet would stand. */
    fun render(width: Int, height: Int, floorY: Int, phase: Sky.Phase, seed: Int = 7, season: Season = Season.NONE, layout: RoomLayout = RoomLayout.DEFAULT): PixelImage =
        scene(width, height, floorLineFor(floorY), phase, seed, season, layout).flatten()

    fun scene(width: Int, height: Int, floorLine0: Int, phase: Sky.Phase, seed: Int = 7, season: Season = Season.NONE, layout: RoomLayout = RoomLayout.DEFAULT): Scene {
        val p = palette(phase)
        val img = PixelImage(width, height)
        val floorLine = floorLine0.coerceIn(10, height)
        val g = Geometry(width, height, floorLine)
        box(img, g, p)

        // The season's garland along the top of a tall wall.
        if (floorLine > 60) when (season) {
            Season.CHRISTMAS -> lights(img, g, 4, p)
            Season.VALENTINES -> hearts(img, g, 4, p)
            Season.HALLOWEEN -> bunting(img, g, 4, p, intArrayOf(0xFFF28C28.toInt(), 0xFF2B2135.toInt(), 0xFF9C6BD6.toInt()))
            Season.NONE -> Unit
        }

        // Wall pieces, flat on the back wall, drawn at the wall's distance. The window's glass is remembered for the light.
        val glass = ArrayList<IntArray>()
        for (pl in layout.items.filter { it.item.onWall }.sortedBy { it.y }) {
            val sprite = wallPiece(pl, p, phase, seed)
            val s = g.size(0.0)
            val scaled = sprite.image.resampled(maxOf(1, (sprite.image.width * s).roundToInt()), maxOf(1, (sprite.image.height * s).roundToInt()))
            val x = (g.x(pl.x * TILE_W.toDouble(), 0.0) + sprite.dx * s).roundToInt()
            val y = (g.wallBottom(pl.y) + sprite.dy * s).roundToInt()
            img.draw(scaled, x, y)
            if (pl.item == Furniture.WINDOW) glass += intArrayOf(x + (sprite.glass[0] * s).roundToInt(), y + (sprite.glass[1] * s).roundToInt(), maxOf(1, (sprite.glass[2] * s).roundToInt()), maxOf(1, (sprite.glass[3] * s).roundToInt()))
        }

        // Rugs lie flat on the floor, under everything, foreshortened with it.
        for (pl in layout.items.filter { it.item.flat }) {
            if (pl.y + pl.item.rows > g.rows + 1) continue
            when (pl.item) {
                Furniture.RUG -> rug(img, g, pl, p, round = false)
                Furniture.ROUND_RUG -> rug(img, g, pl, p, round = true)
                else -> Unit
            }
        }

        // Standing pieces: the owner's, then the season's stand-in (a pumpkin or a tree on a free tile, never saved).
        val standing = layout.items.filter { !it.item.onWall && !it.item.flat }.map { it to false } + seasonal(season, layout, g).map { it to true }
        val props = ArrayList<Prop>()
        for ((pl, isSeasonal) in standing) {
            if (pl.y + pl.item.rows > g.rows + 1) continue
            props += prop(pl, g, p, phase, seed, isSeasonal, light = null)
        }
        // Their shadows fall across the floor, away from the window by day and away from the lamp at night.
        val mask = FloatArray(width * height)
        val lampX = lampAt(props, g)
        for (pr in props) shadowOf(mask, width, height, pr.image, pr.x, pr.y, lampX)
        drawShadows(img, mask, phase)

        // Then the light: over the backdrop, and baked into each piece where it stands.
        val lamps = lampShades(props)
        val light = light(g, phase, glass, lamps)
        for (i in img.pixels.indices) img.pixels[i] = Light.lit(img.pixels[i], light.r[i], light.g[i], light.b[i])
        val lit = props.map { Prop(it.placed, litSprite(it.image, it.x, it.y, light), it.x, it.y, it.baseline) }
        bloom(img, lit)
        return Scene(img, lit, g, light, glass, lamps)
    }

    /**
     * A standing piece drawn for its place in the room: [Furniture.cols] tiles wide at the front
     * edge of its tiles, smaller the deeper it stands, and under the room's [light] when given.
     */
    fun prop(pl: Placed, g: Geometry, p: Palette, phase: Sky.Phase, seed: Int = 7, seasonal: Boolean = false, light: Light? = null): Prop {
        val sprite = piece(pl, p, phase, seed, seasonal)
        val frontRow = pl.y + pl.item.rows - 1
        val depth = (frontRow + 1).toDouble()
        val s = g.size(depth)
        val scaled = sprite.resampled(maxOf(1, (sprite.width * s).roundToInt()), maxOf(1, (sprite.height * s).roundToInt()))
        val front = g.baseline(frontRow)
        val x = (g.x((pl.x + pl.item.cols / 2.0) * TILE_W, depth) - scaled.width / 2.0).roundToInt()
        val y = front - scaled.height
        return Prop(pl, if (light != null) litSprite(scaled, x, y, light) else scaled, x, y, front)
    }

    /** The season's piece on the floor, on a free tile by the right wall: a jack-o'-lantern or a little tree. */
    private fun seasonal(season: Season, layout: RoomLayout, g: Geometry): List<Placed> {
        val item = when (season) { Season.HALLOWEEN -> Furniture.TOY_BOX; Season.CHRISTMAS -> Furniture.BIG_PLANT; else -> return emptyList() }
        // Borrow the catalogue's footprint; the piece is drawn as the season's own thing (see [piece]).
        for (y in 0 until minOf(g.rows, 2)) for (x in RoomLayout.COLS - 1 downTo 0) if (layout.canPlace(item, x, y)) return listOf(Placed(item, x, y, flip = true))
        return emptyList()
    }

    // ---------------------------------------------------------------- the box

    /** The walls, the ceiling strip on a tall stage, and the floor with its boards running toward the back. */
    private fun box(img: PixelImage, g: Geometry, p: Palette) {
        val w = img.width; val h = img.height; val floorLine = g.floorLine
        val sideDark = Argb.mix(p.side, p.ink, 0.07)
        val seam = Argb.mix(p.floor, p.tileLine, 0.55)
        val skirting = p.floorTop
        for (y in 0 until h) {
            val onFloor = y >= floorLine
            // A floor pixel's depth, or a ceiling pixel's (the ceiling recedes the other way, to the stage's top edge).
            val depth = when {
                onFloor -> g.depth(y)
                y < g.wallTop -> (g.wallTop - y - 0.5) / maxOf(1, g.wallTop) * RoomLayout.ROWS
                else -> 0.0
            }
            var prevU = g.u(-0.5, depth)
            for (x in 0 until w) {
                val u = g.u(x + 0.5, depth)
                val inside = u >= 0 && u < GRID_W
                val edge = (prevU < 0) != (u < 0) || (prevU < GRID_W) != (u < GRID_W)
                img[x, y] = when {
                    !inside && !edge -> if (x > g.cx) sideDark else p.side
                    edge -> if (onFloor || y < g.wallTop) skirting else sideDark
                    !onFloor && y < g.wallTop -> p.ceiling
                    !onFloor -> {
                        // The back wall: paper with a quiet dot pattern, a wainscot band above the floor.
                        val wainscotTop = floorLine - 7
                        when {
                            y == g.wallTop && g.wallTop > 0 -> Argb.mix(p.wall, p.ink, 0.12)
                            y == wainscotTop -> p.wainscotLine
                            y > wainscotTop -> if (floor(u / 8).toInt() != floor((u - 0.5 / g.back) / 8).toInt() && y < floorLine - 1) p.wainscotLine else p.wainscot
                            (x + y * 3) % 10 == 0 && y % 5 == 2 -> p.wallDot
                            else -> p.wall
                        }
                    }
                    y == floorLine -> skirting
                    else -> {
                        // Boards a tile wide running front to back; their ends staggered every few rows.
                        val board = floor(u / TILE_W).toInt()
                        val boardSeam = board != floor(prevU / TILE_W).toInt()
                        val row = (y - floorLine) / TILE_D
                        val joint = (y - floorLine) % TILE_D == 0 && (row + board * 2) % 3 == 0
                        if (boardSeam || joint) seam else p.floor
                    }
                }
                prevU = u
            }
        }
    }

    /** A rug on its tiles, foreshortened with the floor: a bordered rectangle or an oval, with a quiet dot pattern. */
    private fun rug(img: PixelImage, g: Geometry, pl: Placed, p: Palette, round: Boolean) {
        val top = g.tileTop(pl.y); val bottom = g.tileTop(pl.y + pl.item.rows)
        val u0 = pl.x * TILE_W.toDouble(); val uw = pl.item.cols * TILE_W.toDouble(); val dh = (bottom - top).toDouble()
        for (y in top until bottom) {
            if (y !in 0 until img.height) continue
            val depth = g.depth(y)
            val x0 = floor(g.x(u0, depth)).toInt(); val x1 = floor(g.x(u0 + uw, depth)).toInt()
            for (x in x0 until x1) {
                if (x !in 0 until img.width) continue
                val du = g.u(x + 0.5, depth) - u0; val dv = y - top + 0.5
                val c = if (round) {
                    val nx = (du - uw / 2) / (uw / 2); val ny = (dv - dh / 2) / (dh / 2)
                    val r = nx * nx + ny * ny
                    if (r > 1.0) continue
                    if (r > 0.72) p.rugEdge else if ((x + y) % 4 == 0) p.rugDot else p.rug
                } else {
                    val edge = du < 1.0 || du > uw - 1.0 || y == top || y == bottom - 1
                    val corner = (du < 1.0 || du > uw - 1.0) && (y == top || y == bottom - 1)
                    if (corner) continue
                    if (edge) p.rugEdge else if ((x + y) % 5 == 0 && y % 2 == 1) p.rugDot else p.rug
                }
                img[x, y] = c
            }
        }
    }

    // ---------------------------------------------------------------- light and shadow

    /**
     * The room's light: the time of day's ambient, darker where the wall meets the floor and in the
     * corners, a beam slanting from each window (warm sun by day, cool moon by night), a pool of warm
     * light around each lamp (faint by day, the room's light at night), and a glow around the glass.
     */
    private fun lampShades(props: List<Prop>): List<FloatArray> = props.filter { it.placed.item == Furniture.LAMP }.map { floatArrayOf(it.x + it.image.width / 2f, it.y + 4f) }

    private fun light(g: Geometry, phase: Sky.Phase, glass: List<IntArray>, lamps: List<FloatArray>): Light {
        val w = g.width; val h = g.height
        val light = Light(w, h)
        val amb = ambient(phase)
        val night = phase.dark
        // Distances were tuned on a 100-pixel-wide room; a wider stage spreads them.
        val f = (w / 100.0).toFloat().coerceAtLeast(0.5f)
        val cxv = w / 2f; val cyv = h * 0.55f
        val beam = if (night) floatArrayOf(0.06f, 0.10f, 0.20f) else if (phase == Sky.Phase.DUSK) floatArrayOf(0.26f, 0.16f, 0.07f) else floatArrayOf(0.24f, 0.20f, 0.11f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            // Ambient occlusion at the floor seam and down the side edges; the corners fall off a little.
            val dSeam = (y - g.floorLine) / (7f * f)
            val ao = 1f - 0.28f * exp(-dSeam * dSeam) - 0.12f * maxOf(0f, 1f - minOf(x, w - 1 - x) / (10f * f))
            val dv = sqrt(((x - cxv) / w) * ((x - cxv) / w) + ((y - cyv) / h) * ((y - cyv) / h)) * 2f
            val vig = 1f - 0.35f * maxOf(0f, dv - 0.55f) / 0.6f
            var r = amb[0] * ao * vig; var gg = amb[1] * ao * vig; var b = amb[2] * ao * vig
            for (gl in glass) {
                val gx = gl[0]; val gy = gl[1]; val gw = gl[2]; val gh = gl[3]
                val inGlass = x >= gx && x < gx + gw && y >= gy && y < gy + gh
                val wx = gx + gw / 2f; val wy = (gy + gh).toFloat()
                val bx = (x - (wx + (y - wy) * 0.45f)) / (26f * f); val by = (y - wy) / (60f * f)
                var strength = if (inGlass || y < wy - 4) 0f else maxOf(0f, 1f - (bx * bx * 1.1f + by * by * 0.5f)) * minOf(1f, (y - wy + 4) / 10f)
                // Most of the beam lands on the floor; the wall below the sill only warms a little.
                strength *= if (y > g.floorLine) 1.1f else 0.4f
                r += beam[0] * strength; gg += beam[1] * strength; b += beam[2] * strength
                if (!night && !inGlass) {
                    val hx = (x - (gx + gw / 2f)) / (gw * 0.75f); val hy = (y - (gy + gh / 2f)) / (gh * 0.75f)
                    val halo = maxOf(0f, 1f - sqrt(hx * hx + hy * hy)) * 0.12f
                    r += halo; gg += halo * 0.95f; b += halo * 0.8f
                }
            }
            for (lp in lamps) {
                val lx = (x - lp[0]) / ((if (night) 30f else 24f) * f); val ly = (y - lp[1]) / ((if (night) 34f else 26f) * f)
                val pool = exp(-(lx * lx + ly * ly) * 1.2f) * (if (night) 0.95f else 0.22f)
                r += pool; gg += pool * 0.78f; b += pool * 0.45f
            }
            light.r[i] = r.coerceIn(0f, 1.6f); light.g[i] = gg.coerceIn(0f, 1.6f); light.b[i] = b.coerceIn(0f, 1.6f)
        }
        return light
    }

    private fun litSprite(sprite: PixelImage, x: Int, y: Int, light: Light): PixelImage {
        val out = PixelImage(sprite.width, sprite.height)
        for (yy in 0 until sprite.height) for (xx in 0 until sprite.width) out[xx, yy] = light.apply(sprite[xx, yy], x + xx, y + yy)
        return out
    }

    /** The x of the lamp's shade, when there is a lamp: at night shadows fall away from it. */
    private fun lampAt(props: List<Prop>, g: Geometry): Float? = props.firstOrNull { it.placed.item == Furniture.LAMP }?.let { it.x + it.image.width / 2f }

    /**
     * A piece's shadow: its silhouette flattened onto the floor at its feet, pushed a little to the
     * right (the window is on the left by day) or away from the lamp, into [mask] (0..1 per pixel).
     */
    private fun shadowOf(mask: FloatArray, w: Int, h: Int, sprite: PixelImage, x: Int, y: Int, lampX: Float?) {
        val shrink = 3
        val dx = if (lampX != null) ((x + sprite.width / 2f - lampX) * 0.15f).roundToInt().coerceIn(-5, 5) else 3
        val dy = 2
        for (sy in 0 until sprite.height) for (sx in 0 until sprite.width) {
            if (Argb.alpha(sprite[sx, sy]) < 128) continue
            val tx = x + sx + dx
            val ty = y + sprite.height + dy - (sprite.height - sy) / shrink - 1
            if (tx in 0 until w && ty in 0 until h) mask[ty * w + tx] = 1f
        }
    }

    /** Softens [mask] (a small blur, twice) and darkens [img] under it. */
    private fun drawShadows(img: PixelImage, mask: FloatArray, phase: Sky.Phase) {
        val w = img.width; val h = img.height
        var m = mask
        repeat(2) {
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f; var n = 0
                for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) { sum += m[yy * w + xx]; n++ }
                out[y * w + x] = sum / n
            }
            m = out
        }
        val strength = if (phase.dark) 0.55f else 0.42f
        val shade = 0x14 shl 16 or (0x0E shl 8) or 0x28
        for (i in m.indices) if (m[i] > 0.01f) img.pixels[i] = Argb.blend(img.pixels[i], Argb.withAlpha(shade, (m[i] * strength * 255).roundToInt()))
    }

    /** The brightest spots (the window by day, the lamp at night) glow onto what's around them. */
    private fun bloom(img: PixelImage, props: List<Prop>) {
        val w = img.width; val h = img.height
        val bright = IntArray(w * h * 3)
        fun splat(c: Int, x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h || Argb.alpha(c) < 128) return
            val i = (y * w + x) * 3
            bright[i] = maxOf(bright[i], maxOf(0, Argb.r(c) - 215) * 5); bright[i + 1] = maxOf(bright[i + 1], maxOf(0, Argb.g(c) - 215) * 5); bright[i + 2] = maxOf(bright[i + 2], maxOf(0, Argb.b(c) - 215) * 5)
        }
        for (y in 0 until h) for (x in 0 until w) splat(img[x, y], x, y)
        for (p in props) for (yy in 0 until p.image.height) for (xx in 0 until p.image.width) splat(p.image[xx, yy], p.x + xx, p.y + yy)
        if (bright.all { it == 0 }) return
        var g = bright
        repeat(2) {
            val out = IntArray(w * h * 3)
            for (y in 0 until h) for (x in 0 until w) for (c in 0..2) {
                var sum = 0; var n = 0
                for (yy in maxOf(0, y - 2)..minOf(h - 1, y + 2)) for (xx in maxOf(0, x - 2)..minOf(w - 1, x + 2)) { sum += g[(yy * w + xx) * 3 + c]; n++ }
                out[(y * w + x) * 3 + c] = sum / n
            }
            g = out
        }
        for (i in 0 until w * h) {
            val c = img.pixels[i]
            val r = (Argb.r(c) + g[i * 3] * 0.3f).roundToInt().coerceAtMost(255)
            val gg = (Argb.g(c) + g[i * 3 + 1] * 0.3f).roundToInt().coerceAtMost(255)
            val b = (Argb.b(c) + g[i * 3 + 2] * 0.3f).roundToInt().coerceAtMost(255)
            img.pixels[i] = Argb.of(Argb.alpha(c), r, gg, b)
        }
    }

    /**
     * A diorama photographed up close: the top and bottom of [img] fall softly out of focus. For
     * stills; a live view blends its own smooth copy instead.
     */
    fun softFocus(img: PixelImage, top: Double = 0.10, bottom: Double = 0.14): PixelImage {
        val w = img.width; val h = img.height
        val blurred = PixelImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) { val c = img[xx, yy]; a += Argb.alpha(c); r += Argb.r(c); g += Argb.g(c); b += Argb.b(c); n++ }
            blurred[x, y] = Argb.of(a / n, r / n, g / n, b / n)
        }
        val out = img.copy()
        for (y in 0 until h) {
            val t = (y + 0.5) / h
            val share = if (t < top) (top - t) / top else if (t > 1 - bottom) (t - (1 - bottom)) / bottom else 0.0
            if (share <= 0) continue
            val k = share * sqrt(share)
            for (x in 0 until w) out[x, y] = Argb.mix(img[x, y], blurred[x, y], k)
        }
        return out
    }

    // ---------------------------------------------------------------- pieces

    /**
     * The drawing of a standing piece at its own size: [Furniture.cols] tiles wide, its top face
     * [Furniture.rows] tiles deep, and its height below that. [seasonal] draws the season's stand-in
     * on that footprint (a pumpkin, a tree).
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

    /** A wall piece's drawing, with where its top-left goes relative to its column's x and its slot's bottom, and the window's glass rectangle within it. */
    class WallSprite(val image: PixelImage, val dx: Int, val dy: Int, val glass: IntArray = intArrayOf(0, 0, 0, 0))

    /** The drawing of a wall piece at its own size (see [WallSprite]). */
    fun wallPiece(pl: Placed, p: Palette, phase: Sky.Phase, seed: Int = 7): WallSprite {
        // Drawn on a scratch wall with the column at x = 8 and the slot's bottom at y = 32, then cut out.
        val pad = 8; val bottom = 32
        val scratch = PixelImage(pl.item.cols * TILE_W + 2 * pad, bottom + 6)
        var glass: IntArray? = null
        when (pl.item) {
            Furniture.WINDOW -> { val gw = TILE_W * 3 - 2; window(scratch, pad + 1, bottom - 24, gw, 22, phase, p, seed); glass = intArrayOf(pad + 1, bottom - 24, gw, 22) }
            Furniture.SHELF -> shelf(scratch, pad, bottom, p, pl.flip)
            Furniture.FRAME -> frame(scratch, pad, bottom - 8, p)
            Furniture.CLOCK -> clock(scratch, pad + 4, bottom - 5, p)
            Furniture.HANGING_PLANT -> hanging(scratch, pad + 4, bottom - 18, p)
            Furniture.POSTER -> poster(scratch, pad, bottom - 12, p)
            Furniture.STRING_LIGHTS -> stringLights(scratch, pad, bottom - 6, TILE_W * 4, p, phase)
            Furniture.PAW_BANNER -> banner(scratch, pad + 1, bottom - 14, p)
            else -> Unit
        }
        val b = Animator.opaqueBounds(scratch) ?: return WallSprite(PixelImage(1, 1), 0, 0)
        val img = PixelImage(b[2] - b[0], b[3] - b[1])
        for (y in 0 until img.height) for (x in 0 until img.width) img[x, y] = scratch[b[0] + x, b[1] + y]
        val g = glass?.let { intArrayOf(it[0] - b[0], it[1] - b[1], it[2], it[3]) } ?: intArrayOf(0, 0, 0, 0)
        return WallSprite(img, b[0] - pad, b[1] - bottom, g)
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
        for (xx in 5 until w - 5) for (yy in 3 until d - 1) set(img, xx, yy, p.cushion)
        return img
    }

    private fun bowl(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 3)
        val rows = listOf("..kkkk...", ".kkkkkk..", "bbbbbbbb.", "dbbbbbbd.", ".dddddd..", "..dddd...")
        rows.forEachIndexed { i, row -> row.forEachIndexed { j, c -> if (c != '.') set(img, j, img.height - 6 + i, when (c) { 'k' -> p.kibble; 'b' -> p.bowl; else -> p.bowlDark }) } }
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
        for (yy in 6 until TILE_D + 8) { set(img, 0, yy, p.fabricDark); set(img, 1, yy, p.fabricLight); set(img, TILE_W - 2, yy, p.fabric); set(img, TILE_W - 1, yy, p.fabricDark) }
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
        for (yy in TILE_D until TILE_D + 3) for (xx in 2..4) set(img, xx, yy, p.metalDark)
        set(img, 6, TILE_D + 1, p.metal); set(img, 7, TILE_D + 1, p.metal); set(img, 7, 0, p.metalDark); set(img, 8, 0, p.metalDark)
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
        val top = img.height - rows.size
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> when (ch) { 'x' -> set(img, c, top + r, if (c == 2 || c == 6) dark else orange); 'g' -> set(img, c, top + r, 0xFF5EA64C.toInt()) } } }
        set(img, 2, top + 3, face); set(img, 6, top + 3, face)
        for (c in 2..6) set(img, c, top + 5, face); set(img, 3, top + 6, face); set(img, 5, top + 6, face)
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

    // ---------------------------------------------------------------- wall decor

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

    /** A string of lights along the top of the back wall: a dark wire with bulbs in turn red, green and gold. */
    private fun lights(img: PixelImage, g: Geometry, top: Int, p: Palette) = stringLights(img, g.backLeft + 2, maxOf(top, g.wallTop + 2), g.backRight - g.backLeft - 4, p, Sky.Phase.NIGHT)

    /** A garland of little hearts. */
    private fun hearts(img: PixelImage, g: Geometry, top0: Int, p: Palette) {
        val top = maxOf(top0, g.wallTop + 2)
        val w = g.backRight - g.backLeft
        val colours = intArrayOf(0xFFE8374E.toInt(), 0xFFFFA9C9.toInt())
        var i = 0; var x = g.backLeft + 2
        while (x + 6 < g.backRight) {
            val t = (x + 3 - g.backLeft - w / 2.0) / (w / 2.0)
            val y = top + (3 * (1 - t * t)).toInt()
            for (xx in x until x + 8) set(img, xx, y, p.wood)
            val c = colours[i % colours.size]
            val rows = listOf(".xx.xx.", "xxxxxxx", "xxxxxxx", ".xxxxx.", "..xxx..", "...x...")
            rows.forEachIndexed { r, row -> row.forEachIndexed { cc, ch -> if (ch == 'x') set(img, x + cc, y + 1 + r, c) } }
            i++; x += 8
        }
    }

    /** A string of little flags across the top of the wall, sagging a little in the middle. */
    private fun bunting(img: PixelImage, g: Geometry, top0: Int, p: Palette, colours: IntArray) {
        val top = maxOf(top0, g.wallTop + 2)
        val w = g.backRight - g.backLeft
        var i = 0; var x = g.backLeft + 2
        while (x + 6 < g.backRight) {
            val t = (x + 3 - g.backLeft - w / 2.0) / (w / 2.0)
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
