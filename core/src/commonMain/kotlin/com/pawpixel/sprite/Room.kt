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

    data class Palette(
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

    /** The same room by night: everything dimmed toward a deep blue, the lamp's shade still warm. */
    private fun night(d: Palette) = Palette(
        wall = dim(d.wall, 0.62), wallDot = dim(d.wallDot, 0.62), wainscot = dim(d.wainscot, 0.62), wainscotLine = dim(d.wainscotLine, 0.62), side = dim(d.side, 0.62), ceiling = dim(d.ceiling, 0.65),
        floor = dim(d.floor, 0.58), plank = dim(d.plank, 0.58), floorTop = dim(d.floorTop, 0.58), tileLine = dim(d.tileLine, 0.58),
        frame = dim(d.frame, 0.55), sill = dim(d.sill, 0.55), curtain = dim(d.curtain, 0.5), curtainDark = dim(d.curtainDark, 0.5),
        wood = dim(d.wood, 0.5), woodLight = dim(d.woodLight, 0.5), woodDark = dim(d.woodDark, 0.5),
        pot = dim(d.pot, 0.5), leaf = dim(d.leaf, 0.5), leafDark = dim(d.leafDark, 0.5),
        cushion = dim(d.cushion, 0.5), cushionLight = dim(d.cushionLight, 0.5), cushionDark = dim(d.cushionDark, 0.5),
        rug = dim(d.rug, 0.55), rugEdge = dim(d.rugEdge, 0.55), rugDot = dim(d.rugDot, 0.55),
        bowl = dim(d.bowl, 0.5), bowlDark = dim(d.bowlDark, 0.5), kibble = dim(d.kibble, 0.5), lamp = dim(d.lamp, 0.4),
        shade = 0xFFFFE9B0.toInt(),
        fabric = dim(d.fabric, 0.5), fabricLight = dim(d.fabricLight, 0.5), fabricDark = dim(d.fabricDark, 0.5),
        glass = dim(d.glass, 0.45), water = dim(d.water, 0.4), fish = dim(d.fish, 0.3), metal = dim(d.metal, 0.5), metalDark = dim(d.metalDark, 0.5), ink = dim(d.ink, 0.3),
    )

    /** Each room's walls and floor by day: sage and walnut for the living room, butter and foam tiles for the playroom, hedge and grass for the garden. */
    private val DAY_BY_THEME: Map<RoomTheme, Palette> = mapOf(
        RoomTheme.COZY to DAY,
        RoomTheme.LIVING to DAY.copy(
            wall = 0xFFD3DFC8.toInt(), wallDot = 0xFFC4D3B7.toInt(), wainscot = 0xFFEFE6D6.toInt(), wainscotLine = 0xFFD6C7B0.toInt(), side = 0xFFC6D3BA.toInt(), ceiling = 0xFFE8EDE1.toInt(),
            floor = 0xFFB27C50.toInt(), plank = 0xFFA36E46.toInt(), floorTop = 0xFF7E5232.toInt(), tileLine = 0xFF9A6A42.toInt(),
            rug = 0xFFD9C3A5.toInt(), rugEdge = 0xFFB89A74.toInt(), rugDot = 0xFFE8D7BE.toInt(),
        ),
        RoomTheme.PLAYROOM to DAY.copy(
            wall = 0xFFFFF1BC.toInt(), wallDot = 0xFFFFD98E.toInt(), wainscot = 0xFFFFE6C4.toInt(), wainscotLine = 0xFFF2CF9C.toInt(), side = 0xFFF6E3A8.toInt(), ceiling = 0xFFFFF7DC.toInt(),
            floor = 0xFFC2E4F2.toInt(), plank = 0xFFB0D9EC.toInt(), floorTop = 0xFF7FB8D6.toInt(), tileLine = 0xFF9FCDE4.toInt(),
            rug = 0xFFFFC2CF.toInt(), rugEdge = 0xFFE88AA4.toInt(), rugDot = 0xFFFFE0E8.toInt(),
        ),
        RoomTheme.GARDEN to DAY.copy(
            wall = 0xFF9ECC80.toInt(), wallDot = 0xFF86B86A.toInt(), wainscot = 0xFFF4EAD6.toInt(), wainscotLine = 0xFFCDB996.toInt(), side = 0xFF8CBE70.toInt(), ceiling = 0xFFCFE6FA.toInt(),
            floor = 0xFFA9D67E.toInt(), plank = 0xFF99C96E.toInt(), floorTop = 0xFF7EB35C.toInt(), tileLine = 0xFF8EC264.toInt(),
            rug = 0xFFE3D3B2.toInt(), rugEdge = 0xFFC0A980.toInt(), rugDot = 0xFFF0E4CB.toInt(),
        ),
    )
    private val MORE_DAY_BY_THEME: Map<RoomTheme, Palette> = mapOf(
        RoomTheme.BEDROOM to DAY.copy(
            wall = 0xFFE4DBEF.toInt(), wallDot = 0xFFF6F0FA.toInt(), wainscot = 0xFFF0E9F5.toInt(), wainscotLine = 0xFFD3C7E1.toInt(), side = 0xFFD9D0E7.toInt(), ceiling = 0xFFEDE7F5.toInt(),
            floor = 0xFFCBD1E2.toInt(), plank = 0xFFC1C8DC.toInt(), floorTop = 0xFF9AA3C2.toInt(), tileLine = 0xFFB3BBD2.toInt(),
            rug = 0xFFF3D9DE.toInt(), rugEdge = 0xFFD9A6B2.toInt(), rugDot = 0xFFFBEAEE.toInt(),
        ),
        RoomTheme.KITCHEN to DAY.copy(
            wall = 0xFFFBF3E2.toInt(), wallDot = 0xFFE8DCC6.toInt(), wainscot = 0xFFDCEAE6.toInt(), wainscotLine = 0xFFB9D2CB.toInt(), side = 0xFFF1E8D4.toInt(), ceiling = 0xFFFBF6EC.toInt(),
            floor = 0xFFF2EBDD.toInt(), plank = 0xFFC9BFB0.toInt(), floorTop = 0xFF9A8E7C.toInt(), tileLine = 0xFFD9D0C2.toInt(),
            rug = 0xFFE8A79A.toInt(), rugEdge = 0xFFC97A6C.toInt(), rugDot = 0xFFF3C9BF.toInt(),
        ),
        RoomTheme.BATHROOM to DAY.copy(
            wall = 0xFFD9EEF2.toInt(), wallDot = 0xFFBFDCE3.toInt(), wainscot = 0xFFBFE0E8.toInt(), wainscotLine = 0xFF98C7D3.toInt(), side = 0xFFCCE5EB.toInt(), ceiling = 0xFFEAF5F7.toInt(),
            floor = 0xFFEDEFF1.toInt(), plank = 0xFFD5DADF.toInt(), floorTop = 0xFFA7B0B8.toInt(), tileLine = 0xFFC5CCD2.toInt(),
            rug = 0xFFBFE3F2.toInt(), rugEdge = 0xFF7FB8D6.toInt(), rugDot = 0xFFDCF0F8.toInt(),
        ),
    )
    private val NIGHT_BY_THEME: Map<RoomTheme, Palette> = (DAY_BY_THEME + MORE_DAY_BY_THEME).mapValues { night(it.value) }

    fun palette(phase: Sky.Phase, theme: RoomTheme = RoomTheme.COZY): Palette = (if (phase.dark) NIGHT_BY_THEME else DAY_BY_THEME + MORE_DAY_BY_THEME).getValue(theme)

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
        val p = palette(phase, layout.theme)
        val img = PixelImage(width, height)
        val floorLine = floorLine0.coerceIn(10, height)
        val g = Geometry(width, height, floorLine)
        box(img, g, p, layout.theme)

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
                Furniture.RUG -> rug(img, g, pl, p, Flat.RUG)
                Furniture.ROUND_RUG -> rug(img, g, pl, p, Flat.ROUND)
                Furniture.POND -> rug(img, g, pl, p, Flat.POND)
                Furniture.KITCHEN_MAT, Furniture.BATH_MAT -> rug(img, g, pl, p, Flat.RUG)
                Furniture.PLAY_MAT -> rug(img, g, pl, p, Flat.PLAY_MAT)
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
    private fun box(img: PixelImage, g: Geometry, p: Palette, theme: RoomTheme) {
        val w = img.width; val h = img.height; val floorLine = g.floorLine
        val sideDark = Argb.mix(p.side, p.ink, 0.07)
        val seam = Argb.mix(p.floor, p.tileLine, 0.55)
        val skirting = p.floorTop
        // A garden has no wall: a hedge up to about head height, and sky above it (with a cloud or two).
        val hedgeTop = if (theme == RoomTheme.GARDEN) floorLine - minOf(46, floorLine * 11 / 20) else -1
        val sky = p.ceiling; val skyDeep = Argb.mix(p.ceiling, 0xFF7FB2E6.toInt(), 0.35); val cloud = Argb.withAlpha(Argb.mix(p.ceiling, 0xFFFFFFFF.toInt(), 0.8), 0xD0)
        for (y in 0 until h) {
            if (y < hedgeTop) {
                // Sky: deeper blue high up, a soft cloud band, the hedge's top edge a shade darker.
                val t = y.toDouble() / maxOf(1, hedgeTop)
                val base = Argb.mix(skyDeep, sky, t)
                for (x in 0 until w) img[x, y] = if (y == hedgeTop - 1) Argb.mix(p.wall, p.ink, 0.15) else base
                continue
            }
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
                        // The back wall: paper (or hedge) with the room's own pattern, a wainscot band (or picket fence) above the floor.
                        val wainscotTop = floorLine - 7
                        when {
                            y == g.wallTop && g.wallTop > 0 -> Argb.mix(p.wall, p.ink, 0.12)
                            y == wainscotTop -> p.wainscotLine
                            y > wainscotTop -> when (theme) {
                                // Pickets two units wide with a gap of hedge between, a rail along the top.
                                RoomTheme.GARDEN -> if (floor(u).toInt().mod(4) == 3 && y < floorLine - 1) p.wallDot else if (y == wainscotTop + 1) p.wainscotLine else p.wainscot
                                RoomTheme.KITCHEN, RoomTheme.BATHROOM -> if (floor(u).toInt().mod(4) == 0 || y == floorLine - 4) p.wainscotLine else p.wainscot // smaller tiles
                                else -> if (floor(u / 8).toInt() != floor((u - 0.5 / g.back) / 8).toInt() && y < floorLine - 1) p.wainscotLine else p.wainscot
                            }
                            else -> when (theme) {
                                RoomTheme.KITCHEN, RoomTheme.BATHROOM -> if (floor(u).toInt().mod(6) == 0 || y % 6 == 0) p.wallDot else p.wall // tiles with grout
                                RoomTheme.BEDROOM -> if ((x * 5 + y * 7) % 23 == 0 || (x * 3 + y * 11) % 29 == 0) p.wallDot else p.wall // a scatter of stars
                                RoomTheme.LIVING -> if (floor(u / 6).toInt() % 2 == 0 && floor(u).toInt().mod(6) == 0) p.wallDot else p.wall // thin stripes
                                RoomTheme.PLAYROOM -> if ((x % 7 == 2 || x % 7 == 3) && ((y + (x / 7) * 3) % 7 == 1 || (y + (x / 7) * 3) % 7 == 2)) p.wallDot else p.wall // polka dots
                                RoomTheme.GARDEN -> if ((x * 3 + y * 5) % 7 == 0 || (x * 5 + y * 3) % 11 == 4) p.wallDot else p.wall // leaves
                                RoomTheme.COZY -> if ((x + y * 3) % 10 == 0 && y % 5 == 2) p.wallDot else p.wall
                            }
                        }
                    }
                    y == floorLine -> skirting
                    else -> when (theme) {
                        RoomTheme.KITCHEN -> {
                            // A checker of cream and stone, one square a tile.
                            val col = floor(u / TILE_W).toInt(); val row = (y - floorLine) / TILE_D
                            val tileSeam = col != floor(prevU / TILE_W).toInt() || (y - floorLine) % TILE_D == 0
                            if (tileSeam) p.tileLine else if ((col + row) % 2 == 0) p.floor else p.plank
                        }
                        RoomTheme.BATHROOM -> {
                            // Small mosaic tiles, three units a side, with grout.
                            val cu = floor(u / 3).toInt(); val cy = (y - floorLine) / 3
                            if (floor(u).toInt().mod(3) == 0 || (y - floorLine) % 3 == 0) p.tileLine else if ((cu + cy) % 2 == 0) p.floor else p.plank
                        }
                        RoomTheme.BEDROOM -> {
                            // Carpet: a soft pile, two close tones.
                            val n = (x * 7 + y * 13 + (x xor y)) % 11
                            if (n < 3) p.plank else p.floor
                        }
                        RoomTheme.PLAYROOM -> {
                            // Foam tiles: a soft checker, one tile each, with a seam around every tile.
                            val col = floor(u / TILE_W).toInt(); val row = (y - floorLine) / TILE_D
                            val tileSeam = col != floor(prevU / TILE_W).toInt() || (y - floorLine) % TILE_D == 0
                            if (tileSeam) p.tileLine else if ((col + row) % 2 == 0) p.floor else p.plank
                        }
                        RoomTheme.GARDEN -> {
                            // Grass: two greens scattered like blades, a darker tuft here and there.
                            val n = (x * 7 + y * 13 + (x xor y) * 3) % 17
                            if (n == 0) p.floorTop else if (n < 6) p.plank else p.floor
                        }
                        else -> {
                            // Boards a tile wide running front to back; their ends staggered every few rows.
                            val board = floor(u / TILE_W).toInt()
                            val boardSeam = board != floor(prevU / TILE_W).toInt()
                            val row = (y - floorLine) / TILE_D
                            val joint = (y - floorLine) % TILE_D == 0 && (row + board * 2) % 3 == 0
                            if (boardSeam || joint) seam else p.floor
                        }
                    }
                }
                prevU = u
            }
        }
        clouds(img, g, hedgeTop, cloud)
    }

    private enum class Flat { RUG, ROUND, POND, PLAY_MAT }

    /** A few soft clouds in the garden's sky, drawn after the box so they sit over the sky and nothing else. */
    private fun clouds(img: PixelImage, g: Geometry, hedgeTop: Int, cloud: Int) {
        if (hedgeTop < 12) return
        cloud(img, g.backLeft + 6, hedgeTop / 3, cloud); cloud(img, g.backLeft + 10, hedgeTop / 3 + 2, cloud)
        cloud(img, g.backRight - 22, hedgeTop / 2 + 1, cloud); cloud(img, g.backRight - 17, hedgeTop / 2 - 1, cloud)
        if (hedgeTop > 30) cloud(img, g.cx.toInt() - 4, hedgeTop / 6, cloud)
    }

    /** A rug (or a pond) on its tiles, foreshortened with the floor: a bordered rectangle or an oval, with a quiet dot pattern. */
    private fun rug(img: PixelImage, g: Geometry, pl: Placed, p: Palette, kind: Flat) {
        val top = g.tileTop(pl.y); val bottom = g.tileTop(pl.y + pl.item.rows)
        val u0 = pl.x * TILE_W.toDouble(); val uw = pl.item.cols * TILE_W.toDouble(); val dh = (bottom - top).toDouble()
        for (y in top until bottom) {
            if (y !in 0 until img.height) continue
            val depth = g.depth(y)
            val x0 = floor(g.x(u0, depth)).toInt(); val x1 = floor(g.x(u0 + uw, depth)).toInt()
            for (x in x0 until x1) {
                if (x !in 0 until img.width) continue
                val du = g.u(x + 0.5, depth) - u0; val dv = y - top + 0.5
                val c = if (kind == Flat.POND) {
                    // Still water with a stone rim, a ripple ring and a lily pad.
                    val nx = (du - uw / 2) / (uw / 2); val ny = (dv - dh / 2) / (dh / 2)
                    val r = nx * nx + ny * ny
                    if (r > 1.0) continue
                    val pad = (du - uw * 0.3).let { it * it } + (dv - dh * 0.6).let { it * it * 2 } < 6.0
                    if (r > 0.78) p.metalDark else if (r > 0.6) p.metal else if (pad) p.leafDark else if (r in 0.22..0.3) p.glass else p.water
                } else if (kind == Flat.PLAY_MAT) {
                    // Foam squares in four colours, like a jigsaw play mat.
                    val cu = floor(du / (uw / 4)).toInt().coerceIn(0, 3); val cv = floor(dv / (dh / 2)).toInt().coerceIn(0, 1)
                    val colours = intArrayOf(p.curtain, 0xFFFFCF5C.toInt(), p.bowl, p.leaf)
                    val c = colours[(cu + cv) % 4]
                    val edge = du < 1.0 || du > uw - 1.0 || y == top || y == bottom - 1 || floor(du).toInt().mod((uw / 4).toInt().coerceAtLeast(1)) == 0 || (y - top) == (dh / 2).toInt()
                    if (edge) Argb.mix(c, p.ink, 0.3) else if ((x + y) % 5 == 0) Argb.mix(c, p.frame, 0.25) else c
                } else if (kind == Flat.ROUND) {
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
    private fun lampShades(props: List<Prop>): List<FloatArray> = props.filter { it.placed.item == Furniture.LAMP || it.placed.item == Furniture.GARDEN_LANTERN || it.placed.item == Furniture.MOON_LAMP || it.placed.item == Furniture.FIREPLACE }.map { floatArrayOf(it.x + it.image.width / 2f, it.y + 4f) }

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
    private fun lampAt(props: List<Prop>, g: Geometry): Float? = props.firstOrNull { it.placed.item == Furniture.LAMP || it.placed.item == Furniture.GARDEN_LANTERN || it.placed.item == Furniture.MOON_LAMP || it.placed.item == Furniture.FIREPLACE }?.let { it.x + it.image.width / 2f }

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
            Furniture.SOFA -> sofa(w, d, p)
            Furniture.TV -> tv(w, d, p, phase)
            Furniture.COFFEE_TABLE -> coffeeTable(w, d, p)
            Furniture.FLOOR_CUSHION -> floorCushion(w, d, p)
            Furniture.RECORD_PLAYER -> recordPlayer(w, d, p)
            Furniture.TOY_CHEST -> toyChest(w, d, p)
            Furniture.BLOCKS -> blocks(p)
            Furniture.PLAY_TUNNEL -> playTunnel(w, d, p)
            Furniture.BALL_PIT -> ballPit(w, d, p, seed)
            Furniture.ROCKING_HORSE -> rockingHorse(w, d, p)
            Furniture.PLAY_TENT -> playTent(w, d, p)
            Furniture.FLOWER_BED -> flowerBed(w, d, p)
            Furniture.GARDEN_BENCH -> gardenBench(w, d, p)
            Furniture.GARDEN_LANTERN -> gardenLantern(p)
            Furniture.BIRD_BATH -> birdBath(p)
            Furniture.LITTLE_TREE -> littleTree(w, d, p)
            Furniture.BIG_BED -> bigBed(w, d, p)
            Furniture.NIGHTSTAND -> nightstand(w, d, p)
            Furniture.MOON_LAMP -> moonLamp(p)
            Furniture.SLIPPERS -> slippers(p)
            Furniture.WARDROBE -> wardrobe(w, d, p)
            Furniture.LAUNDRY_BASKET -> laundryBasket(p)
            Furniture.COUNTER -> counter(w, d, p)
            Furniture.BOWL_STAND -> bowlStand(p)
            Furniture.STOVE -> stove(w, d, p)
            Furniture.FRIDGE -> fridge(w, d, p)
            Furniture.CAT_GRASS -> catGrass(p)
            Furniture.BATHTUB -> bathtub(w, d, p)
            Furniture.SINK -> sink(p)
            Furniture.RUBBER_DUCK -> rubberDuck(p)
            Furniture.TOWEL_STACK -> towelStack(p)
            Furniture.WASHING_MACHINE -> washingMachine(w, d, p)
            Furniture.FIREPLACE -> fireplace(w, d, p, phase)
            Furniture.SLIDE -> slide(w, d, p)
            Furniture.TRAIN_SET -> trainSet(w, d, p)
            Furniture.DOGHOUSE -> doghouse(w, d, p)
            Furniture.WAGON -> wagon(p)
            Furniture.SCARECROW -> scarecrow(p)
            Furniture.PICKET_FENCE -> picketFence(w, d, p)
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
            Furniture.BUNTING -> buntingStrip(scratch, pad, bottom - 10, TILE_W * 4, p, intArrayOf(p.curtain, p.bowl, 0xFFFFCF5C.toInt(), p.leaf))
            Furniture.CHALKBOARD -> chalkboard(scratch, pad, bottom - 14, TILE_W * 2, p)
            Furniture.BIRDHOUSE -> birdhouse(scratch, pad + 1, bottom - 16, p)
            Furniture.WIND_CHIME -> windChime(scratch, pad + 4, bottom - 18, p)
            Furniture.STAR_GARLAND -> starGarland(scratch, pad, bottom - 8, TILE_W * 4, p)
            Furniture.DREAMCATCHER -> dreamcatcher(scratch, pad + 4, bottom - 16, p)
            Furniture.HERB_SHELF -> herbShelf(scratch, pad, bottom, TILE_W * 2, p)
            Furniture.POT_RACK -> potRack(scratch, pad, bottom - 12, TILE_W * 2, p)
            Furniture.MIRROR -> mirror(scratch, pad, bottom - 12, p)
            Furniture.TOWEL_RAIL -> towelRail(scratch, pad, bottom - 10, TILE_W * 2, p)
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

    // ---- living room ----

    private fun sofa(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 11)
        // A long back rest, the seat in front of it, an arm at each end and two cushions.
        box(img, 1, 0, w - 2, 2, 7, p.fabricLight, p.fabric, p.fabricDark, p.fabricDark)
        box(img, 1, 7, w - 2, d, 4, p.fabricLight, p.fabric, p.fabricDark, p.fabricDark)
        for (yy in 5 until d + 10) { set(img, 0, yy, p.fabricDark); set(img, 1, yy, p.fabricLight); set(img, w - 2, yy, p.fabric); set(img, w - 1, yy, p.fabricDark) }
        for (xx in 3 until w - 3) if (xx % 8 == 3) for (yy in 8 until 7 + d) set(img, xx, yy, p.fabricDark)
        for ((cx, c) in listOf(6 to p.cushion, w - 10 to p.curtain)) for (yy in 2..5) for (xx in cx until cx + 5) set(img, xx, yy, if (yy == 2 || xx == cx) Argb.mix(c, p.frame, 0.3) else c)
        return img
    }

    private fun tv(w: Int, d: Int, p: Palette, phase: Sky.Phase): PixelImage {
        val img = PixelImage(w, d + 16)
        // A low stand with a dark screen standing on it; by night the screen shows a dim glow.
        box(img, 0, d + 10, w, 2, 4, p.woodLight, p.wood, p.woodDark, p.woodDark)
        val screen = if (phase.dark) 0xFF4A6E8C.toInt() else 0xFF2F3A4A.toInt()
        for (yy in 0 until d + 9) for (xx in 1 until w - 1) set(img, xx, yy, if (yy == 0 || yy == d + 8 || xx == 1 || xx == w - 2) p.metalDark else screen)
        for (xx in 3..5) set(img, xx, 2, Argb.mix(screen, 0xFFFFFFFF.toInt(), 0.35))
        if (phase.dark) for (yy in 3 until d + 6) for (xx in 4 until w - 4) if ((xx + yy) % 3 == 0) set(img, xx, yy, Argb.mix(screen, 0xFFFFE9B0.toInt(), 0.25))
        for (xx in w / 2 - 1..w / 2) set(img, xx, d + 9, p.metalDark)
        return img
    }

    private fun coffeeTable(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 6)
        for (lx in listOf(1, w - 3)) for (yy in d + 2 until d + 6) { set(img, lx, yy, p.woodDark); set(img, lx + 1, yy, p.wood) }
        box(img, 0, 0, w, d, 2, p.woodLight, p.wood, p.woodDark, p.woodDark)
        // A book and a mug on it.
        for (xx in 3..7) for (yy in 2..3) set(img, xx, yy, if (yy == 2) p.curtainDark else p.curtain)
        for (xx in w - 6..w - 4) set(img, xx, 2, p.frame); set(img, w - 3, 2, p.frame)
        return img
    }

    private fun floorCushion(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 3)
        box(img, 0, 0, w, d, 2, p.rugDot, p.rug, p.rugEdge, p.rugEdge)
        img[0, 0] = 0; img[w - 1, 0] = 0; img[0, d + 2] = 0; img[w - 1, d + 2] = 0
        set(img, w / 2, d / 2, p.rugEdge)
        return img
    }

    private fun recordPlayer(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 5)
        box(img, 0, 2, w, d - 1, 4, p.woodLight, p.wood, p.woodDark, p.woodDark)
        // The record, its label, and the arm across it.
        disc(img, 4, d / 2 + 2, 3.0, p.ink); set(img, 4, d / 2 + 2, p.curtain)
        for (yy in 1..3) set(img, w - 2, yy, p.metal); set(img, w - 3, 4, p.metal); set(img, 6, 4, p.metal); set(img, 5, 4, p.metal)
        return img
    }

    // ---- playroom ----

    private fun toyChest(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 8)
        box(img, 0, 2, w, d - 1, 7, p.bowl, p.bowlDark, Argb.mix(p.bowlDark, p.ink, 0.3), Argb.mix(p.bowlDark, p.ink, 0.45))
        // A lifted lid with toys peeking out: a ball, a block, a bone.
        for (xx in 1 until w - 1) set(img, xx, 2, Argb.mix(p.bowl, p.frame, 0.3))
        set(img, 3, 1, p.curtain); set(img, 4, 1, p.curtain); set(img, 3, 0, p.curtain); set(img, 4, 0, p.curtainDark)
        for (xx in 7..9) set(img, xx, 1, 0xFFFFCF5C.toInt()); set(img, 8, 0, 0xFFFFCF5C.toInt())
        for (xx in 12..14) set(img, xx, 1, p.frame); set(img, 12, 0, p.frame); set(img, 14, 0, p.frame)
        return img
    }

    private fun blocks(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 6)
        // Three stacked blocks, each a little box with a letter-like mark.
        val colours = listOf(p.curtain, p.bowl, p.leaf)
        box(img, 1, 8, 5, 2, 3, Argb.mix(colours[0], p.frame, 0.3), colours[0], Argb.mix(colours[0], p.ink, 0.3), Argb.mix(colours[0], p.ink, 0.45))
        box(img, 4, 8, 5, 2, 3, Argb.mix(colours[1], p.frame, 0.3), colours[1], Argb.mix(colours[1], p.ink, 0.3), Argb.mix(colours[1], p.ink, 0.45))
        box(img, 2, 3, 5, 2, 3, Argb.mix(colours[2], p.frame, 0.3), colours[2], Argb.mix(colours[2], p.ink, 0.3), Argb.mix(colours[2], p.ink, 0.45))
        set(img, 4, 6, p.frame); set(img, 2, 11, p.frame); set(img, 6, 11, p.frame)
        return img
    }

    private fun playTunnel(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 10)
        // A fabric tube lying across: striped body, a dark round mouth at the right end.
        for (yy in 2 until d + 9) for (xx in 2 until w - 4) set(img, xx, yy, if ((xx / 4) % 2 == 0) p.fabric else p.fabricLight)
        for (xx in 2 until w - 4) { set(img, xx, 2, p.fabricDark); set(img, xx, d + 8, p.fabricDark) }
        for (yy in 0 until d + 11) for (xx in w - 8 until w) {
            val nx = (xx - (w - 4) + 0.5) / 4.0; val ny = (yy - (d + 10) / 2.0 + 0.5) / ((d + 10) / 2.0)
            val r = nx * nx + ny * ny
            if (r <= 1.0) set(img, xx, yy, if (r > 0.7) p.fabricDark else if (r > 0.45) p.fabric else p.ink)
        }
        return img
    }

    private fun ballPit(w: Int, d: Int, p: Palette, seed: Int): PixelImage {
        val img = PixelImage(w, d + 5)
        box(img, 0, 0, w, d, 5, p.fabricLight, p.fabric, p.fabricDark, p.fabricDark)
        // Balls piled on top, in the toy box's colours.
        val colours = intArrayOf(p.curtain, p.bowl, 0xFFFFCF5C.toInt(), p.leaf, p.cushion)
        val r = Random(seed)
        for (yy in 1 until d - 1 step 2) for (xx in 1 until w - 2 step 3) {
            val c = colours[r.nextInt(colours.size)]; val ox = xx + r.nextInt(2)
            set(img, ox, yy, c); set(img, ox + 1, yy, c); set(img, ox, yy + 1, Argb.mix(c, p.ink, 0.25)); set(img, ox + 1, yy + 1, c)
        }
        return img
    }

    private fun rockingHorse(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 12)
        val wood = p.wood; val dark = p.woodDark
        // Body, head up on the right, a mane, the rockers underneath.
        for (yy in 5..9) for (xx in 4 until w - 5) set(img, xx, yy, if (yy == 5 || yy == 9) dark else wood)
        for (yy in 1..7) for (xx in w - 6 until w - 2) set(img, xx, yy, if (yy == 1 || xx == w - 3) dark else wood)
        set(img, w - 3, 3, p.ink); set(img, w - 7, 2, p.curtain); set(img, w - 7, 3, p.curtain); set(img, w - 8, 4, p.curtain)
        for (xx in 5..7) for (yy in 9 until d + 9) set(img, xx, yy, dark)
        for (xx in w - 9 until w - 7) for (yy in 9 until d + 9) set(img, xx, yy, dark)
        for (xx in 1 until w - 1) { val dip = if (xx < 4 || xx > w - 5) 1 else 0; set(img, xx, d + 9 + dip, dark); set(img, xx, d + 10 + dip, p.woodLight) }
        return img
    }

    private fun playTent(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 16)
        val h = d + 15; val cx = w / 2
        // A tipi: a striped triangle with a dark opening and a pole at the top.
        for (yy in 1..h) {
            val half = (yy * (w / 2 - 1)) / h
            for (xx in cx - half..cx + half) {
                val stripe = ((xx - cx + half) / 3) % 2 == 0
                set(img, xx, yy, if (xx == cx - half || xx == cx + half) p.fabricDark else if (stripe) p.fabricLight else p.fabric)
            }
        }
        for (yy in h - 7..h) { val half = (yy - (h - 7)) / 3 + 1; for (xx in cx - half..cx + half) set(img, xx, yy, p.ink) }
        set(img, cx, 0, p.woodDark); set(img, cx - 1, 0, p.woodDark); set(img, cx + 1, 0, p.woodDark)
        return img
    }

    // ---- garden ----

    private fun flowerBed(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 8)
        box(img, 0, 5, w, d, 3, Argb.mix(p.wood, p.ink, 0.35), p.woodDark, Argb.mix(p.woodDark, p.ink, 0.3), Argb.mix(p.woodDark, p.ink, 0.45))
        for (xx in 1 until w - 1) for (yy in 6 until 5 + d) if ((xx + yy) % 3 != 0) set(img, xx, yy, 0xFF6B4A2E.toInt())
        // Flowers on stems: pink, gold and blue heads with a leaf or two.
        val heads = intArrayOf(p.curtain, 0xFFFFCF5C.toInt(), p.bowl, 0xFFFFA9C9.toInt())
        var i = 0
        for (xx in 2 until w - 1 step 3) {
            for (yy in 2..5) set(img, xx, yy, p.leafDark)
            val c = heads[i % heads.size]
            set(img, xx, 1, c); set(img, xx - 1, 1, c); set(img, xx + 1, 1, c); set(img, xx, 0, c); set(img, xx, 2, c)
            set(img, xx, 1, Argb.mix(c, p.frame, 0.4)); set(img, xx + 1, 3, p.leaf)
            i++
        }
        return img
    }

    private fun gardenBench(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 10)
        // A slatted back, a slatted seat, two legs.
        for (yy in 0..5) for (xx in 1 until w - 1) set(img, xx, yy, if (yy % 2 == 0) p.woodDark else p.woodLight)
        box(img, 0, 6, w, d - 2, 2, p.woodLight, p.wood, p.woodDark, p.woodDark)
        for (xx in 2 until w - 2 step 4) for (yy in 6 until 6 + d - 2) set(img, xx, yy, p.wood)
        for (lx in listOf(1, w - 3)) for (yy in d + 6 until d + 10) { set(img, lx, yy, p.woodDark); set(img, lx + 1, yy, p.wood) }
        return img
    }

    private fun gardenLantern(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, 26)
        val x = 4
        // A post with a little glass lantern on top, lit like the floor lamp.
        for (yy in 9 until 25) set(img, x, yy, p.metalDark)
        for (dx in -2..2) set(img, x + dx, 25, p.metalDark)
        for (dy in 2..8) for (dx in -2..2) set(img, x + dx, dy, if (dy == 2 || dy == 8 || dx == -2 || dx == 2) p.metalDark else p.shade)
        set(img, x, 1, p.metalDark); set(img, x, 0, p.metalDark); set(img, x, 5, Argb.mix(p.shade, 0xFFFFFFFF.toInt(), 0.5))
        return img
    }

    private fun birdBath(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 12)
        // A stone pedestal with a shallow bowl of water, and a bird on the rim.
        for (yy in 7 until TILE_D + 10) { set(img, 3, yy, p.metalDark); set(img, 4, yy, p.metal); set(img, 5, yy, p.metal) }
        for (dx in 1..7) { set(img, dx, TILE_D + 10, p.metalDark); set(img, dx, TILE_D + 11, p.metal) }
        for (dx in 0 until TILE_W) { set(img, dx, 4, p.metal); set(img, dx, 5, p.metalDark); set(img, dx, 6, p.metalDark) }
        for (dx in 1..7) set(img, dx, 4, p.water); set(img, 1, 4, p.glass); set(img, 7, 4, p.glass)
        set(img, 7, 3, p.bowl); set(img, 8, 2, p.bowl); set(img, 7, 2, p.bowl); set(img, 6, 3, p.bowlDark)
        return img
    }

    private fun littleTree(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 22)
        val cx = w / 2
        // A round canopy with lighter leaves on the sunny side, a short trunk in a ring of soil.
        disc(img, cx, 8, 8.5, p.leafDark); disc(img, cx - 2, 6, 6.0, p.leaf)
        for (yy in 0 until 17) for (xx in 0 until w) if (img[xx, yy] == p.leaf && (xx * 3 + yy * 5) % 7 == 0) img[xx, yy] = Argb.mix(p.leaf, p.frame, 0.35)
        for (yy in 15 until d + 20) for (xx in cx - 1..cx + 1) set(img, xx, yy, if (xx == cx + 1) p.woodDark else p.wood)
        for (xx in cx - 4..cx + 4) { set(img, xx, d + 20, 0xFF6B4A2E.toInt()); set(img, xx, d + 21, Argb.mix(0xFF6B4A2E.toInt(), p.ink, 0.2)) }
        set(img, cx + 3, 9, p.curtain); set(img, cx - 5, 11, p.curtain)
        return img
    }

    /** A string of little flags from [x0] across [w], sagging in the middle. */
    private fun buntingStrip(img: PixelImage, x0: Int, top: Int, w: Int, p: Palette, colours: IntArray) {
        var i = 0; var x = x0
        while (x + 6 <= x0 + w) {
            val t = (x + 3 - x0 - w / 2.0) / (w / 2.0)
            val y = top + (3 * (1 - t * t)).toInt()
            for (xx in x until x + 8) set(img, xx, y, p.woodDark)
            val c = colours[i % colours.size]
            for (dy in 0..4) for (dx in dy..6 - dy) set(img, x + 1 + dx, y + 1 + dy, c)
            i++; x += 8
        }
    }

    private fun chalkboard(img: PixelImage, x: Int, y: Int, w: Int, p: Palette) {
        // A dark green board in a wooden frame, with a chalk paw and a line of chalk.
        for (dy in 0 until 13) for (dx in 0 until w) set(img, x + dx, y + dy, if (dy == 0 || dy == 12 || dx == 0 || dx == w - 1) p.wood else 0xFF3E5C4A.toInt())
        for ((dx, dy) in listOf(4 to 4, 6 to 4, 3 to 5, 7 to 5, 5 to 6, 4 to 7, 5 to 7, 6 to 7)) set(img, x + dx, y + dy, p.frame)
        for (dx in w - 7 until w - 2) set(img, x + dx, y + 9, Argb.mix(p.frame, 0xFF3E5C4A.toInt(), 0.4))
        for (dx in 1 until w - 1) set(img, x + dx, y + 11, if (dx % 5 == 2) p.frame else p.woodLight)
    }

    private fun birdhouse(img: PixelImage, x: Int, y: Int, p: Palette) {
        // A little house on a bracket: a roof, a round door, a perch.
        for (dy in 0..2) for (dx in 1 - dy..5 + dy) set(img, x + dx, y + dy, p.curtainDark)
        for (dy in 3..10) for (dx in 1..5) set(img, x + dx, y + dy, if (dx == 1 || dx == 5 || dy == 10) p.woodDark else p.woodLight)
        set(img, x + 3, y + 5, p.ink); set(img, x + 3, y + 6, p.ink); set(img, x + 2, y + 6, p.ink); set(img, x + 4, y + 6, p.ink)
        set(img, x + 3, y + 8, p.woodDark); set(img, x + 3, y + 9, p.woodDark)
        for (dy in 11..13) set(img, x + 3, y + dy, p.woodDark)
    }

    private fun windChime(img: PixelImage, x: Int, top: Int, p: Palette) {
        // A wooden disc with metal tubes of different lengths hanging from it.
        for (dy in 0..3) set(img, x, top + dy, p.woodDark)
        for (dx in -3..3) { set(img, x + dx, top + 4, p.wood); set(img, x + dx, top + 5, p.woodDark) }
        val lengths = intArrayOf(9, 12, 7, 11, 8)
        lengths.forEachIndexed { i, len -> val xx = x - 3 + i * 6 / 4; for (dy in 6 until 6 + len) set(img, xx, top + dy, if (dy % 3 == 0) p.metal else p.metalDark) }
    }

    // ---- bedroom ----

    private fun bigBed(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 10)
        // A headboard at the back, a quilt over the mattress with a fold, two pillows.
        box(img, 0, 0, w, 2, 6, p.woodLight, p.wood, p.woodDark, p.woodDark)
        box(img, 1, 6, w - 2, d, 4, p.cushionLight, p.cushion, p.cushionDark, p.cushionDark)
        for (xx in 2 until w - 2) for (yy in 7..9) set(img, xx, yy, if (yy == 9) p.cushionDark else p.frame)
        for ((px0, _) in listOf(3 to 0, w - 10 to 0)) for (yy in 7..8) for (xx in px0 until px0 + 7) set(img, xx, yy, if (xx == px0 || xx == px0 + 6) Argb.mix(p.frame, p.ink, 0.25) else p.frame)
        for (xx in 2 until w - 2) if ((xx / 3) % 2 == 0) for (yy in 11 until 6 + d) set(img, xx, yy, Argb.mix(p.cushion, p.cushionLight, 0.5))
        return img
    }

    private fun nightstand(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 8)
        box(img, 0, 2, w, d, 6, p.woodLight, p.wood, p.woodDark, p.woodDark)
        for (xx in 2 until w - 2) set(img, xx, d + 4, p.woodDark); set(img, w / 2, d + 4, p.metal)
        // A little book and a glass on top.
        for (xx in 1..4) set(img, xx, 1, p.curtain); set(img, 1, 0, p.curtain); set(img, 4, 0, p.curtain)
        set(img, 6, 0, p.glass); set(img, 7, 0, p.glass); set(img, 6, 1, p.water); set(img, 7, 1, p.water)
        return img
    }

    private fun moonLamp(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 8)
        // A glowing moon on a short wooden stand.
        box(img, 2, TILE_D + 4, 5, 2, 2, p.woodLight, p.wood, p.woodDark, p.woodDark)
        disc(img, 4, 5, 4.4, Argb.mix(p.shade, p.lamp, 0.25)); disc(img, 4, 5, 3.2, p.shade)
        set(img, 3, 3, Argb.mix(p.shade, p.lamp, 0.35)); set(img, 5, 6, Argb.mix(p.shade, p.lamp, 0.35)); set(img, 2, 6, Argb.mix(p.shade, 0xFFFFFFFF.toInt(), 0.5))
        return img
    }

    private fun slippers(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 2)
        for ((x0, _) in listOf(0 to 0, 5 to 0)) {
            for (yy in 2..7) for (xx in x0 until x0 + 4) set(img, xx, yy, if (yy == 2 || yy == 7 || xx == x0 || xx == x0 + 3) p.curtainDark else p.curtain)
            for (xx in x0 + 1..x0 + 2) set(img, xx, 3, p.rugDot); set(img, x0 + 1, 5, p.frame); set(img, x0 + 2, 5, p.frame)
        }
        return img
    }

    private fun wardrobe(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 28)
        box(img, 0, 0, w, d, 28, p.woodLight, p.wood, p.woodDark, p.woodDark)
        for (yy in d + 2 until d + 26) set(img, w / 2, yy, p.woodDark)
        set(img, w / 2 - 2, d + 14, p.metal); set(img, w / 2 + 1, d + 14, p.metal)
        for (yy in d + 24 until d + 27) { set(img, 2, yy, p.woodDark); set(img, w - 3, yy, p.woodDark) }
        return img
    }

    private fun laundryBasket(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 8)
        // A woven basket with a sock hanging over the rim.
        for (yy in 3 until TILE_D + 8) for (xx in 1 until TILE_W - 1) set(img, xx, yy, if ((xx + yy) % 2 == 0) p.woodLight else p.wood)
        for (xx in 0 until TILE_W) { set(img, xx, 3, p.woodDark); set(img, xx, TILE_D + 7, p.woodDark) }
        for (yy in 3 until TILE_D + 8) { set(img, 0, yy, p.woodDark); set(img, TILE_W - 1, yy, p.woodDark) }
        for (xx in 2..4) for (yy in 4..6) set(img, xx, yy, p.frame); set(img, 5, 1, p.bowl); set(img, 5, 2, p.bowl); set(img, 6, 2, p.bowl); set(img, 6, 3, p.bowl)
        return img
    }

    // ---- kitchen ----

    private fun counter(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 14)
        // A stone top over wooden cupboards, a cutting board and a kettle on it.
        box(img, 0, 2, w, d, 12, p.metal, p.wood, p.woodDark, p.woodDark)
        for (xx in 0 until w) set(img, xx, 2 + d, p.metalDark)
        for (yy in d + 3 until d + 13) { set(img, w / 3, yy, p.woodDark); set(img, 2 * w / 3, yy, p.woodDark) }
        for (xx in listOf(w / 6, w / 2, 5 * w / 6)) set(img, xx, d + 7, p.metal)
        for (xx in 2..7) for (yy in 1..2) set(img, xx, yy, p.woodLight)
        for (xx in w - 8..w - 4) for (yy in 0..2) set(img, xx, yy, if (yy == 0) p.metalDark else p.metal); set(img, w - 3, 1, p.metalDark)
        return img
    }

    private fun bowlStand(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 5)
        // Two bowls side by side in a raised wooden stand: food and water.
        box(img, 0, 4, TILE_W, TILE_D - 2, 3, p.woodLight, p.wood, p.woodDark, p.woodDark)
        for ((x0, c) in listOf(1 to p.kibble, 5 to p.water)) { for (xx in x0..x0 + 2) { set(img, xx, 3, p.bowlDark); set(img, xx, 2, c) }; set(img, x0 + 1, 1, c) }
        return img
    }

    private fun stove(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 14)
        box(img, 0, 2, w, d, 12, p.metal, Argb.mix(p.metal, p.frame, 0.4), p.metalDark, p.metalDark)
        for ((xx, yy) in listOf(4 to 3, w - 5 to 3, 4 to d, w - 5 to d)) { set(img, xx, yy, p.ink); set(img, xx + 1, yy, p.ink) }
        for (xx in 2 until w - 2) set(img, xx, d + 5, p.metalDark)
        for (yy in d + 7 until d + 12) for (xx in 2 until w - 2) set(img, xx, yy, if (yy == d + 7 || yy == d + 11 || xx == 2 || xx == w - 3) p.metalDark else p.ink)
        set(img, w / 2, d + 9, Argb.mix(p.shade, p.lamp, 0.4))
        for (xx in 1 until w - 1) set(img, xx, 0, p.metalDark); set(img, 3, 1, p.metal); set(img, w - 4, 1, p.metal)
        return img
    }

    private fun fridge(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 30)
        box(img, 0, 0, w, d, 30, Argb.mix(p.frame, p.metal, 0.3), p.frame, p.metal, p.metalDark)
        for (xx in 1 until w - 1) set(img, xx, d + 10, p.metalDark)
        for (yy in d + 3..d + 7) set(img, 2, yy, p.metalDark); for (yy in d + 13..d + 20) set(img, 2, yy, p.metalDark)
        // A note and a magnet on the door.
        for (xx in w - 7..w - 4) for (yy in d + 14..d + 17) set(img, xx, yy, 0xFFFFE9A8.toInt()); set(img, w - 6, d + 13, p.curtain)
        return img
    }

    private fun catGrass(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 6)
        box(img, 2, TILE_D + 2, 5, 2, 2, Argb.mix(p.pot, p.frame, 0.3), p.pot, Argb.mix(p.pot, p.woodDark, 0.4), Argb.mix(p.pot, p.ink, 0.4))
        for (i in 0..5) { val xx = 2 + i; val h = 3 + (i * 7) % 4; for (yy in TILE_D + 2 - h until TILE_D + 2) set(img, xx, yy, if (i % 2 == 0) p.leaf else p.leafDark) }
        return img
    }

    // ---- bathroom ----

    private fun bathtub(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 8)
        // A white tub with a rolled rim, water inside, a tap at one end, little feet.
        box(img, 0, 1, w, d, 6, p.frame, Argb.mix(p.frame, p.metal, 0.35), p.metal, p.metalDark)
        for (yy in 2 until d) for (xx in 2 until w - 2) set(img, xx, yy, if (yy == 2) p.glass else p.water)
        for (xx in 4 until w - 4 step 5) set(img, xx, 3, Argb.mix(p.water, 0xFFFFFFFF.toInt(), 0.5))
        set(img, w - 3, 0, p.metalDark); set(img, w - 4, 0, p.metalDark); set(img, w - 4, 1, p.metalDark)
        for (xx in listOf(2, w - 4)) for (yy in d + 7 until d + 8) { set(img, xx, yy, p.metalDark); set(img, xx + 1, yy, p.metalDark) }
        return img
    }

    private fun sink(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 12)
        // A basin on a pedestal with a tap.
        box(img, 0, 2, TILE_W, TILE_D - 2, 3, p.frame, Argb.mix(p.frame, p.metal, 0.35), p.metal, p.metalDark)
        for (xx in 2 until TILE_W - 2) set(img, xx, 3, p.water)
        for (yy in TILE_D + 3 until TILE_D + 12) for (xx in 3..5) set(img, xx, yy, if (xx == 5) p.metal else p.frame)
        set(img, 4, 0, p.metalDark); set(img, 4, 1, p.metalDark); set(img, 5, 0, p.metalDark)
        return img
    }

    private fun rubberDuck(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 2)
        val yellow = 0xFFFFCF5C.toInt(); val dark = 0xFFD9A52E.toInt()
        // Body, head, a beak and an eye.
        for (yy in 4..7) for (xx in 1..7) set(img, xx, yy, if (yy == 4 && (xx == 1 || xx == 7)) 0 else if (yy == 7) dark else yellow)
        for (yy in 1..4) for (xx in 4..7) set(img, xx, yy, yellow)
        set(img, 8, 3, 0xFFF28C28.toInt()); set(img, 6, 2, p.ink); set(img, 1, 3, yellow)
        return img
    }

    private fun towelStack(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 4)
        val colours = listOf(p.curtain, p.bowl, p.leaf)
        colours.forEachIndexed { i, c -> for (yy in 0..2) for (xx in 1 until TILE_W - 1) set(img, xx, TILE_D + 3 - i * 3 - yy, if (yy == 0) Argb.mix(c, p.ink, 0.25) else if (yy == 2) Argb.mix(c, p.frame, 0.3) else c) }
        return img
    }

    private fun washingMachine(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 16)
        box(img, 0, 0, w, d, 16, Argb.mix(p.frame, p.metal, 0.3), p.frame, p.metal, p.metalDark)
        disc(img, w / 2, d + 9, 5.0, p.metalDark); disc(img, w / 2, d + 9, 3.8, p.glass); disc(img, w / 2, d + 10, 2.2, p.water)
        for (xx in 2..4) set(img, xx, d + 2, p.metalDark); set(img, w - 3, d + 2, p.curtain)
        return img
    }

    private fun starGarland(img: PixelImage, x: Int, y: Int, w: Int, p: Palette) {
        // A string with little gold stars hanging from it.
        for (xx in x until x + w) set(img, xx, y + ((xx - x - w / 2) * (xx - x - w / 2)) / (w * 3), p.woodDark)
        var i = 0; var xx = x + 3
        val gold = 0xFFFFCF5C.toInt()
        while (xx + 2 < x + w) {
            val yy = y + 2 + ((xx - x - w / 2) * (xx - x - w / 2)) / (w * 3)
            set(img, xx, yy, p.woodDark)
            set(img, xx, yy + 1, gold); set(img, xx - 1, yy + 2, gold); set(img, xx, yy + 2, gold); set(img, xx + 1, yy + 2, gold); set(img, xx, yy + 3, gold)
            if (i % 2 == 1) { set(img, xx - 1, yy + 3, gold); set(img, xx + 1, yy + 3, gold) }
            i++; xx += 7
        }
    }

    private fun dreamcatcher(img: PixelImage, x: Int, top: Int, p: Palette) {
        disc(img, x, top + 5, 4.4, p.wood); disc(img, x, top + 5, 3.3, p.wall)
        for ((dx, dy) in listOf(0 to 0, -2 to 0, 2 to 0, 0 to -2, 0 to 2, -1 to -1, 1 to 1, -1 to 1, 1 to -1)) set(img, x + dx, top + 5 + dy, p.frame)
        for (yy in top + 10..top + 14) { set(img, x - 2, yy, p.woodDark); set(img, x + 2, yy, p.woodDark); if (yy > top + 11) set(img, x, yy, p.woodDark) }
        set(img, x - 2, top + 15, p.curtain); set(img, x + 2, top + 15, p.curtain); set(img, x, top + 16, p.bowl)
        set(img, x, top, p.woodDark)
    }

    private fun herbShelf(img: PixelImage, x: Int, bottom: Int, w: Int, p: Palette) {
        for (xx in x until x + w) { set(img, xx, bottom, p.wood); set(img, xx, bottom + 1, p.woodDark) }
        for (i in 0 until 3) {
            val px = x + 1 + i * 6
            for (xx in px..px + 3) for (yy in bottom - 3 until bottom) set(img, xx, yy, if (yy == bottom - 3) Argb.mix(p.pot, p.frame, 0.3) else p.pot)
            for ((dx, dy) in listOf(1 to -4, 2 to -4, 1 to -5, 2 to -6, 0 to -5, 3 to -5)) set(img, px + dx, bottom + dy, if ((dx + dy) % 2 == 0) p.leaf else p.leafDark)
        }
    }

    private fun potRack(img: PixelImage, x: Int, y: Int, w: Int, p: Palette) {
        for (xx in x until x + w) set(img, xx, y, p.metalDark)
        for (i in 0 until 3) {
            val px = x + 2 + i * 6
            set(img, px + 1, y + 1, p.metalDark)
            for (yy in y + 2..y + 5) for (xx in px until px + 4) set(img, xx, yy, if (yy == y + 2 || xx == px + 3) p.metalDark else if (i == 1) Argb.mix(p.pot, p.metal, 0.3) else p.metal)
            set(img, px + 4, y + 3, p.metalDark)
        }
    }

    private fun mirror(img: PixelImage, x: Int, y: Int, p: Palette) {
        for (dy in 0..11) for (dx in 0..7) {
            val edge = dx == 0 || dy == 0 || dx == 7 || dy == 11
            val corner = (dx == 0 || dx == 7) && (dy == 0 || dy == 11)
            if (!corner) set(img, x + dx, y + dy, if (edge) p.wood else if (dx + dy == 9 || dx + dy == 10) Argb.mix(p.glass, 0xFFFFFFFF.toInt(), 0.5) else p.glass)
        }
    }

    private fun towelRail(img: PixelImage, x: Int, y: Int, w: Int, p: Palette) {
        for (xx in x until x + w) set(img, xx, y, p.metalDark)
        set(img, x, y + 1, p.metalDark); set(img, x + w - 1, y + 1, p.metalDark)
        for (xx in x + 2 until x + 7) for (yy in y + 1..y + 8) set(img, xx, yy, if (yy == y + 8 || xx == x + 2) Argb.mix(p.curtain, p.ink, 0.25) else p.curtain)
        for (xx in x + 10 until x + 15) for (yy in y + 1..y + 7) set(img, xx, yy, if (yy == y + 7 || xx == x + 10) Argb.mix(p.bowl, p.ink, 0.25) else p.bowl)
    }

    // ---- more for the first rooms ----

    private fun fireplace(w: Int, d: Int, p: Palette, phase: Sky.Phase): PixelImage {
        val img = PixelImage(w, d + 18)
        // A stone surround with a mantel, a dark hearth and a fire that burns brighter at night.
        box(img, 0, 0, w, 2, 16, p.metal, Argb.mix(p.metal, p.frame, 0.3), p.metalDark, p.metalDark)
        for (xx in 0 until w) set(img, xx, 2, p.woodLight)
        for (yy in 5 until d + 16) for (xx in 3 until w - 3) set(img, xx, yy, if (yy == 5 || xx == 3 || xx == w - 4) p.metalDark else p.ink)
        val flame = if (phase.dark) 0xFFFFB347.toInt() else 0xFFF28C28.toInt(); val core = 0xFFFFE27A.toInt()
        for ((dx, dy) in listOf(0 to 0, -1 to 0, 1 to 0, -2 to 1, 2 to 1, -1 to 1, 1 to 1, 0 to 1, 0 to -1, -1 to -2, 1 to -2, 0 to -3)) set(img, w / 2 + dx, d + 11 + dy, flame)
        for ((dx, dy) in listOf(0 to 0, 0 to -1, -1 to 1, 1 to 1)) set(img, w / 2 + dx, d + 11 + dy, core)
        for (xx in 4 until w - 4) set(img, xx, d + 13, p.woodDark)
        for (xx in 1 until w - 1 step 4) set(img, xx, d + 15, p.metal)
        return img
    }

    private fun slide(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 14)
        // A ladder up the left, a chute sloping down to the right.
        for (yy in 1 until d + 13) { set(img, 2, yy, p.metalDark); set(img, 4, yy, p.metalDark); if (yy % 3 == 1) set(img, 3, yy, p.metal) }
        for (xx in 2..6) set(img, xx, 0, p.curtainDark)
        for (xx in 5 until w - 1) {
            val yy = 1 + ((xx - 5) * (d + 10)) / (w - 6)
            set(img, xx, yy, p.curtainDark); set(img, xx, yy + 1, p.curtain); set(img, xx, yy + 2, p.curtain); set(img, xx, yy + 3, p.curtainDark)
        }
        for (xx in w - 4 until w - 1) set(img, xx, d + 13, p.curtainDark)
        return img
    }

    private fun trainSet(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 6)
        // An oval of track with a little engine and a carriage on it.
        for (yy in 2 until d + 5) for (xx in 0 until w) {
            val nx = (xx + 0.5 - w / 2.0) / (w / 2.0); val ny = (yy + 0.5 - (d + 7) / 2.0 - 1) / ((d + 3) / 2.0)
            val r = nx * nx + ny * ny
            if (r in 0.62..1.0) set(img, xx, yy, if ((xx + yy) % 2 == 0) p.woodDark else p.wood)
        }
        for (xx in 2..5) for (yy in 0..3) set(img, xx, yy, if (yy == 0 || xx == 2) Argb.mix(p.curtain, p.ink, 0.3) else p.curtain)
        set(img, 3, 0, p.ink); set(img, 6, 2, p.ink); set(img, 6, 3, p.ink)
        for (xx in 8..11) for (yy in 1..3) set(img, xx, yy, if (yy == 1) Argb.mix(p.bowl, p.ink, 0.3) else p.bowl)
        for (xx in listOf(3, 5, 9, 11)) set(img, xx, 4, p.ink)
        return img
    }

    private fun doghouse(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 16)
        // A little house with a peaked roof and a dark round door; the pet can sleep inside.
        for (dy in 0..5) for (dx in dy..w - 1 - dy) set(img, dx, 5 - dy, if (dy == 0 || dx == dy || dx == w - 1 - dy) Argb.mix(p.curtainDark, p.ink, 0.3) else p.curtainDark)
        for (xx in 0 until w) set(img, xx, 5, Argb.mix(p.curtainDark, p.ink, 0.4))
        for (yy in 6 until d + 15) for (xx in 1 until w - 1) set(img, xx, yy, if (xx == 1 || xx == w - 2 || yy == d + 14) p.woodDark else if (yy % 4 == 2) p.wood else p.woodLight)
        for (yy in 9 until d + 14) for (xx in w / 2 - 3..w / 2 + 2) if (yy > 9 || xx in w / 2 - 2..w / 2 + 1) set(img, xx, yy, p.ink)
        for (xx in w / 2 - 2..w / 2 + 1) set(img, xx, d + 15, p.woodDark)
        return img
    }

    private fun wagon(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 5)
        // A red garden wagon with two wheels and a handle.
        for (yy in 3..7) for (xx in 0..7) set(img, xx, yy, if (yy == 3 || yy == 7 || xx == 0 || xx == 7) p.curtainDark else p.curtain)
        for (xx in 1..6) set(img, xx, 4, Argb.mix(p.curtain, p.frame, 0.35))
        set(img, 1, 8, p.ink); set(img, 2, 8, p.ink); set(img, 5, 8, p.ink); set(img, 6, 8, p.ink); set(img, 1, 9, p.ink); set(img, 6, 9, p.ink)
        set(img, 8, 2, p.metalDark); set(img, 8, 1, p.metalDark); set(img, 7, 2, p.metalDark)
        for (xx in 2..5) set(img, xx, 2, if (xx % 2 == 0) p.leaf else p.leafDark)
        return img
    }

    private fun scarecrow(p: Palette): PixelImage {
        val img = PixelImage(TILE_W, TILE_D + 20)
        val x = 4
        // A pole with a cross bar, a shirt, a straw head under a hat.
        for (yy in 8 until TILE_D + 19) set(img, x, yy, p.woodDark)
        for (xx in 0 until TILE_W) set(img, xx, 11, p.woodDark)
        for (yy in 10..17) for (xx in 2..6) set(img, xx, yy, if (xx == 2 || xx == 6 || yy == 17) Argb.mix(p.bowl, p.ink, 0.3) else if ((xx + yy) % 3 == 0) Argb.mix(p.bowl, p.frame, 0.3) else p.bowl)
        for (yy in 5..9) for (xx in 2..6) set(img, xx, yy, if ((xx + yy) % 2 == 0) 0xFFFFCF5C.toInt() else 0xFFE0B23A.toInt())
        set(img, 3, 7, p.ink); set(img, 5, 7, p.ink); set(img, 4, 8, p.curtainDark)
        for (xx in 1..7) set(img, xx, 4, p.woodDark); for (xx in 2..6) for (yy in 2..3) set(img, xx, yy, p.wood)
        set(img, 1, 12, p.leaf); set(img, 7, 13, p.leaf)
        return img
    }

    private fun picketFence(w: Int, d: Int, p: Palette): PixelImage {
        val img = PixelImage(w, d + 8)
        for (xx in 0 until w) { set(img, xx, 3, p.frame); set(img, xx, 4, p.sill); set(img, xx, d + 4, p.frame); set(img, xx, d + 5, p.sill) }
        for (xx in 1 until w step 4) {
            set(img, xx, 0, p.frame)
            for (yy in 1 until d + 8) { set(img, xx, yy, p.frame); set(img, xx + 1, yy, p.sill) }
        }
        set(img, 3, d + 6, p.leaf); set(img, 4, d + 7, p.leafDark); set(img, w - 5, d + 7, p.curtain)
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
