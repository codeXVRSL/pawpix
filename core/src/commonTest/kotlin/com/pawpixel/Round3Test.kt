package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledRemindersTest {
    private val clock = LocalClock.MANILA
    private val today = 20500L
    private val now = clock.at(today, 5 * 60)
    private val mochi = Pet("mochi", "Mochi", Species.DOG, 0)
    private val kiko = Pet("kiko", "Kiko", Species.CAT, 0)
    private fun t(id: String, pet: String, kind: TaskKind, title: String, min: Int) =
        CareTask(id, pet, kind, title, listOf(min), anchorDay = 0, adaptive = false)

    @Test fun careDueTogetherComesAsOneNotificationWithOneDone() {
        val s = AppState(pets = listOf(mochi, kiko), tasks = listOf(
            t("f1", "mochi", TaskKind.FEED, "Feed", 7 * 60), t("w1", "mochi", TaskKind.WATER, "Fresh water", 7 * 60 + 10),
            t("f2", "kiko", TaskKind.FEED, "Feed", 7 * 60 + 15), t("walk", "mochi", TaskKind.WALK, "Walk", 17 * 60),
            t("m1", "kiko", TaskKind.MEDS, "Ear drops", 7 * 60),
        ))
        val today7 = ReminderPlanner.plan(s, now, clock).filter { clock.dayIndex(it.atMs) == today && clock.minuteOfDay(it.atMs) < 12 * 60 }
        val bundle = today7.single { it.taskIds.size > 1 }
        assertEquals(setOf("f1", "w1", "f2"), bundle.taskIds.toSet())
        assertEquals(clock.at(today, 7 * 60), bundle.atMs)
        assertEquals("🐾 Care time · Mochi and Kiko", bundle.title)
        assertEquals("Mochi: feed and fresh water · Kiko: feed. Tap Done when it's all done.", bundle.body)
        assertTrue(bundle.quickDone)
        // Medicine keeps its own notification (and its follow-up).
        assertEquals(2, today7.count { it.taskId == "m1" })
        // The evening walk is on its own.
        assertTrue(ReminderPlanner.plan(s, now, clock).any { it.taskIds == listOf("walk") })
    }
}

class BundleReviewFixesTest {
    private val clock = LocalClock.MANILA
    private val today = 20500L
    private fun at(min: Int, day: Long = today) = clock.at(day, min)
    private val mochi = Pet("mochi", "Mochi", Species.DOG, 0)
    private val feed = CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(7 * 60, 18 * 60), anchorDay = 0, adaptive = false)
    private val water = CareTask("water", "mochi", TaskKind.WATER, "Fresh water", listOf(7 * 60 + 10), anchorDay = 0, adaptive = false)
    private val base = AppState(pets = listOf(mochi), tasks = listOf(feed, water))

    @Test fun aBundleDoneSkipsWhatWasAlreadyLogged() {
        val bundle = ReminderPlanner.plan(base, at(5 * 60), clock).first { it.taskIds.size == 2 && clock.dayIndex(it.atMs) == today }
        assertEquals(listOf(at(7 * 60), at(7 * 60 + 10)), bundle.slots)
        // Fed in the app at 7:02, then Done on the notification at 7:15 (water done too).
        var s = StateOps.complete(base, "feed", at(7 * 60 + 2), clock)
        val refs = ReminderRef.decodeAll(ReminderRef.encodeAll(bundle.refs))
        assertEquals(bundle.refs, refs)
        s = StateOps.completeFromReminder(s, refs, at(7 * 60 + 15), clock)
        assertEquals(1, s.completions.count { it.taskId == "feed" }, "breakfast isn't logged twice")
        assertEquals(1, s.completions.count { it.taskId == "water" })
        val evening = CareEngine.status(feed, s.completions, at(18 * 60 + 30), clock)
        assertTrue(evening.isOverdue, "the evening feed is still owed")
    }

    @Test fun bundlesNeverCrossMidnight() {
        val late = base.copy(tasks = listOf(
            CareTask("w", "mochi", TaskKind.WATER, "Fresh water", listOf(23 * 60 + 55), anchorDay = 0, adaptive = false),
            CareTask("f", "mochi", TaskKind.FEED, "Feed", listOf(10), anchorDay = 0, adaptive = false),
        ))
        assertTrue(ReminderPlanner.plan(late, at(20 * 60), clock).all { it.taskIds.size == 1 })
    }

    @Test fun pastHealthRecordsArentCareDays() {
        val pet = mochi
        var s = HealthPlan.addTo(AppState(pets = listOf(pet)), pet, at(12 * 60), clock)
        for (t in s.tasks) s = StateOps.logOnDay(s, t.id, today - 30, at(12 * 60), clock)
        assertEquals(0, Milestones.caredDays(s.pets[0]))
        s = StateOps.logOnDay(s, s.tasks[0].id, today, at(12 * 60), clock)
        assertEquals(1, Milestones.caredDays(s.pets[0]), "given today counts")
    }
}

