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
/**
 * What kind of room it is: the wallpaper, the floor and which pieces belong. Every theme is free from
 * the start; the pieces inside it are earned with days of care like everything else. Encoded in the
 * layout code as `T:NAME`, which older apps skip (they show the cozy nook).
 */
enum class RoomTheme(val label: String) {
    COZY("Cozy nook"),
    LIVING("Living room"),
    PLAYROOM("Playroom"),
    GARDEN("Garden"),
    BEDROOM("Bedroom"),
    KITCHEN("Kitchen"),
    BATHROOM("Bathroom");

    companion object {
        fun of(name: String?): RoomTheme? = entries.firstOrNull { it.name == name }
    }
}

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
    /** The room this piece belongs to; null means it fits any room. */
    val theme: RoomTheme? = null,
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
    PAW_BANNER("Paw banner", 60, 2, place = Place.WALL),
    // ---- living room ----
    SOFA("Sofa", 0, 3, use = Use.SLEEP, theme = RoomTheme.LIVING),
    TV("TV", 3, 2, theme = RoomTheme.LIVING),
    COFFEE_TABLE("Coffee table", 5, 2, theme = RoomTheme.LIVING),
    FLOOR_CUSHION("Floor cushion", 2, 1, use = Use.SLEEP, theme = RoomTheme.LIVING),
    RECORD_PLAYER("Record player", 10, 1, theme = RoomTheme.LIVING),
    FIREPLACE("Fireplace", 7, 2, theme = RoomTheme.LIVING),
    PIANO("Piano", 14, 3, theme = RoomTheme.LIVING),
    GALLERY_WALL("Gallery wall", 4, 3, place = Place.WALL, theme = RoomTheme.LIVING),
    // ---- playroom ----
    TOY_CHEST("Toy chest", 0, 2, theme = RoomTheme.PLAYROOM),
    BLOCKS("Building blocks", 3, 1, theme = RoomTheme.PLAYROOM),
    PLAY_TUNNEL("Play tunnel", 7, 3, theme = RoomTheme.PLAYROOM),
    BALL_PIT("Ball pit", 10, 2, 2, theme = RoomTheme.PLAYROOM),
    ROCKING_HORSE("Rocking horse", 14, 2, theme = RoomTheme.PLAYROOM),
    PLAY_TENT("Play tent", 21, 2, use = Use.SLEEP, theme = RoomTheme.PLAYROOM),
    BUNTING("Bunting", 0, 4, place = Place.WALL, theme = RoomTheme.PLAYROOM),
    CHALKBOARD("Chalkboard", 5, 2, place = Place.WALL, theme = RoomTheme.PLAYROOM),
    SLIDE("Slide", 12, 2, theme = RoomTheme.PLAYROOM),
    TRAIN_SET("Train set", 17, 2, theme = RoomTheme.PLAYROOM),
    PLAY_MAT("Play mat", 2, 2, 2, Place.RUG, theme = RoomTheme.PLAYROOM),
    TEDDY("Teddy bear", 1, 1, theme = RoomTheme.PLAYROOM),
    HEIGHT_CHART("Height chart", 3, 1, place = Place.WALL, theme = RoomTheme.PLAYROOM),
    // ---- garden ----
    FLOWER_BED("Flower bed", 0, 2, theme = RoomTheme.GARDEN),
    GARDEN_BENCH("Garden bench", 3, 2, use = Use.SLEEP, theme = RoomTheme.GARDEN),
    GARDEN_LANTERN("Garden lantern", 5, 1, theme = RoomTheme.GARDEN),
    BIRD_BATH("Bird bath", 7, 1, theme = RoomTheme.GARDEN),
    POND("Pond", 10, 3, 2, Place.RUG, theme = RoomTheme.GARDEN),
    LITTLE_TREE("Little tree", 14, 2, theme = RoomTheme.GARDEN),
    BIRDHOUSE("Birdhouse", 12, 1, place = Place.WALL, theme = RoomTheme.GARDEN),
    WIND_CHIME("Wind chime", 20, 1, place = Place.WALL, theme = RoomTheme.GARDEN),
    DOGHOUSE("Doghouse", 2, 2, use = Use.SLEEP, theme = RoomTheme.GARDEN),
    WAGON("Wagon", 9, 1, theme = RoomTheme.GARDEN),
    SCARECROW("Scarecrow", 17, 1, theme = RoomTheme.GARDEN),
    PICKET_FENCE("Picket fence", 4, 3, theme = RoomTheme.GARDEN),
    SWING("Swing", 6, 2, theme = RoomTheme.GARDEN),
    SUNFLOWERS("Sunflowers", 2, 1, theme = RoomTheme.GARDEN),
    // ---- bedroom ----
    BIG_BED("Big bed", 0, 3, 2, use = Use.SLEEP, theme = RoomTheme.BEDROOM),
    NIGHTSTAND("Nightstand", 3, 1, theme = RoomTheme.BEDROOM),
    MOON_LAMP("Moon lamp", 5, 1, theme = RoomTheme.BEDROOM),
    SLIPPERS("Slippers", 2, 1, theme = RoomTheme.BEDROOM),
    WARDROBE("Wardrobe", 7, 2, theme = RoomTheme.BEDROOM),
    LAUNDRY_BASKET("Laundry basket", 10, 1, theme = RoomTheme.BEDROOM),
    STAR_GARLAND("Star garland", 0, 4, place = Place.WALL, theme = RoomTheme.BEDROOM),
    DREAMCATCHER("Dreamcatcher", 14, 1, place = Place.WALL, theme = RoomTheme.BEDROOM),
    DRESSER("Dresser", 4, 2, theme = RoomTheme.BEDROOM),
    CANOPY("Canopy", 8, 3, place = Place.WALL, theme = RoomTheme.BEDROOM),
    // ---- kitchen ----
    COUNTER("Counter", 0, 3, theme = RoomTheme.KITCHEN),
    BOWL_STAND("Bowl stand", 2, 1, use = Use.EAT, theme = RoomTheme.KITCHEN),
    KITCHEN_MAT("Kitchen mat", 0, 2, 1, Place.RUG, theme = RoomTheme.KITCHEN),
    STOVE("Stove", 5, 2, theme = RoomTheme.KITCHEN),
    FRIDGE("Fridge", 7, 2, theme = RoomTheme.KITCHEN),
    CAT_GRASS("Cat grass", 14, 1, theme = RoomTheme.KITCHEN),
    HERB_SHELF("Herb shelf", 3, 2, place = Place.WALL, theme = RoomTheme.KITCHEN),
    POT_RACK("Pot rack", 10, 2, place = Place.WALL, theme = RoomTheme.KITCHEN),
    DINING_TABLE("Dining table", 4, 3, theme = RoomTheme.KITCHEN),
    CAKE_STAND("Cake stand", 8, 1, theme = RoomTheme.KITCHEN),
    // ---- bathroom ----
    BATHTUB("Bathtub", 0, 3, theme = RoomTheme.BATHROOM),
    SINK("Sink", 3, 1, theme = RoomTheme.BATHROOM),
    BATH_MAT("Bath mat", 0, 2, 1, Place.RUG, theme = RoomTheme.BATHROOM),
    RUBBER_DUCK("Rubber duck", 5, 1, theme = RoomTheme.BATHROOM),
    TOWEL_STACK("Towel stack", 7, 1, theme = RoomTheme.BATHROOM),
    WASHING_MACHINE("Washing machine", 12, 2, theme = RoomTheme.BATHROOM),
    MIRROR("Mirror", 0, 1, place = Place.WALL, theme = RoomTheme.BATHROOM),
    TOWEL_RAIL("Towel rail", 2, 2, place = Place.WALL, theme = RoomTheme.BATHROOM),
    LITTER_BOX("Litter box", 0, 1, theme = RoomTheme.BATHROOM),
    SHOWER("Shower", 6, 2, place = Place.WALL, theme = RoomTheme.BATHROOM);

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
data class RoomLayout(val items: List<Placed>, val theme: RoomTheme = RoomTheme.COZY) {
    /** The pieces that may stand in this room: the theme's own and the ones that fit anywhere. */
    fun allows(item: Furniture): Boolean = item.theme == null || item.theme == theme

