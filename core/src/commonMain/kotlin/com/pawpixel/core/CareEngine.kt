package com.pawpixel.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS
const val MINUTES_PER_DAY = 1440

/**
 * Converts between epoch millis and the owner's local calendar. The offset function lets the
 * platform supply DST-aware offsets; tests and the Philippines (no DST) can use [fixed].
 */
class LocalClock(private val offsetAt: (Long) -> Long) {
    fun dayIndex(ms: Long): Long = (ms + offsetAt(ms)).floorDiv(DAY_MS)
    fun minuteOfDay(ms: Long): Int = ((ms + offsetAt(ms)).mod(DAY_MS) / MINUTE_MS).toInt()
    fun startOfDay(day: Long): Long {
        val guess = day * DAY_MS
        return guess - offsetAt(guess)
    }
    fun at(day: Long, minute: Int): Long = startOfDay(day) + minute * MINUTE_MS

    companion object {
        /** Year, month (1-12), day of month for a day index (days since 1970-01-01). Howard Hinnant's algorithm. */
        fun civil(day: Long): Triple<Int, Int, Int> {
            val z = day + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return Triple(y.toInt(), m.toInt(), d.toInt())
        }

        /** "2026-09-29" */
        fun isoDate(day: Long): String {
            val (y, m, d) = civil(day)
            return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        }

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** "Sep 29, 2026" (month in the owner's language). */
        fun shortDate(day: Long): String {
            val (y, m, d) = civil(day)
            return "${com.pawpixel.i18n.tr(MONTHS[m - 1])} $d, $y"
        }

        fun fixed(offsetMs: Long) = LocalClock { offsetMs }
        /** Philippine Standard Time, UTC+8, no daylight saving. */
        val MANILA = fixed(8 * HOUR_MS)
    }
}

/** Where a task stands right now within its current cycle (a day, or N days for every-N-days tasks). */
data class TaskStatus(
    val task: CareTask,
    val cycleStartDay: Long,
    /** Planned times in the current cycle (epoch ms). */
    val slotTimes: List<Long>,
    /** Planned times already passed. */
    val passed: Int,
    /** Completions in this cycle, capped at the number of slots. */
    val done: Int,
    /** Planned time of the first slot that has passed without being done, or null if nothing is overdue. */
    val overdueSinceMs: Long?,
    /** Next planned time that is still open and in the future (may be in a later cycle). */
    val nextDueMs: Long?,
    val lastDoneMs: Long?,
    /** Every completion logged this cycle, even beyond the planned slots (e.g. a new pet's first evening). */
    val logged: Int = done,
    /** Health: false when nothing was ever recorded, so the due date is a guess (see [HealthDue]). */
    val known: Boolean = true,
    /** Health: doses of the first-year series given so far. */
    val dosesGiven: Int = 0,
) {
    val isOverdue: Boolean get() = overdueSinceMs != null
    /**
     * Nothing left to do this cycle. A cycle with no slots left to count (a pet added after
     * today's last slot) is only "done" once the owner logs it, so they can still tap Done.
     */
    val allDoneThisCycle: Boolean get() = done >= slotTimes.size && (slotTimes.isNotEmpty() || logged > 0)
}

object CareEngine {

    fun cycleStart(task: CareTask, day: Long): Long {
        val n = task.everyDays.coerceAtLeast(1).toLong()
        return task.anchorDay + (day - task.anchorDay).floorDiv(n) * n
    }

    fun status(
        task: CareTask,
        completions: List<Completion>,
        nowMs: Long,
        clock: LocalClock,
        slots: List<Int> = task.slots,
    ): TaskStatus {
        if (task.kind.health) return HealthDue.status(task, completions, nowMs, clock)
        val n = task.everyDays.coerceAtLeast(1)
        val today = clock.dayIndex(nowMs)
        val cycleStart = cycleStart(task, today)
        val cycleEnd = cycleStart + n
        // Care planned before the task existed isn't owed (a pet added at 9am isn't hungry for 7am).
        val slotTimes = slots.sorted().map { clock.at(cycleStart, it) }.filter { it >= task.createdAtMs }
        val passed = slotTimes.count { it <= nowMs }
        val mine = completions.filter { it.taskId == task.id }
        val doneCount = mine.count { it.localDay in cycleStart until cycleEnd && it.atMs <= nowMs }
        val done = doneCount.coerceAtMost(slotTimes.size)
        val overdueSince = if (done < passed) slotTimes[done] else null

        var nextDue: Long? = null
        for (i in done until slotTimes.size) {
            if (slotTimes[i] > nowMs) { nextDue = slotTimes[i]; break }
        }
        if (nextDue == null && slots.isNotEmpty()) {
            nextDue = clock.at(cycleEnd, slots.min())
        }
        val lastDone = mine.filter { it.atMs <= nowMs }.maxOfOrNull { it.atMs }
        return TaskStatus(task, cycleStart, slotTimes, passed, done, overdueSince, nextDue, lastDone, logged = doneCount)
    }
}

/**
 * When health care is due. It isn't on a calendar cycle: it's due a fixed number of days after it
 * was last given, however long ago that was ("given 6 months ago" on a 3-monthly item is 3 months
 * overdue, not "due in a few days").
 *
 * A first-year series (see [CareTask.series]) is walked first: each recorded dose covers the next
 * planned one, but only if it's given no more than [EARLY_DAYS] before it and on a later day than
 * the previous dose, so a double tap or an old record doesn't skip a dose. After the series, the
 * item repeats [CareTask.everyDays] after the last dose.
 *
 * Nothing recorded and no series: due on the day the item was added, and marked unknown
 * ([TaskStatus.known] false) so the pet doesn't fret over something that may well have been done.
 */
object HealthDue {
    const val EARLY_DAYS = 7