class MilestoneAndWeightTest {
    private val clock = LocalClock.MANILA
    private val pet = Pet("mochi", "Mochi", Species.DOG, 0)
    private val feed = CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(420, 1020), anchorDay = 0)
    private val walk = CareTask("walk", "mochi", TaskKind.WALK, "Walk", listOf(360), anchorDay = 0)
    private val base = AppState(pets = listOf(pet), tasks = listOf(feed, walk))

    @Test fun careDaysAddUpAndNeverGoBackwards() {
        var s = base
        // 7 days, some with several taps; one gap day in between.
        for (d in listOf(1L, 2, 3, 5, 6, 7, 8)) {
            s = StateOps.complete(s, "feed", clock.at(20000 + d, 480), clock)
            s = StateOps.complete(s, "walk", clock.at(20000 + d, 400), clock)
        }
        assertEquals(7, Milestones.caredDays(s.pets[0]))
        assertEquals(7, Milestones.toCelebrate(s.pets[0]))
        s = Milestones.celebrate(s, "mochi", 7)
        assertNull(Milestones.toCelebrate(s.pets[0]))
        assertEquals(30 to 23, Milestones.next(s.pets[0]))
        // Undo of one of two taps that day keeps the day; undoing the last one removes it.
        s = StateOps.undoLast(s, "feed")
        assertEquals(7, Milestones.caredDays(s.pets[0]))
        s = StateOps.undoLast(s, "walk")
        assertEquals(6, Milestones.caredDays(s.pets[0]))
        // History trimmed away still counts.
        var long = base
        for (d in 0 until 160L) long = StateOps.complete(long, "feed", clock.at(20000 + d, 480), clock)
        assertTrue(long.completions.size <= AppState.MAX_COMPLETIONS_PER_TASK)
        assertEquals(160, Milestones.caredDays(long.pets[0]))
        assertEquals(100, Milestones.toCelebrate(long.pets[0]), "after a long gap, the highest one reached")
        assertEquals(long, StateCodec.decode(StateCodec.encode(long)))
    }

    @Test fun weighInsAndTrend() {
        assertEquals(4250, WeightTrend.parseKg("4,25 kg"))
        assertEquals(4200, WeightTrend.parseKg(" 4.2 "))
        assertNull(WeightTrend.parseKg("heavy"))
        assertNull(WeightTrend.parseKg("0"))
        var s = StateOps.logWeight(base, "mochi", 20000, 4200)
        s = StateOps.logWeight(s, "mochi", 20030, 4400)
        s = StateOps.logWeight(s, "mochi", 20030, 4500) // same day: replaces
        assertEquals(listOf(4200, 4500), s.weightsFor("mochi").map { it.grams })
        assertEquals("4.5 kg", WeightTrend.kg(4500))
        assertEquals("+0.3 kg since ${LocalClock.shortDate(20000)}", WeightTrend.change(s.weightsFor("mochi")))
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
        assertTrue(StateOps.removePet(s, "mochi").weights.isEmpty())
    }
}

