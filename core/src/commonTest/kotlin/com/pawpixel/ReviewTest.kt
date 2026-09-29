package com.pawpixel

import com.pawpixel.core.*
import com.pawpixel.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Defects found in the independent review of households, health v2, widgets v2, reminders and i18n. */
class ReviewTest {

    // ---- Time: zones with daylight saving (iOS owners abroad) ----

    /** A zone that moves from [before] to [after] (hours from UTC) at [switchMs]. */
    private fun dstClock(switchMs: Long, before: Int, after: Int) =
        LocalClock { ms -> (if (ms < switchMs) before else after) * HOUR_MS }

    @Test fun slotsOnTheDayClocksGoForwardInNewYork() {
        // 2026-03-08: 2:00 AM EST (UTC-5) becomes 3:00 AM EDT (UTC-4), at 07:00 UTC.
        val day = LocalClock.dayOf(2026, 3, 8)
        val clock = dstClock(day * DAY_MS + 7 * HOUR_MS, -5, -4)
        assertEquals(day * DAY_MS + 5 * HOUR_MS, clock.startOfDay(day), "midnight is still EST")
        assertEquals(day * DAY_MS + 12 * HOUR_MS, clock.at(day, 8 * 60), "the 8:00 AM feed is at 8:00 EDT, not 9:00")
        assertEquals(8 * 60, clock.minuteOfDay(clock.at(day, 8 * 60)))
        val late = clock.at(day, 23 * 60 + 30)
        assertEquals(day, clock.dayIndex(late), "an 11:30 PM slot stays on its own day")
        assertEquals(23 * 60 + 30, clock.minuteOfDay(late))
    }

    @Test fun midnightOnTheDayClocksGoForwardInSydney() {
        // 2026-10-04: 2:00 AM AEST (UTC+10) becomes 3:00 AM AEDT (UTC+11), at 16:00 UTC the day before.
        val day = LocalClock.dayOf(2026, 10, 4)
        val clock = dstClock(day * DAY_MS - 8 * HOUR_MS, 10, 11)
        assertEquals(day * DAY_MS - 10 * HOUR_MS, clock.startOfDay(day), "midnight AEST")
        assertEquals(day, clock.dayIndex(clock.startOfDay(day)))
        assertEquals(day, clock.dayIndex(clock.at(day, 30)), "a 12:30 AM slot is on that day, not the evening before")
        assertEquals(30, clock.minuteOfDay(clock.at(day, 30)))
        assertEquals(8 * 60, clock.minuteOfDay(clock.at(day, 8 * 60)))
    }

    @Test fun fixedZonesAreUnchanged() {
        for (clock in listOf(LocalClock.MANILA, LocalClock.fixed(-7 * HOUR_MS), LocalClock.fixed(0))) {
            for (day in listOf(0L, 20000L, 20725L)) for (m in listOf(0, 1, 420, 1439)) {
                val t = clock.at(day, m)
                assertEquals(day, clock.dayIndex(t)); assertEquals(m, clock.minuteOfDay(t))
            }
        }
    }

    // ---- Health reminders: a heads-up and a "due today" at the same moment ----

    @Test fun aHeadsUpAndADueTodayAtTheSameTimeAreNotMixedUp() {
        val clock = LocalClock.MANILA
        val today = 20700L
        val d = today + 5 // flea is due on d; deworming 3 days after, so its heads-up is also on d at 9:00
        val pet = Pet("m", "Mochi", Species.DOG, 0)
        fun t(id: String, kind: TaskKind, title: String, every: Int) =
            CareTask(id, "m", kind, title, listOf(9 * 60), everyDays = every, anchorDay = 0, adaptive = false)
        val worm = t("w", TaskKind.DEWORM, "Deworming", 90)
        val flea = t("f", TaskKind.FLEA_TICK, "Tick & flea prevention", 30)
        val s = AppState(
            pets = listOf(pet), tasks = listOf(worm, flea),
            completions = listOf(
                Completion("w", clock.at(d + 3 - 90, 9 * 60), 9 * 60, d + 3 - 90, id = "c1"),
                Completion("f", clock.at(d - 30, 9 * 60), 9 * 60, d - 30, id = "c2"),
            ),
        )
        val onD = ReminderPlanner.plan(s, clock.at(today, 8 * 60), clock).filter { it.atMs == clock.at(d, 9 * 60) }
        assertTrue(onD.isNotEmpty())
        val all = onD.joinToString(" | ") { it.body }
        assertTrue(onD.any { it.body == "Mochi's tick & flea prevention is due today." }, all)
        assertTrue(onD.any { it.body == "Mochi's deworming is due in 3 days. A good time to book the vet." }, all)
        assertEquals(onD.size, onD.map { it.id }.toSet().size, "distinct notification ids")
    }

