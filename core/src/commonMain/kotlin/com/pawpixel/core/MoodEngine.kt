package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName
import com.pawpixel.i18n.inSentence

/** The poses a sprite can show. Each has its own pre-rendered PNG so widgets never render anything. */
enum class Mood(val key: String) {
    HAPPY("happy"),
    CONTENT("content"),
    HUNGRY("hungry"),
    RESTLESS("restless"),
    NEEDS_MEDS("meds"),
    SLEEPY("sleepy"),
    SAD("sad");

    companion object {
        fun fromKey(key: String): Mood = entries.firstOrNull { it.key == key } ?: CONTENT
    }
}

data class MoodReading(
    val mood: Mood,
    val caption: String,
    /** 0 (neglected) .. 100 (fully cared for). */
    val score: Int,
    /** The task most responsible for a bad mood, if any. */
    val urgentTaskId: String?,
)

data class MoodPoint(val atMs: Long, val mood: Mood, val caption: String)

/**
 * Turns real care (tasks done or overdue) into how the pixel pet feels.
 *
 * Every overdue task adds a penalty that grows linearly from 0 to its weight over its grace period
 * (feeding is urgent within ~2h, grooming within a day). The largest penalty picks the mood; a big
 * total makes the pet sad. Care done recently makes it happy, and at night it sleeps unless
 * something important is overdue.
 */
object MoodEngine {
    const val RECENT_CARE_MS = 90 * MINUTE_MS
    const val SAD_THRESHOLD = 1.6
    const val NEED_THRESHOLD = 0.35

    fun weight(kind: TaskKind): Double = when (kind) {
        TaskKind.FEED, TaskKind.MEDS -> 1.0
        TaskKind.WALK -> 0.8
        TaskKind.WATER -> 0.7
        TaskKind.PLAY, TaskKind.LITTER -> 0.5
        TaskKind.GROOM -> 0.3
        // Health care that's due shows as a gentle "needs care" look, never enough to make the pet sad.
        TaskKind.VACCINE, TaskKind.DEWORM, TaskKind.FLEA_TICK, TaskKind.VET -> 0.4
    }

    fun graceMinutes(kind: TaskKind): Int = when (kind) {
        TaskKind.MEDS -> 60
        TaskKind.FEED -> 120
        TaskKind.WALK, TaskKind.WATER -> 180
        TaskKind.PLAY -> 240
        TaskKind.LITTER -> 360
        TaskKind.GROOM -> 1440
        TaskKind.VACCINE, TaskKind.DEWORM, TaskKind.FLEA_TICK, TaskKind.VET -> 2 * 1440
    }

    /**
     * How much an overdue task weighs on the pet right now. Care missed while the owner was away
     * ([awayUntilMs]) doesn't count: the clock starts when they're back.
     */
    fun penalty(status: TaskStatus, nowMs: Long, awayUntilMs: Long = 0): Double {
        val since = maxOf(status.overdueSinceMs ?: return 0.0, awayUntilMs)
        val minutes = (nowMs - since).coerceAtLeast(0).toDouble() / MINUTE_MS
        val kind = status.task.kind
        return weight(kind) * (minutes / graceMinutes(kind)).coerceIn(0.0, 1.0)
    }

    fun isNight(minute: Int, settings: Settings): Boolean {
        val s = settings.nightStart
        val e = settings.nightEnd
        return if (s <= e) minute in s until e else minute >= s || minute < e
    }

