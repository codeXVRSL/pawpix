package com.pawpixel.core

/** Pure state transitions. The app's repository applies these and persists the result. */
object StateOps {

    fun canAddPet(state: AppState): Boolean =
        state.settings.pro || state.pets.size < AppState.FREE_PET_LIMIT

    /** Adds a pet with the default care tasks for its species. */
    fun addPet(
        state: AppState,
        pet: Pet,
        nowMs: Long,
        clock: LocalClock,
        kinds: List<TaskKind> = TaskDefaults.kindsFor(pet.species),
        newId: () -> String = { Ids.newId() },
    ): AppState {
        val today = clock.dayIndex(nowMs)
        val tasks = kinds.map { kind -> defaultTask(pet, kind, today, newId(), nowMs) }
        return state.copy(pets = state.pets + pet, tasks = state.tasks + tasks)
    }

    fun defaultTask(pet: Pet, kind: TaskKind, today: Long, id: String, nowMs: Long) = CareTask(
        id = id,
        petId = pet.id,
        kind = kind,
        title = kind.defaultTitle,
        slots = TaskDefaults.slotsFor(kind, pet.species),
        everyDays = TaskDefaults.everyDaysFor(kind),
        anchorDay = today,
        adaptive = kind != TaskKind.MEDS,
        exactAlarm = false,
        createdAtMs = nowMs,
    )

    fun updatePet(state: AppState, pet: Pet): AppState =
        state.copy(pets = state.pets.map { if (it.id == pet.id) pet else it })

    fun removePet(state: AppState, petId: String): AppState {
        val taskIds = state.tasks.filter { it.petId == petId }.map { it.id }.toSet()
        return state.copy(
            pets = state.pets.filterNot { it.id == petId },
            tasks = state.tasks.filterNot { it.petId == petId },
            completions = state.completions.filterNot { it.taskId in taskIds },
            weights = state.weights.filterNot { it.petId == petId },
            album = state.album.filterNot { it.petId == petId },
        )
    }

    // ---- The album ----

    /** Adds a photo to the front of the pet's album (the newest is first). Over the limit, the oldest goes. */
    fun addAlbumPhoto(state: AppState, photo: AlbumPhoto): AppState {
        val clean = photo.copy(caption = cleanCaption(photo.caption))
        val others = state.album.filterNot { it.id == clean.id }
        val mine = others.filter { it.petId == clean.petId }.sortedByDescending { it.atMs }.take(AppState.MAX_ALBUM_PHOTOS_PER_PET - 1)
        return state.copy(album = others.filterNot { it.petId == clean.petId } + mine + clean)
    }

    fun setAlbumCaption(state: AppState, photoId: String, caption: String): AppState =
        state.copy(album = state.album.map { if (it.id == photoId) it.copy(caption = cleanCaption(caption)) else it })

    fun removeAlbumPhoto(state: AppState, photoId: String): AppState = state.copy(album = state.album.filterNot { it.id == photoId })

    private fun cleanCaption(caption: String) = caption.trim().take(AppState.MAX_CAPTION)

    /**
     * The pet passed away on local day [day] (null: it's back to being cared for, in case of a slip).
     * Its reminders stop at once; its tasks, records, weights and album all stay.
     */
    fun rememberPet(state: AppState, petId: String, day: Long?, nowMs: Long): AppState {
        val pet = state.pet(petId) ?: return state
        return updatePet(state, pet.copy(rememberedDay = day, editedAtMs = nowMs))
    }

    fun upsertTask(state: AppState, task: CareTask): AppState {
        val clean = task.copy(
            slots = task.slots.map { it.coerceIn(0, MINUTES_PER_DAY - 1) }.distinct().sorted()
                .take(if (task.kind.health) 1 else 4)
                .ifEmpty { listOf(8 * 60) },
            everyDays = task.everyDays.coerceIn(1, AppState.MAX_EVERY_DAYS),
            title = task.title.trim().ifEmpty { task.kind.defaultTitle },
            // Health care is counted from when it was actually done, so the routine isn't "learned".
            adaptive = task.adaptive && !task.kind.health,
            series = if (task.kind.health) task.series.distinct().sorted().take(HealthPlan.MAX_DOSES) else emptyList(),
        )
        val exists = state.tasks.any { it.id == task.id }
        return state.copy(tasks = if (exists) state.tasks.map { if (it.id == task.id) clean else it } else state.tasks + clean)
    }

