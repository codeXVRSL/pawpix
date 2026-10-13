package com.pawpixel

import com.pawpixel.core.AppState
import com.pawpixel.core.Milestones
import com.pawpixel.core.Mood
import com.pawpixel.core.Pet
import com.pawpixel.core.Sky
import com.pawpixel.core.Species
import com.pawpixel.core.StateCodec
import com.pawpixel.sprite.Behavior
import com.pawpixel.sprite.Furniture
import com.pawpixel.sprite.PetBrain
import com.pawpixel.sprite.Placed
import com.pawpixel.sprite.Places
import com.pawpixel.sprite.Room
import com.pawpixel.sprite.RoomLayout
import com.pawpixel.sprite.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decorated room: the layout's rules and code, the drawing, and the pet using what's in it. */
class DecorTest {
    @Test fun theDefaultRoomFitsAndRoundTrips() {
        val d = RoomLayout.DEFAULT
        for (p in d.items) assertTrue(d.without(p).canPlace(p.item, p.x, p.y), "${p.item} at ${p.x},${p.y} fits")
        assertEquals(d, RoomLayout.decode(d.encode()))
        assertTrue(d.encode().all { it.isLetterOrDigit() || it in "_;:," }, "the code travels through the server's check: ${d.encode()}")
    }

    @Test fun piecesCannotOverlapButRugsLieUnderThem() {
        val r = RoomLayout(listOf(Placed(Furniture.BED, 2, 2)))
        assertFalse(r.canPlace(Furniture.PLANT, 4, 2), "the bed is three tiles wide")
        assertTrue(r.canPlace(Furniture.PLANT, 5, 2))
        assertTrue(r.canPlace(Furniture.RUG, 1, 1), "a rug goes under the bed")
        assertFalse(r.with(Placed(Furniture.RUG, 1, 1)).canPlace(Furniture.ROUND_RUG, 2, 2), "two rugs can't share a tile")
        assertFalse(r.canPlace(Furniture.BED, 9, 0), "off the right edge")
        assertFalse(r.canPlace(Furniture.FRAME, 0, RoomLayout.WALL_SLOTS), "above the wall's slots")
        assertTrue(r.canPlace(Furniture.FRAME, 0, 0))
        val moved = r.replace(r.items[0], Placed(Furniture.BED, 3, 2))
        assertTrue(r.canPlace(Furniture.BED, 3, 2, except = r.items[0]), "a piece may move onto tiles it already covers")
        assertEquals(3, moved.items[0].x)
    }

    @Test fun aNewPieceGoesToTheFrontRowFirst() {
        val spot = assertNotNull(RoomLayout.DEFAULT.freeSpot(Furniture.CHAIR))
        assertEquals(RoomLayout.ROWS - 1, spot.second)
        val wall = assertNotNull(RoomLayout.DEFAULT.freeSpot(Furniture.POSTER))
        assertTrue(RoomLayout.DEFAULT.canPlace(Furniture.POSTER, wall.first, wall.second))
        var full = RoomLayout(emptyList())
        while (true) { val s = full.freeSpot(Furniture.BOOKCASE) ?: break; full = full.with(Placed(Furniture.BOOKCASE, s.first, s.second)) }
        assertEquals(RoomLayout.COLS / 2 * RoomLayout.ROWS, full.items.size, "bookcases fill the floor")
        assertNull(full.freeSpot(Furniture.CHAIR))
    }

    @Test fun aBadCodeStillGivesARoom() {
        assertNull(RoomLayout.decode("2;BED:1,1"), "a newer format is not guessed at")
        val r = assertNotNull(RoomLayout.decode("1;BED:1,3;BED:2,3;SOFA:0,0;LAMP:x,1;BOWL:7,3,f;"))
        assertEquals(listOf(Furniture.BED, Furniture.BOWL), r.items.map { it.item }, "an overlapping bed, an unknown piece and a bad position are dropped")
        assertTrue(r.items[1].flip)
    }

