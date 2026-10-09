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
    /** Offset from UTC at [ms]. */
    fun offsetMs(ms: Long): Long = offsetAt(ms)
    fun dayIndex(ms: Long): Long = (ms + offsetAt(ms)).floorDiv(DAY_MS)
    fun minuteOfDay(ms: Long): Int = ((ms + offsetAt(ms)).mod(DAY_MS) / MINUTE_MS).toInt()
    fun startOfDay(day: Long): Long = at(day, 0)

    /**
     * The moment the local clock shows [minute] on [day]. The offset is the one in force at that
     * moment (found in two steps), so on a day the clocks change, 8:00 AM is still 8:00 AM.
     */
    fun at(day: Long, minute: Int): Long {
        val local = day * DAY_MS + minute * MINUTE_MS
        return local - offsetAt(local - offsetAt(local))
    }

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

        /** The day index of a calendar date (the inverse of [civil]). */
        fun dayOf(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

        /** The same day of the month [months] later (or earlier), or that month's last day ("Nov 30" + 3 = "Feb 28"). */
        fun plusMonths(day: Long, months: Int): Long {
            val (y, m, d) = civil(day)
            val index = y * 12 + (m - 1) + months
            val ny = index.floorDiv(12); val nm = index.mod(12) + 1
            val last = (dayOf(if (nm == 12) ny + 1 else ny, if (nm == 12) 1 else nm + 1, 1) - dayOf(ny, nm, 1)).toInt()
            return dayOf(ny, nm, minOf(d, last))
        }

        /** Whole calendar months from [from] to [to] (0 if [to] is earlier). */
        fun monthsBetween(from: Long, to: Long): Int {
            if (to < from) return 0
            val (y1, m1, _) = civil(from); val (y2, m2, _) = civil(to)
            var n = (y2 - y1) * 12 + (m2 - m1)
            if (plusMonths(from, n) > to) n--
            return n
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
            // "Sep 30, 2026"; the order is the language's own ("30 sep 2026" in Spanish and Portuguese).
            return com.pawpixel.i18n.tr("{0} {1}, {2}", com.pawpixel.i18n.tr(MONTHS[m - 1]), d, y)
        }

        fun fixed(offsetMs: Long) = LocalClock { offsetMs }

        /** "7:00 AM" for a minute of the day (the app and the vet summary share it). */
        fun formatMinute(minute: Int): String {
            val h = minute / 60; val m = minute % 60
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "$h12:${m.toString().padStart(2, '0')} " + (if (h < 12) com.pawpixel.i18n.tr("AM") else com.pawpixel.i18n.tr("PM"))
        }
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
    /** Health, in an unfinished first-year series: the next one is dose [dose] of [doses]. */
    val dose: Int? = null,
    val doses: Int? = null,
    /** Health: the due date follows the pet's age (see [HealthPlan]). */
    val scheduled: Boolean = false,
) {
    /**
     * How full the task's meter is, 0..1: empty when overdue, full when all done this cycle, otherwise the
     * share of the cycle still ahead (never under a tenth, so the meter reads as alive). The HUD tiles and
     * the Care panel rows draw the same number.
     */
    fun meterFraction(nowMs: Long): Float {
        val t = task
        val interval = if (t.everyDays > 1) t.everyDays * DAY_MS else DAY_MS / t.slots.size.coerceAtLeast(1)
        val next = nextDueMs
        return when {
            isOverdue -> 0f
            allDoneThisCycle -> 1f
            next != null -> ((next - nowMs).toFloat() / interval).coerceIn(0.1f, 1f)
            else -> 1f
        }
    }

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
        /** The task's pet: a puppy's or kitten's health items follow its birthday (see [HealthPlan]). */
        pet: Pet? = null,
    ): TaskStatus {
        if (task.kind.health) return HealthDue.status(task, completions, nowMs, clock, pet)
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
 * A puppy's or kitten's usual items follow its birthday instead (see [HealthPlan.due]): the first dose
 * at the right age, a vaccine series dose by dose, deworming more often while young.
 *
 * Older saves may have a planned series of days ([CareTask.series]): it's walked first, each
 * recorded dose covering the next planned one if given no more than [EARLY_DAYS] before it and on a
 * later day than the previous dose. After it, the item repeats [CareTask.everyDays] after the last dose.
 *
 * Nothing recorded (and no planned first dose): due on the day the item was added, and marked unknown
 * ([TaskStatus.known] false) so the pet doesn't fret over something that may well have been done.
 */
object HealthDue {
    const val EARLY_DAYS = 7

    fun status(task: CareTask, completions: List<Completion>, nowMs: Long, clock: LocalClock, pet: Pet? = null): TaskStatus {
        val slot = task.slots.firstOrNull() ?: (9 * 60)
        val mine = completions.filter { it.taskId == task.id && it.atMs <= nowMs }.sortedBy { it.atMs }
        val last = mine.lastOrNull()
        val schedule = HealthPlan.scheduleFor(pet, task)
        val due = if (schedule != null) {
            HealthPlan.due(schedule, pet!!.birthDay!!, mine.map { it.localDay }, task.everyDays, clock.dayIndex(task.createdAtMs))
        } else legacyDue(task, mine, clock)
        val dueAt = clock.at(due.day, slot)
        val passed = if (dueAt <= nowMs) 1 else 0
        return TaskStatus(
            task = task,
            cycleStartDay = due.day,
            slotTimes = listOf(dueAt),
            passed = passed,
            done = 0,
            overdueSinceMs = if (passed == 1) dueAt else null,
            nextDueMs = dueAt,
            lastDoneMs = last?.atMs,
            logged = 0,
            known = due.known,
            dosesGiven = due.dose?.minus(1) ?: 0,
            dose = due.dose?.takeIf { (due.doses ?: 0) > 1 },
            doses = due.doses?.takeIf { it > 1 },
            scheduled = schedule != null,
        )
    }

    private fun legacyDue(task: CareTask, mine: List<Completion>, clock: LocalClock): HealthPlan.Due {
        var dose = 0
        var lastDoseDay = Long.MIN_VALUE
        for (c in mine) {
            if (dose >= task.series.size) break
            if (c.localDay >= task.series[dose] - EARLY_DAYS && c.localDay > lastDoseDay) {
                dose++; lastDoseDay = c.localDay
            }
        }
        val last = mine.lastOrNull()
        return when {
            dose < task.series.size -> HealthPlan.Due(task.series[dose], true, dose + 1, task.series.size)
            last != null -> HealthPlan.Due(last.localDay + task.everyDays.coerceAtLeast(1), true)
            else -> HealthPlan.Due(clock.dayIndex(task.createdAtMs), false)
        }
    }

    /** Whether a quick "Done" (notification button) should count now: only when it's due within a day. */
    fun canQuickComplete(task: CareTask, completions: List<Completion>, nowMs: Long, clock: LocalClock, pet: Pet? = null): Boolean =
        !task.kind.health || status(task, completions, nowMs, clock, pet).slotTimes[0] <= nowMs + DAY_MS
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
