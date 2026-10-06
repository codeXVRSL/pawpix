package com.pawpixel

import com.pawpixel.core.AlbumPhoto
import com.pawpixel.core.AppState
import com.pawpixel.core.CareTask
import com.pawpixel.core.Challenge
import com.pawpixel.core.Challenges
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.core.StateOps
import com.pawpixel.core.TaskKind
import com.pawpixel.core.Units
import com.pawpixel.core.Walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChallengeTest {
    private val clock = LocalClock.MANILA
    private val dog = Pet("d", "Kape", Species.DOG, 0)
    private val cat = Pet("c", "Tala", Species.CAT, 0)

    @Test fun everyMonthHasAGoalAndCatsNeverWalk() {
        for (m in 1..12) {
            val d = Challenges.forMonth(2026, m, Species.DOG)
            assertTrue(d.target > 0); assertTrue(d.goal.isNotBlank()); assertTrue(d.title.isNotBlank())
            val c = Challenges.forMonth(2026, m, Species.CAT)
            assertTrue(c.kind == Challenge.Kind.CARE_DAYS || c.kind == Challenge.Kind.PHOTOS, "cats in month $m: ${c.kind}")
        }
        assertEquals(Challenge.Kind.CARE_DAYS, Challenges.forMonth(2026, 10, Species.DOG).kind)
        assertEquals(Challenge.Kind.WALK_KM, Challenges.forMonth(2026, 9, Species.DOG).kind)
        assertEquals("2026-09", Challenges.forMonth(2026, 9, Species.DOG).id)
    }

    @Test fun careDaysCountDistinctDaysInTheMonthOnly() {
        val oct1 = LocalClock.dayOf(2026, 10, 1)
        var s = AppState(pets = listOf(dog), tasks = listOf(CareTask("t", "d", TaskKind.FEED, "Breakfast", listOf(8 * 60))))
        for (day in listOf(oct1 - 1, oct1, oct1, oct1 + 3, oct1 + 31)) s = StateOps.complete(s, "t", clock.at(day, 8 * 60), clock)
        val now = clock.at(oct1 + 5, 12 * 60)
        val ch = Challenges.current(now, clock, Species.DOG)
        assertEquals(10, ch.month); assertEquals(Challenge.Kind.CARE_DAYS, ch.kind)
        assertEquals(2, Challenges.progress(s, "d", ch, clock))
        assertEquals(26, Challenges.daysLeft(ch, now, clock))
        assertFalse(ch.isDone(2)); assertTrue(ch.isDone(25))
        assertEquals("2 of 25 days", ch.progressText(2))
    }

    @Test fun walkKmAddsUpInMetresAndShowsInTheOwnersUnit() {
        val sep1 = LocalClock.dayOf(2026, 9, 1)
        var s = AppState(pets = listOf(dog))
        s = StateOps.addWalk(s, Walk("w1", "d", clock.at(sep1 + 2, 7 * 60), clock.at(sep1 + 2, 7 * 60 + 30), 3_000)) // 2.1 km
        s = StateOps.addWalk(s, Walk("w2", "d", clock.at(sep1 + 9, 7 * 60), clock.at(sep1 + 9, 8 * 60), 5_000)) // 3.5 km
        s = StateOps.addWalk(s, Walk("aug", "d", clock.at(sep1 - 1, 7 * 60), clock.at(sep1 - 1, 8 * 60), 9_000))
        val ch = Challenges.forMonth(2026, 9, Species.DOG)
        val m = Challenges.progress(s, "d", ch, clock)
        assertEquals(5_600, m)
        Units.configure(Units.METRIC, "")
        assertEquals("5.6 of 40 km", ch.progressText(m))
        assertTrue(ch.fraction(m) > 0.13f && ch.fraction(m) < 0.15f)
        Units.configure(Units.IMPERIAL, "")
        assertEquals("3.5 of 25 mi", ch.progressText(m))
        Units.configure("", "PH")
    }

    @Test fun photosCountTheMonthsAdditions() {
        val feb1 = LocalClock.dayOf(2026, 2, 1)
        var s = AppState(pets = listOf(cat))
        s = StateOps.addAlbumPhoto(s, AlbumPhoto("a", "c", clock.at(feb1, 10 * 60)))
        s = StateOps.addAlbumPhoto(s, AlbumPhoto("b", "c", clock.at(feb1 + 27, 10 * 60)))
        s = StateOps.addAlbumPhoto(s, AlbumPhoto("mar", "c", clock.at(feb1 + 28, 10 * 60)))
        val ch = Challenges.forMonth(2026, 2, Species.CAT)
        assertEquals(Challenge.Kind.PHOTOS, ch.kind)
        assertEquals(2, Challenges.progress(s, "c", ch, clock))
    }
}
