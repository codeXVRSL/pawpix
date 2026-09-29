package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

/**
 * The usual health care for a pet, as care tasks, from its species and (if known) its birthday.
 *
 * Adult defaults follow common Philippine practice: anti-rabies and a core-vaccine booster yearly
 * (anti-rabies is required by RA 9482), deworming every 3 months, monthly tick & flea (and
 * heartworm for dogs), and a yearly check-up. A puppy or kitten also gets its first-year series,
 * from the schedules Philippine pet-food makers and the Naga City Veterinary Office publish:
 *
 * - Dogs: 5-in-1 at 6, 9, 12 and 16 weeks. Deworming every 2 weeks from 2 to 12 weeks, then at 4, 5 and 6 months.
 * - Cats: FVRCP at 8, 12 and 16 weeks. Deworming every 2 weeks from 3 to 11 weeks, then at 4, 5 and 6 months.
 * - Both: anti-rabies at 13 weeks (the city vet vaccinates from 3 months), then yearly. Tick, flea and
 *   heartworm prevention start at 8 weeks (what most products are labelled for), a first check-up at 6-8 weeks.
 *
 * Doses more than [GRACE_DAYS] in the past are left out: PawPixel can't know if they were given, and
 * the pet page says to check with a vet. Every schedule is labelled "confirm with your vet".
 */
object HealthPlan {
    const val MAX_DOSES = 12
    /** A dose due up to this many days ago still shows (as due), so a just-missed one isn't lost. */
    const val GRACE_DAYS = 7
    /** Younger than this and the pet gets a first-year series. */
    const val YOUNG_DAYS = 7 * 30

    data class Item(val kind: TaskKind, val title: String, val everyDays: Int, val series: List<Long> = emptyList())

    fun isYoung(birthDay: Long?, today: Long): Boolean = birthDay != null && today - birthDay in 0 until YOUNG_DAYS

    fun items(species: Species, birthDay: Long?, today: Long): List<Item> {
        val young = isYoung(birthDay, today)
        fun doses(vararg ageDays: Int): List<Long> =
            if (!young) emptyList() else ageDays.map { birthDay!! + it }.filter { it >= today - GRACE_DAYS }
        /** Young pets: the first one at [ageDays] (most tick, flea and heartworm products are for 8 weeks and up). */
        fun start(ageDays: Int): List<Long> = if (!young) emptyList() else listOf(maxOf(birthDay!! + ageDays, today))
        val w = 7
        return when (species) {
            Species.DOG -> listOf(
                Item(TaskKind.VACCINE, "Anti-rabies shot", 365, doses(13 * w)),
                Item(TaskKind.VACCINE, "5-in-1 vaccine", 365, doses(6 * w, 9 * w, 12 * w, 16 * w)),
                Item(TaskKind.DEWORM, "Deworming", 90, doses(2 * w, 4 * w, 6 * w, 8 * w, 10 * w, 12 * w, 120, 150, 180)),
                Item(TaskKind.FLEA_TICK, "Tick & flea prevention", 30, start(8 * w)),
                Item(TaskKind.FLEA_TICK, "Heartworm prevention", 30, start(8 * w)),
                Item(TaskKind.VET, "Vet check-up", 365, start(6 * w)),
            )
            Species.CAT -> listOf(
                Item(TaskKind.VACCINE, "Anti-rabies shot", 365, doses(13 * w)),
                Item(TaskKind.VACCINE, "FVRCP vaccine", 365, doses(8 * w, 12 * w, 16 * w)),
                Item(TaskKind.DEWORM, "Deworming", 90, doses(3 * w, 5 * w, 7 * w, 9 * w, 11 * w, 120, 150, 180)),
                Item(TaskKind.FLEA_TICK, "Tick & flea prevention", 30, start(8 * w)),
                Item(TaskKind.VET, "Vet check-up", 365, start(8 * w)),
            )
            Species.OTHER -> listOf(Item(TaskKind.VET, "Vet check-up", 365))
        }
    }

    /**
     * Adds the plan's items the pet doesn't have yet (matched by name), as tasks. A series item's
     * cycle starts on its first planned dose; items without one are due now, since PawPixel doesn't
     * know when they were last done (the owner records that with "When was it done?").
     */
    fun addTo(state: AppState, pet: Pet, nowMs: Long, clock: LocalClock, newId: () -> String = { Ids.newId() }): AppState {
        val today = clock.dayIndex(nowMs)
        val have = state.tasksFor(pet.id).map { it.title.lowercase() }.toSet()
        return items(pet.species, pet.birthDay, today).filter { it.title.lowercase() !in have }.fold(state) { acc, item ->
            val base = StateOps.defaultTask(pet, item.kind, today, newId(), nowMs)
            StateOps.upsertTask(acc, base.copy(title = item.title, everyDays = item.everyDays, series = item.series))
        }
    }

    /** "8 weeks old", "5 months old", "2 years old". */
    fun ageLabel(birthDay: Long, today: Long): String {
        val days = today - birthDay
        return when {
            days < 0 -> tr("Not born yet")
            days < 14 -> tr("{0} days old", days)
            days < 16 * 7 -> tr("{0} weeks old", days / 7)
            days < 730 -> tr("{0} months old", days / 30)
            else -> tr("{0} years old", days / 365)
        }
    }
}
