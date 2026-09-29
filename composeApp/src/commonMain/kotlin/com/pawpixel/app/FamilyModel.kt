package com.pawpixel.app

import com.pawpixel.core.AppState
import com.pawpixel.core.HouseholdSync
import com.pawpixel.core.Json
import com.pawpixel.core.Pet
import com.pawpixel.core.SharedData
import com.pawpixel.core.StateOps
import com.pawpixel.core.SyncResult
import com.pawpixel.i18n.tr
import com.pawpixel.map.Household
import com.pawpixel.map.HouseholdClient
import com.pawpixel.map.MapException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How household sharing is doing, for the screen. */
data class FamilyStatus(
    val household: Household? = null,
    val syncing: Boolean = false,
    val lastSyncMs: Long = 0,
    /** A message for the owner when the last sync failed (usually "offline"). */
    val error: String? = null,
    /** Something the owner should know that isn't an error ("You're no longer in ..."). */
    val notice: String? = null,
)

/**
 * Sharing pets with your household on this phone: which household you're in, and syncing the pets
 * you share with it (see [HouseholdSync]). Uses the same sign-in as the pet map. Works offline:
 * changes wait on the phone and go at the next sync.
 */
class FamilyModel(private val repo: PawRepository, private val map: PetMapModel) {
    private val files get() = repo.platform.files
    val client = HouseholdClient(map.client.api)
    private val syncLock = Mutex()
    /** Syncs run here, not in a screen's scope, so leaving a screen never cuts one off halfway. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The shared part as last synced, to skip syncs for changes that aren't shared (a reminder switch). */
    private var lastShared: SharedData? = null
    private var pendingSync: Job? = null

    private val _status = MutableStateFlow(FamilyStatus(household = loadHousehold()))
    val status: StateFlow<FamilyStatus> = _status.asStateFlow()

    /** False when this build has no server (the screens say it's coming soon). */
    val isSetUp: Boolean get() = map.settings.isConfigured
    val isSignedIn: Boolean get() = client.isSignedIn
    val household: Household? get() = _status.value.household
    val myUserId: String? get() = client.userId

    /** A record logged on this phone (before sharing) or by this account: the owner's own, which Undo may take back. */
    fun isMine(by: String?): Boolean = by == null || by == myUserId

    /** Who logged a record, for "Fed by Jamaica". Null for this phone's owner or unknown people. */
    fun nameOf(userId: String?): String? = if (isMine(userId)) null else household?.nameOf(userId)

    suspend fun signIn(): Boolean = map.signIn()

    /** Re-reads your household (members may have joined or left). */
    suspend fun refresh() {
        if (!isSetUp || !isSignedIn) return
        setHousehold(client.mine())
    }

    /** Starts a household named after you ("Save's household"). */
    suspend fun create(yourName: String) {
        client.create(tr("{0}'s household", yourName.trim()), yourName)
        forgetBase()
        refresh()
    }

    suspend fun join(code: String, yourName: String) {
        client.join(code, yourName)
        forgetBase() // a fresh start: nothing on this phone is "deleted here" relative to this household
        refresh()
        sync()
    }

    suspend fun removeMember(userId: String) { client.removeMember(userId); refresh() }
    suspend fun revokeInvites() = client.revokeInvites()

    suspend fun invite(): String = client.invite(household?.id ?: throw MapException(MapException.Kind.REFUSED, tr("Start or join a household first")))

    /** Leaves the household. Shared pets stay on this phone, with their history, no longer shared. */
    suspend fun leave() {
        client.leave()
        forgetLocally()
    }

    /** The one who started it stops sharing for everyone. Every phone keeps its own copy of the pets. */
    suspend fun stopSharing() {
        client.deleteHousehold()
        forgetLocally()
    }

    /** Local only: after leaving, or when the account is deleted. Waits for a sync in flight. */
    suspend fun forgetLocally() = syncLock.withLock { forgetLocked() }

    private suspend fun forgetLocked(notice: String? = null) {
        repo.update(stamp = false) { s -> s.copy(pets = s.pets.map { it.copy(shared = false) }) }
        files.delete(DIR)
        lastShared = null
        _status.value = FamilyStatus(notice = notice)
    }

    /**
     * Replaces the phone's pets ([block]: a restore) with no sync in flight, then forgets the base.
     * A sync that pulled before the swap and merged after it would see the family's pets missing
     * here and delete them for everyone.
     */
    suspend fun <T> replacingPets(block: suspend () -> T): T = syncLock.withLock { block().also { forgetBase() } }

    /** After restoring a backup or joining: the phone's pets are no longer what the household last saw from it. */
    fun forgetBase() {
        files.delete(BASE)
        files.delete(CURSOR)
    }

    /** Forgets the household on this phone without touching pets ("Delete all my data" wipes those itself). */
    fun clearLocal() {
        files.delete(DIR)
        lastShared = null
        _status.value = FamilyStatus()
    }

    /** Shares a pet with the household (or stops), then syncs. Stopping keeps it on everyone's phone, unshared. */
    suspend fun share(pet: Pet, on: Boolean) {
        val look = repo.art(pet)?.look?.encode() ?: pet.lookCode
        // By id, on the current state: an edit that just arrived from the household isn't overwritten.
        repo.update { s -> s.pet(pet.id)?.let { StateOps.updatePet(s, it.copy(shared = on, lookCode = look ?: it.lookCode)) } ?: s }
        sync()
    }