    @Test fun theRoomIsSavedWithThePetAndOnlyEarnedPiecesAreKept() {
        val pet = Pet("p1", "Chelsea", Species.CAT, 0, careDays = (1L..4L).toList())
        val wanted = RoomLayout.DEFAULT.with(Placed(Furniture.TABLE, 4, 4)).with(Placed(Furniture.BOOKCASE, 6, 0))
        val s = Milestones.decorate(AppState(pets = listOf(pet)), "p1", wanted, nowMs = 5)
        val saved = s.pets[0]
        assertEquals(5, saved.editedAtMs, "the household's later-edit-wins clock moves")
        assertTrue(saved.layout.items.any { it.item == Furniture.TABLE }, "earned on day 3")
        assertFalse(saved.layout.items.any { it.item == Furniture.BOOKCASE }, "not until day 10")
        val back = StateCodec.decode(StateCodec.encode(s)).pets[0]
        assertEquals(saved.layout, back.layout)
        // Back to the default: nothing is stored.
        assertNull(Milestones.decorate(s, "p1", RoomLayout.DEFAULT, 6).pets[0].room)
    }

    @Test fun aThemedRoomKeepsItsThemeAndOlderAppsStillReadIt() {
        val garden = RoomLayout.starter(com.pawpixel.sprite.RoomTheme.GARDEN)
        assertTrue(garden.encode().startsWith("1;T:GARDEN;"), garden.encode())
        assertEquals(garden, RoomLayout.decode(garden.encode()))
        assertTrue(garden.encode().all { it.isLetterOrDigit() || it in "_;:," })
        // A piece from another room can't be placed here; the ones that fit anywhere can.
        assertFalse(garden.canPlace(Furniture.SOFA, 3, 3))
        assertTrue(garden.canPlace(Furniture.FLOWER_BED, 4, 1) || garden.canPlace(Furniture.FLOWER_BED, 6, 1))
        // Switching rooms keeps what fits and puts the rest away.
        val living = garden.inTheme(com.pawpixel.sprite.RoomTheme.LIVING)
        assertFalse(living.items.any { it.item == Furniture.FLOWER_BED })
        assertTrue(living.items.any { it.item == Furniture.BOWL })
        // The theme survives the unlock check, and every starter room only uses pieces free from day one.
        val pet = Pet("p1", "Chelsea", Species.CAT, 0)
        val s = Milestones.decorate(AppState(pets = listOf(pet)), "p1", garden, nowMs = 5)
        assertEquals(com.pawpixel.sprite.RoomTheme.GARDEN, s.pets[0].layout.theme)
        for (t in com.pawpixel.sprite.RoomTheme.entries) {
            val st = RoomLayout.starter(t)
            assertEquals(t, st.theme)
            assertTrue(st.items.all { it.item.unlockDays == 0 }, "$t's starter pieces are all free")
            for (pl in st.items) assertTrue(st.without(pl).canPlace(pl.item, pl.x, pl.y), "$t: ${pl.item} at ${pl.x},${pl.y} fits")
            assertTrue(Milestones.decorate(AppState(pets = listOf(pet)), "p1", st, 5).pets[0].layout == st, "$t's starter survives the unlock check")
        }
        // The code with the theme is what older apps see: they skip the T token and keep the cozy nook.
        assertNull(RoomLayout.decode("1;T:ATTIC;BED:0,3")?.takeIf { it.theme != com.pawpixel.sprite.RoomTheme.COZY })
    }

    @Test fun everyThemeDrawsEveryOfItsPieces() {
        for (t in com.pawpixel.sprite.RoomTheme.entries) for (phase in listOf(Sky.Phase.DAY, Sky.Phase.NIGHT)) {
            val pieces = Furniture.entries.filter { it.theme == t }
            var layout = RoomLayout(emptyList(), t)
            for (f in pieces) { val spot = layout.freeSpot(f) ?: continue; layout = layout.with(Placed(f, spot.first, spot.second)) }
            val img = Room.render(100, 150, 118, phase, layout = layout)
            assertTrue(img.pixels.any { it != 0 })
            assertTrue(layout.items.size >= pieces.size - 2, "$t: ${layout.items.size} of ${pieces.size} pieces fit the room")
        }
    }

    @Test fun theSceneSortsStandingPiecesByTheirFrontEdge() {
        val scene = Room.scene(120, 90, 50, Sky.Phase.DAY)
        assertEquals(RoomLayout.DEFAULT.items.count { !it.item.onWall && !it.item.flat }, scene.props.size)
        val lamp = scene.props.first { it.placed.item == Furniture.LAMP }
        val bed = scene.props.first { it.placed.item == Furniture.BED }
        assertTrue(bed.baseline > lamp.baseline, "the bed (row 3) is drawn over the lamp (row 1)")
        assertEquals(bed.baseline, bed.y + bed.image.height, "a piece stands on its front edge")
        val still = Room.render(120, 90, 62, Sky.Phase.NIGHT, season = Season.CHRISTMAS)
        assertEquals(120, still.width)
        assertTrue(still.pixels.any { it != 0 })
        // The season's piece stands on a free tile as one more prop, drawn as the season's own thing, never saved.
        val xmas = Room.scene(120, 90, 50, Sky.Phase.DAY, season = Season.CHRISTMAS)
        assertEquals(scene.props.size + 1, xmas.props.size, "the tree is in the room")
        val tree = xmas.props.single { it.placed !in RoomLayout.DEFAULT.items }
        assertTrue(tree.image.pixels.any { it != 0 } && tree.placed.y in 0 until RoomLayout.ROWS)
    }

