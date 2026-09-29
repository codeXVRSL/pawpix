package com.pawpixel.core

/** The part of a family's data everyone shares: pets they care for together, their tasks and records. */
data class SharedData(
    val pets: List<Pet> = emptyList(),
    val tasks: List<CareTask> = emptyList(),
    val completions: List<Completion> = emptyList(),
) {
    fun encode(): String = StateCodec.encode(AppState(pets, tasks, completions))

    companion object {
        fun decode(text: String?): SharedData = text?.let { runCatching { StateCodec.decode(it) }.getOrNull() }
            ?.let { SharedData(it.pets, it.tasks, it.completions) } ?: SharedData()
    }
}

/** What this phone must send to the family's server copy after a merge. */
data class SyncPush(
    val upsertPets: List<Pet> = emptyList(),
    val deletePetIds: List<String> = emptyList(),
    val upsertTasks: List<CareTask> = emptyList(),
    val deleteTaskIds: List<String> = emptyList(),
    val addCompletions: List<Completion> = emptyList(),
    val deleteCompletionIds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = upsertPets.isEmpty() && deletePetIds.isEmpty() && upsertTasks.isEmpty() &&
        deleteTaskIds.isEmpty() && addCompletions.isEmpty() && deleteCompletionIds.isEmpty()
}

data class SyncResult(
    /** This phone's new state. */
    val state: AppState,
    /** What everyone will have once [push] is sent: the base for the next merge. */
    val base: SharedData,
    val push: SyncPush,
    /** Pets whose pixel look arrived or changed, so their widget poses need drawing. */
    val redraw: List<String>,
    /**
     * The base to keep if sending [push] fails: the server's copy as pulled (minus pets this phone
     * keeps unshared). Everything this phone changed then still differs from it and goes next time,
     * and nothing just received from others looks like this phone's own edit.
     */
    val baseIfPushFails: SharedData,
)

/**
 * Family sharing without a live connection: a three-way merge between what this phone has
 * ([local]), what everyone had at the last sync ([base]), and what the server has now ([remote]).
 *
 * - Care records (a Done tap) are a set: records added anywhere are kept, records undone anywhere
 *   are removed. Two people tapping Done for the same feed shows as two records, never a lost one.
 * - A pet or task changed on this phone since the last sync wins; otherwise the server's copy
 *   (someone else's edit, or a new pet) is taken.
 * - A shared pet removed by someone else isn't deleted here: it stays on this phone, unshared,
 *   with its history. Nothing of yours disappears because of someone else's tap.
 *
 * Reminder choices (on/off, exact time) and how the sprite is drawn stay per phone.
 */
object HouseholdSync {

    fun sharedPart(state: AppState): SharedData {
        val pets = state.pets.filter { it.shared }
        val petIds = pets.map { it.id }.toSet()
        val tasks = state.tasks.filter { it.petId in petIds }
        val taskIds = tasks.map { it.id }.toSet()
        return SharedData(pets, tasks, state.completions.filter { it.taskId in taskIds })
    }

    /** The fields everyone shares; the rest (sprite settings, reminder switches) is this phone's own. */
    fun view(p: Pet) = p.copy(sprite = SpriteSettings(), spriteVersion = 0, shared = true, careDays = emptyList(), milestoneSeen = 0)
    fun view(t: CareTask) = t.copy(remindersOn = true, exactAlarm = false)

