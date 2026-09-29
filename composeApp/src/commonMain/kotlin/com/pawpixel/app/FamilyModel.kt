package com.pawpixel.app

import com.pawpixel.core.HouseholdSync
import com.pawpixel.core.Json
import com.pawpixel.core.Pet
import com.pawpixel.core.SharedData
import com.pawpixel.core.StateOps
import com.pawpixel.map.Household
import com.pawpixel.map.HouseholdClient
import com.pawpixel.map.MapException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How family sharing is doing, for the screen. */
data class FamilyStatus(
    val household: Household? = null,
    val syncing: Boolean = false,
    val lastSyncMs: Long = 0,
    /** A message for the owner when the last sync failed (usually "offline"). */
    val error: String? = null,
)

/**
 * Family sharing on this phone: which household you're in, and syncing the pets you share with it
 * (see [HouseholdSync]). Uses the same sign-in as the pet map. Works offline: changes wait on the
 * phone and go at the next sync.
 */
class FamilyModel(private val repo: PawRepository, private val map: PetMapModel) {
    private val files get() = repo.platform.files
    val client = HouseholdClient(map.client.api)
    private val syncLock = Mutex()
    /** Syncs run here, not in a screen's scope, so leaving a screen never cuts one off halfway. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The shared part as last synced, to skip syncs for changes that aren't shared (a reminder switch). */
    private var lastShared: SharedData? = null

    private val _status = MutableStateFlow(FamilyStatus(household = loadHousehold()))
    val status: StateFlow<FamilyStatus> = _status.asStateFlow()

    val isSetUp: Boolean get() = map.settings.isConfigured
    val isSignedIn: Boolean get() = client.isSignedIn
    val household: Household? get() = _status.value.household
    val myUserId: String? get() = client.userId

    /** Who logged a record, for "Done by Jamaica". Null for this phone's owner or unknown people. */
    fun nameOf(userId: String?): String? = if (userId == null || userId == myUserId) null else household?.nameOf(userId)

    suspend fun signIn(): Boolean = map.signIn()

    /** Re-reads your household (members may have joined or left). */
    suspend fun refresh() {
        if (!isSetUp || !isSignedIn) return
        setHousehold(client.mine())
    }

    suspend fun create(familyName: String, yourName: String) {
        client.create(familyName, yourName)
        refresh()
    }

    suspend fun join(code: String, yourName: String) {
        client.join(code, yourName)
        forgetBase() // a fresh start: nothing on this phone is "deleted here" relative to this family
        refresh()
        sync()
    }

    suspend fun removeMember(userId: String) { client.removeMember(userId); refresh() }
    suspend fun revokeInvites() = client.revokeInvites()

    suspend fun invite(): String = client.invite(household?.id ?: throw MapException(MapException.Kind.REFUSED, com.pawpixel.i18n.tr("Start or join a family first")))

    /** Leaves the family. Shared pets stay on this phone, with their history, no longer shared. */
    suspend fun leave() {
        client.leave()
        forgetLocally()
    }

    /** Local only: after leaving, or when the account is deleted. Waits for a sync in flight. */
    suspend fun forgetLocally() = syncLock.withLock {
        repo.update { s -> s.copy(pets = s.pets.map { it.copy(shared = false) }) }
        files.delete(DIR)
        lastShared = null
        _status.value = FamilyStatus()
    }

    /** After restoring a backup: the phone's pets are no longer what the family last saw from it. */
    suspend fun forgetBase() = files.delete(BASE)

    /** Forgets the household on this phone without touching pets ("Delete all my data" wipes those itself). */
    fun clearLocal() {
        files.delete(DIR)
        lastShared = null
        _status.value = FamilyStatus()
    }

    /** Shares a pet with the family (or stops), then syncs. Stopping keeps it on everyone's phone, unshared. */
    suspend fun share(pet: Pet, on: Boolean) {
        val look = repo.art(pet)?.look?.encode() ?: pet.lookCode
        // By id, on the current state: an edit that just arrived from the family isn't overwritten.
        repo.update { s -> s.pet(pet.id)?.let { StateOps.updatePet(s, it.copy(shared = on, lookCode = look ?: it.lookCode)) } ?: s }
        sync()
    }

