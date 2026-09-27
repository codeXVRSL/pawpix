package com.pawpixel.core

data class Reminder(
    /** Stable positive id, so rescheduling replaces rather than duplicates. */
    val id: Int,
    val taskId: String,
    val petId: String,
    val atMs: Long,
    val title: String,
    val body: String,
    val exact: Boolean,
)

/**
 * Plans local notifications for open care slots. Platforms cancel everything and schedule this
 * list after every change, so reminders for tasks already done simply disappear.
 *
 * iOS allows 64 pending local notifications per app, so the list is capped at [MAX_PENDING].
 */
object ReminderPlanner {
    const val MAX_PENDING = 60
    const val HORIZON_MS = 2 * DAY_MS
    /** Follow-up nudge for medicine if it is still not given. */
    const val MEDS_NUDGE_MS = 45 * MINUTE_MS

    fun plan(state: AppState, nowMs: Long, clock: LocalClock, horizonMs: Long = HORIZON_MS): List<Reminder> {
        if (!state.settings.remindersEnabled) return emptyList()
        val out = ArrayList<Reminder>()
        val end = nowMs + horizonMs
        for (task in state.tasks) {
            if (!task.remindersOn) continue
            val pet = state.pet(task.petId) ?: continue
            val slots = AdaptiveTiming.effectiveSlots(task, state.completions, nowMs, clock)
            if (slots.isEmpty()) continue
            val status = CareEngine.status(task, state.completions, nowMs, clock, slots)
            val n = task.everyDays.coerceAtLeast(1)

            // Current cycle: every slot at or after the first open one.
            for (i in status.done until status.slotTimes.size) {
                val at = status.slotTimes[i]
                if (at > nowMs && at <= end) out += reminder(task, pet, at, false)
                if (task.kind == TaskKind.MEDS) {
                    val nudge = at + MEDS_NUDGE_MS
                    if (nudge > nowMs && nudge <= end) out += reminder(task, pet, nudge, true)
                }
            }
            // Later cycles: every slot is open.
            var cycle = status.cycleStartDay + n
            while (clock.startOfDay(cycle) <= end) {
                for (m in slots) {
                    val at = clock.at(cycle, m)
                    if (at in (nowMs + 1)..end) out += reminder(task, pet, at, false)
                }
                cycle += n
            }
        }
        return out.sortedBy { it.atMs }.take(MAX_PENDING)
    }

    private fun reminder(task: CareTask, pet: Pet, at: Long, nudge: Boolean): Reminder {
        val title = "${task.kind.emoji} ${task.title} · ${pet.name}"
        val body = when {
            nudge -> "${pet.name} still needs ${task.title.lowercase()}."
            task.kind == TaskKind.FEED -> "${pet.name} is getting hungry. Tap Done after feeding."
            task.kind == TaskKind.WALK -> "${pet.name} is ready for a walk!"
            task.kind == TaskKind.MEDS -> "Time for ${pet.name}'s ${task.title.lowercase()}."
            else -> "Time to ${task.title.lowercase()} for ${pet.name}."
        }
        return Reminder(stableId(task.id, at, nudge), task.id, pet.id, at, title, body, task.exactAlarm)
    }

    fun stableId(taskId: String, at: Long, nudge: Boolean): Int {
        var h = 17
        for (c in taskId) h = h * 31 + c.code
        h = h * 31 + (at / MINUTE_MS).hashCode()
        h = h * 31 + if (nudge) 1 else 0
        return h and 0x7fffffff
    }
}
