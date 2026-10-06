package com.pawpixel.map

import com.pawpixel.core.Json
import com.pawpixel.core.LocationGrid
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PetLook

/** Build-time settings for the map (see docs/MAP_SETUP.md). Blank values mean "not set up". */
data class MapSettings(
    val supabaseUrl: String,
    val anonKey: String,
    /** Raster tile URL with {z}/{x}/{y}, e.g. MapTiler's 256px street tiles. */
    val tileUrl: String,
    val tileAttribution: String,
    /** Google OAuth "web" client id: Android's Google sign-in asks Google for a token for it. */
    val googleWebClientId: String,
    /** Test builds only: sign in with this email/password instead of Google/Apple. */
    val testEmail: String = "",
    val testPassword: String = "",
    /** A key for the default (CARTO) street tiles: free, no account (docs/MAP_SETUP.md section 4). */
    val tileKey: String = "",
) {
    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    /**
     * Without a tile URL of its own, the build draws OpenFreeMap's vector tiles as PawPixel's own
     * cartoon (free, no key, see [VECTOR_TILEJSON]). With one, it draws those raster tiles,
     * repainted (see [MapStyle]).
     */
    val usesVectorTiles: Boolean get() = !tileUrl.contains("{z}")
    val streetTileUrl: String get() = if (tileKey.isBlank() || tileUrl.contains("key=")) tileUrl else tileUrl + (if ('?' in tileUrl) "&" else "?") + "key=$tileKey"
    val streetAttribution: String get() = if (usesVectorTiles) FREE_ATTRIBUTION else tileAttribution
    val hasTiles: Boolean get() = true

    companion object {
        /**
         * OpenFreeMap: free vector tiles of the whole world (OpenMapTiles schema, OpenStreetMap
         * data), no key, no registration, no limits (https://openfreemap.org). This TileJSON names
         * the current tile URL; the app reads it once per session. Attribution is required and
         * shown on the map.
         */
        const val VECTOR_TILEJSON = "https://tiles.openfreemap.org/planet"
        const val FREE_ATTRIBUTION = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
        /** Vector tiles stop at this zoom; closer views scale a part of this zoom's tile. */
        const val VECTOR_MAX_ZOOM = 14
    }
}

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)
data class HttpResponse(val status: Int, val body: String)

/** The platform's HTTP stack. Throws on network failure (no connection, timeout). */
fun interface Http {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Where the sign-in session is kept (a private file on the device). */
interface SessionStore {
    fun load(): String?
    fun save(json: String?)
}

data class Session(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val userId: String)

class MapException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NOT_SET_UP, OFFLINE, SIGNED_OUT, REFUSED, FULL, SERVER }
}

/** An area (~1 km square) with 3+ owners. */
data class MapArea(val cellId: String, val lat: Double, val lng: Double, val pets: Int)

/** A pet as other owners see it: name and pixel look, never a photo or its owner. */
data class MapPet(val id: String, val name: String, val species: String, val ears: String?, val look: PetLook?, val mine: Boolean)

data class Gathering(
    val id: String, val title: String, val startsAt: String, val cellId: String, val areaLabel: String,
    val capacity: Int, val going: Int, val iAmGoing: Boolean,
    /** The host's note ("Bring water, we'll stop at the fountain"). */
    val details: String? = null,
    /** The walk's ~1 km area, for a pin on the map (null for walks added before this was kept). */
    val cellLat: Double? = null, val cellLng: Double? = null,
    val iAmHost: Boolean = false,
)

data class Venue(val name: String, val lat: Double, val lng: Double)

/** A walk an owner proposes from the app; it shows to everyone once the moderator approves it. */
data class WalkDraft(
    val title: String, val startsAtMs: Long, val areaLabel: String, val venueName: String,
    val venueLat: Double, val venueLng: Double, val capacity: Int = 20, val details: String = "",
)

/** One of your own walks: waiting for approval or on the map. */
data class MyWalk(
    val id: String, val title: String, val startsAt: String, val areaLabel: String, val venueName: String,
    val capacity: Int, val going: Int, val approved: Boolean, val details: String?,
)