    fun removeTask(state: AppState, taskId: String): AppState = state.copy(
        tasks = state.tasks.filterNot { it.id == taskId },
        completions = state.completions.filterNot { it.taskId == taskId },
    )

    /** Logs a task as done at [atMs] (now, or an earlier date for health records). */
    fun complete(
        state: AppState, taskId: String, atMs: Long, clock: LocalClock, newId: () -> String = { Ids.newId() },
        /** False for records of the past ("given a year ago"): they aren't days of care logged in PawPixel. */
        careDay: Boolean = true,
    ): AppState {
        val task = state.task(taskId) ?: return state
        val c = Completion(taskId, atMs, clock.minuteOfDay(atMs), clock.dayIndex(atMs), id = newId())
        val added = state.copy(completions = state.completions + c)
        return prune(if (careDay) markCareDays(added, task.petId, listOf(c.localDay)) else added)
    }

    /**
     * A tap on Done (in the app or on the widget). A second tap on the same task within
     * [DOUBLE_TAP_MS] is the same tap landing twice (an impatient finger, a slow phone): it must not
     * also log the evening feed.
     */
    fun completeTap(state: AppState, taskId: String, atMs: Long, clock: LocalClock): AppState {
        if (state.completions.any { it.taskId == taskId && atMs - it.atMs in 0 until DOUBLE_TAP_MS }) return state
        return complete(state, taskId, atMs, clock)
    }

    const val DOUBLE_TAP_MS = 3_000L

    /** Adds days to a pet's care calendar (see [Pet.careDays]). */
    fun markCareDays(state: AppState, petId: String, days: Collection<Long>): AppState {
        if (days.isEmpty()) return state
        return state.copy(pets = state.pets.map { p ->
            if (p.id != petId || p.careDays.containsAll(days)) p
            else p.copy(careDays = (p.careDays + days).distinct().sorted().takeLast(AppState.MAX_CARE_DAYS))
        })
    }

    /** Records a weigh-in (one per day: a second one that day replaces the first). */
    fun logWeight(state: AppState, petId: String, day: Long, grams: Int): AppState {
        if (state.pet(petId) == null || grams !in 1..200_000) return state
        val others = state.weights.filterNot { it.petId == petId && it.day == day }
        val mine = (others.filter { it.petId == petId } + Weight(petId, day, grams)).sortedBy { it.day }.takeLast(AppState.MAX_WEIGHTS_PER_PET)
        return state.copy(weights = others.filter { it.petId != petId } + mine)
    }

    fun removeWeight(state: AppState, petId: String, day: Long): AppState =
        state.copy(weights = state.weights.filterNot { it.petId == petId && it.day == day })

    /** Corrects a weigh-in: its weight, or the day it was taken (replacing any other that day). */
    fun editWeight(state: AppState, petId: String, day: Long, newDay: Long, grams: Int): AppState {
        if (state.weights.none { it.petId == petId && it.day == day } || grams !in 1..200_000) return state
        return logWeight(removeWeight(state, petId, day), petId, newDay, grams)
    }

    /**
     * A "Done" from a notification. Health care counts only if it's due within a day, so tapping an
     * old notification after already logging it in the app doesn't record it twice.
     */
    fun completeFromReminder(state: AppState, taskId: String, atMs: Long, clock: LocalClock): AppState =
        completeFromReminder(state, listOf(ReminderRef(taskId, null)), atMs, clock)

    /**
     * "Done" on a notification, for every task it's about, in one change. A task whose planned time
     * is already covered (logged in the app, or by family) is skipped, so a stale Done never uses up
     * a later slot. In a bundle, the other tasks are logged at their own planned time (if it has
     * passed), so learning the owner's routine isn't pulled toward the first task's time.
     */
    fun completeFromReminder(state: AppState, refs: List<ReminderRef>, atMs: Long, clock: LocalClock): AppState =
        refs.fold(state) { s, ref ->
            val task = s.task(ref.taskId) ?: return@fold s
            if (!HealthDue.canQuickComplete(task, s.completions, atMs, clock, s.pet(task.petId)) || isCovered(s, ref, atMs, clock)) return@fold s
            val at = if (refs.size > 1 && ref.slotAt != null && ref.slotAt <= atMs) ref.slotAt else atMs
            complete(s, task.id, at, clock)
        }

