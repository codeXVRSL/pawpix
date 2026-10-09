package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName
import com.pawpixel.i18n.inSentence

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
 * iOS allows 64 pending local notifications per app, so the list is capped at [MAX_PENDING], the
 * soonest first. Planning a week ahead keeps reminders coming if the app isn't opened for days
 * (iOS can't replan in the background).
 */
object ReminderPlanner {
    const val MAX_PENDING = 60
    const val HORIZON_MS = 7 * DAY_MS
    /** Follow-up nudge for medicine if it is still not given. */
    const val MEDS_NUDGE_MS = 45 * MINUTE_MS

    /** Health care: a heads-up this long before it's due, and a follow-up this long after if not done. */
    const val HEALTH_HEADS_UP_MS = 3 * DAY_MS
    const val HEALTH_FOLLOW_UP_MS = 3 * DAY_MS
    /** Of [MAX_PENDING], at most this many are health reminders (they can be months ahead). */
    const val MAX_HEALTH = 20
    /** Of [MAX_HEALTH], at most this many are notes rather than tasks (Rabies Month, noise nights, birthdays). */
    const val MAX_NOTES = 6

    fun plan(state: AppState, nowMs: Long, clock: LocalClock, horizonMs: Long = HORIZON_MS, country: String = HealthPlan.homeCountry): List<Reminder> {
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
            if (pet.remembered) continue
            val slots = AdaptiveTiming.effectiveSlots(task, state.completions, nowMs, clock)
            if (slots.isEmpty()) continue
            val status = CareEngine.status(task, state.completions, nowMs, clock, slots, pet)
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
                    // The follow-up too, so it comes even if the app isn't opened before then.
                    if (task.kind == TaskKind.MEDS && at + MEDS_NUDGE_MS in (nowMs + 1)..end) out += reminder(task, pet, at + MEDS_NUDGE_MS, true, slotAt = at)
                }
                cycle += n
            }
        }
        // Notes that aren't tasks (Rabies Month, noise nights, birthdays) wait out away mode too, and the health cap holds whatever the pet count.
        val notes = (listOfNotNull(rabiesMonth(state, nowMs, clock, country)) + noiseNights(state, nowMs, clock, country) + occasions(state, nowMs, clock))
            .filter { it.atMs >= quietUntil }.sortedBy { it.atMs }.take(MAX_NOTES)
        val keptHealth = (bundle(health.filter { it.atMs >= quietUntil }, state).sortedBy { it.atMs }.take(MAX_HEALTH - notes.size) + notes).sortedBy { it.atMs }
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
        val what = inSentence(shown)
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

    /** Local time of the yearly Rabies Awareness Month note. */
    const val RABIES_MONTH_MINUTE = 9 * 60

    /**
     * March is Rabies Awareness Month in the Philippines (Executive Order 84, 1999), when cities and
     * barangays often hold free anti-rabies drives. Owners of a dog or cat get one note a year, on
     * March 1. It isn't about a task, so it has no Done button ([Reminder.taskIds] is empty). Like
     * the other reminders it's planned again whenever PawPixel runs, so only within [RABIES_MONTH_LEAD_MS].
     */
    fun rabiesMonth(state: AppState, nowMs: Long, clock: LocalClock, country: String = HealthPlan.homeCountry): Reminder? {
        if (!HealthPlan.isPhilippines(country)) return null // a Philippine month
        val pet = state.pets.firstOrNull { (it.species == Species.DOG || it.species == Species.CAT) && !it.remembered } ?: return null // the shots are for dogs and cats
        val (year, _, _) = LocalClock.civil(clock.dayIndex(nowMs))
        val at = listOf(year, year + 1).map { clock.at(LocalClock.dayOf(it, 3, 1), RABIES_MONTH_MINUTE) }.first { it > nowMs }
        if (at - nowMs > RABIES_MONTH_LEAD_MS) return null
        return Reminder(
            stableId(RABIES_MONTH_ID, at, false), taskId = "", petId = pet.id, atMs = at,
            title = "💉 " + tr("Rabies Awareness Month"),
            body = tr("Free anti-rabies shots are often offered in March — check your barangay."),
            exact = false, quickDone = false, taskIds = emptyList(), slots = emptyList(),
        )
    }

    private const val RABIES_MONTH_ID = "rabies-month"
    const val RABIES_MONTH_LEAD_MS = 45 * DAY_MS

    /**
     * Noise nights: more pets run away on fireworks nights than on any other (reports jump 30 to 60
     * percent around July 4 in the US; PAWS counts the same spike every New Year's Eve in the
     * Philippines). Two notes per night for owners of a living pet: the day before at 9:00 (check
     * the collar and tag, plan a quiet spot) and the evening itself at 17:00 (keep them inside).
     * New Year's Eve is everyone's; the others follow the phone's country. No Done button.
     */
    fun noiseNights(state: AppState, nowMs: Long, clock: LocalClock, country: String = HealthPlan.homeCountry): List<Reminder> {
        val pet = state.pets.firstOrNull { !it.remembered } ?: return emptyList()
        val (year, _, _) = LocalClock.civil(clock.dayIndex(nowMs))
        val out = ArrayList<Reminder>()
        for ((key, month, day) in noiseNightDates(country)) for (y in listOf(year, year + 1)) {
            val night = LocalClock.dayOf(y, month, day)
            val eve = clock.at(night - 1, NOISE_EVE_MINUTE)
            val tonight = clock.at(night, NOISE_NIGHT_MINUTE)
            if (tonight <= nowMs || eve - nowMs > NOISE_LEAD_MS) continue
            if (eve > nowMs) out += Reminder(
                stableId("noise-$key-eve", eve, false), taskId = "", petId = pet.id, atMs = eve,
                title = "🎆 " + tr("Noise night tomorrow"),
                body = tr("Fireworks tomorrow night. Check {0}'s collar and tag today, and plan a quiet spot inside.", pet.name),
                exact = false, quickDone = false, taskIds = emptyList(), slots = emptyList(),
            )
            out += Reminder(
                stableId("noise-$key", tonight, false), taskId = "", petId = pet.id, atMs = tonight,
                title = "🎆 " + tr("Noise night tonight"),
                body = tr("Keep {0} inside tonight, doors and gates closed. More pets go missing tonight than any other night.", pet.name),
                exact = false, quickDone = false, taskIds = emptyList(), slots = emptyList(),
            )
        }
        return out.sortedBy { it.atMs }.take(2)
    }

    /** Birthdays and gotcha days: one note at 9:00 on the day, for each pet with one coming up. */
    fun occasions(state: AppState, nowMs: Long, clock: LocalClock): List<Reminder> = state.pets.mapNotNull { pet ->
        val (day, occasion) = Occasions.upcoming(pet, nowMs, clock, NOISE_LEAD_MS) ?: return@mapNotNull null
        val at = clock.at(day, OCCASION_MINUTE)
        if (at <= nowMs) return@mapNotNull null
        Reminder(
            stableId("occasion-${pet.id}", at, false), taskId = "", petId = pet.id, atMs = at,
            title = "🎉 " + occasion.title,
            body = occasion.bubble + " " + tr("Open PawPixel: the room is decorated."),
            exact = false, quickDone = false, taskIds = emptyList(), slots = emptyList(),
        )
    }
    const val OCCASION_MINUTE = 9 * 60

    /** (key, month, day): New Year's Eve for everyone; the Fourth of July (US) and Bonfire Night (GB) by country. */
    fun noiseNightDates(country: String): List<Triple<String, Int, Int>> = buildList {
        add(Triple("nye", 12, 31))
        when (country.uppercase()) {
            "US" -> add(Triple("july4", 7, 4))
            "GB" -> add(Triple("bonfire", 11, 5))
        }
    }

    const val NOISE_EVE_MINUTE = 9 * 60
    const val NOISE_NIGHT_MINUTE = 17 * 60
    const val NOISE_LEAD_MS = 45 * DAY_MS

    /** Reminders this close together become one notification. */
    const val BUNDLE_WINDOW_MS = 30 * MINUTE_MS

    /**
     * At most this many everyday-care notifications a day (a bundle counts once). Medicine and health
     * care don't count and are never dropped. Past the cap, the least pressing care goes quiet (the
     * widget still shows it): feeding and walks before play and grooming.
     */
    const val MAX_DAILY_CARE = 6

    /**
     * Fewer, calmer notifications: everyday care due within [BUNDLE_WINDOW_MS] of each other (breakfast
     * and fresh water, or feeding two pets) comes as one, "Mochi: feed and fresh water · Kiko: feed",
     * whose Done logs them all. Medicine always keeps its own notification and follow-up.
     */
    private fun bundleDaily(reminders: List<Reminder>, state: AppState, clock: LocalClock): List<Reminder> {
        val (meds, rest) = reminders.partition { state.task(it.taskId)?.kind == TaskKind.MEDS }
        val out = ArrayList<Reminder>()
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
        return meds + capPerDay(out, state, clock)
    }

    private fun capPerDay(reminders: List<Reminder>, state: AppState, clock: LocalClock): List<Reminder> =
        reminders.groupBy { clock.dayIndex(it.atMs) }.values.flatMap { day ->
            if (day.size <= MAX_DAILY_CARE) day
            else day.sortedWith(compareByDescending<Reminder> { r -> r.taskIds.maxOf { id -> state.task(id)?.kind?.let(MoodEngine::weight) ?: 0.0 } }
                .thenBy { it.atMs }).take(MAX_DAILY_CARE)
        }

    private fun merged(group: List<Reminder>, state: AppState): Reminder {
        val byPet = group.groupBy { it.petId }
        val parts = byPet.map { (petId, rs) ->
            val name = state.pet(petId)?.name ?: tr("Your pet")
            name + ": " + joinNames(rs.mapNotNull { state.task(it.taskId)?.title?.let(::trName)?.let(::inSentence) }.distinct())
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
     * Only the same kind of note: one item's heads-up and another's "due today" at the same moment stay apart.
     */
    private fun bundle(reminders: List<Reminder>, state: AppState): List<Reminder> =
        reminders.groupBy { Triple(it.petId, it.atMs, it.atMs - (it.slots.firstOrNull() ?: it.atMs)) }.values.map { group ->
            if (group.size == 1) return@map group[0]
            val first = group[0]
            val pet = state.pet(first.petId)?.name ?: tr("Your pet")
            val names = group.mapNotNull { state.task(it.taskId)?.title?.let(::trName)?.let(::inSentence) }
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
            nudge -> tr("{0} still needs their {1}.", pet.name, inSentence(name))
            task.kind == TaskKind.FEED -> tr("{0} is getting hungry. Tap Done after feeding.", pet.name)
            task.kind == TaskKind.WALK -> tr("{0} is ready for a walk!", pet.name)
            task.kind == TaskKind.MEDS -> tr("Time for {0}'s {1}.", pet.name, inSentence(name))
            // Whole sentences per kind, so they read naturally in every language.
            task.kind == TaskKind.WATER -> tr("{0}'s water bowl needs a refill.", pet.name)
            task.kind == TaskKind.PLAY -> tr("{0} wants to play!", pet.name)
            task.kind == TaskKind.GROOM -> tr("Time to groom {0}.", pet.name)
            task.kind == TaskKind.LITTER -> tr("Time to clean {0}'s litter.", pet.name)
            else -> tr("Reminder for {0}: {1}.", pet.name, name)
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
