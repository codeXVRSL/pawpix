package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthCareTest {
    private val clock = LocalClock.MANILA
    private fun at(minute: Int, day: Long) = clock.at(day, minute)
    private val pet = Pet("p1", "Mochi", Species.DOG, 0)
    private val today = 20400L
    private val now = at(12 * 60, today)

    private fun withTask(t: CareTask) = AppState(pets = listOf(pet), tasks = listOf(t))
    private fun rabies(createdAt: Long = now) =
        StateOps.defaultTask(pet, TaskKind.VACCINE, today, "v", createdAt)

    @Test fun civilDates() {
        assertEquals("1970-01-01", LocalClock.isoDate(0))
        assertEquals("2000-02-29", LocalClock.isoDate(11016))
        assertEquals("Sep 29, 2026", LocalClock.shortDate(20725))
        assertEquals("1969-12-31", LocalClock.isoDate(-1))
    }

    @Test fun healthDefaultsFollowPhilippineSchedules() {
        assertEquals(365, TaskDefaults.everyDaysFor(TaskKind.VACCINE))
        assertEquals(90, TaskDefaults.everyDaysFor(TaskKind.DEWORM))
        assertEquals(30, TaskDefaults.everyDaysFor(TaskKind.FLEA_TICK))
        assertEquals("Anti-rabies shot", rabies().title)
        assertTrue(TaskDefaults.healthKindsFor(Species.CAT).all { it.health })
        // A yearly repeat survives the editor's clean-up and a save/load.
        val saved = StateCodec.decode(StateCodec.encode(StateOps.upsertTask(withTask(rabies()), rabies().copy(slots = listOf(540, 600)))))
        assertEquals(365, saved.tasks.single().everyDays)
        assertEquals(1, saved.tasks.single().slots.size, "health care has one time: the morning it's due")
        assertFalse(saved.tasks.single().adaptive)
    }

    @Test fun neverGivenIsDueNowEvenForANewTask() {
        val s = withTask(rabies())
        val item = CareStats.healthDue(s, "p1", now, clock).single()
        assertTrue(item.due, "a vaccine never given is due, not due next year")
        assertEquals("Due today", CareStats.dueLabel(item, now, clock))
    }

    @Test fun nextDueCountsFromWhenItWasGiven() {
        // Given 400 days ago: 35 days overdue.
        var s = StateOps.logOnDay(withTask(rabies()), "v", today - 400, now, clock)
        var item = CareStats.healthDue(s, "p1", now, clock).single()
        assertTrue(item.due)
        assertEquals("Overdue by 35 days", CareStats.dueLabel(item, now, clock))
        assertEquals(Mood.NEEDS_MEDS, MoodEngine.read(s, "p1", now, clock).mood)
        assertEquals("Mochi's anti-rabies shot is due", MoodEngine.read(s, "p1", now, clock).caption)

        // Given today: due again in a year, and the pet is fine.
        s = StateOps.complete(s, "v", now, clock)
        item = CareStats.healthDue(s, "p1", now, clock).single()
        assertFalse(item.due)
        assertEquals(clock.at(today + 365, 9 * 60), item.dueMs)
        assertEquals("Due in 12 months", CareStats.dueLabel(item, now, clock))
        assertTrue(MoodEngine.read(s, "p1", now, clock).mood in setOf(Mood.HAPPY, Mood.CONTENT))

        // Undo goes back to the earlier record.
        s = StateOps.undoLast(s, "v")
        assertTrue(CareStats.healthDue(s, "p1", now, clock).single().due)
    }

    @Test fun givenRecentlyShowsDaysLeft() {
        val deworm = StateOps.defaultTask(pet, TaskKind.DEWORM, today, "d", now)
        val s = StateOps.logOnDay(withTask(deworm), "d", today - 80, now, clock)
        assertEquals("Due in 10 days", CareStats.dueLabel(CareStats.healthDue(s, "p1", now, clock).single(), now, clock))
    }

    @Test fun overdueHealthIsGentleAndNotOnTheWidget() {
        val s = withTask(rabies(createdAt = at(0, today - 30)).copy(anchorDay = today - 30))
        // Weeks overdue, the pet still isn't sad: health weighs less than a missed meal.
        assertTrue(MoodEngine.read(s, "p1", now, clock).mood != Mood.SAD)
        val widget = WidgetSnapshot.build(s, now, clock)["pets"].list.single()
        assertEquals(Json.Null, widget["action"], "no one-tap 'Vaccinated' on the home screen")
    }

    @Test fun healthRemindersReachBeyondTheDailyHorizon() {
        val s = StateOps.complete(withTask(rabies()), "v", now, clock)
        val r = ReminderPlanner.plan(s, now, clock)
        val due = clock.at(today + 365, 9 * 60)
        assertEquals(listOf(due - 3 * DAY_MS, due, due + 3 * DAY_MS), r.map { it.atMs })
        assertTrue(r[0].body.contains("due in 3 days"))
        assertEquals(listOf(false, true, true), r.map { it.quickDone }, "no Done button on a heads-up")
        assertEquals(r.map { it.id }.distinct().size, 3)
    }

    @Test fun healthRemindersSurviveABusyDailySchedule() {
        var s = StateOps.complete(withTask(rabies()), "v", now, clock)
        repeat(30) { i ->
            s = StateOps.upsertTask(s, CareTask("f$i", "p1", TaskKind.FEED, "Feed $i", listOf(8 * 60, 12 * 60, 16 * 60, 20 * 60), anchorDay = today, createdAtMs = 0))
        }
        val r = ReminderPlanner.plan(s, now, clock)
        assertTrue(r.size <= ReminderPlanner.MAX_PENDING)
        assertTrue(r.any { it.taskId == "v" })
    }
}

