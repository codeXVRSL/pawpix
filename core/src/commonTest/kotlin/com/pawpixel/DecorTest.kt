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
