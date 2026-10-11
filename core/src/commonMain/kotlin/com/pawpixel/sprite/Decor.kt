package com.pawpixel.sprite

/**
 * The furniture of the pet's room, and where the owner put it.
 *
 * The room is a 3/4 view, the way cozy games draw interiors (Stardew, Unpacking): the back wall is
 * seen straight on, the floor is a grid of tiles that recede upward, and every piece has a lit top
 * and a shaded front, so a flat drawing reads as a small box. Floor pieces sit on tiles; wall pieces
 * hang on the wall at a column and a height. The owner moves them in Decorate mode.
 *
 * Every piece is free: the ones not there from the start are earned with days of care (see
 * [com.pawpixel.core.Milestones]), like outfits. Nothing is ever taken away.
 */
enum class Furniture(
    val label: String,
    /** Days of care before it appears in the catalogue; 0 = from the start. */
    val unlockDays: Int,
    /** Footprint in tiles (floor pieces) or columns (wall pieces). */
    val cols: Int,
    /** Depth in tile rows; wall pieces use 1. */
    val rows: Int = 1,
    val place: Place = Place.FLOOR,
    /** The pet can lie on it (a bed) or eat from it (a bowl). */
    val use: Use = Use.NONE,
) {
    // ---- floor: from the start ----
    BED("Bed", 0, 3, use = Use.SLEEP),
    RUG("Rug", 0, 3, 2, Place.RUG),
    BOWL("Bowl", 0, 1, use = Use.EAT),
    LAMP("Floor lamp", 0, 1),
    PLANT("Plant", 0, 1),
    // ---- floor: earned ----
    TABLE("Side table", 3, 2),
    CHAIR("Armchair", 5, 1),
    TOY_BOX("Toy box", 7, 1),
    BOOKCASE("Bookcase", 10, 2),
    SCRATCH_POST("Scratch post", 14, 1),
    BIG_PLANT("Big plant", 21, 1),
    FISH_TANK("Fish tank", 28, 2),
    RADIO("Radio", 35, 1),
    ROUND_RUG("Round rug", 40, 2, 2, Place.RUG),
    TEA_SET("Tea set", 50, 1),
    // ---- wall: from the start ----
    WINDOW("Window", 0, 3, place = Place.WALL),
    SHELF("Shelf", 0, 3, place = Place.WALL),
    FRAME("Picture", 0, 1, place = Place.WALL),
    CLOCK("Clock", 0, 1, place = Place.WALL),
    // ---- wall: earned ----
    HANGING_PLANT("Hanging plant", 12, 1, place = Place.WALL),
    POSTER("Poster", 17, 1, place = Place.WALL),
    STRING_LIGHTS("String lights", 30, 4, place = Place.WALL),
    PAW_BANNER("Paw banner", 60, 2, place = Place.WALL);

    enum class Place { FLOOR, RUG, WALL }
    enum class Use { NONE, SLEEP, EAT }

    val onWall: Boolean get() = place == Place.WALL
    /** Rugs lie flat under everything else and can share tiles with furniture. */
    val flat: Boolean get() = place == Place.RUG

    companion object {
        fun of(name: String?): Furniture? = entries.firstOrNull { it.name == name }
    }
}

/**
 * One piece in the room. Floor pieces: [x] is the tile column of the left edge, [y] the tile row of
 * the back edge (row 0 touches the wall). Wall pieces: [x] the column, [y] the height above the
 * floor line in [RoomLayout.WALL_STEP]-pixel steps. [flip] mirrors the piece.
 */
data class Placed(val item: Furniture, val x: Int, val y: Int, val flip: Boolean = false) {
    /** The tiles a floor piece covers, as (col, row) pairs; empty for wall pieces. */
    fun tiles(): List<Pair<Int, Int>> =
        if (item.onWall) emptyList() else (0 until item.cols).flatMap { dx -> (0 until item.rows).map { dy -> (x + dx) to (y + dy) } }
}

/**
 * The room's furniture on a grid of [COLS] by [ROWS] tiles (the wall above). Rooms wider than the
 * grid centre it; deeper floors show more rows. Stored on the pet as [encode]'s string and shared
 * with the household, so everyone sees the same room.
 */
