package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Few, calm, reliable reminders: bundled, capped per day, planned far enough ahead. */
class CalmRemindersTest {
    private val clock = LocalClock.MANILA
    private val today = 20700L
    private fun at(minute: Int, day: Long = today) = clock.at(day, minute)
    private val chelsea = Pet("c", "Chelsea", Species.CAT, 0)
    private fun t(id: String, kind: TaskKind, vararg minutes: Int, title: String = kind.label) =
        CareTask(id, "c", kind, title, minutes.toList(), anchorDay = 0, adaptive = false)

    @Test fun careWithinHalfAnHourIsOneNotification() {
        val s = AppState(pets = listOf(chelsea), tasks = listOf(t("f", TaskKind.FEED, 7 * 60), t("w", TaskKind.WATER, 7 * 60 + 25), t("l", TaskKind.LITTER, 8 * 60)))
        val morning = ReminderPlanner.plan(s, at(5 * 60), clock).filter { clock.dayIndex(it.atMs) == today }
        assertEquals(listOf(listOf("f", "w"), listOf("l")), morning.map { it.taskIds })
        assertEquals("Chelsea: feed and fresh water. Tap Done when it's all done.", morning[0].body)
        // Done on it covers both.
        val done = StateOps.completeFromReminder(s, morning[0].refs, at(7 * 60 + 5), clock)
        assertEquals(setOf("f", "w"), done.completions.map { it.taskId }.toSet())
    }

    @Test fun aBusyDayIsCappedButMedicineAlwaysComes() {
        // Eight separate everyday reminders, two hours apart, plus medicine twice a day.
        val kinds = listOf(TaskKind.GROOM, TaskKind.FEED, TaskKind.PLAY, TaskKind.WALK, TaskKind.WATER, TaskKind.LITTER, TaskKind.PLAY, TaskKind.FEED)
        val tasks = kinds.mapIndexed { i, k -> t("t$i", k, (6 + 2 * i) * 60, title = "${k.label} $i") } +
            t("m", TaskKind.MEDS, 9 * 60, 21 * 60)
        val s = AppState(pets = listOf(chelsea), tasks = tasks)
        val tomorrow = ReminderPlanner.plan(s, at(23 * 60), clock).filter { clock.dayIndex(it.atMs) == today + 1 }
        val care = tomorrow.filter { s.task(it.taskId)?.kind != TaskKind.MEDS }
        assertEquals(ReminderPlanner.MAX_DAILY_CARE, care.size)
        // Grooming and one play time go quiet; feeding and the walk never do.
        assertTrue(care.none { it.taskId == "t0" }, "grooming dropped first")
        assertTrue(listOf("t1", "t3", "t7").all { id -> care.any { it.taskId == id } })
        // Medicine: both doses and both follow-ups.
        assertEquals(4, tomorrow.count { it.taskId == "m" })
        assertEquals(tomorrow.sortedBy { it.atMs }, tomorrow, "still in time order")
    }

    @Test fun remindersKeepComingForDaysWithoutOpeningTheApp() {
        val s = AppState(pets = listOf(chelsea), tasks = listOf(t("f", TaskKind.FEED, 7 * 60, 18 * 60)))
        val plan = ReminderPlanner.plan(s, at(12 * 60), clock)
        assertTrue(plan.any { clock.dayIndex(it.atMs) == today + 6 }, "a week ahead")
        assertTrue(plan.all { it.atMs > at(12 * 60) })
    }

    @Test fun lostRemindersAreNoticed() {
        val scheduled = listOf(1 to at(7 * 60), 2 to at(9 * 60), 3 to at(11 * 60), 4 to at(20 * 60))
        assertEquals(scheduled, ReminderDelivery.decode(ReminderDelivery.encode(scheduled)))
        assertTrue(ReminderDelivery.decode("junk,5@,@7,").isEmpty())
        // At noon: 7 AM went off; 9 AM never did (3 hours late); 11 AM may still be held back; 8 PM is ahead.
        assertEquals(1, ReminderDelivery.lost(scheduled, fired = setOf(1), nowMs = at(12 * 60)))
        assertEquals(0, ReminderDelivery.lost(scheduled, fired = setOf(1, 2), nowMs = at(12 * 60)))
        assertEquals(3, ReminderDelivery.lost(scheduled, fired = emptySet(), nowMs = at(21 * 60)))
    }

    @Test fun neverMoreThanIosAllows() {
        val tasks = (0 until 12).map { i -> t("f$i", if (i % 3 == 0) TaskKind.MEDS else TaskKind.FEED, 6 * 60 + i * 75) }
        val s = AppState(pets = listOf(chelsea), tasks = tasks)
        val plan = ReminderPlanner.plan(s, at(5 * 60), clock)
        assertEquals(ReminderPlanner.MAX_PENDING, plan.size)
        assertTrue(ReminderPlanner.MAX_PENDING < 64)
        // The soonest are kept.
        assertEquals(at(6 * 60), plan.first().atMs)
        val last = plan.maxOf { it.atMs }
        val all = ReminderPlanner.plan(s, at(5 * 60), clock, horizonMs = 30 * DAY_MS)
        assertEquals(plan.map { it.id }.toSet(), all.filter { it.atMs <= last }.map { it.id }.toSet())
    }
}