/** How big the community is, pilot-wide: no area, no name, no id. */
data class CommunityStats(val owners: Int, val pets: Int, val areas: Int, val walks: Int)

/** What an owner puts on the map for one of their pets. */
data class SharedPet(val localId: String, val name: String, val species: String, val ears: String, val look: String)

/**
 * PawPixel's server (Supabase: Auth + PostgREST), shared by the pet map and family sharing: one
 * sign-in, one session. Errors are [MapException]s with a [MapException.Kind] the UI can explain.
 */
class SupabaseApi(
    val settings: MapSettings,
    private val http: Http,
    private val store: SessionStore,
    val nowMs: () -> Long,
) {
    private var session: Session? = store.load()?.let(::parseStoredSession)

    val isSignedIn: Boolean get() = session != null
    val userId: String? get() = session?.userId

    /** Google (Android) or Apple (iOS) identity token. [nonce] is the raw nonce whose hash went to the provider. */
    suspend fun signInWithIdToken(provider: String, idToken: String, nonce: String?) {
        val body = Json.obj("provider" to provider, "id_token" to idToken, "nonce" to nonce).stringify()
        acceptSession(auth("/auth/v1/token?grant_type=id_token", body))
    }

    /** Test builds only (a seeded test account on a local server). */
    suspend fun signInWithPassword(email: String, password: String) {
        acceptSession(auth("/auth/v1/token?grant_type=password", Json.obj("email" to email, "password" to password).stringify()))
    }

    fun signOutLocally() { session = null; store.save(null) }

    private suspend fun auth(path: String, body: String): Json {
        val r = send("POST", path, body, bearer = settings.anonKey)
        if (r.status !in 200..299) throw MapException(MapException.Kind.REFUSED, tr("Sign-in failed ({0})", r.status))
        return Json.parse(r.body)
    }

    private fun acceptSession(j: Json) {
        val s = Session(
            accessToken = j["access_token"].str ?: throw MapException(MapException.Kind.SERVER, "No token"),
            refreshToken = j["refresh_token"].str ?: "",
            expiresAtMs = nowMs() + (j["expires_in"].long ?: 3600L) * 1000,
            userId = j["user"]["id"].str ?: throw MapException(MapException.Kind.SERVER, "No user"),
        )
        session = s
        store.save(Json.obj("a" to s.accessToken, "r" to s.refreshToken, "e" to s.expiresAtMs, "u" to s.userId).stringify())
    }

    private fun parseStoredSession(text: String): Session? = runCatching {
        val j = Json.parse(text)
        Session(j["a"].str!!, j["r"].str!!, j["e"].long!!, j["u"].str!!)
    }.getOrNull()

    /** A fresh access token, refreshing it if it's about to expire. */
    private suspend fun token(): String {
        val s = session ?: throw MapException(MapException.Kind.SIGNED_OUT, tr("Please sign in"))
        if (s.expiresAtMs - nowMs() > 60_000) return s.accessToken
        val r = send("POST", "/auth/v1/token?grant_type=refresh_token", Json.obj("refresh_token" to s.refreshToken).stringify(), bearer = settings.anonKey)
        if (r.status !in 200..299) {
            signOutLocally()
            throw MapException(MapException.Kind.SIGNED_OUT, tr("Please sign in again"))
        }
        acceptSession(Json.parse(r.body))
        return session!!.accessToken
    }

    suspend fun rpc(name: String, args: Json = Json.obj()): String = rest("POST", "/rest/v1/rpc/$name", args.stringify())

    suspend fun rest(method: String, path: String, body: String? = null, prefer: String? = null): String {
        val r = send(method, path, body, bearer = token(), prefer = prefer)
        when {
            r.status in 200..299 -> return r.body
            r.status == 401 -> { signOutLocally(); throw MapException(MapException.Kind.SIGNED_OUT, tr("Please sign in again")) }
            r.body.contains("gathering is full") -> throw MapException(MapException.Kind.FULL, tr("This gathering is full"))
            r.status in 400..499 -> throw MapException(MapException.Kind.REFUSED, errorMessage(r.body) ?: tr("Not allowed ({0})", r.status))
            else -> throw MapException(MapException.Kind.SERVER, tr("PawPixel's server had a problem ({0})", r.status))
        }
    }

    private fun errorMessage(body: String): String? = runCatching { Json.parse(body)["message"].str }.getOrNull()

    private suspend fun send(method: String, path: String, body: String?, bearer: String, prefer: String? = null): HttpResponse {
        if (!settings.isConfigured) throw MapException(MapException.Kind.NOT_SET_UP, tr("This build isn't connected to PawPixel's server yet"))
        val headers = buildMap {
            put("apikey", settings.anonKey)
            put("Authorization", "Bearer $bearer")
            put("Content-Type", "application/json")
            if (prefer != null) put("Prefer", prefer)
        }
        val r = try {
            http.send(HttpRequest(method, settings.supabaseUrl.trimEnd('/') + path, headers, body))
        } catch (e: MapException) {
            throw e
        } catch (e: Exception) {
            throw MapException(MapException.Kind.OFFLINE, tr("Can't reach PawPixel's server. Check your connection."))
        }
        // Only a real reply goes further: a Wi-Fi login page (public hotspots) or a reply cut off
        // mid-way becomes a plain message here, never "JSON: unexpected '<'" on the owner's screen.
        if (r.status in 200..299 && r.body.isNotBlank() && runCatching { Json.parse(r.body) }.isFailure) {
            throw if (r.body.trimStart().startsWith("<")) MapException(MapException.Kind.OFFLINE, tr("Can't reach PawPixel's server. Check your connection."))
            else MapException(MapException.Kind.SERVER, tr("PawPixel's server had a problem ({0})", r.status))
        }
        return r
    }

    fun isoNow(): String = isoAt(nowMs())

    /** A moment as the server reads it: "2026-10-11T08:00:00Z". */
    fun isoAt(ms: Long): String {
        val days = ms.floorDiv(86_400_000L)
        val rem = ms - days * 86_400_000L
        val (y, m, d) = com.pawpixel.core.LocalClock.civil(days)
        fun p(n: Long, w: Int = 2) = n.toString().padStart(w, '0')
        return "${p(y.toLong(), 4)}-${p(m.toLong())}-${p(d.toLong())}T${p(rem / 3_600_000)}:${p(rem / 60_000 % 60)}:${p(rem / 1000 % 60)}Z"
    }
}