    @Test fun aLayoutCodeIsKeptShortEnoughToSaveAndShare() {
        var r = RoomLayout(emptyList())
        for (item in listOf(Furniture.SCRATCH_POST, Furniture.BIG_PLANT, Furniture.TEA_SET, Furniture.RADIO, Furniture.CHAIR, Furniture.TOY_BOX, Furniture.PLANT, Furniture.LAMP)) {
            while (true) { val s = r.freeSpot(item) ?: break; r = r.with(Placed(item, s.first, s.second, flip = true)) }
        }
        assertFalse(r.fits(), "a floor packed with long-named pieces outgrows the code: ${r.encode().length}")
        assertNull(RoomLayout.decode(r.encode()), "and such a code is not read back")
        assertTrue(RoomLayout.DEFAULT.fits())
    }

    @Test fun everyPieceDrawsAtEverySize() {
        for (phase in Sky.Phase.entries) for (item in Furniture.entries) {
            val layout = RoomLayout(listOf(Placed(item, 0, 0), Placed(item, 2 + item.cols, 1, flip = true)).filter { RoomLayout(emptyList()).canPlace(it.item, it.x, it.y) })
            for ((w, h, floorY) in listOf(Triple(130, 100, 70), Triple(60, 40, 30), Triple(200, 300, 230))) {
                val img = Room.render(w, h, floorY, phase, layout = layout)
                assertEquals(w * h, img.pixels.size)
            }
        }
    }

    @Test fun thePetWalksToItsBedToSleepAndToItsBowlToEat() {
        val body = intArrayOf(5, 4, 45, 42)
        val places = Places(rows = 5, bed = 10.0 to 3, bowl = 60.0 to 1)
        val brain = PetBrain(seed = 3, stageWidth = 120.0, canvasWidth = 51, body = body, places = places)
        var t = 0L
        var pose = brain.pose(t, Mood.SLEEPY)
        assertEquals(Behavior.WALK, pose.behavior, "sleepy, it heads for the bed first")
        while (pose.behavior == Behavior.WALK && t < 30_000) { t += 50; pose = brain.pose(t, Mood.SLEEPY) }
        assertEquals(Behavior.SLEEP, pose.behavior)
        assertEquals(10.0, brain.x, 0.01)
        assertEquals(3.0, brain.row, 0.01)
        assertEquals((3 - Room.PET_ROW) * Room.TILE_D, pose.dy)

        brain.react(com.pawpixel.sprite.PetEvent.Cared(com.pawpixel.core.TaskKind.FEED), t)
        pose = brain.pose(t, Mood.HUNGRY)
        assertEquals(Behavior.WALK, pose.behavior)
        while (pose.behavior == Behavior.WALK && t < 60_000) { t += 50; pose = brain.pose(t, Mood.HUNGRY) }
        assertEquals(Behavior.EAT, pose.behavior)
        assertTrue(pose.atBowl, "it eats from the room's bowl, so none is drawn beside it")
        assertTrue(pose.effects.none { it.kind == com.pawpixel.sprite.EffectKind.BOWL })
    }

