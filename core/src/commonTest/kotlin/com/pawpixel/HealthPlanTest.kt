package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Young-pet schedules from the birthday (HealthPlan), and how they show up everywhere else. */
class HealthPlanTest {
    private val clock = LocalClock.MANILA
    private val today = LocalClock.dayOf(2026, 9, 29)
    private val now = clock.at(today, 12 * 60)
    private var n = 0
    private fun ids() = { "t${n++}" }

    private fun planFor(species: Species, ageDays: Long?): AppState {
        val pet = Pet("p1", "Bantay", species, 0, birthDay = ageDays?.let { today - it })
        return HealthPlan.addTo(AppState(pets = listOf(pet)), pet, now, clock, ids())
    }
    private fun AppState.byTitle(t: String) = tasks.single { it.title == t }
    private fun AppState.item(title: String, at: Long = now) =
        CareStats.healthDue(this, "p1", at, clock).single { it.task.title == title }
    private fun AppState.dueDay(title: String, at: Long = now) = clock.dayIndex(item(title, at).dueMs!!)
    private fun AppState.give(title: String, day: Long) = StateOps.logOnDay(this, byTitle(title).id, day, clock.at(day, 18 * 60), clock)

    @Test fun adultDogGetsTheYearlyRoutineDueNow() {
        val s = planFor(Species.DOG, null)
        assertEquals(
            listOf("Anti-rabies shot", "5-in-1 vaccine", "Deworming", "Tick & flea prevention", "Heartworm prevention", "Vet check-up"),
            s.tasks.map { it.title },
        )
        assertEquals(listOf(365, 365, 90, 30, 30, 365), s.tasks.map { it.everyDays })
        val items = CareStats.healthDue(s, "p1", now, clock)
        assertTrue(items.all { it.due && !it.scheduled && it.dose == null }, "not known when last given: due now, no schedule")
        assertTrue(ReminderPlanner.plan(s, now, clock).isEmpty(), "no reminders for guessed dates")
        assertEquals(s.tasks.size, HealthPlan.addTo(s, s.pets[0], now, clock, ids()).tasks.size, "adding again doesn't duplicate")
    }

    @Test fun puppyGetsItsFiveInOneDoseByDose() {
        var s = planFor(Species.DOG, 42) // 6 weeks old today
        val shot = s.item("5-in-1 vaccine")
        assertTrue(shot.scheduled)
        assertEquals(today, clock.dayIndex(shot.dueMs!!))
        assertEquals(1 to 4, shot.dose to shot.doses, "6, 9, 12 and 16 weeks")
        s = s.give("5-in-1 vaccine", today)
        assertEquals(today + 21, s.dueDay("5-in-1 vaccine"))
        assertEquals(2 to 4, s.item("5-in-1 vaccine").let { it.dose to it.doses })
        s = s.give("5-in-1 vaccine", today + 21)
        assertEquals(today + 42, s.dueDay("5-in-1 vaccine", clock.at(today + 21, 20 * 60)))
        s = s.give("5-in-1 vaccine", today + 42)
        // 15 weeks would be a week short of 16: the last dose waits for 16 weeks.
        val third = clock.at(today + 42, 20 * 60)
        assertEquals(today - 42 + 112, s.dueDay("5-in-1 vaccine", third))
        assertEquals(4 to 4, s.item("5-in-1 vaccine", third).let { it.dose to it.doses })
        s = s.give("5-in-1 vaccine", today + 70)
        // Series done: the yearly booster counts from the last dose, and has no dose number.
        val done = s.item("5-in-1 vaccine", clock.at(today + 70, 20 * 60))
        assertEquals(today + 70 + 365, clock.dayIndex(done.dueMs!!))
        assertNull(done.dose)
        assertEquals(listOf(null, 4, 3, 2, 1), CareStats.healthRecords(s.give("5-in-1 vaccine", today + 435), s.byTitle("5-in-1 vaccine").id).map { it.dose })
    }