    /** The same arrangement in another room; pieces that don't belong there are put away. */
    fun inTheme(t: RoomTheme): RoomLayout = RoomLayout(items.filter { it.item.theme == null || it.item.theme == t }, t)

    fun at(x: Int, y: Int, onWall: Boolean): Placed? = items.lastOrNull { p ->
        if (p.item.onWall != onWall) false
        else if (onWall) x in p.x until p.x + p.item.cols && y == p.y
        else (x to y) in p.tiles()
    }

    /** Where a piece of [item] could go at ([x], [y]) in this room, ignoring [except] (the piece being moved). */
    fun canPlace(item: Furniture, x: Int, y: Int, except: Placed? = null): Boolean {
        val others = items.filter { it !== except }
        if (!allows(item)) return false
        if (item.onWall) {
            if (x < 0 || x + item.cols > COLS || y < 0 || y >= WALL_SLOTS) return false
            return others.none { o -> o.item.onWall && o.y == y && x < o.x + o.item.cols && o.x < x + item.cols }
        }
        if (x < 0 || x + item.cols > COLS || y < 0 || y + item.rows > ROWS) return false
        if (item.flat) return others.none { o -> o.item.flat && o.tiles().any { it in Placed(item, x, y).tiles() } }
        val mine = Placed(item, x, y).tiles().toSet()
        return others.none { o -> !o.item.flat && !o.item.onWall && o.tiles().any { it in mine } }
    }

    fun with(p: Placed): RoomLayout = copy(items = items + p)
    fun without(p: Placed): RoomLayout = copy(items = items.filter { it !== p })
    fun replace(old: Placed, new: Placed): RoomLayout = copy(items = items.map { if (it === old) new else it })

