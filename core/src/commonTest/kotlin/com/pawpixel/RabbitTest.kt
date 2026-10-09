package com.pawpixel

import com.pawpixel.core.*
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetLook
import com.pawpixel.sprite.PixelImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rabbits, the third species: their own care, health plan, weather advice and sprite. */
class RabbitTest {
    private val clock = LocalClock.MANILA
    private val today = LocalClock.dayOf(2026, 9, 29)
    private val now = clock.at(today, 12 * 60)
    private var n = 0
    private fun ids() = { "b${n++}" }

    @Test fun rabbitsGetHayWaterAndALitterTrayNotWalks() {
        assertEquals(listOf(TaskKind.FEED, TaskKind.WATER, TaskKind.LITTER), TaskDefaults.kindsFor(Species.RABBIT))
        assertEquals(listOf(TaskKind.VACCINE, TaskKind.VET), TaskDefaults.healthKindsFor(Species.RABBIT))
        val c = Challenges.forMonth(2026, 4, Species.RABBIT)
        assertTrue(c.kind != Challenge.Kind.WALKS && c.kind != Challenge.Kind.WALK_KM, "no walking challenges for a rabbit")
    }

    @Test fun eachRegionHasTheRabbitVaccineItsVetsUse() {
        val titles = { c: String -> HealthPlan.items(Species.RABBIT, c).map { it.title } }
        assertEquals(listOf("Myxo-RHD vaccine", "Vet check-up"), titles("GB"))
        assertEquals(listOf("RHDV2 vaccine", "Vet check-up"), titles("US"))
        assertEquals(listOf("RHD vaccine", "Vet check-up"), titles("AU"))
        assertEquals(listOf("RHD vaccine", "Vet check-up"), titles("DE"))
        assertEquals(listOf("Vet check-up"), titles("PH"))
        for (r in HealthPlan.Region.entries) {
            val items = HealthPlan.itemsIn(Species.RABBIT, r)
            assertTrue(items.none { it.title.contains("abies") || it.kind == TaskKind.DEWORM || it.kind == TaskKind.FLEA_TICK }, "no dog-and-cat items for rabbits in $r")
        }
        val byTitle = HealthPlan.Region.entries.flatMap { HealthPlan.itemsIn(Species.RABBIT, it) }.groupBy { it.kind to it.title.lowercase() }
        for ((key, items) in byTitle) assertEquals(1, items.map { it.schedule }.distinct().size, "schedules for $key differ across regions")
    }

    @Test fun aUsKitFollowsTheTwoDoseRhdv2Course() {
        val kit = Pet("r1", "Bun", Species.RABBIT, 0, birthDay = today - 5 * 7)
        val s = HealthPlan.addTo(AppState(pets = listOf(kit)), kit, now, clock, ids(), country = "US")
        val rhd = s.tasks.single { it.title == "RHDV2 vaccine" }
        val due = HealthPlan.due(HealthPlan.scheduleFor(kit, rhd)!!, kit.birthDay!!, emptyList(), 365, today)
        assertEquals(2, due.doses); assertEquals(1, due.dose); assertEquals(today, due.day) // 4 weeks passed at 5: due now, then at 7
        // Adding the plan again on a UK phone adds nothing new: the vaccine and check-up are the same items under other names.
        assertEquals(s.tasks.size, HealthPlan.addTo(s, kit, now, clock, ids(), country = "GB").tasks.size)
    }

    @Test fun rhdv2IsTwoDosesEvenWhenTheFirstIsLate() {
        val bun = Pet("r1", "Bun", Species.RABBIT, 0, birthDay = today - 400)
        val first = today - 2
        val due = HealthPlan.due(HealthPlan.RHDV2, bun.birthDay!!, listOf(first), 365, today)
        assertEquals(first + 21, due.day, "the second dose three weeks after the first")
        assertEquals(2, due.dose); assertEquals(2, due.doses)
        val after = HealthPlan.due(HealthPlan.RHDV2, bun.birthDay!!, listOf(first, first + 21), 365, today)
        assertEquals(first + 21 + 365, after.day, "then yearly")
        assertEquals(listOf(1, 2, null), HealthPlan.doseNumbers(HealthPlan.RHDV2, bun.birthDay!!, listOf(first, first + 21, first + 386)))
        // An adult dog's first leptospirosis shot needs its booster too.
        assertEquals(first + 21, HealthPlan.due(HealthPlan.LEPTO, today - 900, listOf(first), 365, today).day)
    }