class HealthReviewFixesTest {
    private val clock = LocalClock.MANILA
    private val today = 20400L
    private val now = clock.at(today, 12 * 60)
    private val pet = Pet("p1", "Mochi", Species.DOG, 0)
    private fun task(kind: TaskKind, id: String) = StateOps.defaultTask(pet, kind, today, id, now)
    private fun label(s: AppState, id: String, at: Long = now) =
        CareStats.dueLabel(CareStats.healthDue(s, "p1", at, clock).first { it.task.id == id }, at, clock)

    @Test fun longAgoRecordsShowTheRealOverdueTime() {
        var s = AppState(pets = listOf(pet), tasks = listOf(task(TaskKind.DEWORM, "d"), task(TaskKind.FLEA_TICK, "f")))
        s = StateOps.logOnDay(s, "d", today - 182, now, clock)
        s = StateOps.logOnDay(s, "f", today - 365, now, clock)
        assertEquals("Overdue by 92 days", label(s, "d"))
        assertEquals("Overdue by 335 days", label(s, "f"))
    }

    @Test fun unknownDatesNeverUpsetThePetAndOnlyOneHealthItemCounts() {
        val adult = HealthPlan.addTo(AppState(pets = listOf(pet)), pet, now, clock)
        for (h in listOf(0, 24, 36, 72, 24 * 30)) {
            val r = MoodEngine.read(adult, "p1", now + h * HOUR_MS, clock)
            assertTrue(r.mood in setOf(Mood.CONTENT, Mood.SLEEPY), "+${h}h: ${r.mood}")
        }
        assertTrue(ReminderPlanner.plan(adult, now, clock).isEmpty(), "no reminders for guessed dates")
        // Everything recorded as long overdue: still just one gentle need, never sad.
        var s = adult
        for (t in s.tasks) s = StateOps.logOnDay(s, t.id, today - 400, now, clock)
        val r = MoodEngine.read(s, "p1", now + 3 * DAY_MS, clock)
        assertEquals(Mood.NEEDS_MEDS, r.mood)
    }