class OutfitTest {
    private val look = "1;b0703c,f4f1ea;" + "0".repeat(40) + "1".repeat(24)
    private val pet = com.pawpixel.core.Pet("mochi", "Mochi", com.pawpixel.core.Species.CAT, 0, lookCode = look)

    @Test fun outfitsAreEarnedWithCareAndDrawnOnEveryPose() {
        var s = com.pawpixel.core.AppState(pets = listOf(pet))
        assertTrue(com.pawpixel.core.Milestones.unlocked(s.pets[0]).isEmpty())
        // Not earned yet: nothing happens.
        s = com.pawpixel.core.Milestones.wear(s, "mochi", com.pawpixel.sprite.Accessory.CROWN)
        assertNull(s.pets[0].accessory)
        s = com.pawpixel.core.StateOps.markCareDays(s, "mochi", (1L..30L).toList())
        assertEquals(listOf("BANDANA", "FLOWER", "BOW_TIE", "PARTY_HAT"), com.pawpixel.core.Milestones.unlocked(s.pets[0]).map { it.name })
        s = com.pawpixel.core.Milestones.wear(s, "mochi", com.pawpixel.sprite.Accessory.PARTY_HAT)
        assertEquals("PARTY_HAT", s.pets[0].accessory)
        assertEquals(2, s.pets[0].spriteVersion, "poses redraw")
        assertEquals(s, com.pawpixel.core.StateCodec.decode(com.pawpixel.core.StateCodec.encode(s)))
        val plain = com.pawpixel.sprite.PetArt(com.pawpixel.sprite.PetLook.decode(look)!!, com.pawpixel.core.Species.CAT)
        val hat = com.pawpixel.sprite.PetArt(com.pawpixel.sprite.PetLook.decode(look)!!, com.pawpixel.core.Species.CAT, null, com.pawpixel.sprite.Accessory.PARTY_HAT)
        assertTrue(!plain.still.pixels.contentEquals(hat.still.pixels))
        assertTrue(!com.pawpixel.sprite.Chibi.sleeping(plain).pixels.contentEquals(com.pawpixel.sprite.Chibi.sleeping(hat).pixels), "also when asleep")
        // Takes it off.
        s = com.pawpixel.core.Milestones.wear(s, "mochi", null)
        assertNull(s.pets[0].accessory)
    }
}

class StaleDoneTest {
    private val clock = com.pawpixel.core.LocalClock.MANILA
    private val feed = com.pawpixel.core.CareTask("feed", "p", com.pawpixel.core.TaskKind.FEED, "Feed", listOf(420, 1080), anchorDay = 0, adaptive = false)
    private val s0 = com.pawpixel.core.AppState(pets = listOf(com.pawpixel.core.Pet("p", "Mochi", com.pawpixel.core.Species.DOG, 0)), tasks = listOf(feed))

    @Test fun yesterdaysNotificationTappedTodayLogsNothing() {
        val yesterdayBreakfast = clock.at(20499, 420)
        val s = com.pawpixel.core.StateOps.completeFromReminder(s0, listOf(com.pawpixel.core.ReminderRef("feed", yesterdayBreakfast)), clock.at(20500, 9 * 60), clock)
        assertTrue(s.completions.isEmpty(), "the evening feed isn't used up by an old tap")
    }

    @Test fun tomorrowsTappedEarlyCountsAndAMovedSlotStillMatches() {
        val tomorrow = clock.at(20501, 420)
        val s = com.pawpixel.core.StateOps.completeFromReminder(s0, listOf(com.pawpixel.core.ReminderRef("feed", tomorrow)), clock.at(20500, 22 * 60), clock)
        assertEquals(1, s.completions.size)
        // Breakfast planned at 7:00 but the slot has since moved to 7:30: the 7:00 tap still means breakfast.
        val done = com.pawpixel.core.StateOps.complete(s0, "feed", clock.at(20500, 7 * 60 + 35), clock)
        assertTrue(com.pawpixel.core.StateOps.isCovered(done, com.pawpixel.core.ReminderRef("feed", clock.at(20500, 450)), clock.at(20500, 8 * 60), clock))
    }
}