    @Test fun aLateDoseMovesTheNextOneAndTheCount() {
        var s = planFor(Species.DOG, 42)
        s = s.give("5-in-1 vaccine", today)
        s = s.give("5-in-1 vaccine", today + 30) // 9 days late (at ~10.3 weeks)
        val at = clock.at(today + 30, 20 * 60)
        assertEquals(today + 51, s.dueDay("5-in-1 vaccine", at))
        // 13.3 weeks, then 16.3 weeks (not short of 16): 4 doses in all.
        assertEquals(3 to 4, s.item("5-in-1 vaccine", at).let { it.dose to it.doses })
        // A double tap is one dose.
        s = s.give("5-in-1 vaccine", today + 30)
        assertEquals(3 to 4, s.item("5-in-1 vaccine", at).let { it.dose to it.doses })
    }

    @Test fun aPuppyAddedLateStartsNowWithoutFretting() {
        val s = planFor(Species.DOG, 70) // 10 weeks, no records: maybe the breeder gave some
        val shot = s.item("5-in-1 vaccine")
        assertTrue(shot.due)
        assertEquals(1 to 3, shot.dose to shot.doses, "10, 13, 16 weeks")
        val status = CareEngine.status(s.byTitle("5-in-1 vaccine"), s.completions, now, clock, pet = s.pets[0])
        assertFalse(status.known, "a guess: no reminders or sad pet until it's recorded")
        assertTrue(ReminderPlanner.plan(s, now, clock).none { it.taskId == shot.task.id })
        // An older puppy with no records: one dose, then yearly.
        assertNull(planFor(Species.DOG, 140).item("5-in-1 vaccine").dose)
    }

    @Test fun kittenFvrcpAt8_12_16Weeks() {
        var s = planFor(Species.CAT, 50)
        assertEquals(today + 6, s.dueDay("FVRCP vaccine"))
        assertEquals(1 to 3, s.item("FVRCP vaccine").let { it.dose to it.doses })
        s = s.give("FVRCP vaccine", today + 6)
        assertEquals(today + 34, s.dueDay("FVRCP vaccine", clock.at(today + 6, 20 * 60)))
        // Ten weeks old with nothing recorded (the e2e journey's kitten): 10, 14, 18 weeks.
        assertEquals(1 to 3, planFor(Species.CAT, 70).item("FVRCP vaccine").let { it.dose to it.doses })
    }

    @Test fun antiRabiesAtThreeCalendarMonthsThenYearly() {
        val born = LocalClock.dayOf(2026, 7, 15)
        assertEquals(LocalClock.dayOf(2026, 10, 15), HealthPlan.RABIES.firstDay(born))
        assertEquals(LocalClock.dayOf(2027, 2, 28), HealthPlan.RABIES.firstDay(LocalClock.dayOf(2026, 11, 30)))
        assertEquals(LocalClock.dayOf(2028, 2, 29), HealthPlan.RABIES.firstDay(LocalClock.dayOf(2027, 11, 30)))
        var s = planFor(Species.CAT, today - born)
        assertEquals(LocalClock.dayOf(2026, 10, 15), s.dueDay("Anti-rabies shot"))
        assertNull(s.item("Anti-rabies shot").dose, "a single dose, not a series")
        s = s.give("Anti-rabies shot", LocalClock.dayOf(2026, 10, 16))
        assertEquals(LocalClock.dayOf(2026, 10, 16) + 365, s.dueDay("Anti-rabies shot", clock.at(LocalClock.dayOf(2026, 10, 16), 20 * 60)))
    }

    @Test fun dewormingGetsLessFrequentAsThePetGrows() {
        var s = planFor(Species.DOG, 10)
        val born = today - 10
        assertEquals(born + 14, s.dueDay("Deworming"), "from 2 weeks")
        fun nextAfter(age: Long): Long {
            s = s.give("Deworming", born + age)
            return s.dueDay("Deworming", clock.at(born + age, 20 * 60)) - born
        }
        assertEquals(28, nextAfter(14), "every 2 weeks while under 12 weeks")
        assertEquals(84, nextAfter(70))
        assertEquals(114, nextAfter(84), "monthly from 12 weeks")
        assertEquals(211, nextAfter(181), "still monthly just under 6 months")
        assertEquals(290, nextAfter(200), "then every 3 months (the item's own repeat)")
        assertEquals(born + 21, planFor(Species.CAT, 10).dueDay("Deworming"), "kittens from 3 weeks")
    }