    /** Starts a sync in the background (app opened, timers). */
    fun requestSync() {
        if (household != null) scope.launch { sync() }
    }

    /**
     * Called with every state change: when something shared changed, syncs a moment later, so a
     * burst of taps (Done, then Undo) goes as one sync.
     */
    fun onLocalChange(state: AppState) {
        if (household == null) return
        val shared = sharedView(state)
        if (shared == lastShared) return
        lastShared = shared
        pendingSync?.cancel()
        pendingSync = scope.launch { delay(DEBOUNCE_MS); sync() }
    }

    /**
     * Pulls what changed in the household since the last sync, merges it with this phone's copy, and
     * sends this phone's changes. Safe to call often (one at a time); does nothing outside a
     * household. Returns false if it couldn't reach the server (changes stay on the phone for next time).
     */
    suspend fun sync(): Boolean = syncLock.withLock {
        val saved = household ?: return@withLock true
        if (!isSetUp || !isSignedIn) return@withLock true
        _status.value = _status.value.copy(syncing = true)
        try {
            // Who's in it now: new members' names for "Fed by", or you were removed / it was stopped.
            val h = client.mine()
            if (h == null || h.id != saved.id) {
                forgetLocked(tr("You're no longer in {0}. Your pets stay on this phone with their history.", saved.name))
                if (h != null) setHousehold(h)
                return@withLock true
            }
            setHousehold(h)
            val base = SharedData.decode(files.readText(BASE))
            val cursor = files.readText(CURSOR)?.trim()?.toLongOrNull() ?: 0L
            val pulled = client.pull(h.id, cursor, base.tasks.map { it.id }.toSet())
            // Once merged into this phone, the rest must finish (or record the failure) even if the
            // caller is cancelled; otherwise the next sync would mistake what arrived for local edits.
            withContext(NonCancellable) {
                var result: SyncResult? = null
                repo.update(stamp = false) { local ->
                    HouseholdSync.merge(local, base, pulled.changes, myUserId, repo.clock, repo.now()).also { result = it }.state
                }
                val r = result!!
                repo.redrawPoses(r.redraw)
                try {
                    if (!r.push.isEmpty) client.push(h.id, r.push)
                    files.writeText(BASE, r.base.encode())
                } catch (e: Exception) {
                    files.writeText(BASE, r.baseIfPushFails.encode())
                    throw e
                } finally {
                    // After the base: if the phone stops in between, the next pull just looks further back.
                    files.writeText(CURSOR, pulled.cursorMs.toString())
                }
            }
            lastShared = sharedView(repo.state.value) // what just arrived isn't a change to send back
            _status.value = _status.value.copy(syncing = false, lastSyncMs = repo.now(), error = null)
            true
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(syncing = false)
            throw e
        } catch (e: MapException) {
            _status.value = _status.value.copy(syncing = false, error = e.message)
            false
        } catch (e: Exception) {
            _status.value = _status.value.copy(syncing = false, error = tr("Couldn't sync: {0}", e.message ?: ""))
            false
        }
    }

    /** Quick sync for background moments (a reminder about to show, a Done from the widget): gives up after [timeoutMs]. */
    suspend fun syncWithin(timeoutMs: Long = BACKGROUND_TIMEOUT_MS): Boolean =
        household != null && (withTimeoutOrNull(timeoutMs) { sync() } ?: false)

    private fun sharedView(state: AppState) =
        HouseholdSync.sharedPart(state).let { d -> SharedData(d.pets.map(HouseholdSync::view), d.tasks.map(HouseholdSync::view), d.completions) }

    private fun setHousehold(h: Household?) {
        if (h == null) files.delete(HOUSEHOLD) else files.writeText(HOUSEHOLD, Json.obj(
            "me" to myUserId, "id" to h.id, "name" to h.name, "owner" to h.ownerId,
            "members" to h.members.map { Json.obj("u" to it.userId, "n" to it.name) },
        ).stringify())
        _status.value = _status.value.copy(household = h, notice = if (h != null) null else _status.value.notice)
    }

    /** The saved household, if it belongs to the account signed in now. */
    private fun loadHousehold(): Household? = files.readText(HOUSEHOLD)?.let { text ->
        runCatching {
            val j = Json.parse(text)
            if (j["me"].str != client.userId) return@runCatching null
            Household(j["id"].str!!, j["name"].str ?: "", j["members"].list.map { Household.Member(it["u"].str!!, it["n"].str ?: "") }, j["owner"].str)
        }.getOrNull()
    }

    companion object {
        /** Kept out of backups (Android rules, backup file): it belongs to the account, not the phone. */
        const val DIR = "family"
        private const val HOUSEHOLD = "$DIR/household.json"
        private const val BASE = "$DIR/base.json"
        /** The server time of the last pulled change (see [HouseholdClient.pull]). */
        private const val CURSOR = "$DIR/cursor.txt"
        private const val DEBOUNCE_MS = 2_000L
        const val BACKGROUND_TIMEOUT_MS = 8_000L
    }
}