    // ---- i18n: a name that looks like a placeholder ----

    @Test fun aNameWithBracesIsNotFilledInTwice() {
        assertEquals("{1}'s deworming is due today.", tr("{0}'s {1} is due today.", "{1}", "deworming"))
        assertEquals("Mochi is 5 weeks old", tr("{0} is {1} weeks old", "Mochi", 5))
        assertEquals("{2} stays", tr("{2} stays", "x"))
    }

    // ---- Households ----

    @Test fun aPetRemovedFromTheFamilyWhileRenamedHereKeepsItsCareHistory() {
        val clock = LocalClock.MANILA
        val day = 20500L
        val pet = Pet("chelsea", "Chelsea", Species.CAT, 0, shared = true, lookCode = "1;ffffff;")
        val feed = CareTask("feed", "chelsea", TaskKind.FEED, "Feed", listOf(420), anchorDay = 0)
        val done = Completion("feed", clock.at(day, 425), 425, day, id = "c1")
        val base = SharedData(listOf(HouseholdSync.view(pet)), listOf(HouseholdSync.view(feed)), listOf(done))
        // Offline, Save renames her; meanwhile Jamaica took her out of the family (the server cascades her tasks and records).
        val local = AppState(pets = listOf(pet.copy(name = "Chels", editedAtMs = clock.at(day, 600))), tasks = listOf(feed), completions = listOf(done))
        val r = HouseholdSync.merge(local, base, SharedData(), me = "save", clock = clock, nowMs = clock.at(day, 700))
        assertEquals(listOf("feed"), r.state.tasks.map { it.id }, "her feeding task stays")
        assertEquals(listOf("c1"), r.state.completions.map { it.id }, "and its history")
        assertEquals(false, r.state.pet("chelsea")!!.shared, "kept here, no longer shared")
        assertEquals("Chels", r.state.pet("chelsea")!!.name)
        assertTrue(r.push.upsertPets.isEmpty() && r.push.upsertTasks.isEmpty(), "not put back half-shared: ${r.push}")
    }

    // ---- Saved state from earlier versions still loads ----

    /** state.json as the first version (MVP, 1b30ad8) wrote it. */
    private val mvpState = """{"schema":1,"settings":{"remindersEnabled":true,"pro":false,"nightStart":1320,"nightEnd":360},
        "pets":[{"id":"k3x9abc0defg","name":"Mochi","species":"DOG","createdAt":1780000000000,"spriteVersion":2,"eyes":[[0.3,0.4],[0.7,0.4]],
        "sprite":{"size":32,"colors":12,"outline":true,"vibrance":1.1}}],
        "tasks":[{"id":"t1a2b3c4d5e6","petId":"k3x9abc0defg","kind":"FEED","title":"Feed","slots":[420,1080],"everyDays":1,"anchorDay":20600,
        "adaptive":true,"exactAlarm":false,"remindersOn":true,"createdAt":1780000000000},
        {"id":"t9z8y7x6w5v4","petId":"k3x9abc0defg","kind":"MEDS","title":"Medicine","slots":[480],"everyDays":1,"anchorDay":20600,
        "adaptive":false,"exactAlarm":true,"remindersOn":false,"createdAt":1780000000000}],
        "completions":[{"taskId":"t1a2b3c4d5e6","at":1780100000000,"minute":433,"day":20601},
        {"taskId":"t1a2b3c4d5e6","at":1780140000000,"minute":1093,"day":20601},
        {"taskId":"t9z8y7x6w5v4","at":1780100500000,"minute":441,"day":20601}]}"""

