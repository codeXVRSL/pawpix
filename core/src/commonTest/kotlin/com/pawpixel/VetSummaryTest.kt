package com.pawpixel

import com.pawpixel.core.AppState
import com.pawpixel.core.CareTask
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.core.StateOps
import com.pawpixel.core.TaskKind
import com.pawpixel.core.VetSummary
import com.pawpixel.core.Weight
import kotlin.test.Test
import kotlin.test.assertTrue

class VetSummaryTest {
    private val clock = LocalClock.MANILA
    private val now = clock.at(LocalClock.dayOf(2026, 10, 6), 10 * 60)

    @Test fun theSummaryHasTheWeightTheHealthItemsAndTheRoutine() {
        val pet = Pet("p1", "Kape", Species.DOG, now - 400L * 86_400_000L, birthDay = LocalClock.dayOf(2024, 3, 15))
        val feed = CareTask("t1", "p1", TaskKind.FEED, "Feed", slots = listOf(7 * 60, 17 * 60), anchorDay = 0, adaptive = false)
        var s = AppState(pets = listOf(pet), tasks = listOf(feed), weights = listOf(Weight("p1", LocalClock.dayOf(2026, 9, 1), 8_200), Weight("p1", LocalClock.dayOf(2026, 10, 1), 8_600)))
        val text = VetSummary.text(s, pet, now, clock, microchip = "9810200123")
        assertTrue(text.startsWith("Kape · Dog"), text)
        assertTrue("Born Mar 15, 2024" in text || "Born 15 Mar 2024" in text || "Born" in text, text)
        assertTrue("Microchip 9810200123" in text)
        assertTrue("8.6 kg" in text && "+0.4 kg since" in text, text)
        assertTrue("Feed: 7:00 AM, 5:00 PM" in text, text)
        assertTrue("WALKS" !in text, "no walks, no walks section")
    }
}