    @Test fun preventionAndCheckUpsStartAtTheRightAge() {
        val s = planFor(Species.DOG, 30)
        assertEquals(today + 26, s.dueDay("Heartworm prevention"))
        assertEquals(today + 26, s.dueDay("Tick & flea prevention"))
        assertEquals(today + 12, s.dueDay("Vet check-up"))
        assertEquals(today + 26, planFor(Species.CAT, 30).dueDay("Vet check-up"))
        // Planned first doses are real dates: heads-up 3 days before, on the day, and a follow-up.
        val r = ReminderPlanner.plan(s, now, clock).filter { it.body.contains("check-up") }
        assertEquals(listOf(today + 9, today + 12, today + 15).map { clock.at(it, 9 * 60) }, r.map { it.atMs })
    }

    @Test fun theScheduleFollowsTheBirthdayAndTheUsualName() {
        // Added as an adult (no birthday), then the owner gives one: the same items now follow it.
        var s = planFor(Species.DOG, null)
        val pet = s.pets[0].copy(birthDay = today - 42)
        s = StateOps.updatePet(s, pet)
        assertEquals(1 to 4, s.item("5-in-1 vaccine").let { it.dose to it.doses })
        // A changed birthday moves the plan.
        s = StateOps.updatePet(s, pet.copy(birthDay = today - 20))
        assertEquals(today + 22, s.dueDay("5-in-1 vaccine"))
        // Renamed: a plain yearly item again.
        val shot = s.byTitle("5-in-1 vaccine")
        s = StateOps.upsertTask(s, shot.copy(title = "DHPP (Nobivac)"))
        assertFalse(s.item("DHPP (Nobivac)").scheduled)
        // Other species' names don't match: a cat has no "5-in-1 vaccine" plan.
        assertNull(HealthPlan.scheduleFor(pet.copy(species = Species.CAT), shot))
    }

    @Test fun dueHealthCareIsGentleOnTheMood() {
        var s = planFor(Species.DOG, 30)
        val later = clock.at(today + 30, 12 * 60) // everything planned is days overdue
        val r = MoodEngine.read(s, "p1", later, clock)
        assertEquals(Mood.NEEDS_MEDS, r.mood)
        s = s.copy(tasks = s.tasks.filter { it.kind.health })
        for (h in listOf(0, 24, 72, 24 * 20)) assertTrue(MoodEngine.read(s, "p1", later + h * HOUR_MS, clock).mood != Mood.SAD)
    }

    @Test fun birthdaysFromAnAgeAndAgeLabels() {
        assertEquals(today - 70, HealthPlan.birthDayFromAge(today, 10, HealthPlan.AgeUnit.WEEKS))
        assertEquals(LocalClock.dayOf(2026, 5, 29), HealthPlan.birthDayFromAge(today, 4, HealthPlan.AgeUnit.MONTHS))
        assertEquals(LocalClock.dayOf(2024, 9, 29), HealthPlan.birthDayFromAge(today, 2, HealthPlan.AgeUnit.YEARS))
        assertEquals("1 day old", HealthPlan.ageLabel(today - 1, today))
        assertEquals("8 weeks old", HealthPlan.ageLabel(today - 56, today))
        assertEquals("4 months old", HealthPlan.ageLabel(LocalClock.dayOf(2026, 5, 29), today))
        assertEquals("3 months old", HealthPlan.ageLabel(LocalClock.dayOf(2026, 5, 30), today))
        assertEquals("23 months old", HealthPlan.ageLabel(LocalClock.dayOf(2024, 9, 30), today))
        assertEquals("2 years old", HealthPlan.ageLabel(LocalClock.dayOf(2024, 9, 29), today))
        assertEquals("Not born yet", HealthPlan.ageLabel(today + 1, today))
        assertFalse(HealthPlan.isYoung(today - 300, today))
        assertTrue(HealthPlan.isYoung(today - 100, today))
    }

    @Test fun calendarArithmetic() {
        for (d in listOf(-1000L, 0, 11016, 20725, 30000)) {
            val (y, m, day) = LocalClock.civil(d)
            assertEquals(d, LocalClock.dayOf(y, m, day))
        }
        assertEquals(LocalClock.dayOf(2027, 1, 31), LocalClock.plusMonths(LocalClock.dayOf(2026, 12, 31), 1))
        assertEquals(LocalClock.dayOf(2025, 11, 30), LocalClock.plusMonths(LocalClock.dayOf(2026, 1, 30), -2))
        assertEquals(0, LocalClock.monthsBetween(today, today - 5))
        assertEquals(12, LocalClock.monthsBetween(LocalClock.dayOf(2025, 9, 29), today))
    }

