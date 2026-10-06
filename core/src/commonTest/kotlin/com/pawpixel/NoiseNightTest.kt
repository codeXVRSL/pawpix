package com.pawpixel

import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.ReminderPlanner
import com.pawpixel.core.Species
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoiseNightTest {
    private val clock = LocalClock.MANILA
    private val state = AppState(pets = listOf(Pet("p1", "Kape", Species.DOG, 0)))

    @Test fun newYearsEveGetsTwoNotesForEveryone() {
        val dec20 = clock.at(LocalClock.dayOf(2026, 12, 20), 12 * 60)
        val notes = ReminderPlanner.noiseNights(state, dec20, clock, country = "PH")
        assertEquals(2, notes.size)
        assertEquals(clock.at(LocalClock.dayOf(2026, 12, 30), 9 * 60), notes[0].atMs)
        assertEquals("🎆 Noise night tomorrow", notes[0].title)
        assertTrue("Kape" in notes[0].body)
        assertEquals(clock.at(LocalClock.dayOf(2026, 12, 31), 17 * 60), notes[1].atMs)
        assertTrue(notes[1].taskIds.isEmpty(), "no Done button on a note")
        // They ride along with the plan.
        assertTrue(ReminderPlanner.plan(state, dec20, clock, country = "PH").any { it.title == "🎆 Noise night tonight" })
    }

    @Test fun theFourthOfJulyIsAmericanAndBonfireNightBritish() {
        val june25 = clock.at(LocalClock.dayOf(2027, 6, 25), 12 * 60)
        assertEquals(2, ReminderPlanner.noiseNights(state, june25, clock, country = "US").size)
        assertEquals(0, ReminderPlanner.noiseNights(state, june25, clock, country = "PH").size)
        val oct20 = clock.at(LocalClock.dayOf(2027, 10, 20), 12 * 60)
        assertEquals(2, ReminderPlanner.noiseNights(state, oct20, clock, country = "gb").size)
        assertEquals(0, ReminderPlanner.noiseNights(state, oct20, clock, country = "US").size)
    }

    @Test fun nothingTooFarAheadOrAlreadyPastAndNothingForARememberedPet() {
        val sept = clock.at(LocalClock.dayOf(2026, 9, 1), 12 * 60)
        assertEquals(0, ReminderPlanner.noiseNights(state, sept, clock).size)
        // On the night itself after the note's time, only next year's eve would be left, which is too far.
        val late = clock.at(LocalClock.dayOf(2026, 12, 31), 20 * 60)
        assertEquals(0, ReminderPlanner.noiseNights(state, late, clock).size)
        val remembered = AppState(pets = listOf(Pet("p1", "Kape", Species.DOG, 0, rememberedDay = 20000)))
        assertEquals(0, ReminderPlanner.noiseNights(remembered, clock.at(LocalClock.dayOf(2026, 12, 20), 0), clock).size)
    }
}