data class RoomLayout(val items: List<Placed>) {
    fun at(x: Int, y: Int, onWall: Boolean): Placed? = items.lastOrNull { p ->
        if (p.item.onWall != onWall) false
        else if (onWall) x in p.x until p.x + p.item.cols && y == p.y
        else (x to y) in p.tiles()
    }

    /** Where a piece of [item] could go at ([x], [y]) in this room, ignoring [except] (the piece being moved). */
    fun canPlace(item: Furniture, x: Int, y: Int, except: Placed? = null): Boolean {
        val others = items.filter { it !== except }
        if (item.onWall) {
            if (x < 0 || x + item.cols > COLS || y < 0 || y >= WALL_SLOTS) return false
            return others.none { o -> o.item.onWall && o.y == y && x < o.x + o.item.cols && o.x < x + item.cols }
        }
        if (x < 0 || x + item.cols > COLS || y < 0 || y + item.rows > ROWS) return false
        if (item.flat) return others.none { o -> o.item.flat && o.tiles().any { it in Placed(item, x, y).tiles() } }
        val mine = Placed(item, x, y).tiles().toSet()
        return others.none { o -> !o.item.flat && !o.item.onWall && o.tiles().any { it in mine } }
    }

    fun with(p: Placed): RoomLayout = RoomLayout(items + p)
    fun without(p: Placed): RoomLayout = RoomLayout(items.filter { it !== p })
    fun replace(old: Placed, new: Placed): RoomLayout = RoomLayout(items.map { if (it === old) new else it })

    /** The first spot a new piece fits, front rows first (where the owner sees it), or null when the room is full. */
    fun freeSpot(item: Furniture): Pair<Int, Int>? {
        if (item.onWall) {
            for (y in WALL_SLOTS - 1 downTo 0) for (x in 0..COLS - item.cols) if (canPlace(item, x, y)) return x to y
            return null
        }
        for (y in ROWS - item.rows downTo 0) for (x in 0..COLS - item.cols) if (canPlace(item, x, y)) return x to y
        return null
    }

    fun encode(): String = "1;" + items.joinToString(";") { p -> "${p.item.name}:${p.x},${p.y}" + (if (p.flip) ",f" else "") }

    /** True when [encode]'s string is short enough to be saved and shared ([MAX_CODE]); the editor stops adding pieces before that. */
    fun fits(): Boolean = encode().length <= MAX_CODE

    companion object {
        const val COLS = 10
        const val ROWS = 5
        /** The longest layout code the save file, the server column (0021_rooms.sql) and household sync accept. */
        const val MAX_CODE = 400
        /** Wall pieces hang at one of these heights above the floor line (slot 0 lowest). */
        const val WALL_SLOTS = 6
        const val WALL_STEP = 12

        /** Today's room: the window and shelf on the wall, the bed, rug, bowl, plant and lamp on the floor. */
        val DEFAULT = RoomLayout(listOf(
            Placed(Furniture.WINDOW, 1, 3), Placed(Furniture.CLOCK, 5, 4), Placed(Furniture.SHELF, 6, 2), Placed(Furniture.FRAME, 8, 4),
            Placed(Furniture.RUG, 4, 2), Placed(Furniture.BED, 0, 3), Placed(Furniture.PLANT, 0, 1), Placed(Furniture.BOWL, 8, 3), Placed(Furniture.LAMP, 9, 1),
        ))

        /** Reads [encode]'s string; pieces that don't fit or overlap are dropped, so a bad string still gives a room. Null for a different format. */
        fun decode(code: String?): RoomLayout? {
            if (code == null || code.length > MAX_CODE) return null
            val parts = code.split(';')
            if (parts.firstOrNull() != "1") return null
            var layout = RoomLayout(emptyList())
            for (part in parts.drop(1)) {
                if (part.isEmpty()) continue
                val (name, pos) = part.split(':').let { if (it.size != 2) return@let null to null else it[0] to it[1] }
                val item = Furniture.of(name) ?: continue
                val f = pos?.split(',') ?: continue
                val x = f.getOrNull(0)?.toIntOrNull() ?: continue
                val y = f.getOrNull(1)?.toIntOrNull() ?: continue
                if (layout.canPlace(item, x, y)) layout = layout.with(Placed(item, x, y, f.getOrNull(2) == "f"))
            }
            return layout
        }
    }
}