    fun read(state: AppState, petId: String, nowMs: Long, clock: LocalClock): MoodReading {
        val pet = state.pet(petId)
        val name = pet?.name ?: tr("Your pet")
        if (state.isAway(nowMs)) {
            val night = isNight(clock.minuteOfDay(nowMs), state.settings)
            return if (night) MoodReading(Mood.SLEEPY, tr("{0} is sleeping", name), 100, null)
            else MoodReading(Mood.CONTENT, tr("{0} is being looked after", name), 100, null)
        }
        val tasks = state.tasksFor(petId)
        val statuses = tasks.map { t ->
            val slots = AdaptiveTiming.effectiveSlots(t, state.completions, nowMs, clock)
            CareEngine.status(t, state.completions, nowMs, clock, slots)
        }
        // Health care weighs lightly: only the most overdue item counts, and only one with a known
        // date (a record or a planned puppy/kitten dose), so a pet never gets sad over paperwork.
        val daily = statuses.filter { !it.task.kind.health }.map { it to penalty(it, nowMs, state.settings.awayUntilMs) }
        val health = statuses.filter { it.task.kind.health && it.known }
            .map { it to penalty(it, nowMs, state.settings.awayUntilMs) }.maxByOrNull { it.second }
        val penalties = daily + listOfNotNull(health)
        val total = penalties.sumOf { it.second }
        val worst = penalties.maxByOrNull { it.second }
        val score = (100 - total / 2.0 * 100).toInt().coerceIn(0, 100)
        val night = isNight(clock.minuteOfDay(nowMs), state.settings)
        val lastCare = statuses.mapNotNull { it.lastDoneMs }.maxOrNull()

        if (worst != null && total >= SAD_THRESHOLD) {
            return MoodReading(Mood.SAD, tr("{0} misses you", name), score, worst.first.task.id)
        }
        if (worst != null && worst.second >= NEED_THRESHOLD) {
            val task = worst.first.task
            val (mood, caption) = when (task.kind) {
                TaskKind.FEED -> Mood.HUNGRY to tr("{0} is hungry", name)
                TaskKind.WATER -> Mood.HUNGRY to tr("{0} is thirsty", name)
                TaskKind.WALK -> Mood.RESTLESS to tr("{0} wants a walk", name)
                TaskKind.PLAY -> Mood.RESTLESS to tr("{0} wants to play", name)
                TaskKind.LITTER -> Mood.RESTLESS to tr("The litter needs cleaning")
                TaskKind.GROOM -> Mood.RESTLESS to tr("{0} needs grooming", name)
                TaskKind.MEDS -> Mood.NEEDS_MEDS to tr("Time for {0}'s {1}", name, inSentence(trName(task.title)))
                TaskKind.VACCINE, TaskKind.DEWORM, TaskKind.FLEA_TICK, TaskKind.VET ->
                    Mood.NEEDS_MEDS to tr("{0}'s {1} is due", name, inSentence(trName(task.title)))
            }
            return MoodReading(mood, caption, score, task.id)
        }
        if (night) return MoodReading(Mood.SLEEPY, tr("{0} is sleeping", name), score, null)
        if (lastCare != null && nowMs - lastCare <= RECENT_CARE_MS) {
            return MoodReading(Mood.HAPPY, tr("{0} is happy!", name), score, null)
        }
        return MoodReading(Mood.CONTENT, tr("{0} is doing fine", name), score, null)
    }

    /**
     * Mood over the next [horizonMs], sampled every [stepMs] and compressed to change points.
     * Widgets show these without running any logic, so mood keeps decaying even if the app
     * never opens (iOS WidgetKit timeline entries, Android alarm at the next change).
     */
    fun timeline(
        state: AppState,
        petId: String,
        nowMs: Long,
        clock: LocalClock,
        horizonMs: Long = DAY_MS,
        stepMs: Long = 15 * MINUTE_MS,
    ): List<MoodPoint> {
        val points = ArrayList<MoodPoint>()
        var t = nowMs
        val end = nowMs + horizonMs
        while (t <= end) {
            val r = read(state, petId, t, clock)
            val last = points.lastOrNull()
            if (last == null || last.mood != r.mood || last.caption != r.caption) {
                points += MoodPoint(t, r.mood, r.caption)
            }
            // Align later samples to the step grid so timelines are stable between refreshes.
            t = if (t == nowMs) (nowMs / stepMs + 1) * stepMs else t + stepMs
        }
        return points
    }

    /** First time after [nowMs] when the mood or caption changes, or null within the horizon. */
    fun nextChangeMs(state: AppState, petId: String, nowMs: Long, clock: LocalClock): Long? =
        timeline(state, petId, nowMs, clock).getOrNull(1)?.atMs
}
