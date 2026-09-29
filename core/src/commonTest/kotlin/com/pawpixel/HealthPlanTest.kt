package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthPlanTest {
    private val clock = LocalClock.MANILA
    private val today = 20500L
    private val now = clock.at(today, 12 * 60)
    private var n = 0
    private fun ids() = { "t${n++}" }

    private fun planFor(species: Species, ageDays: Long?): AppState {
        val pet = Pet("p1", "Bantay", species, 0, birthDay = ageDays?.let { today - it })
        return HealthPlan.addTo(AppState(pets = listOf(pet)), pet, now, clock, ids())
    }
    private fun AppState.byTitle(t: String) = tasks.single { it.title == t }

    @Test fun adultDogGetsTheYearlyRoutineDueNow() {
        val s = planFor(Species.DOG, null)
        assertEquals(
            listOf("Anti-rabies shot", "5-in-1 vaccine", "Deworming", "Tick & flea prevention", "Heartworm prevention", "Vet check-up"),
            s.tasks.map { it.title },
        )
        assertTrue(s.tasks.all { it.series.isEmpty() && it.kind.health })
        assertTrue(CareStats.healthDue(s, "p1", now, clock).all { it.due }, "not known when last given: due now")
        // Adding again doesn't duplicate.
        val again = HealthPlan.addTo(s, s.pets[0], now, clock, ids())
        assertEquals(s.tasks.size, again.tasks.size)
    }

    @Test fun eightWeekPuppyGetsItsSeries() {
        val s = planFor(Species.DOG, 56)
        val shots = s.byTitle("5-in-1 vaccine")
        // 6 weeks was 14 days ago: left out. 9, 12 and 16 weeks remain.
        assertEquals(listOf(today + 7, today + 28, today + 56), shots.series)
        assertEquals(listOf(today + 35), s.byTitle("Anti-rabies shot").series, "anti-rabies at 13 weeks")
        val item = CareStats.healthDue(s, "p1", now, clock).first { it.task.id == shots.id }
        assertFalse(item.due)
        assertEquals("Due in 7 days", CareStats.dueLabel(item, now, clock))
        // Deworming at 8 weeks is today's dose.
        assertEquals("Due today", CareStats.dueLabel(CareStats.healthDue(s, "p1", now, clock).first { it.task.title == "Deworming" }, now, clock))
    }

    @Test fun eachDoseMovesToTheNextThenYearly() {
        var s = planFor(Species.DOG, 56)
        val id = s.byTitle("5-in-1 vaccine").id
        fun due(at: Long) = CareStats.healthDue(s, "p1", at, clock).first { it.task.id == id }
        // Dose 1 on its day.
        val d1 = clock.at(today + 7, 10 * 60)
        assertTrue(due(d1).due)
        s = StateOps.complete(s, id, d1, clock)
        assertEquals(clock.at(today + 28, 9 * 60), due(d1).dueMs)
        // Dose 2 a few days late still counts as dose 2.
        val d2 = clock.at(today + 31, 10 * 60)
        assertTrue(due(d2).due)
        s = StateOps.complete(s, id, d2, clock)
        assertEquals(clock.at(today + 56, 9 * 60), due(d2).dueMs)
        // Dose 3, then the yearly booster counts from it.
        val d3 = clock.at(today + 56, 10 * 60)
        s = StateOps.complete(s, id, d3, clock)
        assertEquals(clock.at(today + 56 + 365, 9 * 60), due(d3).dueMs)
        assertFalse(due(d3).due)
        // Undo the last dose: dose 3 is due again.
        s = StateOps.undoLast(s, id)
        assertTrue(due(d3).due)
    }

    @Test fun seriesRemindersAndSaving() {
        val s = planFor(Species.CAT, 50)
        val fvrcp = s.byTitle("FVRCP vaccine")
        assertEquals(listOf(today + 6, today + 34, today + 62), fvrcp.series)
        val r = ReminderPlanner.plan(s, now, clock).filter { it.body.contains("fvrcp") }
        assertEquals(listOf(clock.at(today + 3, 540), clock.at(today + 6, 540), clock.at(today + 9, 540)), r.map { it.atMs })
        val back = StateCodec.decode(StateCodec.encode(s))
        assertEquals(s, back)
        assertEquals(today - 50, back.pets[0].birthDay)
    }

    @Test fun olderThanSevenMonthsIsAnAdult() {
        assertFalse(HealthPlan.isYoung(today - 300, today))
        assertTrue(planFor(Species.CAT, 300).tasks.all { it.series.isEmpty() })
        assertEquals("8 weeks old", HealthPlan.ageLabel(today - 56, today))
        assertEquals("5 months old", HealthPlan.ageLabel(today - 150, today))
        assertEquals("2 years old", HealthPlan.ageLabel(today - 800, today))
    }
}