    fun status(task: CareTask, completions: List<Completion>, nowMs: Long, clock: LocalClock): TaskStatus {
        val slot = task.slots.firstOrNull() ?: (9 * 60)
        val mine = completions.filter { it.taskId == task.id && it.atMs <= nowMs }.sortedBy { it.atMs }
        var dose = 0
        var lastDoseDay = Long.MIN_VALUE
        for (c in mine) {
            if (dose >= task.series.size) break
            if (c.localDay >= task.series[dose] - EARLY_DAYS && c.localDay > lastDoseDay) {
                dose++; lastDoseDay = c.localDay
            }
        }
        val last = mine.lastOrNull()
        val (dueDay, known) = when {
            dose < task.series.size -> task.series[dose] to true
            last != null -> last.localDay + task.everyDays.coerceAtLeast(1) to true
            else -> clock.dayIndex(task.createdAtMs) to false
        }
        val dueAt = clock.at(dueDay, slot)
        val passed = if (dueAt <= nowMs) 1 else 0
        return TaskStatus(
            task = task,
            cycleStartDay = dueDay,
            slotTimes = listOf(dueAt),
            passed = passed,
            done = 0,
            overdueSinceMs = if (passed == 1) dueAt else null,
            nextDueMs = dueAt,
            lastDoneMs = last?.atMs,
            logged = 0,
            known = known,
            dosesGiven = dose,
        )
    }

    /** Whether a quick "Done" (notification button) should count now: only when it's due within a day. */
    fun canQuickComplete(task: CareTask, completions: List<Completion>, nowMs: Long, clock: LocalClock): Boolean =
        !task.kind.health || status(task, completions, nowMs, clock).slotTimes[0] <= nowMs + DAY_MS
}

/**
 * Learns when the owner actually does each task and nudges reminder times toward it.
 *
 * Each completion is matched to its nearest planned slot. Per slot we take a recency-weighted
 * circular mean of the owner's actual times (half-life 7 days, last 28 days). Once a slot has at
 * least [MIN_SAMPLES] samples, the reminder moves to that mean, capped at ±[MAX_SHIFT_MIN] minutes
 * from what the owner originally planned and rounded to 5 minutes.
 */
object AdaptiveTiming {
    const val MIN_SAMPLES = 3
    const val MAX_SHIFT_MIN = 120
    const val WINDOW_DAYS = 28
    const val HALF_LIFE_DAYS = 7.0

    fun effectiveSlots(task: CareTask, completions: List<Completion>, nowMs: Long, clock: LocalClock): List<Int> {
        val base = task.slots.sorted()
        if (!task.adaptive || base.isEmpty()) return base
        val today = clock.dayIndex(nowMs)
        val recent = completions.filter {
            it.taskId == task.id && it.atMs <= nowMs && today - it.localDay in 0 until WINDOW_DAYS
        }
        if (recent.isEmpty()) return base

        val sumSin = DoubleArray(base.size)
        val sumCos = DoubleArray(base.size)
        val count = IntArray(base.size)
        for (c in recent) {
            val idx = base.indices.minBy { circularDistance(base[it], c.localMinute) }
            val w = 0.5.pow((today - c.localDay) / HALF_LIFE_DAYS)
            val angle = 2 * PI * c.localMinute / MINUTES_PER_DAY
            sumSin[idx] += w * sin(angle)
            sumCos[idx] += w * cos(angle)
            count[idx]++
        }
        return base.indices.map { i ->
            if (count[i] < MIN_SAMPLES) base[i] else {
                var mean = atan2(sumSin[i], sumCos[i]) / (2 * PI) * MINUTES_PER_DAY
                if (mean < 0) mean += MINUTES_PER_DAY
                val shift = signedCircularDiff(base[i], mean.roundToInt()).coerceIn(-MAX_SHIFT_MIN, MAX_SHIFT_MIN)
                val rounded = ((base[i] + shift) / 5.0).roundToInt() * 5
                rounded.mod(MINUTES_PER_DAY)
            }
        }.sorted()
    }

    fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % MINUTES_PER_DAY
        return minOf(d, MINUTES_PER_DAY - d)
    }

    /** Signed minutes to go from [from] to [to] the short way round the clock. */
    fun signedCircularDiff(from: Int, to: Int): Int {
        var d = (to - from).mod(MINUTES_PER_DAY)
        if (d > MINUTES_PER_DAY / 2) d -= MINUTES_PER_DAY
        return d
    }
}