    /**
     * @param me this phone's account: only its own records (or ones made before sharing) are sent,
     *   so a re-shared history keeps its authors.
     * @param clock this phone's calendar: records from other phones get their local day and minute
     *   recomputed here, so a family member in another time zone lands on the right day.
     */
    fun merge(local: AppState, base: SharedData, remoteAll: SharedData, me: String? = null, clock: LocalClock? = null): SyncResult {
        val mine = sharedPart(local)
        // A pet this phone holds but doesn't share (it stopped sharing, or left and rejoined) is not
        // taken from the server: it stays this phone's own. The family keeps its copy.
        val ignored = local.pets.filter { !it.shared }.map { it.id }.toSet().intersect(remoteAll.pets.map { it.id }.toSet())
        val ignoredTasks = remoteAll.tasks.filter { it.petId in ignored }.map { it.id }.toSet()
        val remote = SharedData(
            remoteAll.pets.filter { it.id !in ignored },
            remoteAll.tasks.filter { it.id !in ignoredTasks },
            remoteAll.completions.filter { it.taskId !in ignoredTasks },
        )
        val push = Push()
        val redraw = ArrayList<String>()

        // ---- Pets ----
        val lp = mine.pets.associateBy { it.id }
        val bp = base.pets.associateBy { it.id }
        val rp = remote.pets.associateBy { it.id }
        val keptPets = LinkedHashMap<String, Pet>()     // shared after the merge
        val unshared = HashSet<String>()                 // removed by someone else: kept here, unshared
        for (id in ordered(lp.keys, rp.keys, bp.keys)) {
            val l = lp[id]; val b = bp[id]; val r = rp[id]
            val changedHere = l?.let(::view) != b?.let(::view)
            when {
                changedHere && l != null -> { keptPets[id] = l; if (r?.let(::view) != view(l)) push.upsertPets += l }
                // Stopped sharing (or deleted) here: take it out of the family. Everyone else keeps a copy.
                changedHere && l == null -> { if (remoteAll.pets.any { it.id == id }) push.deletePetIds += id }
                r != null -> {
                    val merged = l?.let { mergePet(it, r) } ?: r.copy(shared = true, spriteVersion = 1)
                    if (l == null || l.lookCode != r.lookCode || l.species != r.species || l.ears != r.ears) redraw += id
                    keptPets[id] = merged
                }
                l != null -> unshared += id // someone else removed it from the family
            }
        }

        // ---- Tasks (of pets still shared) ----
        val lt = mine.tasks.associateBy { it.id }
        val bt = base.tasks.associateBy { it.id }
        val rt = remote.tasks.associateBy { it.id }
        val keptTasks = LinkedHashMap<String, CareTask>()
        val deletedTasks = HashSet<String>()
        for (id in ordered(lt.keys, rt.keys, bt.keys)) {
            val l = lt[id]; val b = bt[id]; val r = rt[id]
            // Tasks of pets no longer shared (removed elsewhere, or unshared/deleted here) are left alone.
            if ((l ?: r ?: b)!!.petId !in keptPets) continue
            val changedHere = l?.let(::view) != b?.let(::view)
            when {
                changedHere && l != null -> { keptTasks[id] = l; if (r?.let(::view) != view(l)) push.upsertTasks += l }
                changedHere && l == null -> { deletedTasks += id; if (r != null) push.deleteTaskIds += id }
                r != null -> keptTasks[id] = l?.let { r.copy(remindersOn = it.remindersOn, exactAlarm = it.exactAlarm) } ?: r
                else -> deletedTasks += id // removed by someone else
            }
        }

        // ---- Records ----
        val sharedTaskIds = keptTasks.keys.toSet()
        val lc = mine.completions.filter { it.taskId in sharedTaskIds }.associateBy { it.id }
        val bc = base.completions.associateBy { it.id }
        val rc = remote.completions.filter { it.taskId in sharedTaskIds }.associateBy { it.id }
        // This phone keeps at most MAX_COMPLETIONS_PER_TASK records per task. Records older than the
        // oldest one kept were trimmed here, not undone: they're neither deleted on the server nor
        // brought back.
        val floor = mine.completions.groupBy { it.taskId }
            .filterValues { it.size >= AppState.MAX_COMPLETIONS_PER_TASK }.mapValues { (_, l) -> l.minOf { it.atMs } }
        fun trimmed(c: Completion) = floor[c.taskId]?.let { c.atMs < it } ?: false
        val undoneHere = bc.keys - lc.keys - bc.values.filter(::trimmed).map { it.id }.toSet()
        val addedHere = lc.keys - bc.keys
        for (id in addedHere) {
            val c = lc.getValue(id)
            if (id !in rc && (c.by == null || c.by == me)) push.addCompletions += c
        }
        for (id in undoneHere) if (id in rc) push.deleteCompletionIds += id
        val undoneElsewhere = (bc.keys - rc.keys) - addedHere
        val known = local.completions.map { it.id }.toSet()
        val newFromOthers = rc.values.filter { it.id !in known && it.id !in undoneHere && !trimmed(it) }.sortedBy { it.atMs }
            .map { c -> if (clock == null) c else c.copy(localDay = clock.dayIndex(c.atMs), localMinute = clock.minuteOfDay(c.atMs)) }

        // ---- New local state ----
        val removedTaskIds: Set<String> = deletedTasks
        val completions = local.completions
            .filter { c -> c.taskId !in removedTaskIds && !(c.taskId in sharedTaskIds && c.id in undoneElsewhere) }
            .map { c -> rc[c.id]?.takeIf { c.taskId in sharedTaskIds && it.by != null && c.by == null }?.let { c.copy(by = it.by) } ?: c } +
            newFromOthers
        val pets = local.pets.map { p ->
            when {
                p.id in unshared -> p.copy(shared = false)
                p.shared && p.id in keptPets -> keptPets.getValue(p.id)
                else -> p
            }
        } + keptPets.values.filter { k -> local.pets.none { it.id == k.id } }
        val tasks = local.tasks.filter { it.id !in removedTaskIds }.map { t -> keptTasks[t.id] ?: t } +
            keptTasks.values.filter { k -> local.tasks.none { it.id == k.id } }
        var state = StateOps.prune(local.copy(pets = pets, tasks = tasks, completions = completions))
        // Care others logged counts toward this phone's care calendar too.
        for ((petId, recs) in newFromOthers.groupBy { c -> tasks.firstOrNull { it.id == c.taskId }?.petId }) {
            if (petId != null) state = StateOps.markCareDays(state, petId, recs.map { it.localDay })
        }
        val failBase = SharedData(remote.pets, remote.tasks, remote.completions)
        return SyncResult(state, sharedPart(state), push.build(), redraw, failBase)
    }

    /** Takes someone else's edit of a pet, keeping how this phone draws it. */
    private fun mergePet(local: Pet, remote: Pet): Pet {
        val lookChanged = local.lookCode != remote.lookCode || local.species != remote.species || local.ears != remote.ears
        return remote.copy(
            sprite = local.sprite, shared = true, careDays = local.careDays, milestoneSeen = local.milestoneSeen,
            spriteVersion = if (lookChanged) local.spriteVersion + 1 else local.spriteVersion,
        )
    }

    private fun ordered(vararg keys: Set<String>): List<String> = LinkedHashSet<String>().apply { keys.forEach { addAll(it) } }.toList()

    private class Push {
        val upsertPets = ArrayList<Pet>(); val deletePetIds = ArrayList<String>()
        val upsertTasks = ArrayList<CareTask>(); val deleteTaskIds = ArrayList<String>()
        val addCompletions = ArrayList<Completion>(); val deleteCompletionIds = ArrayList<String>()
        fun build() = SyncPush(upsertPets, deletePetIds, upsertTasks, deleteTaskIds, addCompletions, deleteCompletionIds)
    }
}
