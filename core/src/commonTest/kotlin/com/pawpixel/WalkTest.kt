package com.pawpixel

import com.pawpixel.core.AppState
import com.pawpixel.core.CareStats
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.core.StateCodec
import com.pawpixel.core.StateOps
import com.pawpixel.core.Walk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkTest {
    private val clock = LocalClock.MANILA
    private val pet = Pet("p1", "Kape", Species.DOG, 0)
    private val now = clock.at(20000, 18 * 60)

    @Test fun walksAreKeptNewestFirstAndSurviveTheCodec() {
        var s = AppState(pets = listOf(pet))
        s = StateOps.addWalk(s, Walk("w1", "p1", now - 86_400_000L, now - 86_400_000L + 25 * 60_000L, 2_300))
        s = StateOps.addWalk(s, Walk("w2", "p1", now - 30 * 60_000L, now, null))
        assertEquals(listOf("w2", "w1"), s.walksFor("p1").map { it.id })
        assertEquals(25, s.walks[0].minutes); assertTrue(kotlin.math.abs(s.walks[0].km!! - 1.61) < 0.001)
        val back = StateCodec.decode(StateCodec.encode(s))
        assertEquals(s.walks.toSet(), back.walks.toSet())
    }

    @Test fun theWeekSumsWalksMinutesAndKmWhereStepsWereCounted() {
        var s = AppState(pets = listOf(pet))
        s = StateOps.addWalk(s, Walk("w1", "p1", now - 86_400_000L, now - 86_400_000L + 25 * 60_000L, 2_300))
        s = StateOps.addWalk(s, Walk("w2", "p1", now - 30 * 60_000L, now, 1_000))
        s = StateOps.addWalk(s, Walk("old", "p1", now - 10 * 86_400_000L, now - 10 * 86_400_000L + 60_000L, 9_000))
        val week = CareStats.walkWeek(s, "p1", now, clock)
        assertEquals(2, week.walks); assertEquals(55, week.minutes); assertTrue(kotlin.math.abs(week.km!! - 2.31) < 0.001)
        val timedOnly = StateOps.addWalk(AppState(pets = listOf(pet)), Walk("t", "p1", now - 60_000L, now, null))
        assertNull(CareStats.walkWeek(timedOnly, "p1", now, clock).km)
    }
}
