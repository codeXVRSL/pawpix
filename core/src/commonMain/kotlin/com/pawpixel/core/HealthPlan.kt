package com.pawpixel.core

import com.pawpixel.i18n.tr

/**
 * A puppy's or kitten's schedule for one health item, counted from its birthday. Due dates come from
 * the birthday and the doses actually given (see [HealthPlan.due]), so a late dose moves the next
 * one, a changed birthday moves everything, and nothing is stored but the records themselves.
 */
data class HealthSchedule(
    /** Age of the first dose, in days. */
    val firstAgeDays: Int = 0,
    /** Or in calendar months (anti-rabies "from 3 months old"), when above 0. */
    val firstAgeMonths: Int = 0,
    /** A vaccine series: days between doses. 0 = no series (one first dose, then the item's own repeat). */
    val step: Int = 0,
    /** The series is complete once a dose is given at this age (days) or older. */
    val completeAt: Int = 0,
    /** How often while young, as (younger than this many days, every N days). Older: the item's own repeat. */
    val byAge: List<Pair<Int, Int>> = emptyList(),
) {
    val isSeries: Boolean get() = step > 0

    fun firstDay(birthDay: Long): Long =
        if (firstAgeMonths > 0) LocalClock.plusMonths(birthDay, firstAgeMonths) else birthDay + firstAgeDays

    /** The repeat for a dose given at [ageDays] old, outside a series. */
    fun everyAt(ageDays: Long, everyDays: Int): Int = byAge.firstOrNull { ageDays < it.first }?.second ?: everyDays
}

/**
 * The usual health care for a dog or cat, and the typical first-year schedules ("confirm with your vet").
 *
 * Adults: anti-rabies and a core-vaccine booster yearly (anti-rabies is required by RA 9482),
 * deworming every 3 months, monthly tick & flea (and heartworm for dogs), a yearly check-up.
 *
 * Puppies and kittens, from the birthday (common Philippine practice, following the WSAVA vaccination
 * guidelines https://wsava.org/global-guidelines/vaccination-guidelines/ and what the Naga City
 * Veterinary Office asks, https://www2.naga.gov.ph/2023-city-service/veterinary-services/):
 * - Dogs: 5-in-1/6-in-1 (DHPP) from 6 weeks, every 3 weeks until a dose at 16 weeks or older (6, 9, 12, 16).
 * - Cats: FVRCP at 8, 12 and 16 weeks.
 * - Both: anti-rabies at 3 months (the city vet's minimum age), then yearly; the series vaccines then yearly.
 * - Deworming every 2 weeks until 12 weeks old, monthly until 6 months, then every 3 months
 *   (from 2 weeks for puppies, 3 weeks for kittens).
 * - Tick & flea and heartworm prevention from 8 weeks (what most products are labelled for); a first
 *   check-up at 6 (dogs) or 8 (cats) weeks.
 *
 * An item follows its schedule when the pet has a birthday and the item has its usual name and kind
 * (so a plan made before the birthday was known, or on a family member's phone, follows it too).
 * Renaming an item turns it into a plain "every N days" item.
 */
object HealthPlan {
    /** Legacy: the most planned days a [CareTask.series] keeps. */
    const val MAX_DOSES = 12
    /** Younger than this (about 7 months) and the pet is on its first-year plan. */
    const val YOUNG_DAYS = 7 * 30
    /** A series dose that would land less than this many days before [HealthSchedule.completeAt] waits for it. */
    const val SNAP_DAYS = 7
    private const val W = 7

    val DHPP = HealthSchedule(firstAgeDays = 6 * W, step = 3 * W, completeAt = 16 * W)
    val FVRCP = HealthSchedule(firstAgeDays = 8 * W, step = 4 * W, completeAt = 16 * W)
    val RABIES = HealthSchedule(firstAgeMonths = 3)
    private val WORMS = listOf(12 * W to 2 * W, 26 * W to 30)
    val DEWORM_DOG = HealthSchedule(firstAgeDays = 2 * W, byAge = WORMS)
    val DEWORM_CAT = HealthSchedule(firstAgeDays = 3 * W, byAge = WORMS)
    val FROM_8_WEEKS = HealthSchedule(firstAgeDays = 8 * W)
    val FIRST_CHECKUP_DOG = HealthSchedule(firstAgeDays = 6 * W)

    data class Item(val kind: TaskKind, val title: String, val everyDays: Int, val schedule: HealthSchedule? = null)

    fun items(species: Species): List<Item> = when (species) {
        Species.DOG -> listOf(
            Item(TaskKind.VACCINE, "Anti-rabies shot", 365, RABIES),
            Item(TaskKind.VACCINE, "5-in-1 vaccine", 365, DHPP),
            Item(TaskKind.DEWORM, "Deworming", 90, DEWORM_DOG),
            Item(TaskKind.FLEA_TICK, "Tick & flea prevention", 30, FROM_8_WEEKS),
            Item(TaskKind.FLEA_TICK, "Heartworm prevention", 30, FROM_8_WEEKS),
            Item(TaskKind.VET, "Vet check-up", 365, FIRST_CHECKUP_DOG),
        )
        Species.CAT -> listOf(
            Item(TaskKind.VACCINE, "Anti-rabies shot", 365, RABIES),
            Item(TaskKind.VACCINE, "FVRCP vaccine", 365, FVRCP),
            Item(TaskKind.DEWORM, "Deworming", 90, DEWORM_CAT),
            Item(TaskKind.FLEA_TICK, "Tick & flea prevention", 30, FROM_8_WEEKS),
            Item(TaskKind.VET, "Vet check-up", 365, FROM_8_WEEKS),
        )
        Species.OTHER -> listOf(Item(TaskKind.VET, "Vet check-up", 365))
    }

