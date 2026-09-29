package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

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
        val name = state.pet(petId)?.name ?: tr("Your pet")
        val n = caredDays(state, petId, nowMs, clock)
        return when {
            state.isAway(nowMs) -> tr("{0} is being looked after while you're away.", name)
            n == 0 -> tr("Tap Done when you care for {0}, and it shows here.", name)
            n == WEEK -> tr("You cared for {0} every day this week!", name)
            else -> tr("You cared for {0} on {1} of the last 7 days.", name, n)
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
        val due = item.dueMs ?: return tr("No date yet")
        val days = clock.dayIndex(due) - clock.dayIndex(nowMs)
        return when {
            days < 0 -> if (days == -1L) tr("Overdue by 1 day") else tr("Overdue by {0} days", -days)
            days == 0L -> tr("Due today")
            days < 45 -> if (days == 1L) tr("Due in 1 day") else tr("Due in {0} days", days)
            else -> ((days + 15) / 30).let { m -> if (m == 1L) tr("Due in 1 month") else tr("Due in {0} months", m) }
        }
    }
}

data class HealthItem(val task: CareTask, val dueMs: Long?, val due: Boolean, val lastDoneMs: Long?)
