package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two phones and a fake server, synced the way the app does it. */
class HouseholdSyncTest {
    private val clock = LocalClock.MANILA
    private val today = 20500L
    private fun at(min: Int) = clock.at(today, min)
    private var n = 0
    private val ids = { "x${n++}" }

    /**
     * The server, like supabase/migrations 0004 and 0006: pets and tasks are rows (edit times capped
     * at its clock), care records an append-only log (author stamped, times from the future brought
     * back to now, undo marks a record undone). Every change gets the next [changedMs], a minute
     * apart, so a pull's look-back window covers only the last couple of changes.
     */
    private inner class Server {
        var pets = listOf<Pet>(); var tasks = listOf<CareTask>()
        val log = LinkedHashMap<String, Row>()
        var nowMs = at(23 * 60 + 59)
        private var changedMs = 1_000_000_000L
        inner class Row(val c: Completion, var undone: Boolean, var changed: Long)
        val completions: List<Completion> get() = log.values.filter { !it.undone }.map { it.c }

        fun apply(push: SyncPush, by: String) {
            fun <T> upsert(rows: List<T>, new: List<T>, id: (T) -> String) = rows.filter { r -> new.none { id(it) == id(r) } } + new
            pets = upsert(pets, push.upsertPets.map { HouseholdSync.view(it).copy(editedAtMs = minOf(it.editedAtMs, nowMs)) }) { it.id }
                .filter { it.id !in push.deletePetIds }
            tasks = upsert(tasks, push.upsertTasks.map { HouseholdSync.view(it).copy(editedAtMs = minOf(it.editedAtMs, nowMs)) }) { it.id }
                .filter { it.id !in push.deleteTaskIds && pets.any { p -> p.id == it.petId } }
            log.values.removeAll { r -> tasks.none { it.id == r.c.taskId } } // cascade
            for (c in push.addCompletions) if (c.id !in log && tasks.any { it.id == c.taskId }) {
                log[c.id] = Row(c.copy(by = by, atMs = minOf(c.atMs, nowMs)), false, tick())
            }
            for (id in push.undoCompletionIds) log[id]?.takeIf { !it.undone && it.c.by == by }?.let { it.undone = true; it.changed = tick() }
        }

        fun pull(sinceMs: Long, known: Set<String>): Pair<RemoteChanges, Long> {
            val rows = log.values.filter { sinceMs == 0L || it.changed > sinceMs - LOOK_BACK || it.c.taskId !in known }
            val cursor = maxOf(sinceMs, rows.maxOfOrNull { it.changed } ?: 0)
            return RemoteChanges(pets, tasks, rows.map { LoggedCompletion(it.c, it.undone) }) to cursor
        }

        private fun tick() = (changedMs + 60_000).also { changedMs = it }
    }
    private val LOOK_BACK = 120_000L
    private var server = Server()

    private inner class Phone(val user: String, var state: AppState, val tz: LocalClock = clock) {
        var base = SharedData()
        var cursor = 0L
        var lastPush = SyncPush()
        /** This phone's clock (a wrong one, for the clock tests). */
        var nowMs = at(23 * 60 + 59)
        /** [pushFails]: the merge is applied here, but sending it fails (offline, cancelled). */
        fun sync(pushFails: Boolean = false) {
            val (changes, next) = server.pull(cursor, base.tasks.map { it.id }.toSet())
            val r = HouseholdSync.merge(state, base, changes, me = user, clock = tz, nowMs = nowMs)
            lastPush = r.push
            state = r.state
            cursor = next
            if (pushFails) { base = r.baseIfPushFails; return }
            server.apply(r.push, user)
            base = r.base
        }
        fun done(taskId: String, min: Int) { state = StateOps.complete(state, taskId, at(min), clock, ids) }
        /** An edit made on this phone at [atMs], stamped the way the app does it. */
        fun edit(atMs: Long, change: (AppState) -> AppState) { state = HouseholdSync.stamp(state, change(state), atMs) }
    }