    /** The first spot a new piece fits, front rows first (where the owner sees it), or null when the room is full. */
    fun freeSpot(item: Furniture): Pair<Int, Int>? {
        if (item.onWall) {
            for (y in WALL_SLOTS - 1 downTo 0) for (x in 0..COLS - item.cols) if (canPlace(item, x, y)) return x to y
            return null
        }
        for (y in ROWS - item.rows downTo 0) for (x in 0..COLS - item.cols) if (canPlace(item, x, y)) return x to y
        return null
    }

    fun encode(): String = "1;" + (if (theme == RoomTheme.COZY) "" else "T:${theme.name};") +
        items.joinToString(";") { p -> "${p.item.name}:${p.x},${p.y}" + (if (p.flip) ",f" else "") }

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

        /** A starter arrangement for each room, so switching themes never shows an empty box. */
        fun starter(theme: RoomTheme): RoomLayout = when (theme) {
            RoomTheme.COZY -> DEFAULT
            RoomTheme.LIVING -> RoomLayout(listOf(
                Placed(Furniture.WINDOW, 1, 3), Placed(Furniture.FRAME, 5, 4), Placed(Furniture.CLOCK, 8, 4), Placed(Furniture.SHELF, 6, 2),
                Placed(Furniture.RUG, 3, 2), Placed(Furniture.SOFA, 0, 3), Placed(Furniture.PLANT, 9, 1), Placed(Furniture.BOWL, 8, 3), Placed(Furniture.LAMP, 0, 1),
            ), RoomTheme.LIVING)
            RoomTheme.PLAYROOM -> RoomLayout(listOf(
                Placed(Furniture.WINDOW, 1, 3), Placed(Furniture.BUNTING, 5, 4), Placed(Furniture.FRAME, 8, 2),
                Placed(Furniture.RUG, 4, 2), Placed(Furniture.BED, 0, 3), Placed(Furniture.TOY_CHEST, 8, 1), Placed(Furniture.BOWL, 8, 3), Placed(Furniture.PLANT, 0, 1),
            ), RoomTheme.PLAYROOM)
            RoomTheme.GARDEN -> RoomLayout(listOf(
                Placed(Furniture.WINDOW, 1, 3), Placed(Furniture.FRAME, 7, 3),
                Placed(Furniture.FLOWER_BED, 0, 1), Placed(Furniture.BED, 0, 3), Placed(Furniture.BOWL, 8, 3), Placed(Furniture.PLANT, 9, 1), Placed(Furniture.LAMP, 8, 1),
            ), RoomTheme.GARDEN)
            RoomTheme.BEDROOM -> RoomLayout(listOf(
                Placed(Furniture.WINDOW, 1, 3), Placed(Furniture.STAR_GARLAND, 5, 4), Placed(Furniture.FRAME, 8, 2),
                Placed(Furniture.RUG, 4, 2), Placed(Furniture.BIG_BED, 0, 1), Placed(Furniture.BOWL, 8, 3), Placed(Furniture.LAMP, 9, 1), Placed(Furniture.PLANT, 8, 1),
            ), RoomTheme.BEDROOM)
            RoomTheme.KITCHEN -> RoomLayout(listOf(
                Placed(Furniture.WINDOW, 6, 3), Placed(Furniture.CLOCK, 1, 4), Placed(Furniture.SHELF, 1, 2),
                Placed(Furniture.COUNTER, 0, 0), Placed(Furniture.KITCHEN_MAT, 4, 2), Placed(Furniture.BED, 7, 3), Placed(Furniture.BOWL, 4, 1), Placed(Furniture.PLANT, 9, 1),
            ), RoomTheme.KITCHEN)
            RoomTheme.BATHROOM -> RoomLayout(listOf(
                Placed(Furniture.MIRROR, 7, 3), Placed(Furniture.WINDOW, 1, 3),
                Placed(Furniture.BATHTUB, 0, 0), Placed(Furniture.BATH_MAT, 1, 2), Placed(Furniture.BED, 6, 3), Placed(Furniture.BOWL, 4, 3), Placed(Furniture.PLANT, 9, 1),
            ), RoomTheme.BATHROOM)
        }

        /** Reads [encode]'s string; pieces that don't fit or overlap are dropped, so a bad string still gives a room. Null for a different format. */
        fun decode(code: String?): RoomLayout? {
            if (code == null || code.length > MAX_CODE) return null
            val parts = code.split(';')
            if (parts.firstOrNull() != "1") return null
            var layout = RoomLayout(emptyList())
            for (part in parts.drop(1)) {
                if (part.isEmpty()) continue
                val (name, pos) = part.split(':').let { if (it.size != 2) return@let null to null else it[0] to it[1] }
                // The room's theme comes first in the code; an app that doesn't know it keeps the cozy nook.
                if (name == "T") { RoomTheme.of(pos)?.let { layout = layout.inTheme(it) }; continue }
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