/**
 * Talks to the map backend (Supabase: Auth + PostgREST). All privacy rules are enforced by the
 * server (supabase/migrations); this client only ever sends a grid cell, never a coordinate.
 */
class MapClient(
    settings: MapSettings,
    http: Http,
    store: SessionStore,
    nowMs: () -> Long,
) {
    /** Shares the sign-in with family sharing (see [HouseholdClient]). */
    val api = SupabaseApi(settings, http, store, nowMs)

    /** Lost and Found: alerts, sightings, safe home (same sign-in). */
    val lost = LostClient(api)

    /** Pet ID cards: the page a collar tag's QR opens, and the finder's messages. */
    val cards = PetCardClient(api)

    /** Pals: a small circle whose pixel pets visit each other and send treats. */
    val pals = PalClient(api)

    val isSignedIn: Boolean get() = api.isSignedIn
    val userId: String? get() = api.userId

    suspend fun signInWithIdToken(provider: String, idToken: String, nonce: String?) = api.signInWithIdToken(provider, idToken, nonce)
    suspend fun signInWithPassword(email: String, password: String) = api.signInWithPassword(email, password)
    fun signOutLocally() = api.signOutLocally()

    // ---------- Joining, updating and leaving ----------

    /** Joins (or updates): confirms 18+ and consent, replaces your pets on the map, and sets your area. */
    suspend fun join(pets: List<SharedPet>, cell: LocationGrid.Cell) {
        val me = userId ?: throw MapException(MapException.Kind.SIGNED_OUT, tr("Please sign in"))
        rest("POST", "/rest/v1/map_profiles", Json.obj("user_id" to me, "confirmed_adult" to true).stringify(), prefer = "resolution=merge-duplicates")
        rest("DELETE", "/rest/v1/map_pets?owner_id=eq.$me")
        if (pets.isNotEmpty()) {
            val rows = Json.arr(pets.take(5).map {
                Json.obj("owner_id" to me, "local_id" to it.localId, "name" to it.name.take(24).ifBlank { "Pet" },
                    "species" to it.species, "ears" to it.ears, "look" to it.look)
            })
            rest("POST", "/rest/v1/map_pets", rows.stringify())
        }
        setArea(cell)
    }

    /** Refreshes your area (presence expires after 14 days without opening the map). */
    suspend fun setArea(cell: LocationGrid.Cell) {
        val me = userId ?: throw MapException(MapException.Kind.SIGNED_OUT, tr("Please sign in"))
        val body = Json.obj("owner_id" to me, "cell_id" to cell.id, "cell_lat" to cell.centerLat, "cell_lng" to cell.centerLng,
            "updated_at" to isoNow()).stringify()
        rest("POST", "/rest/v1/map_presence", body, prefer = "resolution=merge-duplicates")
    }

    /** True if you've joined (have a map profile). */
    suspend fun hasJoined(): Boolean {
        val me = userId ?: return false
        return Json.parse(rest("GET", "/rest/v1/map_profiles?user_id=eq.$me&select=user_id")).list.isNotEmpty()
    }

    suspend fun leaveMap() { rpc("leave_map") }

    /** Deletes the sign-in account and everything on the server; signs out. */
    suspend fun deleteAccount() {
        rpc("delete_account")
        signOutLocally()
    }

    // ---------- Reading the map ----------

    suspend fun nearbyAreas(around: LocationGrid.Cell, radiusKm: Double = 10.0): List<MapArea> =
        Json.parse(rpc("nearby_cells", Json.obj("p_cell_lat" to around.centerLat, "p_cell_lng" to around.centerLng, "p_radius_km" to radiusKm))).list
            .mapNotNull { c ->
                MapArea(c["cell_id"].str ?: return@mapNotNull null, c["cell_lat"].double ?: 0.0, c["cell_lng"].double ?: 0.0, c["pets"].int ?: 0)
            }

    suspend fun petsInArea(cellId: String): List<MapPet> =
        Json.parse(rpc("pets_in_cell", Json.obj("p_cell_id" to cellId))).list.mapNotNull { p ->
            MapPet(
                id = p["pet_id"].str ?: return@mapNotNull null,
                name = p["name"].str ?: "Pet",
                species = p["species"].str ?: "OTHER",
                ears = p["ears"].str,
                look = p["look"].str?.let(PetLook::decode),
                mine = p["mine"].bool ?: false,
            )
        }

    suspend fun blockOwnerOf(petId: String) { rpc("block_pet_owner", Json.obj("p_pet_id" to petId)) }

    suspend fun report(petId: String, reason: String, details: String?) {
        rpc("report_pet", Json.obj("p_pet_id" to petId, "p_reason" to reason, "p_details" to details))
    }

    // ---------- Gatherings ----------

    suspend fun gatherings(): List<Gathering> =
        Json.parse(rest("GET", "/rest/v1/gatherings_public?select=*")).list.mapNotNull { g ->
            Gathering(
                id = g["id"].str ?: return@mapNotNull null,
                title = g["title"].str ?: "",
                startsAt = g["starts_at"].str ?: "",
                cellId = g["cell_id"].str ?: "",
                areaLabel = g["area_label"].str ?: "",
                capacity = g["capacity"].int ?: 0,
                going = g["going"].int ?: 0,
                iAmGoing = g["i_am_going"].bool ?: false,
                details = g["details"].str?.ifBlank { null },
                cellLat = g["cell_lat"].double, cellLng = g["cell_lng"].double,
                iAmHost = g["i_am_host"].bool ?: false,
            )
        }

    // ---------- Hosting walks ----------

    /**
     * Proposes a walk: it waits for the moderator, then shows to everyone. The venue is a public
     * place the host picked on the map; its ~1 km cell is what the list shows before RSVP.
     * Returns the walk's id. Throws [MapException.Kind.REFUSED] with the server's reason (too many
     * waiting, a time out of range).
     */
    suspend fun hostWalk(draft: WalkDraft): String {
        val cell = LocationGrid.snap(draft.venueLat, draft.venueLng)
        val args = Json.obj(
            "p_title" to draft.title.trim().take(80).ifBlank { tr("Pet walk") },
            "p_starts_at" to api.isoAt(draft.startsAtMs),
            "p_cell_id" to cell.id, "p_cell_lat" to cell.centerLat, "p_cell_lng" to cell.centerLng,
            "p_area_label" to draft.areaLabel.trim().take(60).ifBlank { tr("Near {0}", draft.venueName.trim().take(40)) },
            "p_venue_name" to draft.venueName.trim().take(80),
            "p_venue_lat" to draft.venueLat, "p_venue_lng" to draft.venueLng,
            "p_capacity" to draft.capacity.coerceIn(2, 200),
            "p_details" to draft.details.trim().take(300).ifBlank { null },
        )
        return Json.parse(rpc("host_walk", args)).str ?: throw MapException(MapException.Kind.SERVER, "No id")
    }

    /** Your own walks, waiting for approval or on the map. */
    suspend fun myWalks(): List<MyWalk> =
        Json.parse(rest("GET", "/rest/v1/my_walks?select=*")).list.mapNotNull { w ->
            MyWalk(
                id = w["id"].str ?: return@mapNotNull null, title = w["title"].str ?: "", startsAt = w["starts_at"].str ?: "",
                areaLabel = w["area_label"].str ?: "", venueName = w["venue_name"].str ?: "", capacity = w["capacity"].int ?: 0,
                going = w["going"].int ?: 0, approved = w["approved"].bool ?: false, details = w["details"].str?.ifBlank { null },
            )
        }

    suspend fun cancelWalk(id: String) { rpc("cancel_walk", Json.obj("p_id" to id)) }

    /** Pilot-wide totals: owners on the map, their pets, areas with 3+ owners, walks coming up. */
    suspend fun communityStats(): CommunityStats {
        val r = Json.parse(rpc("community_stats")).list.firstOrNull() ?: return CommunityStats(0, 0, 0, 0)
        return CommunityStats(r["owners"].int ?: 0, r["pets"].int ?: 0, r["areas"].int ?: 0, r["walks"].int ?: 0)
    }

    /** Going or not; returns how many are going. Throws [MapException.Kind.FULL] when full. */
    suspend fun rsvp(gatheringId: String, going: Boolean): Int =
        Json.parse(rpc("rsvp", Json.obj("p_id" to gatheringId, "p_going" to going))).int ?: 0

    /** Who's coming, as pixel pets: the map pets of owners who said they're going (never the owners). */
    suspend fun gatheringPets(gatheringId: String): List<MapPet> =
        Json.parse(rpc("gathering_pets", Json.obj("p_id" to gatheringId))).list.mapNotNull { p ->
            MapPet(p["pet_id"].str ?: return@mapNotNull null, p["name"].str ?: "Pet", p["species"].str ?: "OTHER", p["ears"].str,
                p["look"].str?.let(PetLook::decode), p["mine"].bool ?: false)
        }

    /** The exact venue: only returned once you've RSVP'd. */
    suspend fun venue(gatheringId: String): Venue? =
        Json.parse(rpc("gathering_details", Json.obj("p_id" to gatheringId))).list.firstOrNull()?.let { v ->
            Venue(v["venue_name"].str ?: return@let null, v["venue_lat"].double ?: 0.0, v["venue_lng"].double ?: 0.0)
        }

    private suspend fun rpc(name: String, args: Json = Json.obj()): String = api.rpc(name, args)
    private suspend fun rest(method: String, path: String, body: String? = null, prefer: String? = null): String = api.rest(method, path, body, prefer)
    private fun isoNow(): String = api.isoNow()
}
