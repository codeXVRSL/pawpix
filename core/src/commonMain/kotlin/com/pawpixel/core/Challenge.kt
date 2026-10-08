package com.pawpixel.core

import com.pawpixel.i18n.tr

/**
 * The month's challenge: one small goal everyone with the app shares, counted from what the owner
 * already logs (care days, walks, photos). Nothing to sign up for, nothing stored: progress is read
 * from the records, so the card is right on every phone in the household. Walking goals are for
 * dogs; cats and others get the care or photo goal of that month instead.
 */
data class Challenge(
    val year: Int, val month: Int, val kind: Kind, val target: Int,
) {
    enum class Kind { CARE_DAYS, WALKS, WALK_KM, PHOTOS }

    val id: String get() = "$year-${month.toString().padStart(2, '0')}"

    val title: String get() = Challenges.title(month)

    /** "Log care on 20 days in October" */
    val goal: String get() {
        val m = Challenges.monthName(month)
        return when (kind) {
            Kind.CARE_DAYS -> tr("Log care on {0} days in {1}", target, m)
            Kind.WALKS -> tr("Take {0} walks with the app in {1}", target, m)
            Kind.WALK_KM -> tr("Walk {0} together in {1}", Units.distance(target.toDouble()), m)
            Kind.PHOTOS -> tr("Add {0} photos to the album in {1}", target, m)
        }
    }

    /** "12 of 20 days", "3.4 of 30 km" */
    fun progressText(done: Int): String = when (kind) {
        Kind.CARE_DAYS -> tr("{0} of {1} days", done, target)
        Kind.WALKS -> tr("{0} of {1} walks", done, target)
        Kind.WALK_KM -> tr("{0} of {1}", Units.number(if (Units.miles) done / 1000.0 / Units.KM_PER_MILE else done / 1000.0), Units.distance(target.toDouble()))
        Kind.PHOTOS -> tr("{0} of {1} photos", done, target)
    }

    /** Progress as a fraction of the target (km goals count in metres). */
    fun fraction(done: Int): Float = (done.toFloat() / (if (kind == Kind.WALK_KM) target * 1000f else target.toFloat())).coerceIn(0f, 1f)

    fun isDone(done: Int): Boolean = fraction(done) >= 1f
}

object Challenges {
    /** This month's challenge for [species]. */
    fun forMonth(year: Int, month: Int, species: Species): Challenge {
        val (kind, target) = THEMES[(month - 1).mod(12)]
        val walking = kind == Challenge.Kind.WALKS || kind == Challenge.Kind.WALK_KM
        return if (walking && species != Species.DOG) {
            // Cats and others: alternate care days and photos across the walking months.
            if (month % 2 == 0) Challenge(year, month, Challenge.Kind.PHOTOS, 8) else Challenge(year, month, Challenge.Kind.CARE_DAYS, 20)
        } else Challenge(year, month, kind, target)
    }

    fun current(nowMs: Long, clock: LocalClock, species: Species): Challenge {
        val (y, m, _) = LocalClock.civil(clock.dayIndex(nowMs))
        return forMonth(y, m, species)
    }

    /** How far along the pet is: days, walks, metres, or photos this month. */
    fun progress(state: AppState, petId: String, challenge: Challenge, clock: LocalClock): Int {
        val first = LocalClock.dayOf(challenge.year, challenge.month, 1)
        val next = LocalClock.plusMonths(first, 1)
        fun inMonth(day: Long) = day in first until next
        return when (challenge.kind) {
            // The pet's care calendar is the one source of truth for "a day of care" (family care included, back-dated records not).
            Challenge.Kind.CARE_DAYS -> state.pet(petId)?.careDays?.count { inMonth(it) } ?: 0
            Challenge.Kind.WALKS -> state.walksFor(petId).count { inMonth(clock.dayIndex(it.startMs)) }
            Challenge.Kind.WALK_KM -> state.walksFor(petId).filter { inMonth(clock.dayIndex(it.startMs)) }.sumOf { ((it.km ?: 0.0) * 1000).toInt() }
            Challenge.Kind.PHOTOS -> state.albumFor(petId).count { inMonth(clock.dayIndex(it.atMs)) }
        }
    }

    /** Days left in the challenge's month, today included. */
    fun daysLeft(challenge: Challenge, nowMs: Long, clock: LocalClock): Int {
        val next = LocalClock.plusMonths(LocalClock.dayOf(challenge.year, challenge.month, 1), 1)
        return (next - clock.dayIndex(nowMs)).toInt().coerceAtLeast(0)
    }

    /** The year's themes: a care-habit month, a walking month and a photo month take turns. */
    private val THEMES: List<Pair<Challenge.Kind, Int>> = listOf(
        Challenge.Kind.CARE_DAYS to 20, // Jan: fresh start
        Challenge.Kind.PHOTOS to 8,     // Feb: show the love
        Challenge.Kind.WALKS to 12,     // Mar
        Challenge.Kind.CARE_DAYS to 25, // Apr
        Challenge.Kind.WALK_KM to 30,   // May
        Challenge.Kind.PHOTOS to 10,    // Jun
        Challenge.Kind.CARE_DAYS to 20, // Jul
        Challenge.Kind.WALKS to 15,     // Aug
        Challenge.Kind.WALK_KM to 40,   // Sep
        Challenge.Kind.CARE_DAYS to 25, // Oct
        Challenge.Kind.PHOTOS to 8,     // Nov
        Challenge.Kind.WALKS to 12,     // Dec
    )

    fun title(month: Int): String = when ((month - 1).mod(12) + 1) {
        1 -> tr("Fresh start"); 2 -> tr("Show the love"); 3 -> tr("Spring in your step"); 4 -> tr("Every day counts")
        5 -> tr("Miles of smiles"); 6 -> tr("Summer snapshots"); 7 -> tr("Steady as we go"); 8 -> tr("Walkies month")
        9 -> tr("Long walk home"); 10 -> tr("Spooky streak"); 11 -> tr("Thankful snaps"); else -> tr("Winter walkies")
    }

    fun monthName(month: Int): String = when ((month - 1).mod(12) + 1) {
        1 -> tr("January"); 2 -> tr("February"); 3 -> tr("March"); 4 -> tr("April"); 5 -> tr("May"); 6 -> tr("June")
        7 -> tr("July"); 8 -> tr("August"); 9 -> tr("September"); 10 -> tr("October"); 11 -> tr("November"); else -> tr("December")
    }
}
