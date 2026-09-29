package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The widget file stays right for two days without the app, across midnight, taps and time zones. */
class WidgetTimelineTest {
    private val clock = LocalClock.MANILA
    private val today = 20600L
    private fun at(minute: Int, day: Long = today) = clock.at(day, minute)
    private val mochi = Pet("mochi", "Mochi", Species.DOG, 0)
    private val kiko = Pet("kiko", "Kiko", Species.CAT, 0)
    private val feed = CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(7 * 60, 17 * 60), anchorDay = 0, adaptive = false)
    private val litter = CareTask("litter", "kiko", TaskKind.LITTER, "Clean litter", listOf(6 * 60), anchorDay = 0, adaptive = false)
    private val base = AppState(pets = listOf(mochi), tasks = listOf(feed))

    /** Everything done today, snapshot written at 9 PM; the app then isn't opened again. */
    private fun eveningSnapshot(state: AppState = base): Json {
        var s = StateOps.complete(state, "feed", at(7 * 60), clock)
        s = StateOps.complete(s, "feed", at(17 * 60), clock)
        return Json.parse(WidgetSnapshot.build(s, at(21 * 60), clock).stringify())
    }

    @Test fun staysRightForTwoDaysWithoutTheApp() {
        val snap = eveningSnapshot()
        // Tomorrow morning: hungry, with a Done button for breakfast.
        val morning = WidgetSnapshot.face(snap, at(9 * 60, today + 1))!!
        assertEquals(Mood.HUNGRY, morning.mood)
        assertEquals("feed", morning.actionTaskId)
        assertEquals("Feed", morning.actionTitle)
        assertEquals("🍖", morning.actionEmoji)
        // Two days on, breakfast still not logged: still a real mood, not a stale happy face.
        val later = WidgetSnapshot.face(snap, at(16 * 60 + 30, today + 2))!!
        assertEquals(Mood.HUNGRY, later.mood)
        assertEquals("feed", later.actionTaskId)
        // Fed in the morning of that day (before the file was written, say by the household): next is dinner.
        val fed = Json.parse(WidgetSnapshot.build(StateOps.complete(base, "feed", at(7 * 60, today + 2), clock), at(21 * 60), clock).stringify())
        assertEquals(at(17 * 60, today + 2), WidgetSnapshot.face(fed, at(12 * 60, today + 2))!!.nextAtMs)
    }

    @Test fun midnightRollsOverToTomorrowsCare() {
        val snap = eveningSnapshot()
        val night = WidgetSnapshot.face(snap, at(5, today + 1))!!
        assertEquals(Mood.SLEEPY, night.mood)
        assertNull(night.actionTaskId)
        assertEquals("Feed", night.nextTitle)
        assertEquals(at(7 * 60, today + 1), night.nextAtMs, "next is tomorrow's breakfast, not tonight's done feed")
        // An hour before breakfast the Done button appears.
        assertEquals("feed", WidgetSnapshot.face(snap, at(6 * 60, today + 1))!!.actionTaskId)
        assertNull(WidgetSnapshot.face(snap, at(5 * 60 + 45, today + 1))!!.actionTaskId)
    }

    @Test fun aTapOnTheWidgetShowsAtOnceAndOnlyCoversThatMeal() {
        val snap = Json.parse(WidgetSnapshot.build(base, at(6 * 60), clock).stringify())
        assertEquals(Mood.HUNGRY, WidgetSnapshot.face(snap, at(9 * 60))!!.mood)
        val taps = listOf("feed" to at(9 * 60 + 10))
        val after = WidgetSnapshot.face(snap, at(9 * 60 + 15), taps = taps)!!
        assertEquals(Mood.HAPPY, after.mood)
        assertNull(after.actionTaskId)
        // The evening meal is a new one: its Done comes back.
        val evening = WidgetSnapshot.face(snap, at(16 * 60 + 30), taps = taps)!!
        assertEquals("feed", evening.actionTaskId)
        // Taps older than the file were already applied by the app.
        assertEquals(Mood.HUNGRY, WidgetSnapshot.face(snap, at(9 * 60 + 15), taps = listOf("feed" to at(5 * 60)))!!.mood)
    }

    @Test fun eachWidgetShowsItsChosenPet() {
        val two = AppState(pets = listOf(mochi, kiko), tasks = listOf(feed, litter))
        // Mochi was fed; Kiko's litter is overdue.
        val s = StateOps.complete(two, "feed", at(7 * 60), clock)
        val snap = WidgetSnapshot.build(s, at(12 * 60), clock)
        val now = at(12 * 60 + 30)
        assertEquals("mochi", WidgetSnapshot.face(snap, now)!!.petId, "the first pet by default")
        assertEquals("kiko", WidgetSnapshot.face(snap, now, petChoice = "kiko")!!.petId)
        assertEquals("litter", WidgetSnapshot.face(snap, now, petChoice = "kiko")!!.actionTaskId)
        assertEquals("kiko", WidgetSnapshot.face(snap, now, petChoice = WidgetSnapshot.MOST_IN_NEED)!!.petId)
        assertEquals("mochi", WidgetSnapshot.face(snap, now, petChoice = "gone")!!.petId, "a removed pet falls back to the first")
        assertEquals("sprites/kiko/restless.png", WidgetSnapshot.face(snap, now, petChoice = "kiko")!!.sprite)
        assertNull(WidgetSnapshot.face(WidgetSnapshot.build(AppState(), now, clock), now))
    }

    @Test fun careTimesFollowTheOwnerToAnotherTimeZone() {
        val snap = WidgetSnapshot.build(base, at(5 * 60), clock) // written in Manila (UTC+8)
        val tokyo = LocalClock.fixed(9 * HOUR_MS)
        val offset = 9 * HOUR_MS
        // 7:50 AM in Tokyo: breakfast (7:00 local) is 50 minutes late there.
        assertEquals(Mood.HUNGRY, WidgetSnapshot.face(snap, tokyo.at(today, 7 * 60 + 50), utcOffsetNowMs = offset)!!.mood)
        // Without knowing the phone moved, it would still be 6:50 Manila time.
        assertTrue(WidgetSnapshot.face(snap, tokyo.at(today, 7 * 60 + 50))!!.mood != Mood.HUNGRY)
        // "Next" is shown in Tokyo time.
        assertEquals(tokyo.at(today, 7 * 60), WidgetSnapshot.face(snap, tokyo.at(today, 5 * 60 + 30), utcOffsetNowMs = offset)!!.nextAtMs)
    }

    @Test fun androidRedrawsWhenAnythingChanges() {
        val snap = WidgetSnapshot.build(base, at(5 * 60), clock)
        // 6:00: the Done button for 7:00 breakfast appears (the mood is still fine then).
        assertEquals(at(6 * 60), WidgetSnapshot.nextChangeMs(snap, at(5 * 60)))
        val points = snap["pets"].list.single()["timeline"].list
        assertTrue(points.zipWithNext().all { (a, b) -> a["at"].long!! < b["at"].long!! })
        assertTrue(points.last()["at"].long!! > at(5 * 60, today + 1), "covers the day after")
        assertNotNull(WidgetSnapshot.nextChangeMs(snap, at(23 * 60, today + 1)))
    }
}