    @Test fun healthItemsDueTogetherShareOneNotification() {
        var s = AppState(pets = listOf(pet), tasks = listOf(task(TaskKind.DEWORM, "d"), task(TaskKind.FLEA_TICK, "f")))
        s = StateOps.logOnDay(s, "d", today - 80, now, clock) // due in 10 days
        s = StateOps.logOnDay(s, "f", today - 20, now, clock) // due in 10 days
        val r = ReminderPlanner.plan(s, now, clock)
        assertEquals(3, r.size)
        assertEquals("Mochi's deworming and tick & flea prevention are due in 3 days. A good time to book the vet.", r[0].body)
        assertTrue(r.none { it.quickDone }, "a bundle has no single Done")
    }

    @Test fun seriesIgnoresDoubleTapsAndOldRecords() {
        val series = listOf(today + 10, today + 31, today + 52)
        var s = AppState(pets = listOf(pet), tasks = listOf(task(TaskKind.VACCINE, "v").copy(series = series)))
        s = StateOps.logOnDay(s, "v", today - 1, now, clock) // "yesterday": weeks before dose 1
        assertEquals("Due in 10 days", label(s, "v"))
        val d1 = clock.at(today + 10, 10 * 60)
        s = StateOps.complete(StateOps.complete(s, "v", d1, clock), "v", d1 + MINUTE_MS, clock) // double tap
        assertEquals("Due in 21 days", label(s, "v", d1 + HOUR_MS))
    }

    @Test fun undoRemovesWhatWasJustRecorded() {
        var s = AppState(pets = listOf(pet), tasks = listOf(task(TaskKind.VACCINE, "v")))
        s = StateOps.complete(s, "v", now, clock)          // given today
        s = StateOps.logOnDay(s, "v", today - 365, now, clock) // then a mistaken "a year ago"
        s = StateOps.undoLast(s, "v")
        assertEquals(listOf(now), s.completions.map { it.atMs })
        s = StateOps.undoLast(s, "v")
        assertEquals("Due today", label(s, "v"), "nothing recorded: due from when it was added")
    }

    @Test fun oldNotificationDoneDoesntRecordAgain() {
        var s = AppState(pets = listOf(pet), tasks = listOf(task(TaskKind.VACCINE, "v")))
        s = StateOps.complete(s, "v", now, clock)
        val later = now + 2 * DAY_MS
        assertEquals(s, StateOps.completeFromReminder(s, "v", later, clock))
        val feed = CareTask("f", "p1", TaskKind.FEED, "Feed", listOf(480))
        val daily = s.copy(tasks = s.tasks + feed)
        assertEquals(2, StateOps.completeFromReminder(daily, "f", later, clock).completions.size)
    }

    @Test fun backupsAndStatesRejectPathLikeIds() {
        val json = StateCodec.encode(AppState(pets = listOf(Pet("..", "X", Species.CAT, 0), pet)))
        assertEquals(listOf("p1"), StateCodec.decode(json).pets.map { it.id })
        val empty = Backup.encode(AppState(), emptyMap(), 1)
        assertFailsWith<Backup.NotABackup> { Backup.decode(empty) }
    }

    @Test fun youngPetsStartParasiteCareAtEightWeeks() {
        val pup = pet.copy(birthDay = today - 30)
        val s = HealthPlan.addTo(AppState(pets = listOf(pup)), pup, now, clock)
        assertEquals(listOf(today + 26), s.tasks.single { it.title == "Heartworm prevention" }.series)
        assertEquals("Due in 26 days", label(s, s.tasks.single { it.title == "Tick & flea prevention" }.id))
    }
}

class AwayModeTest {
    private val clock = LocalClock.MANILA
    private fun at(minute: Int, day: Long = 20400) = clock.at(day, minute)
    private val pet = Pet("p1", "Mochi", Species.DOG, 0)
    private val feed = CareTask("t1", "p1", TaskKind.FEED, "Feed", listOf(7 * 60, 17 * 60), anchorDay = 0, adaptive = false)
    private val walk = CareTask("w", "p1", TaskKind.WALK, "Walk", listOf(6 * 60), anchorDay = 0, adaptive = false)
    private val base = AppState(pets = listOf(pet), tasks = listOf(feed, walk))

