package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

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

    /** Outfits this pet has earned so far. */
    fun unlocked(pet: Pet): List<com.pawpixel.sprite.Accessory> =
        com.pawpixel.sprite.Accessory.entries.filter { it.unlockDays <= caredDays(pet) }

    /**
     * Puts on an outfit (null = none). Only one it has earned; the pose images are redrawn, so the
     * sprite version moves on.
     */
    fun wear(state: AppState, petId: String, accessory: com.pawpixel.sprite.Accessory?): AppState =
        state.copy(pets = state.pets.map { p ->
            if (p.id != petId || (accessory != null && accessory !in unlocked(p)) || p.accessory == accessory?.name) p
            else p.copy(accessory = accessory?.name, spriteVersion = p.spriteVersion + 1)
        })

    fun celebrate(state: AppState, petId: String, milestone: Int): AppState =
        state.copy(pets = state.pets.map { if (it.id == petId) it.copy(milestoneSeen = maxOf(it.milestoneSeen, milestone)) else it })

    fun title(days: Int): String = when (days) {
        365 -> tr("A whole year of care")
        730 -> tr("Two years of care")
        else -> tr("{0} days of care", days)
    }
}

/** Weight over time, for the pet page. Shown in kg or lb (see [Units]); stored in grams. */
object WeightTrend {
    /** Always kilograms (backups, tests). What the owner sees goes through [Units.weight]. */
    fun kg(grams: Int): String {
        val tenths = (grams + 50) / 100
        return "${tenths / 10}.${tenths % 10} kg"
    }

    const val STEP_G = 100
    const val MAX_G = 200_000

    /** Parses kilograms as typed ("4.2", "4,2", "4.2 kg") to grams in 0.1 kg steps, whatever unit the owner has chosen (backups, tests). */
    fun parseKg(text: String): Int? {
        val t = text.trim().lowercase().removeSuffix("kg").trim()
        if (t.toDoubleOrNull() == null && t.replace(',', '.').toDoubleOrNull() == null) return null
        return Units.parseWeight("$t kg")?.takeIf { it >= STEP_G }
    }

    /** The weight in kilograms as the owner would type it: "4.2". */
    fun kgInput(grams: Int): String = kg(grams).removeSuffix(" kg")

    /** A chart's weight axis: (bottom, top, step) in grams, on round steps, at least two, with room around the values. */
    fun axis(grams: List<Int>): Triple<Int, Int, Int> {
        if (grams.isEmpty()) return Triple(0, 1000, 500)
        val lo = grams.min(); val hi = grams.max()
        val step = AXIS_STEPS.firstOrNull { (hi - lo) / it < 3 } ?: AXIS_STEPS.last()
        var bottom = lo / step * step
        var top = (hi + step - 1) / step * step
        // A little room, so no point sits on the chart's edge.
        if (lo == bottom && bottom - step >= 0) bottom -= step
        if (hi == top) top += step
        while (top - bottom < 2 * step) {
            if (bottom - step >= 0 && lo - bottom < top - hi) bottom -= step else top += step
        }
        return Triple(bottom, top, step)
    }

    private val AXIS_STEPS = listOf(100, 200, 500, 1000, 2000, 5000, 10_000, 20_000, 50_000)

    /** "+0.3 kg since Aug 30, 2026" compared with the previous weigh-in. */
    fun change(weights: List<Weight>): String? {
        if (weights.size < 2) return null
        val last = weights[weights.size - 1]; val prev = weights[weights.size - 2]
        val d = last.grams - prev.grams
        if (kotlin.math.abs(d) < 50) return tr("About the same as {0}", LocalClock.shortDate(prev.day))
        val sign = if (d > 0) "+" else "−"
        return tr("{0} since {1}", sign + Units.weight(kotlin.math.abs(d)), LocalClock.shortDate(prev.day))
    }
}
