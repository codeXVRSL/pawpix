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
        )
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
    fun complete(state: AppState, taskId: String, atMs: Long, clock: LocalClock): AppState {
        state.task(taskId) ?: return state
        val c = Completion(taskId, atMs, clock.minuteOfDay(atMs), clock.dayIndex(atMs))
        return prune(state.copy(completions = state.completions + c))
    }

    /**
     * A "Done" from a notification. Health care counts only if it's due within a day, so tapping an
     * old notification after already logging it in the app doesn't record it twice.
     */
    fun completeFromReminder(state: AppState, taskId: String, atMs: Long, clock: LocalClock): AppState {
        val task = state.task(taskId) ?: return state
        if (!HealthDue.canQuickComplete(task, state.completions, atMs, clock)) return state
        return complete(state, taskId, atMs, clock)
    }

    /** Removes the most recently *added* completion of a task (undo), even if it was for an earlier date. */
    fun undoLast(state: AppState, taskId: String): AppState {
        val i = state.completions.indexOfLast { it.taskId == taskId }
        if (i < 0) return state
        return state.copy(completions = state.completions.filterIndexed { j, _ -> j != i })
    }

    /**
     * Health records: "last given on [day]". Logs it at the task's usual time that day (never in the
     * future), so the next due date follows from it.
     */
    fun logOnDay(state: AppState, taskId: String, day: Long, nowMs: Long, clock: LocalClock): AppState {
        val task = state.task(taskId) ?: return state
        val at = minOf(clock.at(day, task.slots.firstOrNull() ?: (9 * 60)), nowMs)
        return complete(state, taskId, at, clock)
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