    @Test fun awayMeansNoRemindersAndNoGuilt() {
        val away = StateOps.setAway(base, at(15 * 60, 20403))
        val r = MoodEngine.read(away, "p1", at(12 * 60), clock)
        assertEquals(Mood.CONTENT, r.mood)
        assertEquals("Mochi is being looked after", r.caption)
        val reminders = ReminderPlanner.plan(away, at(12 * 60), clock, horizonMs = 5 * DAY_MS)
        assertTrue(reminders.all { it.atMs >= at(15 * 60, 20403) })
        assertNull(WidgetSnapshot.build(away, at(12 * 60), clock)["pets"].list.single()["action"].str)
    }

    @Test fun tappingImBackForgivesWhatWasMissed() {
        val away = StateOps.setAway(base, at(20 * 60, 20403))
        val back = StateOps.setAway(away, at(12 * 60)) // "I'm back" at noon on day 20400
        assertEquals(Mood.CONTENT, MoodEngine.read(back, "p1", at(12 * 60 + 1), clock).mood)
    }

    @Test fun remindersArePlannedFromTheReturn() {
        val away = StateOps.setAway(base, at(15 * 60, 20410))
        val r = ReminderPlanner.plan(away, at(12 * 60), clock)
        assertTrue(r.isNotEmpty() && r.all { it.atMs >= at(15 * 60, 20410) }, r.toString())
    }

    @Test fun comingBackStartsFresh() {
        // Back at 3pm: breakfast and the morning walk were the sitter's; nothing weighs on the pet yet.
        val away = StateOps.setAway(base, at(15 * 60))
        assertEquals(Mood.CONTENT, MoodEngine.read(away, "p1", at(15 * 60 + 5), clock).mood)
        // Hours later, if nobody logs anything, the pet asks again as usual.
        assertTrue(MoodEngine.read(away, "p1", at(19 * 60), clock).mood in setOf(Mood.HUNGRY, Mood.RESTLESS, Mood.SAD))
        // Clearing it early works too, and saves.
        val back = StateCodec.decode(StateCodec.encode(StateOps.setAway(away, 0)))
        assertEquals(0L, back.settings.awayUntilMs)
        assertEquals(at(15 * 60), StateCodec.decode(StateCodec.encode(away)).settings.awayUntilMs)
    }
}

class CareStatsTest {
    private val clock = LocalClock.MANILA
    private fun at(minute: Int, day: Long) = clock.at(day, minute)
    private val pet = Pet("p1", "Mochi", Species.DOG, 0)
    private val feed = CareTask("t1", "p1", TaskKind.FEED, "Feed", listOf(7 * 60), anchorDay = 0, adaptive = false)
    private val base = AppState(pets = listOf(pet), tasks = listOf(feed))

    @Test fun countsCaredDaysNotStreaks() {
        val now = at(20 * 60, 100)
        assertEquals("Tap Done when you care for Mochi, and it shows here.", CareStats.summary(base, "p1", now, clock))
        var s = base
        for (d in listOf(94L, 95, 97, 98, 99, 100, 100)) s = StateOps.complete(s, "t1", at(8 * 60, d), clock)
        // Day 96 missed: no reset, just 6 of 7.
        assertEquals(6, CareStats.caredDays(s, "p1", now, clock))
        assertEquals(listOf(true, true, false, true, true, true, true), CareStats.week(s, "p1", now, clock))
        assertEquals("You cared for Mochi on 6 of the last 7 days.", CareStats.summary(s, "p1", now, clock))
        s = StateOps.complete(s, "t1", at(8 * 60, 96), clock)
        assertEquals("You cared for Mochi every day this week!", CareStats.summary(s, "p1", now, clock))
        // Old care drops out of the window.
        assertEquals(0, CareStats.caredDays(s, "p1", at(9 * 60, 120), clock))
    }
}