    @Test fun savedAndOlderDataStillWork() {
        val s = planFor(Species.CAT, 50).give("FVRCP vaccine", today)
        assertEquals(s, StateCodec.decode(StateCodec.encode(s)))
        // A save from the first version: a planned series of days on the task, a pet without a birthday.
        val old = """{"schema":1,"pets":[{"id":"p1","name":"Mingming","species":"CAT","createdAt":0}],
            "tasks":[{"id":"v","petId":"p1","kind":"VACCINE","title":"FVRCP vaccine","slots":[540],"everyDays":365,
            "anchorDay":$today,"series":[${today + 5},${today + 33}]}],"completions":[]}"""
        val back = StateCodec.decode(old)
        val item = CareStats.healthDue(back, "p1", now, clock).single()
        assertEquals(today + 5, clock.dayIndex(item.dueMs!!))
        assertEquals(1 to 2, item.dose to item.doses)
    }

    @Test fun rabiesAwarenessMonthOnceAYearForDogAndCatOwners() {
        val feb = clock.at(LocalClock.dayOf(2027, 2, 10), 12 * 60)
        val s = planFor(Species.DOG, null).copy(tasks = emptyList())
        val r = ReminderPlanner.plan(s, feb, clock).single()
        assertEquals(clock.at(LocalClock.dayOf(2027, 3, 1), 9 * 60), r.atMs)
        assertEquals("Free anti-rabies shots are often offered in March — check your barangay.", r.body)
        assertFalse(r.quickDone)
        assertTrue(r.taskIds.isEmpty() && r.refs.isEmpty())
        // Not months ahead (it's planned again whenever PawPixel runs), and not again after it's shown.
        assertTrue(ReminderPlanner.plan(s, clock.at(LocalClock.dayOf(2026, 12, 1), 12 * 60), clock).none { "Rabies" in it.title }) // only New Year's Eve notes on Dec 1
        assertTrue(ReminderPlanner.plan(s, clock.at(LocalClock.dayOf(2027, 3, 1), 10 * 60), clock).isEmpty())
        // Only for dogs and cats, and only with reminders on.
        assertTrue(ReminderPlanner.plan(s.copy(pets = s.pets.map { it.copy(species = Species.OTHER) }), feb, clock).isEmpty())
        assertTrue(ReminderPlanner.plan(s.copy(settings = s.settings.copy(remindersEnabled = false)), feb, clock).isEmpty())
        assertEquals(r.id, ReminderPlanner.plan(s, feb + DAY_MS, clock).single().id, "the same notification, not a second one")
    }
}

class RegionalHealthPlanTest {
    private val clock = LocalClock.MANILA
    private val today = LocalClock.dayOf(2026, 9, 29)
    private val now = clock.at(today, 12 * 60)
    private var n = 0
    private fun ids() = { "r${n++}" }

    @Test fun eachRegionHasItsOwnUsualItems() {
        assertEquals(HealthPlan.Region.PH, HealthPlan.regionOf("")); assertEquals(HealthPlan.Region.PH, HealthPlan.regionOf("ph"))
        assertEquals(HealthPlan.Region.NORTH_AMERICA, HealthPlan.regionOf("US")); assertEquals(HealthPlan.Region.UK, HealthPlan.regionOf("GB"))
        assertEquals(HealthPlan.Region.AUSTRALIA, HealthPlan.regionOf("NZ")); assertEquals(HealthPlan.Region.WORLD, HealthPlan.regionOf("DE"))
        val titles = { species: Species, c: String -> HealthPlan.items(species, c).map { it.title } }
        assertEquals(listOf("Anti-rabies shot", "5-in-1 vaccine", "Deworming", "Tick & flea prevention", "Heartworm prevention", "Vet check-up"), titles(Species.DOG, "PH"))
        assertTrue("Leptospirosis vaccine" in titles(Species.DOG, "US")); assertTrue("FeLV vaccine" in titles(Species.CAT, "CA"))
        assertTrue(titles(Species.DOG, "GB").none { it.contains("abies") }, "no rabies vaccine in the UK")
        assertTrue(titles(Species.CAT, "AU").none { it.contains("abies") }, "Australia is rabies-free")
        assertEquals(listOf("C5 vaccine"), titles(Species.DOG, "AU").filter { it.endsWith("vaccine") })
        assertEquals(listOf("Rabies vaccine", "DHPP vaccine"), titles(Species.DOG, "FR").filter { it.endsWith("vaccine") })
        assertEquals(listOf("Vet check-up"), titles(Species.OTHER, "US"))
        // Every item's name is translated for a Filipino speaker abroad.
        for (r in HealthPlan.Region.entries) for (sp in Species.entries) for (i in HealthPlan.itemsIn(sp, r)) assertTrue(com.pawpixel.i18n.I18n.has(i.title), "no Filipino for ${i.title}")
    }

