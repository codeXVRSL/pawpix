package com.pawpixel.app

import com.pawpixel.core.HouseholdSync
import com.pawpixel.core.Json
import com.pawpixel.core.Pet
import com.pawpixel.core.SharedData
import com.pawpixel.core.StateOps
import com.pawpixel.map.Household
import com.pawpixel.map.HouseholdClient
import com.pawpixel.map.MapException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        refresh()
        sync()
    }

    suspend fun invite(): String = client.invite(household?.id ?: throw MapException(MapException.Kind.REFUSED, "Start or join a family first"))

    /** Leaves the family. Shared pets stay on this phone, with their history, no longer shared. */
    suspend fun leave() {
        client.leave()
        forgetLocally()
    }

    /** Local only: after leaving, or when the account is deleted. */
    suspend fun forgetLocally() {
        repo.update { s -> s.copy(pets = s.pets.map { it.copy(shared = false) }) }
        files.delete(DIR)
        _status.value = FamilyStatus()
    }

    /** Forgets the household on this phone without touching pets ("Delete all my data" wipes those itself). */
    fun clearLocal() {
        files.delete(DIR)
        _status.value = FamilyStatus()
    }

    /** Shares a pet with the family (or stops), then syncs. Stopping keeps it on everyone's phone, unshared. */
    suspend fun share(pet: Pet, on: Boolean) {
        val look = repo.art(pet)?.look?.encode() ?: pet.lookCode
        repo.update { StateOps.updatePet(it, pet.copy(shared = on, lookCode = look)) }
        sync()
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
            var result: com.pawpixel.core.SyncResult? = null
            repo.update { local -> HouseholdSync.merge(local, base, remote).also { result = it }.state }
            val r = result!!
            client.push(h.id, r.push)
            files.writeText(BASE, r.base.encode())
            repo.redrawPoses(r.redraw)
            _status.value = _status.value.copy(syncing = false, lastSyncMs = repo.now(), error = null)
            true
        } catch (e: MapException) {
            if (e.kind == MapException.Kind.REFUSED) runCatching { setHousehold(client.mine()) } // removed from the family?
            _status.value = _status.value.copy(syncing = false, error = e.message)
            false
        } catch (e: Exception) {
            _status.value = _status.value.copy(syncing = false, error = "Couldn't sync: ${e.message}")
            false
        }
    }

    /** Quick sync for background moments (a reminder about to show): gives up after [timeoutMs]. */
    suspend fun syncWithin(timeoutMs: Long): Boolean = household != null && (withTimeoutOrNull(timeoutMs) { sync() } ?: false)

    private fun setHousehold(h: Household?) {
        if (h == null) files.delete(HOUSEHOLD) else files.writeText(HOUSEHOLD, Json.obj(
            "id" to h.id, "name" to h.name,
            "members" to h.members.map { Json.obj("u" to it.userId, "n" to it.name) },
        ).stringify())
        _status.value = _status.value.copy(household = h)
    }

    private fun loadHousehold(): Household? = files.readText(HOUSEHOLD)?.let { text ->
        runCatching {
            val j = Json.parse(text)
            Household(j["id"].str!!, j["name"].str ?: "Family", j["members"].list.map { Household.Member(it["u"].str!!, it["n"].str ?: "") })
        }.getOrNull()
    }

    companion object {
        /** Kept out of backups (Android rules, backup file): it belongs to the account, not the phone. */
        const val DIR = "family"
        private const val HOUSEHOLD = "$DIR/household.json"
        private const val BASE = "$DIR/base.json"
    }
}
