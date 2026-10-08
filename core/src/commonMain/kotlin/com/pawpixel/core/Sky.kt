package com.pawpixel.core

/**
 * The sky over the pet, by the time of day: peach at dawn, soft blue by day, apricot to lavender at
 * dusk, and deep indigo with stars during the owner's set night. The app's stage and both widgets
 * draw from this one table, so the home screen and the app show the same evening.
 */
object Sky {
    enum class Phase(val key: String, val top: Int, val bottom: Int, val floor: Int, val floorLine: Int, val stars: Boolean) {
        NIGHT("night", 0xFF1B2440.toInt(), 0xFF3A2B4A.toInt(), 0xFF2F2742.toInt(), 0xFF4A3F63.toInt(), stars = true),
        DAWN("dawn", 0xFFFFD1C2.toInt(), 0xFFFFF1E0.toInt(), 0xFFDCEBC9.toInt(), 0xFFB9D29B.toInt(), stars = false),
        DAY("day", 0xFFCFE6FA.toInt(), 0xFFFFF1E0.toInt(), 0xFFDCEBC9.toInt(), 0xFFB9D29B.toInt(), stars = false),
        DUSK("dusk", 0xFFFFC49A.toInt(), 0xFFD9C8EC.toInt(), 0xFFDCEBC9.toInt(), 0xFFB9D29B.toInt(), stars = false);

        /** Light text reads on the night sky; dark ink on the others. */
        val dark: Boolean get() = this == NIGHT
    }

    /**
     * Where the daytime phases change (minutes of the day). The owner's bedtime decides the night: before
     * bedtime the sky stays at dusk however late, and after wake-up it is dawn however early.
     */
    private const val DAWN_START = 5 * 60 + 30
    private const val DAY_START = 8 * 60
    private const val DUSK_START = 16 * 60 + 30
    private const val DUSK_END = 19 * 60

    fun isNight(minuteOfDay: Int, nightStart: Int, nightEnd: Int): Boolean =
        if (nightStart <= nightEnd) minuteOfDay in nightStart until nightEnd else (minuteOfDay >= nightStart || minuteOfDay < nightEnd)

    fun phase(minuteOfDay: Int, nightStart: Int, nightEnd: Int): Phase = when {
        isNight(minuteOfDay, nightStart, nightEnd) -> Phase.NIGHT
        minuteOfDay < DAWN_START -> Phase.DAWN // awake before the built-in dawn: an early riser's grey morning, not stars
        minuteOfDay < DAY_START -> Phase.DAWN
        minuteOfDay < DUSK_START -> Phase.DAY
        minuteOfDay < DUSK_END -> Phase.DUSK
        else -> Phase.DUSK // up past the built-in dusk but before bedtime: still evening
    }

    /** Minutes until the sky next changes, so a widget can redraw then (at most a day). */
    fun minutesToNextChange(minuteOfDay: Int, nightStart: Int, nightEnd: Int): Int {
        val now = phase(minuteOfDay, nightStart, nightEnd)
        for (step in 1..MINUTES_PER_DAY) if (phase((minuteOfDay + step) % MINUTES_PER_DAY, nightStart, nightEnd) != now) return step
        return MINUTES_PER_DAY
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
