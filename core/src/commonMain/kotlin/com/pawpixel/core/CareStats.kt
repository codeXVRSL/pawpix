package com.pawpixel.core

/**
 * Gentle progress, not streaks. A streak resets to zero after one missed day, and people tend to
 * give up once it breaks; "cared for 6 of the last 7 days" forgives a bad day and recovers by itself.
 */
object CareStats {
    const val WEEK = 7

    /** Days in the last [days] (today included) with at least one care task logged for the pet. */
    fun caredDays(state: AppState, petId: String, nowMs: Long, clock: LocalClock, days: Int = WEEK): Int {
        val today = clock.dayIndex(nowMs)
        val taskIds = state.tasksFor(petId).map { it.id }.toSet()
        return state.completions.asSequence()
            .filter { it.taskId in taskIds && it.atMs <= nowMs && today - it.localDay in 0 until days }
            .map { it.localDay }.distinct().count()
    }

    /** One cell per day, oldest first: was anything logged that day? For a little 7-dot row. */
    fun week(state: AppState, petId: String, nowMs: Long, clock: LocalClock): List<Boolean> {
        val today = clock.dayIndex(nowMs)
        val taskIds = state.tasksFor(petId).map { it.id }.toSet()
        val cared = state.completions.filter { it.taskId in taskIds && it.atMs <= nowMs }.map { it.localDay }.toSet()
        return (WEEK - 1 downTo 0).map { (today - it) in cared }
    }

    /** A warm line for the pet screen. Never counts what was missed. */
    fun summary(state: AppState, petId: String, nowMs: Long, clock: LocalClock): String {
        val name = state.pet(petId)?.name ?: "Your pet"
        val n = caredDays(state, petId, nowMs, clock)
        return when {
            state.isAway(nowMs) -> "$name is being looked after while you're away."
            n == 0 -> "Tap Done when you care for $name, and it shows here."
            n == WEEK -> "You cared for $name every day this week!"
            else -> "You cared for $name on $n of the last 7 days."
        }
    }

    /** Health care for a pet, soonest due first: (task, due date or null if never planned, overdue?). */
    fun healthDue(state: AppState, petId: String, nowMs: Long, clock: LocalClock): List<HealthItem> =
        state.tasksFor(petId).filter { it.kind.health }.map { t ->
            val s = CareEngine.status(t, state.completions, nowMs, clock)
            val due = if (s.isOverdue) s.overdueSinceMs else if (s.allDoneThisCycle || s.slotTimes.size <= s.done) s.nextDueMs else s.slotTimes[s.done]
            HealthItem(t, due, s.isOverdue || (due != null && due <= nowMs), s.lastDoneMs)
        }.sortedBy { it.dueMs ?: Long.MAX_VALUE }

    /** "Due today", "Due in 12 days", "Due in 3 months", "Overdue by 5 days". */
    fun dueLabel(item: HealthItem, nowMs: Long, clock: LocalClock): String {
        val due = item.dueMs ?: return "No date yet"
        val days = clock.dayIndex(due) - clock.dayIndex(nowMs)
        return when {
            days < 0 -> "Overdue by ${plural(-days, "day")}"
            days == 0L -> "Due today"
            days < 45 -> "Due in ${plural(days, "day")}"
            else -> "Due in ${plural((days + 15) / 30, "month")}"
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

data class HealthItem(val task: CareTask, val dueMs: Long?, val due: Boolean, val lastDoneMs: Long?)