    @Test fun eachSpeciesPicksTheBedAndToyItLikes() {
        val room = RoomLayout(listOf(Placed(Furniture.BED, 0, 3), Placed(Furniture.SOFA, 4, 3), Placed(Furniture.DOGHOUSE, 8, 1), Placed(Furniture.SCRATCH_POST, 9, 4), Placed(Furniture.BALL_PIT, 0, 0)), com.pawpixel.sprite.RoomTheme.COZY)
        assertEquals(Furniture.DOGHOUSE, room.bedFor(com.pawpixel.core.Species.DOG)?.item)
        assertEquals(Furniture.SOFA, room.bedFor(com.pawpixel.core.Species.CAT)?.item)
        assertEquals(Furniture.BED, room.bedFor(com.pawpixel.core.Species.RABBIT)?.item, "no tent: the plain bed")
        assertEquals(Furniture.BED, room.bedFor(com.pawpixel.core.Species.OTHER)?.item)
        assertEquals(Furniture.BALL_PIT, room.toyFor(com.pawpixel.core.Species.DOG)?.item)
        assertEquals(Furniture.SCRATCH_POST, room.toyFor(com.pawpixel.core.Species.CAT)?.item)
        assertEquals(null, RoomLayout.DEFAULT.toyFor(com.pawpixel.core.Species.CAT), "the starter room has no toy")
        // Every sleeping place and every toy is in every species' list, so the choice never falls to list order by accident.
        for (sp in com.pawpixel.core.Species.entries) {
            assertEquals(Furniture.entries.filter { it.use == Furniture.Use.SLEEP }.toSet(), Furniture.sleepPreference(sp).toSet(), "$sp beds")
            assertEquals(Furniture.entries.filter { it.use == Furniture.Use.PLAY }.toSet(), Furniture.playPreference(sp).toSet(), "$sp toys")
        }
    }

    @Test fun afterAWalkThePetZoomsThenHopsOnItsToy() {
        val places = Places(rows = 5, bed = 10.0 to 3, play = 50.0 to 2)
        val brain = PetBrain(seed = 3, stageWidth = 120.0, canvasWidth = 51, body = intArrayOf(5, 4, 45, 42), places = places)
        var t = 0L
        brain.pose(t, Mood.CONTENT)
        brain.react(com.pawpixel.sprite.PetEvent.Cared(com.pawpixel.core.TaskKind.WALK), t)
        var pose = brain.pose(t, Mood.HAPPY)
        assertEquals(Behavior.ZOOMIES, pose.behavior)
        val seen = ArrayList<Behavior>()
        while (t < 40_000 && seen.lastOrNull() != Behavior.HOP) { t += 50; pose = brain.pose(t, Mood.HAPPY); if (seen.lastOrNull() != pose.behavior) seen += pose.behavior }
        assertEquals(Behavior.HOP, pose.behavior, "zoomies, then a walk to the toy, then hops: $seen")
        assertEquals(50.0, brain.x, 0.01); assertEquals(2.0, brain.row, 0.01)
        assertTrue(Behavior.ZOOMIES in seen && Behavior.WALK in seen, "$seen")
    }

    @Test fun aTapBesideThePetMakesItGlanceThatWayUnlessItIsBusy() {
        val brain = PetBrain(seed = 5, stageWidth = 120.0, canvasWidth = 51, body = intArrayOf(5, 4, 45, 42), places = Places(rows = 3))
        var t = 0L
        var pose = brain.pose(t, Mood.SAD) // sad pets mostly sit still
        while (pose.behavior != Behavior.IDLE && t < 20_000) { t += 50; pose = brain.pose(t, Mood.SAD) }
        assertEquals(Behavior.IDLE, pose.behavior)
        brain.react(com.pawpixel.sprite.PetEvent.Noticed(left = true), t)
        assertEquals(com.pawpixel.sprite.Frame.BASE, brain.pose(t + 100, Mood.SAD).frame, "a beat passes before it turns")
        assertEquals(com.pawpixel.sprite.Frame.LOOK_LEFT, brain.pose(t + 600, Mood.SAD).frame)
        assertEquals(Behavior.GLANCE, brain.pose(t + 600, Mood.SAD).behavior)
        // Asleep, it does not stir.
        val sleeper = PetBrain(seed = 5, stageWidth = 120.0, canvasWidth = 51, body = intArrayOf(5, 4, 45, 42), places = Places(rows = 3))
        var s = sleeper.pose(0, Mood.SLEEPY); var u = 0L
        while (s.behavior != Behavior.SLEEP && u < 30_000) { u += 50; s = sleeper.pose(u, Mood.SLEEPY) }
        sleeper.react(com.pawpixel.sprite.PetEvent.Noticed(left = false), u)
        assertEquals(Behavior.SLEEP, sleeper.pose(u + 600, Mood.SLEEPY).behavior)
    }

