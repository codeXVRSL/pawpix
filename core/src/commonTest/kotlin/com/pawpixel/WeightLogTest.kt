package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weight log: entry in 0.1 kg steps, editing, the chart's axis, saving. */
class WeightLogTest {
    private val pet = Pet("mochi", "Mochi", Species.DOG, 0)
    private val base = AppState(pets = listOf(pet))

    @Test fun kilogramsInTenthSteps() {
        assertEquals(4200, WeightTrend.parseKg("4.2"))
        assertEquals(4200, WeightTrend.parseKg("4,2 kg"))
        assertEquals(4200, WeightTrend.parseKg("4.24"))
        assertEquals(12_300, WeightTrend.parseKg(" 12.25KG "))
        assertEquals(100, WeightTrend.parseKg("0.1"))
        assertNull(WeightTrend.parseKg("0.04"))
        assertNull(WeightTrend.parseKg("-3"))
        assertNull(WeightTrend.parseKg("NaN"))
        assertNull(WeightTrend.parseKg("1e9"))
        assertNull(WeightTrend.parseKg(""))
        assertEquals("4.2", WeightTrend.kgInput(4200))
        assertEquals("0.1", WeightTrend.kgInput(100))
    }

    @Test fun editAndDeleteWeighIns() {
        var s = StateOps.logWeight(base, "mochi", 20000, 4200)
        s = StateOps.logWeight(s, "mochi", 20010, 4300)
        // A typo fixed.
        s = StateOps.editWeight(s, "mochi", 20010, 20010, 4400)
        assertEquals(listOf(4200, 4400), s.weightsFor("mochi").map { it.grams })
        // The wrong day: moved, replacing whatever was logged that day.
        s = StateOps.editWeight(s, "mochi", 20010, 20000, 4500)
        assertEquals(listOf(Weight("mochi", 20000, 4500)), s.weightsFor("mochi"))
        // Nothing to edit, or nonsense: unchanged.
        assertEquals(s, StateOps.editWeight(s, "mochi", 19999, 20000, 4000))
        assertEquals(s, StateOps.editWeight(s, "mochi", 20000, 20000, 0))
        assertTrue(StateOps.removeWeight(s, "mochi", 20000).weights.isEmpty())
        assertEquals(s, StateOps.logWeight(s, "nobody", 20000, 4000), "only for a pet that exists")
    }

    @Test fun weightsSaveAndLoadAndBadOnesAreDropped() {
        var s = base
        for (d in 0 until 5L) s = StateOps.logWeight(s, "mochi", 20000 + d * 7, 4000 + d.toInt() * 100)
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
        val json = StateCodec.encode(s).replace(
            "\"weights\":[",
            "\"weights\":[{\"petId\":\"ghost\",\"day\":1,\"g\":10},{\"petId\":\"mochi\",\"day\":2,\"g\":-5},{\"petId\":\"mochi\",\"g\":300},",
        )
        assertEquals(s.weights, StateCodec.decode(json).weights)
        assertTrue(StateCodec.decode("""{"pets":[{"id":"mochi","name":"M"}]}""").weights.isEmpty(), "older saves have none")
        // At most MAX_WEIGHTS_PER_PET are kept, the latest.
        var many = base
        for (d in 0 until AppState.MAX_WEIGHTS_PER_PET + 5L) many = StateOps.logWeight(many, "mochi", d, 4000)
        assertEquals(AppState.MAX_WEIGHTS_PER_PET, many.weights.size)
        assertEquals(5L, many.weightsFor("mochi").first().day)
    }

    @Test fun chartAxisOnRoundSteps() {
        assertEquals(Triple(4000, 4200, 100), WeightTrend.axis(listOf(4000, 4200)))
        assertEquals(Triple(4100, 4300, 100), WeightTrend.axis(listOf(4200, 4200)), "a flat line still gets room")
        assertEquals(Triple(0, 200, 100), WeightTrend.axis(listOf(100, 100)), "never below zero")
        assertEquals(Triple(8000, 12_000, 2000), WeightTrend.axis(listOf(8200, 11_900)))
        assertEquals(Triple(20_000, 40_000, 10_000), WeightTrend.axis(listOf(21_000, 36_500)))
        for (values in listOf(listOf(350, 900), listOf(3000, 3100, 2900), listOf(45_000, 52_000))) {
            val (bottom, top, step) = WeightTrend.axis(values)
            assertTrue(bottom <= values.min() && top >= values.max() && (top - bottom) / step in 2..4, "$values -> $bottom..$top by $step")
            assertEquals(0, bottom % step)
        }
    }
}

/** A health item's records: history, deleting one, and their photos in backups. */
class HealthRecordsTest {
    private val clock = LocalClock.MANILA
    private val today = LocalClock.dayOf(2026, 9, 29)
    private val now = clock.at(today, 12 * 60)
    private val pet = Pet("p1", "Mingming", Species.CAT, 0, birthDay = today - 60)

    @Test fun historyDeleteAndPhotos() {
        var s = HealthPlan.addTo(AppState(pets = listOf(pet)), pet, now, clock)
        val fvrcp = s.tasks.single { it.title == "FVRCP vaccine" }
        var n = 0
        s = StateOps.logOnDay(s, fvrcp.id, today - 4, now, clock, newId = { "rec${n++}" })
        s = StateOps.logOnDay(s, fvrcp.id, today, now, clock, newId = { "rec${n++}" })
        val history = CareStats.healthRecords(s, fvrcp.id)
        assertEquals(listOf("rec1", "rec0"), history.map { it.completion.id })
        assertEquals(listOf(2, 1), history.map { it.dose })
        assertTrue(Backup.filesFor(s, "p1").containsAll(listOf("sprites/p1/rec-rec0.jpg", "sprites/p1/rec-rec1.jpg")))
        // Delete the first one (a mistake): the other becomes dose 1, and today stays a day of care.
        s = StateOps.removeCompletion(s, "rec0")
        assertEquals(listOf(1), CareStats.healthRecords(s, fvrcp.id).map { it.dose })
        assertEquals(listOf(today), s.pets[0].careDays)
        s = StateOps.removeCompletion(s, "rec1")
        assertTrue(s.pets[0].careDays.isEmpty())
        assertEquals(s, StateOps.removeCompletion(s, "missing"))
        assertTrue(Backup.filesFor(s, "p1").none { it.contains("/rec-") })
    }
}