    /** Starts a sync in the background (UI timers, changes). */
    fun requestSync() {
        if (household != null) scope.launch { sync() }
    }

    /** Called with every state change: syncs only when something shared changed. */
    fun onLocalChange(state: com.pawpixel.core.AppState) {
        if (household == null) return
        val shared = HouseholdSync.sharedPart(state).let { d -> SharedData(d.pets.map(HouseholdSync::view), d.tasks.map(HouseholdSync::view), d.completions) }
        if (shared != lastShared) { lastShared = shared; requestSync() }
    }

    /**
     * Pulls the family's copy, merges it with this phone's, and sends this phone's changes. Safe to
     * call often (one at a time); does nothing outside a family. Returns false if it couldn't reach
     * the server (changes stay on the phone for next time).
     */
    suspend fun sync(): Boolean = syncLock.withLock {
        val h = household ?: return@withLock true
        if (!isSetUp || !isSignedIn) return@withLock true
        _status.value = _status.value.copy(syncing = true)
        try {
            val remote = client.pull(h.id)
            val base = SharedData.decode(files.readText(BASE))
            // Once merged into this phone, the rest must finish (or record the failure) even if the
            // caller is cancelled; otherwise the next sync would mistake what arrived for local edits.
            withContext(NonCancellable) {
                var result: com.pawpixel.core.SyncResult? = null
                repo.update { local -> HouseholdSync.merge(local, base, remote, myUserId, repo.clock).also { result = it }.state }
                val r = result!!
                repo.redrawPoses(r.redraw)
                try {
                    if (!r.push.isEmpty) client.push(h.id, r.push)
                    files.writeText(BASE, r.base.encode())
                } catch (e: Exception) {
                    files.writeText(BASE, r.baseIfPushFails.encode())
                    throw e
                }
            }
            _status.value = _status.value.copy(syncing = false, lastSyncMs = repo.now(), error = null)
            true
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(syncing = false)
            throw e
        } catch (e: MapException) {
            if (e.kind == MapException.Kind.REFUSED) runCatching { setHousehold(client.mine()) } // removed from the family?
            _status.value = _status.value.copy(syncing = false, error = e.message)
            false
        } catch (e: Exception) {
            _status.value = _status.value.copy(syncing = false, error = com.pawpixel.i18n.tr("Couldn't sync: {0}", e.message ?: ""))
            false
        }
    }

    /** Quick sync for background moments (a reminder about to show): gives up after [timeoutMs]. */
    suspend fun syncWithin(timeoutMs: Long): Boolean = household != null && (withTimeoutOrNull(timeoutMs) { sync() } ?: false)

    private fun setHousehold(h: Household?) {
        if (h == null) files.delete(HOUSEHOLD) else files.writeText(HOUSEHOLD, Json.obj(
            "me" to myUserId, "id" to h.id, "name" to h.name, "owner" to h.ownerId,
            "members" to h.members.map { Json.obj("u" to it.userId, "n" to it.name) },
        ).stringify())
        _status.value = _status.value.copy(household = h)
    }

    /** The saved household, if it belongs to the account signed in now. */
    private fun loadHousehold(): Household? = files.readText(HOUSEHOLD)?.let { text ->
        runCatching {
            val j = Json.parse(text)
            if (j["me"].str != client.userId) return@runCatching null
            Household(j["id"].str!!, j["name"].str ?: "Family", j["members"].list.map { Household.Member(it["u"].str!!, it["n"].str ?: "") }, j["owner"].str)
        }.getOrNull()
    }

    companion object {
        /** Kept out of backups (Android rules, backup file): it belongs to the account, not the phone. */
        const val DIR = "family"
        private const val HOUSEHOLD = "$DIR/household.json"
        private const val BASE = "$DIR/base.json"
    }
}
