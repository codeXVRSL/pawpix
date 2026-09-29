package com.pawpixel.app

import com.pawpixel.core.Json
import com.pawpixel.core.LocationGrid
import com.pawpixel.core.Pet
import com.pawpixel.map.MapClient
import com.pawpixel.map.MapSettings
import com.pawpixel.map.SessionStore
import com.pawpixel.map.Sha256
import com.pawpixel.map.SharedPet
import com.pawpixel.sprite.PetArt

/**
 * The pet map on this phone: sign-in session, which pets you share, and your ~1 km area.
 * Exact coordinates are snapped to a grid cell here and never stored or sent.
 */
class PetMapModel(private val platform: Platform, private val files: FileStore, val settings: MapSettings = MapBuildConfig) {
    val client = MapClient(settings, platform.http, object : SessionStore {
        override fun load() = files.readText(SESSION)
        override fun save(json: String?) { if (json == null) files.delete(SESSION) else files.writeText(SESSION, json) }
    }, platform::nowMs)

    /** Test builds sign in with a seeded account instead of Google/Apple. */
    val usesTestAccount: Boolean get() = settings.testEmail.isNotBlank()
    val signInLabel: String get() = if (usesTestAccount) "Sign in (test account)" else platform.mapSignInLabel

    /** Your area, as last set (a grid cell only). */
    var myArea: LocationGrid.Cell? = files.readText(AREA)?.let { runCatching {
        val j = Json.parse(it)
        LocationGrid.Cell(j["id"].str!!, 0, 0, j["lat"].double!!, j["lng"].double!!, LocationGrid.DEFAULT_CELL_KM)
    }.getOrNull() }
        private set

    /** Local pet ids shown on the map; null = not chosen yet (all). */
    var sharedPetIds: Set<String>? = files.readText(SHARED)?.let { runCatching { Json.parse(it).list.mapNotNull { p -> p.str }.toSet() }.getOrNull() }
        private set

    /** Signs in with Google/Apple (or the test account). False if the owner cancelled. */
    suspend fun signIn(): Boolean {
        if (client.isSignedIn) return true
        if (usesTestAccount) {
            client.signInWithPassword(settings.testEmail, settings.testPassword)
            return true
        }
        val raw = Sha256.newNonce()
        val identity = platform.signInForMap(Sha256.hex(raw), settings.googleWebClientId) ?: return false
        client.signInWithIdToken(identity.provider, identity.idToken, raw)
        return true
    }

    /** Asks for approximate location and snaps it to a ~1 km cell. Null if refused/unavailable. */
    suspend fun locate(): LocationGrid.Cell? {
        val (lat, lng) = platform.approximateLocation() ?: return null
        return LocationGrid.snap(lat, lng).also { cell ->
            myArea = cell
            files.writeText(AREA, Json.obj("id" to cell.id, "lat" to cell.centerLat, "lng" to cell.centerLng).stringify())
        }
    }

    /** Puts (or updates) the chosen pets on the map in [area]. */
    suspend fun join(pets: List<Pet>, art: (Pet) -> PetArt?, area: LocationGrid.Cell) {
        sharedPetIds = pets.map { it.id }.toSet()
        files.writeText(SHARED, Json.arr(pets.map { it.id }).stringify())
        val shared = pets.mapNotNull { pet ->
            val a = art(pet) ?: return@mapNotNull null
            SharedPet(pet.id, pet.name, pet.species.name, a.ears.name, a.look.encode())
        }
        client.join(shared, area)
    }

    /** Leaves the map (server) and forgets the local map settings. Stays signed in. */
    suspend fun leave() {
        client.leaveMap()
        forgetChoices()
    }

    /** Deletes the map account on the server, then everything map-related on the phone. */
    suspend fun deleteAccount() {
        client.deleteAccount()
        forgetLocally()
    }

    /** Local only: sign out and forget area and choices (e.g. "Delete all my data"). */
    fun forgetLocally() {
        client.signOutLocally()
        files.delete("map")
        myArea = null
        sharedPetIds = null
    }

    private fun forgetChoices() {
        files.delete(AREA); files.delete(SHARED)
        myArea = null
        sharedPetIds = null
    }

    companion object {
        private const val SESSION = "map/session.json"
        private const val AREA = "map/area.json"
        private const val SHARED = "map/shared.json"
    }
}