class BackupTest {
    private val pet = Pet("abc123", "Mochi", Species.CAT, 5, ears = "FLOPPY", careDays = listOf(20000))
    private val task = CareTask("t1", "abc123", TaskKind.VACCINE, "Anti-rabies shot", listOf(540), everyDays = 365, anchorDay = 20000)
    private val state = AppState(
        pets = listOf(pet), tasks = listOf(task),
        completions = listOf(Completion("t1", 1_700_000_000_000, 540, 20000)),
        settings = Settings(nightStart = 21 * 60),
    )

    @Test fun roundTripsStateAndPetFiles() {
        val head = ByteArray(300) { (it * 7).toByte() }
        val photo = byteArrayOf(0, 1, 2, -1, -128, 127)
        assertEquals(listOf("sprites/abc123/head.bin", "sprites/abc123/photo.bin", "sprites/abc123/card-t1.jpg"), Backup.filesFor(state, "abc123"))
        val text = Backup.encode(state, mapOf("sprites/abc123/head.bin" to head, "sprites/abc123/photo.bin" to photo, "sprites/abc123/card-t1.jpg" to photo), 42)
        val back = Backup.decode(text)
        assertEquals(state, back.state)
        assertTrue(head.contentEquals(back.files["sprites/abc123/head.bin"]))
        assertTrue(photo.contentEquals(back.files["sprites/abc123/photo.bin"]))
        assertTrue(photo.contentEquals(back.files["sprites/abc123/card-t1.jpg"]))
        assertEquals(42, back.createdAtMs)
    }

    @Test fun refusesOtherFilesAndPaths() {
        val sneaky = Backup.encode(state, emptyMap(), 1).replace(
            "\"files\":{}",
            "\"files\":{\"../state.json\":\"AAAA\",\"sprites/abc123/../../x.bin\":\"AAAA\",\"sprites/other/head.bin\":\"AAAA\",\"sprites/abc123/head.bin\":\"!!\"}",
        )
        assertTrue(Backup.decode(sneaky).files.isEmpty(), "only this backup's own pet files, as valid base64")
        assertFailsWith<Backup.NotABackup> { Backup.decode("""{"pets":[]}""") }
        assertFailsWith<Backup.NotABackup> { Backup.decode("not json at all") }
        assertFailsWith<Backup.NotABackup> { Backup.decode("""{"format":"pawpixel-backup","version":99}""") }
    }

    @Test fun base64MatchesTheStandard() {
        assertEquals("", Base64.encode(ByteArray(0)))
        assertEquals("Zg==", Base64.encode("f".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64.encode("foobar".encodeToByteArray()))
        assertEquals("Zm9vYg==", Base64.encode("foob".encodeToByteArray()))
        assertEquals("foob", Base64.decode("Zm9vYg==").decodeToString())
        val all = ByteArray(256) { it.toByte() }
        assertTrue(all.contentEquals(Base64.decode(Base64.encode(all))))
    }
}

class NameFilterTest {
    @Test fun blocksProfanityAndDisguises() {
        for (n in listOf("Fuck", "Sh1t head", "PUTANGINA", "tang!na mo", "g@go", "Gaaaago", "Mr B1tch")) {
            assertTrue(NameFilter.isBlocked(n), n)
        }
    }

    @Test fun leavesOrdinaryPetNamesAlone() {
        for (n in listOf("Mochi", "Grape", "Petite", "Dickens", "Scunthorpe", "Puto", "Bantay", "Putakti", "Tita", "Kulit", "Shih Tzu")) {
            assertFalse(NameFilter.isBlocked(n), n)
        }
        assertEquals("Mochi", NameFilter.forMap("Mochi", Species.CAT))
        assertEquals("A dog", NameFilter.forMap("G4go", Species.DOG))
    }
}