    private val pet = Pet("mochi", "Mochi", Species.DOG, 0, lookCode = "1;e08a3a;" + "0".repeat(64))
    private val feed = CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(7 * 60, 17 * 60), anchorDay = 0, adaptive = false)
    private val walk = CareTask("walk", "mochi", TaskKind.WALK, "Walk", listOf(6 * 60), anchorDay = 0, adaptive = false)

    private fun family(): Pair<Phone, Phone> {
        val a = Phone("save", AppState(pets = listOf(pet.copy(shared = true)), tasks = listOf(feed, walk)))
        a.done("feed", 7 * 60 + 5)
        a.sync()
        val b = Phone("jamaica", AppState())
        b.sync()
        return a to b
    }

    @Test fun sharingSendsThePetAndTheOtherPhoneGetsIt() {
        val (a, b) = family()
        assertEquals(1, server.pets.size); assertEquals(2, server.tasks.size); assertEquals(1, server.completions.size)
        val got = b.state.pets.single()
        assertTrue(got.shared); assertEquals("Mochi", got.name); assertEquals(pet.lookCode, got.lookCode)
        assertEquals(listOf("feed", "walk"), b.state.tasks.map { it.id })
        assertEquals("save", b.state.completions.single().by)
        // Save's own record learns its author from the server...
        a.sync()
        assertEquals("save", a.state.completions.single().by)
        // ...and after that, syncing again sends nothing and changes nothing.
        val before = a.state
        a.sync(); b.sync()
        assertTrue(a.lastPush.isEmpty && b.lastPush.isEmpty)
        assertEquals(before, a.state)
    }

    @Test fun doneOnOnePhoneShowsOnTheOtherSoNobodyFeedsTwice() {
        val (a, b) = family()
        b.done("feed", 17 * 60 + 2)
        b.sync(); a.sync()
        val s = CareEngine.status(a.state.task("feed")!!, a.state.completions, at(18 * 60), clock)
        assertTrue(s.allDoneThisCycle, "evening feed shows done on Save's phone")
        assertEquals("jamaica", a.state.completions.last().by)
    }

    @Test fun bothTappingOfflineKeepsBothAndUndoTravels() {
        val (a, b) = family()
        a.done("walk", 6 * 60 + 1); b.done("walk", 6 * 60 + 3)
        a.sync(); b.sync(); a.sync()
        assertEquals(2, a.state.completions.count { it.taskId == "walk" })
        assertEquals(a.state.completions.map { it.id }.toSet(), b.state.completions.map { it.id }.toSet())
        // Jamaica undoes hers; it disappears for Save too.
        b.state = StateOps.undoLast(b.state, "walk", mine = setOf(null, "jamaica"))
        b.sync(); a.sync()
        assertEquals(1, a.state.completions.count { it.taskId == "walk" })
        assertEquals("save", a.state.completions.last { it.taskId == "walk" }.by)
    }

    @Test fun undoNeverTakesSomeoneElsesRecord() {
        val (a, b) = family()
        a.done("walk", 6 * 60 + 1); a.sync()
        b.done("walk", 6 * 60 + 9); b.sync(); a.sync()
        // Save's undo removes Save's own walk, even though Jamaica's arrived after it.
        a.state = StateOps.undoLast(a.state, "walk", mine = setOf(null, "save"))
        assertEquals(listOf("jamaica"), a.state.completions.filter { it.taskId == "walk" }.map { it.by })
    }

    @Test fun editsTravelAndRemindersStayPersonal() {
        val (a, b) = family()
        b.state = StateOps.upsertTask(b.state, b.state.task("feed")!!.copy(remindersOn = false))
        b.sync()
        assertTrue(b.lastPush.isEmpty, "turning your own reminders off isn't a family change")
        a.state = StateOps.upsertTask(a.state, a.state.task("feed")!!.copy(slots = listOf(8 * 60, 18 * 60), title = "Breakfast & dinner"))
        a.sync(); b.sync()
        val t = b.state.task("feed")!!
        assertEquals("Breakfast & dinner", t.title); assertEquals(listOf(480, 1080), t.slots)
        assertFalse(t.remindersOn, "Jamaica's own reminder switch survives Save's edit")
    }

    @Test fun deletingATaskOrChangingTheLookTravels() {
        val (a, b) = family()
        b.state = StateOps.removeTask(b.state, "walk")
        b.sync(); a.sync()
        assertEquals(listOf("feed"), a.state.tasks.map { it.id })
        val newLook = "1;ffffff;" + "1".repeat(64)
        a.state = StateOps.updatePet(a.state, a.state.pets[0].copy(lookCode = newLook, spriteVersion = 2))
        a.sync()
        val r = HouseholdSync.merge(b.state, b.base, server.pull(b.cursor, b.base.tasks.map { it.id }.toSet()).first, "jamaica", clock, b.nowMs)
        assertEquals(listOf("mochi"), r.redraw)
        assertEquals(newLook, r.state.pets[0].lookCode)
        assertEquals(2, r.state.pets[0].spriteVersion, "bumped so the widget redraws")
    }

    @Test fun stoppingSharingKeepsThePetOnEveryPhone() {
        val (a, b) = family()
        b.done("feed", 17 * 60); b.sync(); a.sync()
        a.state = StateOps.updatePet(a.state, a.state.pets[0].copy(shared = false))
        a.sync()
        assertTrue(server.pets.isEmpty())
        assertEquals(2, a.state.tasks.size, "Save keeps the tasks")
        b.sync()
        val kept = b.state.pets.single()
        assertFalse(kept.shared, "Jamaica keeps her copy, no longer shared")
        assertEquals(2, b.state.completions.size, "with its history")
        assertEquals(2, b.state.tasks.size)
    }

    @Test fun reSharingAfterStoppingNeverDuplicatesRecords() {
        val (a, b) = family()
        a.state = StateOps.updatePet(a.state, a.state.pets[0].copy(shared = false))
        a.sync()                          // Save stops: Mochi leaves the family
        b.state = StateOps.updatePet(b.state, b.state.pets[0].copy(shared = true))
        b.sync()                          // Jamaica shares her copy again
        repeat(3) { a.sync() }            // Save's phone must not import it while it holds Mochi unshared
        assertEquals(1, a.state.completions.size, a.state.completions.toString())
        assertFalse(a.state.pets.single().shared)
        assertTrue(a.lastPush.isEmpty)
    }

    @Test fun reSharedHistoryKeepsItsAuthors() {
        val (a, b) = family()
        b.done("feed", 17 * 60); b.sync(); a.sync()
        a.state = StateOps.updatePet(a.state, a.state.pets[0].copy(shared = false)); a.sync()
        a.state = StateOps.updatePet(a.state, a.state.pets[0].copy(shared = true)); a.sync()
        val hers = a.state.completions.single { it.by == "jamaica" }.id
        assertTrue(server.completions.none { it.id == hers }, "Jamaica's old record isn't re-sent (it would be stamped as Save's)")
        assertEquals(1, a.state.completions.count { it.by == "jamaica" }, "Save's phone still shows it as hers")
    }

    @Test fun aFailedPushNeverPushesOldCopiesOverNewerEdits() {
        val (a, b) = family()
        b.state = StateOps.upsertTask(b.state, b.state.task("feed")!!.copy(title = "B1")); b.sync()
        a.sync(pushFails = true)          // Save gets B1, but can't send
        b.state = StateOps.upsertTask(b.state, b.state.task("feed")!!.copy(title = "B2")); b.sync()
        a.sync(); b.sync()
        assertEquals("B2", server.tasks.single { it.id == "feed" }.title)
        assertEquals("B2", a.state.task("feed")!!.title)
        assertEquals("B2", b.state.task("feed")!!.title)
    }

    @Test fun localTrimmingIsntAnUndo() {
        val (a, b) = family()
        repeat(AppState.MAX_COMPLETIONS_PER_TASK + 5) { i -> a.state = StateOps.complete(a.state, "walk", at(0) - i * DAY_MS, clock, ids) }
        a.sync(); b.sync()
        a.done("walk", 6 * 60)            // trims the oldest locally
        a.sync()
        assertTrue(a.lastPush.undoCompletionIds.isEmpty(), "trimmed records aren't undone for everyone")
        a.sync()                          // (learns its new record's author)
        val before = a.state.completions.size
        a.sync()
        assertTrue(a.lastPush.isEmpty && a.state.completions.size == before, "and they don't come back on every sync")
    }

    @Test fun recordsFromAnotherTimeZoneLandOnTheRightDay() {
        val a = Phone("save", AppState(pets = listOf(pet.copy(shared = true)), tasks = listOf(feed, walk)))
        a.sync()
        val b = Phone("jamaica", AppState(), tz = LocalClock.fixed(-7 * HOUR_MS)) // California
        b.sync()
        b.done("feed", 23 * 60 + 30)       // 11:30 pm Manila = 8:30 am California, same instant
        b.sync(); a.sync()
        val c = a.state.completions.single()
        assertEquals(clock.dayIndex(c.atMs), c.localDay)
        assertEquals(clock.minuteOfDay(c.atMs), c.localMinute)
    }

    @Test fun onlySharedPetsLeaveThePhone() {
        val other = Pet("kiko", "Kiko", Species.CAT, 0)
        val a = Phone("save", AppState(pets = listOf(pet.copy(shared = true), other), tasks = listOf(feed, feed.copy(id = "kf", petId = "kiko"))))
        a.done("kf", 480)
        a.sync()
        assertEquals(listOf("mochi"), server.pets.map { it.id })
        assertEquals(listOf("feed"), server.tasks.map { it.id })
        assertTrue(server.completions.isEmpty())
        assertEquals(2, a.state.pets.size)
    }

    // ---------- The append-only log, pulled in pieces ----------

    @Test fun recordsPulledTwiceNeverDuplicate() {
        val (a, b) = family()
        b.done("walk", 6 * 60 + 3); b.sync()
        // The look-back window brings recent records again on every pull: still one each.
        repeat(3) { a.sync(); b.sync() }
        assertEquals(2, a.state.completions.size)
        assertEquals(a.state.completions.map { it.id }.toSet(), b.state.completions.map { it.id }.toSet())
        assertTrue(a.lastPush.isEmpty && b.lastPush.isEmpty)
        // A record pushed twice (a retry after a timeout) is one record on the server.
        server.apply(SyncPush(addCompletions = listOf(b.state.completions.last())), "jamaica")
        assertEquals(2, server.completions.size)
    }

    @Test fun undoOnTheOtherPhoneArrivesEvenLongAfter() {
        val (a, b) = family()
        b.done("walk", 6 * 60 + 3); b.sync(); a.sync()
        // Many unrelated changes later (far outside the look-back window) Jamaica undoes her walk.
        repeat(5) { i -> a.done("feed", 8 * 60 + i); a.sync() }
        b.sync()
        b.state = StateOps.undoLast(b.state, "walk", mine = setOf(null, "jamaica"))
        b.sync()
        assertTrue(server.log.values.single { it.c.taskId == "walk" }.undone, "kept in the log, marked undone")
        a.sync()
        assertTrue(a.state.completions.none { it.taskId == "walk" })
        val s = CareEngine.status(a.state.task("walk")!!, a.state.completions, at(7 * 60), clock)
        assertFalse(s.allDoneThisCycle, "Save's phone asks for the walk again")
    }

    @Test fun onlyTheAuthorsUndoCounts() {
        val (a, b) = family()
        b.done("walk", 6 * 60 + 3); b.sync(); a.sync()
        // A phone that (by a bug) pushes an undo of someone else's record changes nothing.
        server.apply(SyncPush(undoCompletionIds = server.completions.filter { it.by == "jamaica" }.map { it.id }), "save")
        assertEquals(2, server.completions.size)
    }

    @Test fun aTaskAddedLaterBringsItsWholeHistory() {
        val (a, b) = family()
        val meds = CareTask("meds", "mochi", TaskKind.MEDS, "Medicine", listOf(8 * 60), anchorDay = 0, adaptive = false)
        // Save has a medicine task (with a week of records) that hasn't been shared before.
        a.state = a.state.copy(tasks = a.state.tasks + meds)
        repeat(7) { i -> a.state = StateOps.complete(a.state, "meds", at(8 * 60) - i * DAY_MS, clock, ids) }
        a.sync()
        repeat(4) { a.done("feed", 9 * 60 + it); a.sync() } // push the meds records out of the look-back window
        b.sync()
        assertEquals(7, b.state.completions.count { it.taskId == "meds" })
    }

    // ---------- Both phones offline: the later edit wins ----------

    @Test fun offlineEditsOnBothPhonesTheLaterWins() {
        val (a, b) = family()
        a.edit(at(9 * 60)) { StateOps.upsertTask(it, it.task("feed")!!.copy(title = "Save's 9am edit")) }
        b.edit(at(10 * 60)) { StateOps.upsertTask(it, it.task("feed")!!.copy(title = "Jamaica's 10am edit")) }
        b.sync()          // Jamaica's newer edit reaches the server first...
        a.sync(); b.sync()
        assertEquals("Jamaica's 10am edit", a.state.task("feed")!!.title, "...and Save's older one doesn't overwrite it")
        assertEquals("Jamaica's 10am edit", server.tasks.single { it.id == "feed" }.title)

        a.edit(at(12 * 60)) { StateOps.upsertTask(it, it.task("feed")!!.copy(slots = listOf(8 * 60))) }
        b.edit(at(11 * 60)) { StateOps.upsertTask(it, it.task("feed")!!.copy(slots = listOf(9 * 60))) }
        b.sync(); a.sync(); b.sync()
        assertEquals(listOf(8 * 60), b.state.task("feed")!!.slots, "Save's later edit wins, though it synced last")
        assertEquals(listOf(8 * 60), a.state.task("feed")!!.slots)
    }

    @Test fun editsOfDifferentThingsBothStay() {
        val (a, b) = family()
        a.edit(at(9 * 60)) { StateOps.upsertTask(it, it.task("feed")!!.copy(title = "Breakfast")) }
        b.edit(at(9 * 60 + 5)) { StateOps.upsertTask(it, it.task("walk")!!.copy(title = "Morning walk")) }
        a.sync(); b.sync(); a.sync()
        for (p in listOf(a, b)) {
            assertEquals("Breakfast", p.state.task("feed")!!.title)
            assertEquals("Morning walk", p.state.task("walk")!!.title)
        }
    }

    @Test fun aPhoneWhoseClockRunsAheadCantWinEveryLaterEdit() {
        val (a, b) = family()
        server.nowMs = at(10 * 60)
        b.nowMs = at(10 * 60) + DAY_MS                   // Jamaica's clock is a day ahead
        b.edit(b.nowMs) { StateOps.upsertTask(it, it.task("feed")!!.copy(title = "From tomorrow")) }
        b.sync()                                         // the server caps her edit at its own time
        server.nowMs = at(11 * 60)
        a.nowMs = at(11 * 60)
        a.edit(at(11 * 60)) { StateOps.upsertTask(it, it.task("feed")!!.copy(title = "Save, an hour later")) }
        a.sync(); b.sync()
        assertEquals("Save, an hour later", b.state.task("feed")!!.title)
    }

    @Test fun aRecordFromTheFutureCountsAsNow() {
        val (a, b) = family()
        server.nowMs = at(23 * 60 + 59) + DAY_MS         // the server's clock is right; here it's a day later
        a.nowMs = at(12 * 60)                            // Save's phone: noon today
        b.state = StateOps.complete(b.state, "walk", at(18 * 60), clock, ids) // Jamaica's clock says 6pm
        b.sync(); a.sync()
        val got = a.state.completions.single { it.taskId == "walk" }
        assertEquals(at(12 * 60), got.atMs, "shown as done now, not at a time that hasn't come yet")
        assertEquals(today, got.localDay)
    }

    @Test fun stampMarksOnlyWhatTheOwnerChanged() {
        val s0 = AppState(pets = listOf(pet), tasks = listOf(feed, walk))
        val s1 = HouseholdSync.stamp(s0, StateOps.upsertTask(s0, feed.copy(title = "Dinner")), 5_000)
        assertEquals(5_000, s1.task("feed")!!.editedAtMs)
        assertEquals(0, s1.task("walk")!!.editedAtMs)
        assertEquals(0, s1.pets[0].editedAtMs)
        val s2 = HouseholdSync.stamp(s1, StateOps.upsertTask(s1, s1.task("walk")!!.copy(remindersOn = false)), 9_000)
        assertEquals(0, s2.task("walk")!!.editedAtMs, "a personal reminder switch isn't a household edit")
    }

    // ---------- Pets removed by the other member ----------

    @Test fun aPetDeletedByTheOtherMemberStaysHereUnshared() {
        val (a, b) = family()
        b.done("feed", 17 * 60); b.sync(); a.sync()
        b.state = StateOps.removePet(b.state, "mochi")
        b.sync()
        assertTrue(server.pets.isEmpty() && server.completions.isEmpty(), "gone from the household")
        a.sync()
        val kept = a.state.pets.single()
        assertFalse(kept.shared, "Save's Mochi stays, no longer shared")
        assertEquals(2, a.state.tasks.size)
        assertEquals(2, a.state.completions.size, "with everyone's records")
        a.done("walk", 6 * 60); a.sync()
        assertTrue(server.pets.isEmpty() && a.lastPush.isEmpty, "and it doesn't come back by itself")
    }

    @Test fun oldSavesWithoutEditTimesStillDecode() {
        val old = """{"pets":[{"id":"mochi","name":"Mochi","species":"DOG","createdAt":0}],""" +
            """"tasks":[{"id":"feed","petId":"mochi","kind":"FEED","title":"Feed","slots":[420]}],""" +
            """"completions":[{"taskId":"feed","at":1000,"minute":7,"day":0}]}"""
        val s = StateCodec.decode(old)
        assertEquals(0, s.pets.single().editedAtMs)
        assertEquals(0, s.tasks.single().editedAtMs)
        assertEquals(null, s.completions.single().by)
        val again = StateCodec.decode(StateCodec.encode(s.copy(tasks = s.tasks.map { it.copy(editedAtMs = 42) })))
        assertEquals(42, again.tasks.single().editedAtMs)
        assertEquals(s.completions.single().id, again.completions.single().id, "derived ids are stable")
    }
}
