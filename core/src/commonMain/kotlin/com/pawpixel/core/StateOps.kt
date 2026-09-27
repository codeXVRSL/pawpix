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
        title = kind.label,
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
            slots = task.slots.map { it.coerceIn(0, MINUTES_PER_DAY - 1) }.distinct().sorted().take(4)
                .ifEmpty { listOf(8 * 60) },
            everyDays = task.everyDays.coerceIn(1, 90),
            title = task.title.trim().ifEmpty { task.kind.label },
        )
        val exists = state.tasks.any { it.id == task.id }
        return state.copy(tasks = if (exists) state.tasks.map { if (it.id == task.id) clean else it } else state.tasks + clean)
    }

    fun removeTask(state: AppState, taskId: String): AppState = state.copy(
        tasks = state.tasks.filterNot { it.id == taskId },
        completions = state.completions.filterNot { it.taskId == taskId },
    )

    fun complete(state: AppState, taskId: String, atMs: Long, clock: LocalClock): AppState {
        if (state.task(taskId) == null) return state
        val c = Completion(taskId, atMs, clock.minuteOfDay(atMs), clock.dayIndex(atMs))
        return prune(state.copy(completions = state.completions + c))
    }

    /** Removes the most recent completion of a task (undo). */
    fun undoLast(state: AppState, taskId: String): AppState {
        val last = state.completions.filter { it.taskId == taskId }.maxByOrNull { it.atMs } ?: return state
        return state.copy(completions = state.completions - last)
    }

    fun prune(state: AppState): AppState {
        val kept = state.completions.groupBy { it.taskId }.values.flatMap { list ->
            list.sortedBy { it.atMs }.takeLast(AppState.MAX_COMPLETIONS_PER_TASK)
        }.sortedBy { it.atMs }
        return state.copy(completions = kept)
    }

    fun setSettings(state: AppState, settings: Settings): AppState = state.copy(settings = settings)
}