    /** Whether the planned time a reminder was for has already been logged. */
    fun isCovered(state: AppState, ref: ReminderRef, atMs: Long, clock: LocalClock): Boolean {
        val task = state.task(ref.taskId) ?: return true
        if (task.kind.health) return false
        val slots = AdaptiveTiming.effectiveSlots(task, state.completions, atMs, clock)
        val status = CareEngine.status(task, state.completions, atMs, clock, slots)
        val slotAt = ref.slotAt ?: return status.allDoneThisCycle
        // A notification from an earlier cycle (yesterday's breakfast tapped today): nothing to log.
        if (slotAt < clock.startOfDay(status.cycleStartDay)) return true
        // A later cycle (tomorrow's, tapped early) is never "already done".
        if (slotAt >= clock.startOfDay(status.cycleStartDay + task.everyDays.coerceAtLeast(1))) return false
        // This cycle: the planned time it was for, or the nearest one if learning moved it since.
        val i = status.slotTimes.indices.minByOrNull { kotlin.math.abs(status.slotTimes[it] - slotAt) }
            ?.takeIf { kotlin.math.abs(status.slotTimes[it] - slotAt) <= AdaptiveTiming.MAX_SHIFT_MIN * MINUTE_MS }
        return if (i != null) status.done > i else status.allDoneThisCycle
    }

    /**
     * Removes the most recently *added* completion of a task (undo), even if it was for an earlier
     * date. With family sharing, only one of [mine] (this phone's records: null, or your account id),
     * so undo never takes back someone else's Done.
     */
    fun undoLast(state: AppState, taskId: String, mine: Set<String?>? = null): AppState {
        val gone = state.completions.lastOrNull { it.taskId == taskId && (mine == null || it.by in mine) } ?: return state
        return removeCompletion(state, gone.id)
    }

    /** Deletes one record (a health record logged by mistake), and its day from the care calendar if nothing else was logged then. */
    fun removeCompletion(state: AppState, completionId: String): AppState {
        val i = state.completions.indexOfFirst { it.id == completionId }
        if (i < 0) return state
        val gone = state.completions[i]
        val next = state.copy(completions = state.completions.filterIndexed { j, _ -> j != i })
        // Take the day off the care calendar if nothing else was logged for the pet that day.
        val petId = state.task(gone.taskId)?.petId ?: return next
        val petTasks = next.tasksFor(petId).map { it.id }.toSet()
        if (next.completions.any { it.taskId in petTasks && it.localDay == gone.localDay }) return next
        return next.copy(pets = next.pets.map { p -> if (p.id == petId) p.copy(careDays = p.careDays - gone.localDay) else p })
    }

    /**
     * Health records: "last given on [day]". Logs it at the task's usual time that day (never in the
     * future), so the next due date follows from it.
     */
    fun logOnDay(
        state: AppState, taskId: String, day: Long, nowMs: Long, clock: LocalClock, newId: () -> String = { Ids.newId() },
    ): AppState {
        val task = state.task(taskId) ?: return state
        val at = minOf(clock.at(day, task.slots.firstOrNull() ?: (9 * 60)), nowMs)
        return complete(state, taskId, at, clock, newId, careDay = day == clock.dayIndex(nowMs))
    }

    /** Someone else is caring for the pets until [untilMs]. "I'm back" passes now, so care missed while away stays forgiven. */
    fun setAway(state: AppState, untilMs: Long): AppState =
        state.copy(settings = state.settings.copy(awayUntilMs = untilMs))

    /** Keeps the latest [AppState.MAX_COMPLETIONS_PER_TASK] per task, in the order they were added. */
    fun prune(state: AppState): AppState {
        val dropped = state.completions.groupBy { it.taskId }.values.flatMap { list ->
            list.sortedBy { it.atMs }.dropLast(AppState.MAX_COMPLETIONS_PER_TASK)
        }
        if (dropped.isEmpty()) return state
        val drop = dropped.groupingBy { it }.eachCount().toMutableMap()
        val kept = state.completions.filter { c ->
            val n = drop[c] ?: 0
            if (n > 0) { drop[c] = n - 1; false } else true
        }
        return state.copy(completions = kept)
    }

    fun setSettings(state: AppState, settings: Settings): AppState = state.copy(settings = settings)
}