    @Test fun aUsPuppyFollowsTheAahaSeriesAndAUkPuppyTheTwoDoseCourse() {
        val pup = Pet("p1", "Max", Species.DOG, 0, birthDay = today - 10 * 7)
        val us = HealthPlan.addTo(AppState(pets = listOf(pup)), pup, now, clock, ids(), country = "US")
        val lepto = us.tasks.single { it.title == "Leptospirosis vaccine" }
        val leptoDue = HealthPlan.due(HealthPlan.scheduleFor(pup, lepto)!!, pup.birthDay!!, emptyList(), lepto.everyDays, today)
        assertEquals(pup.birthDay!! + 12 * 7, leptoDue.day); assertEquals(1, leptoDue.dose); assertEquals(2, leptoDue.doses)
        val rabies = us.tasks.single { it.title == "Rabies vaccine" }
        assertEquals(pup.birthDay!! + 12 * 7, HealthPlan.due(HealthPlan.scheduleFor(pup, rabies)!!, pup.birthDay!!, emptyList(), 365, today).day)
        val uk = HealthPlan.addTo(AppState(pets = listOf(pup)), pup, now, clock, ids(), country = "GB")
        val dhp = uk.tasks.single { it.title == "DHP vaccine" }
        val dhpDue = HealthPlan.due(HealthPlan.scheduleFor(pup, dhp)!!, pup.birthDay!!, emptyList(), 365, today)
        assertEquals(2, dhpDue.doses) // 8 and 12 weeks; the first is overdue at 10 weeks, so due today
        assertEquals(today, dhpDue.day)
    }

    @Test fun aPlanMadeAbroadKeepsItsScheduleOnAPhilippinePhone() {
        val kitten = Pet("c1", "Tala", Species.CAT, 0, birthDay = today - 9 * 7)
        val us = HealthPlan.addTo(AppState(pets = listOf(kitten)), kitten, now, clock, ids(), country = "US")
        val felv = us.tasks.single { it.title == "FeLV vaccine" }
        assertNotNull(HealthPlan.scheduleFor(kitten, felv)) // matched across regions, not just the phone's
        // Adding the Philippine items on the household's other phone adds nothing: rabies, flea & tick, FVRCP, deworming and the check-up are there under other names.
        val both = HealthPlan.addTo(us, kitten, now, clock, ids(), country = "PH")
        assertEquals(us.tasks.size, both.tasks.size)
        assertEquals(HealthPlan.KEY_RABIES, HealthPlan.keyOf(Species.CAT, us.tasks.single { it.title == "Rabies vaccine" }))
    }

    @Test fun aSharedTitleFollowsThePhonesOwnRegion() {
        val kitten = Pet("c1", "Tala", Species.CAT, 0, birthDay = today - 9 * 7)
        val fvrcp = CareTask("t", "c1", TaskKind.VACCINE, "FVRCP vaccine", listOf(9 * 60), everyDays = 365)
        val was = HealthPlan.homeCountry
        try {
            HealthPlan.homeCountry = "GB"
            assertEquals(HealthPlan.UK_CAT, HealthPlan.scheduleFor(kitten, fvrcp)) // 9 and 12 weeks
            HealthPlan.homeCountry = "US"
            assertEquals(HealthPlan.FVRCP, HealthPlan.scheduleFor(kitten, fvrcp)) // 8, 12, 16 weeks
            HealthPlan.homeCountry = ""
            assertEquals(HealthPlan.FVRCP, HealthPlan.scheduleFor(kitten, fvrcp))
        } finally { HealthPlan.homeCountry = was }
    }
}