    fun isYoung(birthDay: Long?, today: Long): Boolean = birthDay != null && today - birthDay in 0 until YOUNG_DAYS

    /** The first-year schedule [task] follows, or null (no birthday, or not one of the usual items). */
    fun scheduleFor(pet: Pet?, task: CareTask): HealthSchedule? {
        if (pet?.birthDay == null || !task.kind.health) return null
        val title = task.title.trim()
        return items(pet.species).firstOrNull { it.kind == task.kind && it.title.equals(title, ignoreCase = true) }?.schedule
    }

    /** When a scheduled item is next due. */
    data class Due(
        val day: Long,
        /** False when it's only a guess: nothing recorded, and the pet was already past the first dose's age. */
        val known: Boolean,
        /** In an unfinished vaccine series: this is dose [dose] of [doses]. */
        val dose: Int? = null,
        val doses: Int? = null,
    )

    /**
     * The next due day for an item on [s], for a pet born on [birthDay], from the days doses were
     * [given] (several on one day count once). [addedDay] is when the item was added: a first dose
     * whose age had already passed is due from then.
     */
    fun due(s: HealthSchedule, birthDay: Long, given: List<Long>, everyDays: Int, addedDay: Long): Due {
        val days = given.distinct().sorted()
        val first = s.firstDay(birthDay)
        val complete = s.isSeries && days.any { it - birthDay >= s.completeAt }
        val every = everyDays.coerceAtLeast(1)
        val day = when {
            days.isEmpty() -> maxOf(first, addedDay)
            s.isSeries && !complete -> nextInSeries(s, birthDay, days.last())
            else -> days.last() + s.everyAt(days.last() - birthDay, every)
        }
        val known = days.isNotEmpty() || first >= addedDay
        if (!s.isSeries || complete) return Due(day, known)
        // Doses still to go: this one, and every 'step' after it until one at completeAt or older.
        var left = 1
        var d = day
        while (d - birthDay < s.completeAt) { d = nextInSeries(s, birthDay, d); left++ }
        return Due(day, known, days.size + 1, days.size + left)
    }

    private fun nextInSeries(s: HealthSchedule, birthDay: Long, last: Long): Long {
        val next = last + s.step
        val end = birthDay + s.completeAt
        return if (next < end && next + SNAP_DAYS >= end) end else next
    }

    /** Dose numbers for records on these days (oldest first): 1, 2, 3 through the series, then null (boosters). */
    fun doseNumbers(s: HealthSchedule, birthDay: Long, days: List<Long>): List<Int?> {
        if (!s.isSeries) return days.map { null }
        var done = false
        return days.mapIndexed { i, d ->
            if (done) null else (i + 1).also { if (d - birthDay >= s.completeAt) done = true }
        }
    }

    /**
     * Adds the usual items the pet doesn't have yet (matched by name). Each is due now, or for a
     * puppy or kitten on its first dose's day; PawPixel doesn't know when anything was last done,
     * so the owner records it with "When was it done?".
     */
    fun addTo(state: AppState, pet: Pet, nowMs: Long, clock: LocalClock, newId: () -> String = { Ids.newId() }): AppState {
        val today = clock.dayIndex(nowMs)
        val have = state.tasksFor(pet.id).map { it.title.trim().lowercase() }.toSet()
        return items(pet.species).filter { it.title.lowercase() !in have }.fold(state) { acc, item ->
            val base = StateOps.defaultTask(pet, item.kind, today, newId(), nowMs)
            StateOps.upsertTask(acc, base.copy(title = item.title, everyDays = item.everyDays))
        }
    }

    enum class AgeUnit { WEEKS, MONTHS, YEARS }

    /** A birthday from "about [amount] weeks/months/years old" on [today]. */
    fun birthDayFromAge(today: Long, amount: Int, unit: AgeUnit): Long = when (unit) {
        AgeUnit.WEEKS -> today - 7L * amount
        AgeUnit.MONTHS -> LocalClock.plusMonths(today, -amount)
        AgeUnit.YEARS -> LocalClock.plusMonths(today, -12 * amount)
    }

    /** "8 weeks old", "5 months old", "2 years old". */
    fun ageLabel(birthDay: Long, today: Long): String {
        val days = today - birthDay
        return when {
            days < 0 -> tr("Not born yet")
            days < 14 -> if (days == 1L) tr("1 day old") else tr("{0} days old", days)
            days < 16 * 7 -> tr("{0} weeks old", days / 7)
            else -> {
                val months = LocalClock.monthsBetween(birthDay, today)
                when {
                    months < 24 -> if (months == 1) tr("1 month old") else tr("{0} months old", months)
                    else -> tr("{0} years old", months / 12)
                }
            }
        }
    }
}
