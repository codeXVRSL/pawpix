package com.pawpixel.core

import com.pawpixel.i18n.tr

/** A day worth bunting: the pet's birthday, or the anniversary of the day they came home. */
sealed class Occasion {
    abstract val title: String
    abstract val bubble: String
    data class Birthday(val name: String, val age: Int) : Occasion() {
        override val title get() = if (age == 1) tr("{0}'s first birthday", name) else tr("{0} turns {1}", name, age)
        override val bubble get() = tr("Happy birthday, {0}!", name)
    }
    data class GotchaDay(val name: String, val years: Int) : Occasion() {
        override val title get() = if (years == 1) tr("One year since {0} came home", name) else tr("{0} years since {1} came home", years, name)
        override val bubble get() = tr("{0} years together!", years).takeIf { years > 1 } ?: tr("One year together!")
    }
}

/**
 * Birthdays and gotcha days (adoption anniversaries), from the birthday the owner typed and the
 * day the pet was made in the app. Live-ops without a server: the room celebrates on the day.
 */
object Occasions {
    fun today(pet: Pet, nowMs: Long, clock: LocalClock): Occasion? {
        if (pet.remembered) return null
        val day = clock.dayIndex(nowMs)
        val (y, m, d) = LocalClock.civil(day)
        pet.birthDay?.let { born ->
            val (by, bm, bd) = LocalClock.civil(born)
            val age = y - by
            if (age >= 1 && sameDate(m, d, bm, bd, y)) return Occasion.Birthday(pet.name, age)
        }
        if (pet.createdAtMs > 0) {
            val home = clock.dayIndex(pet.createdAtMs)
            val (hy, hm, hd) = LocalClock.civil(home)
            val years = y - hy
            if (years >= 1 && sameDate(m, d, hm, hd, y)) return Occasion.GotchaDay(pet.name, years)
        }
        return null
    }

    /** The next occasion within [leadMs], for a reminder: (local day, occasion). */
    fun upcoming(pet: Pet, nowMs: Long, clock: LocalClock, leadMs: Long): Pair<Long, Occasion>? {
        if (pet.remembered) return null
        val today = clock.dayIndex(nowMs)
        val (y, _, _) = LocalClock.civil(today)
        val out = ArrayList<Pair<Long, Occasion>>()
        pet.birthDay?.let { born ->
            val (by, bm, bd) = LocalClock.civil(born)
            for (yy in listOf(y, y + 1)) { val dd = dateIn(yy, bm, bd); if (dd >= today && yy - by >= 1) out += dd to Occasion.Birthday(pet.name, yy - by) }
        }
        if (pet.createdAtMs > 0) {
            val (hy, hm, hd) = LocalClock.civil(clock.dayIndex(pet.createdAtMs))
            for (yy in listOf(y, y + 1)) { val dd = dateIn(yy, hm, hd); if (dd >= today && yy - hy >= 1) out += dd to Occasion.GotchaDay(pet.name, yy - hy) }
        }
        return out.filter { clock.at(it.first, 0) - nowMs <= leadMs }.minByOrNull { it.first }
    }

    /** Feb 29 birthdays fall on Feb 28 in other years. */
    private fun dateIn(year: Int, month: Int, day: Int): Long =
        if (month == 2 && day == 29 && !isLeap(year)) LocalClock.dayOf(year, 2, 28) else LocalClock.dayOf(year, month, day)
    private fun sameDate(m: Int, d: Int, bm: Int, bd: Int, year: Int): Boolean =
        (m == bm && d == bd) || (bm == 2 && bd == 29 && !isLeap(year) && m == 2 && d == 28)
    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}