    @Test fun aNewVaccineForARabbitIsNotAnAntiRabiesShot() {
        val bun = Pet("r1", "Bun", Species.RABBIT, 0)
        assertEquals("Vaccine", StateOps.defaultTask(bun, TaskKind.VACCINE, today, "t", now).title)
        assertEquals("Anti-rabies shot", StateOps.defaultTask(bun.copy(species = Species.DOG), TaskKind.VACCINE, today, "t", now).title)
    }

    @Test fun rabbitOwnersGetNoRabiesMonthNote() {
        val bun = Pet("r1", "Bun", Species.RABBIT, 0)
        val march = clock.at(LocalClock.dayOf(2027, 2, 20), 12 * 60)
        assertNull(ReminderPlanner.rabiesMonth(AppState(pets = listOf(bun)), march, clock, country = "PH"))
        val withDog = AppState(pets = listOf(bun, Pet("d1", "Kape", Species.DOG, 1)))
        assertEquals("d1", ReminderPlanner.rabiesMonth(withDog, march, clock, country = "PH")?.petId)
    }

    @Test fun heatIsAWarningForARabbitFromTheHighTwenties() {
        val warm = """{"latitude":13.6,"longitude":123.2,"current":{"time":"2026-10-06T14:00","interval":900,"temperature_2m":27.0,"apparent_temperature":29.0,"precipitation":0.0,"weather_code":1,"is_day":1}}"""
        val w = WeatherAdvice.parse(warm, 0)!!
        assertEquals("It feels like 29° out. Rabbits overheat easily: keep Bun somewhere cool and shady, with fresh water.", WeatherAdvice.advice(w, "Bun", Species.RABBIT))
        assertNull(WeatherAdvice.advice(w, "Mochi", Species.CAT))
        assertEquals("Lovely out. Perfect walk weather for Kape.", WeatherAdvice.advice(w, "Kape", Species.DOG))
    }

    @Test fun aRabbitIsSavedAndReadBackAsARabbit() {
        val s = StateCodec.decode(StateCodec.encode(AppState(pets = listOf(Pet("r1", "Bun", Species.RABBIT, 0)))))
        assertEquals(Species.RABBIT, s.pets[0].species)
        assertTrue(VetSummary.text(s, s.pets[0], now, clock).contains("Bun · Rabbit"))
    }

    @Test fun aRabbitIsDrawnWithLongEarsAndBothEarShapesDiffer() {
        val look = PetLook.from(PixelImage(10, 10).fill(0xFFB07A4A.toInt()))
        val upright = PetArt(look, Species.RABBIT).still
        val lop = PetArt(look, Species.RABBIT, Ears.FLOPPY).still
        val cat = PetArt(look, Species.CAT).still
        fun topRow(img: PixelImage) = (0 until img.height).first { y -> (0 until img.width).any { x -> (img[x, y] ushr 24) > 0 } }
        assertEquals(Ears.POINTY, PetArt(look, Species.RABBIT).ears) // upright by default
        assertTrue(topRow(upright) <= 1, "the ears reach the top of the canvas")
        assertTrue(topRow(lop) > topRow(upright) + 4, "lop ears hang down instead")
        assertNotEquals(cat.pixels.toList(), upright.pixels.toList())
        // Asleep, the upright ears lie back over the head instead of vanishing behind it.
        assertNotEquals(Chibi.sleeping(PetArt(look, Species.RABBIT)).pixels.toList(), Chibi.sleeping(PetArt(look, Species.RABBIT, Ears.FLOPPY)).pixels.toList())
        // Every frame draws (sleeping included), on the same canvas as cats and dogs.
        val set = Chibi.build(PetArt(look, Species.RABBIT))
        assertEquals(Chibi.build(PetArt(look, Species.CAT)).width, set.width)
    }
}