    /** state.json as b5ada25 (health care, away mode, ears) wrote it. */
    private val b5State = """{"schema":1,"settings":{"remindersEnabled":false,"awayUntil":1790000000000,"widgetTipDismissed":true,"pro":true,
        "nightStart":1380,"nightEnd":300},
        "pets":[{"id":"p1","name":"Chelsea","species":"CAT","createdAt":1780000000000,"spriteVersion":1,"eyes":[],"ears":"FLOPPY",
        "sprite":{"size":28,"colors":10,"outline":false,"vibrance":1.0}},
        {"id":"p2","name":"Kiko","species":"OTHER","createdAt":1780000000001,"spriteVersion":1,"eyes":[],"ears":"",
        "sprite":{"size":32,"colors":12,"outline":true,"vibrance":1.0}}],
        "tasks":[{"id":"v1","petId":"p1","kind":"VACCINE","title":"Anti-rabies shot","slots":[540],"everyDays":365,"anchorDay":20600,
        "adaptive":false,"exactAlarm":false,"remindersOn":true,"createdAt":1780000000000},
        {"id":"l1","petId":"p1","kind":"LITTER","title":"Litter","slots":[600],"everyDays":2,"anchorDay":20600,
        "adaptive":true,"exactAlarm":false,"remindersOn":true,"createdAt":1780000000000}],
        "completions":[{"taskId":"v1","at":1760000000000,"minute":540,"day":20370},{"taskId":"l1","at":1780100000000,"minute":600,"day":20601}]}"""

    @Test fun theFirstVersionsSaveLoadsWhole() {
        val s = StateCodec.decodeSaved(mvpState)!!
        assertEquals(listOf("Mochi"), s.pets.map { it.name })
        val p = s.pets[0]
        assertEquals(Species.DOG, p.species); assertEquals(2, p.spriteVersion); assertEquals(2, p.eyes.size)
        assertEquals(SpriteSettings(32, 12, true, 1.1), p.sprite)
        assertEquals(null, p.ears); assertEquals(false, p.shared); assertEquals(null, p.lookCode); assertEquals(null, p.birthDay)
        assertEquals(listOf(20601L), p.careDays, "the care calendar starts from the records")
        assertEquals(2, s.tasks.size)
        val meds = s.task("t9z8y7x6w5v4")!!
        assertEquals(TaskKind.MEDS, meds.kind); assertTrue(meds.exactAlarm); assertEquals(false, meds.remindersOn); assertEquals(false, meds.adaptive)
        assertEquals(3, s.completions.size)
        assertEquals(3, s.completions.map { it.id }.toSet().size, "old records get distinct ids")
        assertTrue(s.completions.all { it.by == null && Regex("[a-z0-9]{1,40}").matches(it.id) }, "ids the server accepts")
        assertEquals("", s.settings.language); assertEquals(0L, s.settings.awayUntilMs); assertTrue(s.weights.isEmpty())
        // Saved again and read back, nothing changes.
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
    }

    @Test fun theHealthVersionsSaveLoadsWhole() {
        val s = StateCodec.decodeSaved(b5State)!!
        assertEquals(listOf("p1", "p2"), s.pets.map { it.id })
        assertEquals("FLOPPY", s.pet("p1")!!.ears); assertEquals(null, s.pet("p2")!!.ears)
        assertEquals(false, s.settings.remindersEnabled); assertEquals(1790000000000, s.settings.awayUntilMs)
        assertTrue(s.settings.widgetTipDismissed); assertTrue(s.settings.pro); assertEquals(1380, s.settings.nightStart)
        val rabies = s.task("v1")!!
        assertTrue(rabies.kind.health); assertTrue(rabies.series.isEmpty()); assertEquals(365, rabies.everyDays)
        // The shot given a year before is still the last one: due a year after it.
        val status = CareEngine.status(rabies, s.completions, 1780200000000, LocalClock.MANILA, pet = s.pet("p1"))
        assertEquals(20370L + 365, status.cycleStartDay); assertTrue(status.known)
        assertEquals(listOf(20370L, 20601L), s.pet("p1")!!.careDays)
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
    }
}