    @Test fun aCalmPetNeverWalksHopsOrZooms() {
        val brain = PetBrain(seed = 9, stageWidth = 120.0, canvasWidth = 51, body = intArrayOf(5, 4, 45, 42), places = Places(rows = 5, bed = 10.0 to 3, play = 50.0 to 2), calm = true)
        val still = setOf(Behavior.IDLE, Behavior.LOOK, Behavior.PETTED, Behavior.EAT, Behavior.SLEEP, Behavior.GLANCE)
        var t = 0L
        val x0 = brain.x; val row0 = brain.row
        for (mood in listOf(Mood.HAPPY, Mood.RESTLESS, Mood.CONTENT, Mood.SLEEPY)) {
            repeat(400) { t += 50; val p = brain.pose(t, mood); assertTrue(p.behavior in still, "$mood: ${p.behavior}") }
        }
        brain.react(com.pawpixel.sprite.PetEvent.Cared(com.pawpixel.core.TaskKind.WALK), t)
        repeat(100) { t += 50; assertTrue(brain.pose(t, Mood.HAPPY).behavior in still) }
        assertEquals(x0, brain.x, 0.0); assertEquals(row0, brain.row, 0.0) // it is wherever it started: no movement at all
    }

    @Test fun aPatOnTheWayToBedDropsTheErrand() {
        val places = Places(rows = 5, bed = 10.0 to 3)
        val brain = PetBrain(seed = 3, stageWidth = 120.0, canvasWidth = 51, body = intArrayOf(5, 4, 45, 42), places = places)
        var pose = brain.pose(0, Mood.SLEEPY)
        assertEquals(Behavior.WALK, pose.behavior)
        brain.react(com.pawpixel.sprite.PetEvent.Petted, 100)
        pose = brain.pose(100, Mood.HAPPY)
        assertEquals(Behavior.PETTED, pose.behavior)
        var t = 100L
        while (pose.behavior == Behavior.PETTED && t < 5000) { t += 50; pose = brain.pose(t, Mood.HAPPY) }
        assertTrue(pose.behavior != Behavior.SLEEP, "happy and petted, it doesn't fall asleep on the spot where the walk was interrupted")
    }

    @Test fun withoutABedThePetSleepsWhereItStands() {
        val brain = PetBrain(seed = 3, stageWidth = 120.0, canvasWidth = 51, body = intArrayOf(5, 4, 45, 42))
        val pose = brain.pose(0, Mood.SLEEPY)
        assertEquals(Behavior.SLEEP, pose.behavior)
        assertEquals(0, pose.dy, "a stage with one row keeps the pet on it")
    }
}

class RoomCardTest {
    @Test fun theRoomCardShowsThePetInItsRoom() {
        fun art(fur: Int) = com.pawpixel.sprite.PetArt(com.pawpixel.sprite.PetLook(listOf(fur), IntArray(com.pawpixel.sprite.PetLook.GRID * com.pawpixel.sprite.PetLook.GRID)), Species.CAT)
        for (phase in listOf(Sky.Phase.DAY, Sky.Phase.NIGHT)) {
            val card = com.pawpixel.sprite.RoomCard.render(RoomLayout.DEFAULT, art(0xFFA0784E.toInt()), "Chelsea", phase)
            assertEquals(com.pawpixel.sprite.RoomCard.WIDTH * com.pawpixel.sprite.RoomCard.SCALE, card.width)
            assertEquals(com.pawpixel.sprite.RoomCard.HEIGHT * com.pawpixel.sprite.RoomCard.SCALE, card.height)
            // The pet is on the card, lit by the room: a pet of another colour gives another card.
            val other = com.pawpixel.sprite.RoomCard.render(RoomLayout.DEFAULT, art(0xFF3060C0.toInt()), "Chelsea", phase)
            val differ = card.pixels.indices.count { card.pixels[it] != other.pixels[it] }
            assertTrue(differ > 100 * com.pawpixel.sprite.RoomCard.SCALE * com.pawpixel.sprite.RoomCard.SCALE, "the pet's fur is on the card ($differ pixels differ)")
        }
    }
}

