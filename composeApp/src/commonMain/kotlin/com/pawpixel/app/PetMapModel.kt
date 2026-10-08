package com.pawpixel.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pawpixel.core.Json
import com.pawpixel.core.LocationGrid
import com.pawpixel.core.Pet
import com.pawpixel.map.MapClient
import com.pawpixel.map.MapSettings
import com.pawpixel.map.SessionStore
import com.pawpixel.map.Sha256
import com.pawpixel.core.NameFilter
import com.pawpixel.map.SharedPet
import com.pawpixel.sprite.PetArt

/**
 * The pet map on this phone: sign-in session, which pets you share, and your ~1 km area.
 * Exact coordinates are snapped to a grid cell here and never stored or sent.
 */
class PetMapModel(
    private val platform: Platform,
    private val files: FileStore,
    val settings: MapSettings = MapBuildConfig,
    /** The account is gone (deleted from the map screen): family sharing forgets it too. */
    private val onAccountDeleted: suspend () -> Unit = {},
    /** The server, normally the platform's HTTP; the demo map passes its in-app pretend server. */
    http: com.pawpixel.map.Http = platform.http,
    /** Where this model keeps its session, area and choices (the demo keeps its own, apart from the real map's). */
    private val dir: String = "map",
    /** Used in place of the phone's location when it isn't available (the demo starts in Naga). */
    private val fallbackLocation: Pair<Double, Double>? = null,
) {
    /** The in-app demo, not PawPixel's server: the screen says so. */
    val isDemo: Boolean get() = dir != "map"

    private val SESSION get() = "$dir/session.json"
    private val AREA get() = "$dir/area.json"
    private val SHARED get() = "$dir/shared.json"
    private val LOST get() = "$dir/lost.json"
    private val CARDS get() = "$dir/cards.json"
    private val PALS get() = "$dir/pals.json"
    private val TREATS get() = "$dir/treats-seen.json"

    val client = MapClient(settings, http, object : SessionStore {
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

    /** A local pet id -> server id map on disk (lost alerts, ID cards). */
    private fun loadPetMap(file: String): Map<String, String> = files.readText(file)?.let { runCatching {
        Json.parse(it).list.mapNotNull { e -> val pet = e["pet"].str ?: return@mapNotNull null; val id = e["id"].str ?: return@mapNotNull null; pet to id }.toMap()
    }.getOrNull() } ?: emptyMap()
    private fun savePetMap(file: String, map: Map<String, String>) =
        files.writeText(file, Json.arr(map.map { (pet, id) -> Json.obj("pet" to pet, "id" to id) }).stringify())

    /** Open lost-pet alerts raised from this phone: local pet id -> alert id. */
    var lostAlerts: Map<String, String> by mutableStateOf(loadPetMap(LOST))
        private set

    /** Pet ID cards made from this phone: local pet id -> card id. */
    var cards: Map<String, String> by mutableStateOf(loadPetMap(CARDS))
        private set

    fun cardFor(petId: String): String? = cards[petId]

    fun recordCard(petId: String, cardId: String?) {
        cards = if (cardId == null) cards - petId else cards + (petId to cardId)
        savePetMap(CARDS, cards)
    }

    // ---------- Pals ----------

    /** Your pals' pixel pets as last fetched, so the room has a visitor even offline. */
    var pals: List<com.pawpixel.map.Pal> by mutableStateOf(files.readText(PALS)?.let { runCatching { decodePals(Json.parse(it)) }.getOrNull() } ?: emptyList())
        private set

    private var treatsSeen: Set<String> = files.readText(TREATS)?.let { runCatching { Json.parse(it).list.mapNotNull { it.str }.toSet() }.getOrNull() } ?: emptySet()

    /** Signs in, shows your pets to your pals, and fetches theirs. */
    suspend fun refreshPals(pets: List<Pet>, art: (Pet) -> PetArt?): List<com.pawpixel.map.Pal> {
        if (!signIn()) return pals
        client.pals.setPets(pets.filter { !it.remembered }.mapNotNull { pet ->
            val a = art(pet) ?: return@mapNotNull null
            SharedPet(pet.id, NameFilter.forMap(pet.name, pet.species), pet.species.name, a.ears.name, a.look.encode())
        })
        val list = client.pals.list()
        pals = list
        files.writeText(PALS, encodePals(list).stringify())
        return list
    }

    /** The pal's pet dropping by today: one of them, changing daily. Null without pals who share a pet. */
    fun visitor(day: Long): com.pawpixel.map.PalPet? {
        val all = pals.flatMap { it.pets }
        if (all.isEmpty()) return null
        return all[((day % all.size) + all.size).toInt() % all.size]
    }

    /** Treats that haven't been shown yet (and marks them shown). */
    suspend fun newTreats(): List<com.pawpixel.map.Treat> {
        if (pals.isEmpty() || !client.isSignedIn) return emptyList()
        val fresh = client.pals.inbox().filter { it.id !in treatsSeen }
        if (fresh.isNotEmpty()) {
            treatsSeen = (treatsSeen + fresh.map { it.id }).toList().takeLast(200).toSet()
            files.writeText(TREATS, Json.arr(treatsSeen.toList()).stringify())
        }
        return fresh
    }

    fun forgetPals() { pals = emptyList(); files.delete(PALS) }

    private fun encodePals(list: List<com.pawpixel.map.Pal>) = Json.arr(list.map { p ->
        Json.obj("id" to p.id, "since" to p.sinceMs, "pets" to Json.arr(p.pets.map { Json.obj("id" to it.petId, "name" to it.name, "species" to it.species, "ears" to it.ears, "look" to it.look?.encode()) }))
    })
    private fun decodePals(j: Json) = j.list.mapNotNull { p ->
        val id = p["id"].str ?: return@mapNotNull null
        com.pawpixel.map.Pal(id, p["pets"].list.mapNotNull { q ->
            com.pawpixel.map.PalPet(id, q["id"].str ?: return@mapNotNull null, q["name"].str ?: "Pet", q["species"].str ?: "OTHER", q["ears"].str, q["look"].str?.let(com.pawpixel.sprite.PetLook::decode))
        }, p["since"].long ?: 0L)
    }

    /** The open alert for a pet, if one was raised from this phone. */
    fun alertFor(petId: String): String? = lostAlerts[petId]

    fun recordAlert(petId: String, lostId: String?) {
        lostAlerts = if (lostId == null) lostAlerts - petId else lostAlerts + (petId to lostId)
        savePetMap(LOST, lostAlerts)
    }

    /** Signs in with Google/Apple (or the test account). False if the owner cancelled. */
    suspend fun signIn(): Boolean {
        if (client.isSignedIn) return true
        if (usesTestAccount) {
            client.signInWithPassword(settings.testEmail, settings.testPassword)
            return true
        }
        val raw = Sha256.newNonce(platform.secureRandomBytes(32))
        val identity = platform.signInForMap(Sha256.hex(raw), settings.googleWebClientId) ?: return false
        client.signInWithIdToken(identity.provider, identity.idToken, raw)
        return true
    }

    /** Asks for approximate location and snaps it to a ~1 km cell. Null if refused/unavailable. */
    suspend fun locate(): LocationGrid.Cell? {
        val (lat, lng) = platform.approximateLocation() ?: fallbackLocation ?: return null
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
            // Names other owners see go through the word filter (the server checks again).
            SharedPet(pet.id, NameFilter.forMap(pet.name, pet.species), pet.species.name, a.ears.name, a.look.encode())
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
        onAccountDeleted()
    }

    /** Local only: sign out and forget area and choices (e.g. "Delete all my data"). */
    fun forgetLocally() {
        client.signOutLocally()
        files.delete(dir)
        myArea = null
        sharedPetIds = null
        lostAlerts = emptyMap()
        cards = emptyMap()
        pals = emptyList()
        treatsSeen = emptySet()
    }

    private fun forgetChoices() {
        files.delete(AREA); files.delete(SHARED)
        myArea = null
        sharedPetIds = null
    }

    companion object {
        /** A model for the demo map (debug builds): the pretend server, its own files, starting in Naga. */
        fun demo(platform: Platform, files: FileStore): PetMapModel = PetMapModel(
            platform, files, DemoMapServer.SETTINGS, http = DemoMapServer(platform::nowMs), dir = "map-demo",
            fallbackLocation = DemoMapServer.NAGA_LAT to DemoMapServer.NAGA_LNG,
        )
    }
}
