package com.pawpixel

import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Occasion
import com.pawpixel.core.Occasions
import com.pawpixel.core.Pet
import com.pawpixel.core.ReminderPlanner
import com.pawpixel.core.Species
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OccasionTest {
    private val clock = LocalClock.MANILA
    private val born = LocalClock.dayOf(2024, 3, 15)
    private val home = clock.at(LocalClock.dayOf(2024, 5, 2), 10 * 60)
    private val pet = Pet("p1", "Kape", Species.DOG, home, birthDay = born)

    @Test fun birthdayAndGotchaDayAreFoundOnTheDay() {
        val bday = clock.at(LocalClock.dayOf(2026, 3, 15), 8 * 60)
        assertEquals(Occasion.Birthday("Kape", 2), Occasions.today(pet, bday, clock))
        assertEquals("Kape turns 2", Occasions.today(pet, bday, clock)!!.title)
        val gotcha = clock.at(LocalClock.dayOf(2026, 5, 2), 20 * 60)
        assertEquals(Occasion.GotchaDay("Kape", 2), Occasions.today(pet, gotcha, clock))
        assertNull(Occasions.today(pet, clock.at(LocalClock.dayOf(2026, 3, 16), 8 * 60), clock))
        assertNull(Occasions.today(pet, clock.at(LocalClock.dayOf(2024, 3, 15), 8 * 60), clock), "no birthday the year they were born")
        assertNull(Occasions.today(pet.copy(rememberedDay = 20000), bday, clock))
    }

    @Test fun leapDayBirthdaysFallOnFeb28() {
        val leap = pet.copy(birthDay = LocalClock.dayOf(2024, 2, 29))
        assertEquals(Occasion.Birthday("Kape", 2), Occasions.today(leap, clock.at(LocalClock.dayOf(2026, 2, 28), 12 * 60), clock))
        assertEquals(Occasion.Birthday("Kape", 4), Occasions.today(leap, clock.at(LocalClock.dayOf(2028, 2, 29), 12 * 60), clock))
    }

    @Test fun aReminderAtNineOnTheDayWithinTheLead() {
        val state = AppState(pets = listOf(pet))
        val early = clock.at(LocalClock.dayOf(2026, 3, 1), 12 * 60)
        val notes = ReminderPlanner.occasions(state, early, clock)
        assertEquals(1, notes.size)
        assertEquals(clock.at(LocalClock.dayOf(2026, 3, 15), 9 * 60), notes[0].atMs)
        assertTrue(notes[0].title.contains("Kape turns 2") && notes[0].body.startsWith("Happy birthday, Kape!"))
        assertTrue(ReminderPlanner.occasions(state, clock.at(LocalClock.dayOf(2025, 12, 1), 12 * 60), clock).isEmpty(), "too far ahead")
        assertTrue(ReminderPlanner.plan(state, early, clock).any { it.title.contains("Kape turns 2") })
    }
}

class SeasonTest {
    @Test fun theCalendarDecoratesTheRoom() {
        assertEquals(com.pawpixel.sprite.Season.HALLOWEEN, com.pawpixel.sprite.Season.forDate(10, 28))
        assertEquals(com.pawpixel.sprite.Season.CHRISTMAS, com.pawpixel.sprite.Season.forDate(12, 24))
        assertEquals(com.pawpixel.sprite.Season.CHRISTMAS, com.pawpixel.sprite.Season.forDate(1, 3))
        assertEquals(com.pawpixel.sprite.Season.VALENTINES, com.pawpixel.sprite.Season.forDate(2, 14))
        assertEquals(com.pawpixel.sprite.Season.NONE, com.pawpixel.sprite.Season.forDate(6, 10))
        // The room draws with every season, day and night, at the phone's tall size and the widget's short one.
        for (s in com.pawpixel.sprite.Season.entries) for (phase in listOf(com.pawpixel.core.Sky.Phase.DAY, com.pawpixel.core.Sky.Phase.NIGHT)) {
            com.pawpixel.sprite.Room.render(96, 140, 120, phase, season = s); com.pawpixel.sprite.Room.render(96, 60, 48, phase, season = s)
        }
    }
}

class MoreLanguagesTest {
    @Test fun spanishAndPortugueseFollowThePhoneAndFallBackToEnglish() {
        assertEquals(com.pawpixel.i18n.Lang.ES, com.pawpixel.i18n.Lang.resolve("", "es-MX"))
        assertEquals(com.pawpixel.i18n.Lang.PT, com.pawpixel.i18n.Lang.resolve("", "pt-BR"))
        assertEquals(com.pawpixel.i18n.Lang.FIL, com.pawpixel.i18n.Lang.resolve("fil", "es"))
        assertEquals("{0} tiene hambre", com.pawpixel.i18n.I18n.lookup("{0} is hungry", com.pawpixel.i18n.Lang.ES))
        assertEquals("{0} está com fome", com.pawpixel.i18n.I18n.lookup("{0} is hungry", com.pawpixel.i18n.Lang.PT))
        assertEquals("Crea tu color", com.pawpixel.i18n.I18n.lookup("Mix your own", com.pawpixel.i18n.Lang.ES))
        assertEquals("A string nobody wrote", com.pawpixel.i18n.I18n.lookup("A string nobody wrote", com.pawpixel.i18n.Lang.PT), "untranslated strings stay English")
        // Dates read in each language's own order.
        val was = com.pawpixel.i18n.I18n.lang
        try {
            com.pawpixel.i18n.I18n.lang = com.pawpixel.i18n.Lang.ES
            assertEquals("29 sep 2026", com.pawpixel.core.LocalClock.shortDate(20725))
            com.pawpixel.i18n.I18n.lang = com.pawpixel.i18n.Lang.PT
            assertEquals("29 set 2026", com.pawpixel.core.LocalClock.shortDate(20725))
            com.pawpixel.i18n.I18n.lang = com.pawpixel.i18n.Lang.EN
            assertEquals("Sep 29, 2026", com.pawpixel.core.LocalClock.shortDate(20725))
        } finally { com.pawpixel.i18n.I18n.lang = was }
        // Every Spanish and Portuguese key is a real English string the app uses (it has Filipino).
        for (k in com.pawpixel.i18n.I18n.spanishKeys) assertTrue(com.pawpixel.i18n.I18n.has(k), "no such English string: $k")
    }
}