/** The UI's pixel icons: every glyph on the same 12x12 grid, drawn only with ink, light ink and empty cells, nothing stray. */
class PixelIconTest {
    @Test fun everyIconIsATidy12By12Grid() {
        val all = listOf(
            com.pawpixel.sprite.PixelIcons.BOWL, com.pawpixel.sprite.PixelIcons.DROP, com.pawpixel.sprite.PixelIcons.PAW, com.pawpixel.sprite.PixelIcons.BALL, com.pawpixel.sprite.PixelIcons.PILL,
            com.pawpixel.sprite.PixelIcons.COMB, com.pawpixel.sprite.PixelIcons.SCOOP, com.pawpixel.sprite.PixelIcons.SYRINGE, com.pawpixel.sprite.PixelIcons.WORM, com.pawpixel.sprite.PixelIcons.SHIELD,
            com.pawpixel.sprite.PixelIcons.STETHO, com.pawpixel.sprite.PixelIcons.HEART, com.pawpixel.sprite.PixelIcons.SCALE, com.pawpixel.sprite.PixelIcons.SHIRT, com.pawpixel.sprite.PixelIcons.SPARKLE,
            com.pawpixel.sprite.PixelIcons.SHARE, com.pawpixel.sprite.PixelIcons.PIN, com.pawpixel.sprite.PixelIcons.HOUSE, com.pawpixel.sprite.PixelIcons.GEAR, com.pawpixel.sprite.PixelIcons.BELL,
            com.pawpixel.sprite.PixelIcons.GLOBE, com.pawpixel.sprite.PixelIcons.BAG, com.pawpixel.sprite.PixelIcons.MOON, com.pawpixel.sprite.PixelIcons.STAR, com.pawpixel.sprite.PixelIcons.SAVE,
            com.pawpixel.sprite.PixelIcons.LOCK, com.pawpixel.sprite.PixelIcons.CAMERA, com.pawpixel.sprite.PixelIcons.CHEVRON_LEFT, com.pawpixel.sprite.PixelIcons.CHEVRON_RIGHT, com.pawpixel.sprite.PixelIcons.PENCIL,
            com.pawpixel.sprite.PixelIcons.PLUS, com.pawpixel.sprite.PixelIcons.CHECK, com.pawpixel.sprite.PixelIcons.CLOSE, com.pawpixel.sprite.PixelIcons.DICE, com.pawpixel.sprite.PixelIcons.PARTY,
            com.pawpixel.sprite.PixelIcons.PHONE, com.pawpixel.sprite.PixelIcons.PEOPLE,
        )
        for (icon in all) {
            assertEquals(12, icon.height, "12 rows")
            for (row in icon.rows) {
                assertEquals(12, row.length, "12 cells in '$row'")
                assertTrue(row.all { it == 'x' || it == 'o' || it == '.' }, "only ink, light and empty in '$row'")
            }
            // Something is drawn, and no lone pixel floats away from the rest (a stray that reads as dirt).
            val ink = (0 until 12).flatMap { y -> (0 until 12).filter { x -> icon.cell(x, y) != 0 }.map { x -> x to y } }
            assertTrue(ink.size >= 8)
            for ((x, y) in ink) {
                val neighbour = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1, x - 1 to y - 1, x + 1 to y - 1, x - 1 to y + 1, x + 1 to y + 1).any { it in ink }
                assertTrue(neighbour || icon === com.pawpixel.sprite.PixelIcons.PARTY || icon === com.pawpixel.sprite.PixelIcons.SPARKLE, "a stray pixel at $x,$y")
            }
        }
    }

    @Test fun theFineIconIsTheSameIconOnAGridTwiceAsDense() {
        val icon = com.pawpixel.sprite.PixelIcons.HEART
        val fine = icon.fine
        assertEquals(icon.width * 2, fine.width); assertEquals(icon.height * 2, fine.height)
        // Every ink cell of the icon still holds ink somewhere in its 2x2 block, and no ink appears where the icon had none nearby.
        for (y in 0 until icon.height) for (x in 0 until icon.width) {
            val block = listOf(fine.cell(2 * x, 2 * y), fine.cell(2 * x + 1, 2 * y), fine.cell(2 * x, 2 * y + 1), fine.cell(2 * x + 1, 2 * y + 1))
            if (icon.cell(x, y) != 0) assertTrue(block.any { it != 0 }, "ink lost at $x,$y")
            val neighbours = listOf(x to y - 1, x + 1 to y, x - 1 to y, x to y + 1).filter { (nx, ny) -> nx in 0 until icon.width && ny in 0 until icon.height }
            if (icon.cell(x, y) == 0 && neighbours.all { (nx, ny) -> icon.cell(nx, ny) == 0 }) assertTrue(block.all { it == 0 }, "ink appeared at $x,$y")
        }
        // A diagonal stroke gets its steps halved: the chevron's fine grid has more distinct row shapes than its coarse one.
        val chevron = com.pawpixel.sprite.PixelIcons.CHEVRON_RIGHT
        assertTrue(chevron.fine.rows.distinct().size > chevron.rows.distinct().size)
    }

}
