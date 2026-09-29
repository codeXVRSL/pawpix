package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

data class Reminder(
    /** Stable positive id, so rescheduling replaces rather than duplicates. */
    val id: Int,
    val taskId: String,
    val petId: String,
    val atMs: Long,
    val title: String,
    val body: String,
    val exact: Boolean,
    /** Show a "Done" button on the notification. Off for heads-ups ("due in 3 days"). */
    val quickDone: Boolean = true,
    /** Every task this notification is about (several when bundled); its Done completes them all. */
    val taskIds: List<String> = listOf(taskId),
    /** The planned time each of [taskIds] is for, so a Done can tell whether it's already covered. */
    val slots: List<Long> = listOf(atMs),
) {
    /** What a notification's Done refers to, as "task@slot" pairs (compact for notification extras). */
    val refs: List<ReminderRef> get() = taskIds.mapIndexed { i, id -> ReminderRef(id, slots.getOrNull(i)) }
}

/** One task a notification is about, and the planned time it was for. */
data class ReminderRef(val taskId: String, val slotAt: Long?) {
    fun encode() = if (slotAt == null) taskId else "$taskId@$slotAt"

    companion object {
        fun encodeAll(refs: List<ReminderRef>) = refs.joinToString(",") { it.encode() }
        /** Reads "a@123,b@456" (or bare task ids from older notifications). */
        fun decodeAll(text: String): List<ReminderRef> = text.split(',').mapNotNull { part ->
            val id = part.substringBefore('@').trim()
            if (id.isEmpty()) null else ReminderRef(id, part.substringAfter('@', "").toLongOrNull())
        }
    }
}

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

    /** Health care: a heads-up this long before it's due, and a follow-up this long after if not done. */
    const val HEALTH_HEADS_UP_MS = 3 * DAY_MS
    const val HEALTH_FOLLOW_UP_MS = 3 * DAY_MS
    /** Of [MAX_PENDING], at most this many are health reminders (they can be months ahead). */
    const val MAX_HEALTH = 20

    fun plan(state: AppState, nowMs: Long, clock: LocalClock, horizonMs: Long = HORIZON_MS): List<Reminder> {
        if (!state.settings.remindersEnabled) return emptyList()
        val out = ArrayList<Reminder>()
        val health = ArrayList<Reminder>()
        // While someone else is looking after the pets, reminders stay quiet; plan from their return,
        // so reminders are ready even if the app isn't opened in between.
        val quietUntil = state.settings.awayUntilMs
        @Suppress("NAME_SHADOWING") val nowMs = maxOf(nowMs, quietUntil)
        val end = nowMs + horizonMs
        for (task in state.tasks) {
            if (!task.remindersOn) continue
            val pet = state.pet(task.petId) ?: continue
            val slots = AdaptiveTiming.effectiveSlots(task, state.completions, nowMs, clock)
            if (slots.isEmpty()) continue
            val status = CareEngine.status(task, state.completions, nowMs, clock, slots)
            if (task.kind.health) {
                health += healthReminders(task, pet, status, nowMs, clock)
                continue
            }
            val n = task.everyDays.coerceAtLeast(1)

            // Current cycle: every slot at or after the first open one.
            for (i in status.done until status.slotTimes.size) {
                val at = status.slotTimes[i]
                if (at > nowMs && at <= end) out += reminder(task, pet, at, false)
                if (task.kind == TaskKind.MEDS) {
                    val nudge = at + MEDS_NUDGE_MS
                    if (nudge > nowMs && nudge <= end) out += reminder(task, pet, nudge, true, slotAt = at)
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
        val keptHealth = bundle(health.filter { it.atMs >= quietUntil }, state).sortedBy { it.atMs }.take(MAX_HEALTH)
        val daily = bundleDaily(out.filter { it.atMs >= quietUntil }, state, clock).sortedBy { it.atMs }.take(MAX_PENDING - keptHealth.size)
        return (daily + keptHealth).sortedBy { it.atMs }
    }

    /**
     * Health care can be months away, beyond the daily horizon, so it gets its own few reminders:
     * a heads-up a few days before, one on the day, and a follow-up if it's still not done.
     */
    private fun healthReminders(task: CareTask, pet: Pet, status: TaskStatus, nowMs: Long, clock: LocalClock): List<Reminder> {
        // Nothing recorded yet: the date is a guess, so no reminders until the owner records it.
        if (!status.known) return emptyList()
        val due = status.slotTimes.firstOrNull() ?: return emptyList()
        val shown = trName(task.title)
        val what = shown.lowercase()
        val title = "${task.kind.emoji} $shown · ${pet.name}"
        val list = listOf(
            Reminder(stableId(task.id, due - HEALTH_HEADS_UP_MS, false), task.id, pet.id, due - HEALTH_HEADS_UP_MS,
                title, tr("{0}'s {1} is due in 3 days. A good time to book the vet.", pet.name, what), false, quickDone = false, slots = listOf(due)),
            Reminder(stableId(task.id, due, false), task.id, pet.id, due,
                title, tr("{0}'s {1} is due today.", pet.name, what), task.exactAlarm, slots = listOf(due)),
            Reminder(stableId(task.id, due + HEALTH_FOLLOW_UP_MS, true), task.id, pet.id, due + HEALTH_FOLLOW_UP_MS,
                title, tr("{0}'s {1} is still due. Tap Done in PawPixel once it's given.", pet.name, what), false, slots = listOf(due)),
        )
        return list.filter { it.atMs > nowMs }
    }

    /** Reminders this close together become one notification. */
    const val BUNDLE_WINDOW_MS = 20 * MINUTE_MS

    /**
     * Fewer, calmer notifications: everyday care due within [BUNDLE_WINDOW_MS] of each other (breakfast
     * and fresh water, or feeding two pets) comes as one, "Mochi: feed and fresh water · Kiko: feed",
     * whose Done logs them all. Medicine always keeps its own notification and follow-up.
     */
    private fun bundleDaily(reminders: List<Reminder>, state: AppState, clock: LocalClock): List<Reminder> {
        val (meds, rest) = reminders.partition { state.task(it.taskId)?.kind == TaskKind.MEDS }
        val out = ArrayList<Reminder>(meds)
        var group = ArrayList<Reminder>()
        fun flush() {
            if (group.size == 1) out += group[0]
            if (group.size > 1) out += merged(group, state)
            group = ArrayList()
        }
        for (r in rest.sortedBy { it.atMs }) {
            // Never across midnight: a Done at 11:56 pm shouldn't log tomorrow's 12:10 am feed today.
            if (group.isNotEmpty() && (r.atMs - group[0].atMs > BUNDLE_WINDOW_MS || clock.dayIndex(r.atMs) != clock.dayIndex(group[0].atMs))) flush()
            group += r
        }
        flush()
        return out
    }

    private fun merged(group: List<Reminder>, state: AppState): Reminder {
        val byPet = group.groupBy { it.petId }
        val parts = byPet.map { (petId, rs) ->
            val name = state.pet(petId)?.name ?: tr("Your pet")
            name + ": " + joinNames(rs.mapNotNull { state.task(it.taskId)?.title?.let(::trName)?.lowercase() }.distinct())
        }
        val pets = byPet.keys.mapNotNull { state.pet(it)?.name }
        val first = group[0]
        val unique = group.distinctBy { it.taskId }
        val ids = unique.map { it.taskId }
        return first.copy(
            slots = unique.map { it.slots.first() },
            id = stableId(ids.joinToString(","), first.atMs, false),
            title = "🐾 " + tr("Care time · {0}", joinNames(pets)),
            body = tr("{0}. Tap Done when it's all done.", parts.joinToString(" · ")),
            exact = group.any { it.exact },
            taskIds = ids,
        )
    }

    private fun joinNames(names: List<String>): String =
        if (names.size <= 2) names.joinToString(tr(" and ")) else names.dropLast(1).joinToString(", ") + tr(" and ") + names.last()

    /**
     * Several health items for the same pet at the same moment (deworming and tick & flea due the
     * same day) become one notification: "Mochi's deworming and tick & flea prevention are due today."
     */
    private fun bundle(reminders: List<Reminder>, state: AppState): List<Reminder> =
        reminders.groupBy { it.petId to it.atMs }.values.map { group ->
            if (group.size == 1) return@map group[0]
            val first = group[0]
            val pet = state.pet(first.petId)?.name ?: tr("Your pet")
            val names = group.mapNotNull { state.task(it.taskId)?.title?.let(::trName)?.lowercase() }
            val list = joinNames(names)
            val body = when (first.slots.firstOrNull()?.let { first.atMs - it } ?: 0L) {
                -HEALTH_HEADS_UP_MS -> tr("{0}'s {1} are due in 3 days. A good time to book the vet.", pet, list)
                HEALTH_FOLLOW_UP_MS -> tr("{0}'s {1} are still due. Tap Done in PawPixel once they're given.", pet, list)
                else -> tr("{0}'s {1} are due today.", pet, list)
            }
            first.copy(title = "🩺 " + tr("Health care · {0}", pet), body = body, quickDone = false,
                id = stableId(group.joinToString { it.taskId }, first.atMs, false))
        }

    private fun reminder(task: CareTask, pet: Pet, at: Long, nudge: Boolean, slotAt: Long = at): Reminder {
        val name = trName(task.title)
        val title = "${task.kind.emoji} $name · ${pet.name}"
        val body = when {
            nudge -> tr("{0} still needs {1}.", pet.name, name.lowercase())
            task.kind == TaskKind.FEED -> tr("{0} is getting hungry. Tap Done after feeding.", pet.name)
            task.kind == TaskKind.WALK -> tr("{0} is ready for a walk!", pet.name)
            task.kind == TaskKind.MEDS -> tr("Time for {0}'s {1}.", pet.name, name.lowercase())
            else -> tr("Time to {0} for {1}.", name.lowercase(), pet.name)
        }
        return Reminder(stableId(task.id, at, nudge), task.id, pet.id, at, title, body, task.exactAlarm, slots = listOf(slotAt))
    }

    fun stableId(taskId: String, at: Long, nudge: Boolean): Int {
        var h = 17
        for (c in taskId) h = h * 31 + c.code
        h = h * 31 + (at / MINUTE_MS).hashCode()
        h = h * 31 + if (nudge) 1 else 0
        return h and 0x7fffffff
    }
}
