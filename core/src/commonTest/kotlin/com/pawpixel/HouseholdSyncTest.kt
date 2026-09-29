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

    /** The server: applies a phone's push, stamps the author. */
    private var server = SharedData()
    private fun SharedData.apply(push: SyncPush, by: String): SharedData {
        val pets = pets.filter { it.id !in push.deletePetIds && push.upsertPets.none { u -> u.id == it.id } } + push.upsertPets.map(HouseholdSync::view)
        val petIds = pets.map { it.id }.toSet()
        val tasks = (tasks.filter { it.id !in push.deleteTaskIds && push.upsertTasks.none { u -> u.id == it.id } } + push.upsertTasks.map(HouseholdSync::view))
            .filter { it.petId in petIds }
        val taskIds = tasks.map { it.id }.toSet()
        val comps = (completions.filter { it.id !in push.deleteCompletionIds } + push.addCompletions.map { it.copy(by = it.by ?: by) })
            .filter { it.taskId in taskIds }
        return SharedData(pets, tasks, comps)
    }

    private inner class Phone(val user: String, var state: AppState) {
        var base = SharedData()
        var lastPush = SyncPush()
        fun sync() {
            val r = HouseholdSync.merge(state, base, server)
            lastPush = r.push
            server = server.apply(r.push, user)
            state = r.state; base = r.base
        }
        fun done(taskId: String, min: Int) { state = StateOps.complete(state, taskId, at(min), clock, ids) }
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
        val r = HouseholdSync.merge(b.state, b.base, server)
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
}
