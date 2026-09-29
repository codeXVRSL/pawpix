package com.pawpixel.core

/**
 * Progress that never goes backwards: days of care add up to milestones (7, 30, 100 days...), each
 * celebrated once with a card to share. Missing a day never takes anything away.
 */
object Milestones {
    val DAYS = listOf(7, 30, 50, 100, 200, 365, 500, 730, 1000)

    fun caredDays(pet: Pet): Int = pet.careDays.size

    /** The milestone reached but not yet celebrated, if any (the highest one, after a long gap). */
    fun toCelebrate(pet: Pet): Int? = DAYS.lastOrNull { it <= caredDays(pet) }?.takeIf { it > pet.milestoneSeen }

    /** The next one to reach, and how many days to go. */
    fun next(pet: Pet): Pair<Int, Int>? = DAYS.firstOrNull { it > caredDays(pet) }?.let { it to it - caredDays(pet) }

    fun celebrate(state: AppState, petId: String, milestone: Int): AppState =
        state.copy(pets = state.pets.map { if (it.id == petId) it.copy(milestoneSeen = maxOf(it.milestoneSeen, milestone)) else it })

    fun title(days: Int): String = when (days) {
        365 -> "A whole year of care"
        730 -> "Two years of care"
        else -> "$days days of care"
    }
}

/** Weight over time, for the pet page. */
object WeightTrend {
    fun kg(grams: Int): String {
        val tenths = (grams + 50) / 100
        return "${tenths / 10}.${tenths % 10} kg"
    }

    /** Parses what an owner types: "4.2", "4,2", "4.25 kg". Null if it isn't a sensible pet weight. */
    fun parseKg(text: String): Int? {
        val t = text.trim().lowercase().removeSuffix("kg").trim().replace(',', '.')
        val v = t.toDoubleOrNull() ?: return null
        val g = kotlin.math.round(v * 1000).toInt()
        return g.takeIf { it in 50..200_000 }
    }

    /** "+0.3 kg since Aug 30, 2026" compared with the previous weigh-in. */
    fun change(weights: List<Weight>): String? {
        if (weights.size < 2) return null
        val last = weights[weights.size - 1]; val prev = weights[weights.size - 2]
        val d = last.grams - prev.grams
        val sign = if (d > 0) "+" else if (d < 0) "−" else "±"
        return "$sign${kg(kotlin.math.abs(d)).removeSuffix(" kg")} kg since ${LocalClock.shortDate(prev.day)}"
    }
}
