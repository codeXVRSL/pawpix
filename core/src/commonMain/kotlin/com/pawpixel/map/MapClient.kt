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
) {
    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()
    val hasTiles: Boolean get() = tileUrl.contains("{z}")
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

/** [provider] is how the owner signed in ("google", "apple"; blank for test accounts and older sessions). */
data class Session(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val userId: String, val provider: String = "")

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
)

data class Venue(val name: String, val lat: Double, val lng: Double)

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
    val provider: String get() = session?.provider.orEmpty()

    /** Google (Android) or Apple (iOS) identity token. [nonce] is the raw nonce whose hash went to the provider. */
    suspend fun signInWithIdToken(provider: String, idToken: String, nonce: String?) {
        val body = Json.obj("provider" to provider, "id_token" to idToken, "nonce" to nonce).stringify()
        acceptSession(auth("/auth/v1/token?grant_type=id_token", body), provider)
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

    private fun acceptSession(j: Json, provider: String = session?.provider.orEmpty()) {
        val s = Session(
            accessToken = j["access_token"].str ?: throw MapException(MapException.Kind.SERVER, "No token"),
            refreshToken = j["refresh_token"].str ?: "",
            expiresAtMs = nowMs() + (j["expires_in"].long ?: 3600L) * 1000,
            userId = j["user"]["id"].str ?: throw MapException(MapException.Kind.SERVER, "No user"),
            provider = provider,
        )
        session = s
        store.save(Json.obj("a" to s.accessToken, "r" to s.refreshToken, "e" to s.expiresAtMs, "u" to s.userId, "p" to s.provider).stringify())
    }

    private fun parseStoredSession(text: String): Session? = runCatching {
        val j = Json.parse(text)
        Session(j["a"].str!!, j["r"].str!!, j["e"].long!!, j["u"].str!!, j["p"].str.orEmpty())
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

    /**
     * Calls a Supabase Edge Function (supabase/functions/<name>) as the signed-in owner. Unlike [rest],
     * a refusal never signs out: a function that isn't deployed (or set up) mustn't end the session.
     */
    suspend fun function(name: String, args: Json): String {
        val r = send("POST", "/functions/v1/$name", args.stringify(), bearer = token())
        if (r.status in 200..299) return r.body
        val message = runCatching { Json.parse(r.body)["error"].str }.getOrNull() ?: errorMessage(r.body)
        throw if (r.status in 400..499) MapException(MapException.Kind.REFUSED, message ?: tr("Not allowed ({0})", r.status))
        else MapException(MapException.Kind.SERVER, tr("PawPixel's server had a problem ({0})", r.status))
    }

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

    fun isoNow(): String {
        val ms = nowMs()
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

    /**
     * Sign in with Apple only: hands the one-time authorization code (valid 5 minutes) to the server,
     * which swaps it for the refresh token that [deleteAccount] later revokes. The token stays on the server.
     */
    suspend fun storeAppleAuthorizationCode(code: String) {
        api.function("apple-revoke", Json.obj("action" to "store", "code" to code))
    }

    /**
     * Deletes the sign-in account and everything on the server; signs out. A Sign in with Apple account
     * is deleted by the apple-revoke function, which first revokes the app's token at Apple (App Store
     * rule 5.1.1(v)). If that function can't be reached (not deployed, offline for a moment), the account
     * is deleted directly: deleting always wins. Deleting twice is harmless (the second deletes nothing).
     */
    suspend fun deleteAccount() {
        if (api.provider == "apple") {
            val deleted = runCatching { api.function("apple-revoke", Json.obj("action" to "delete_account")) }.isSuccess
            if (deleted) { signOutLocally(); return }
        }
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
            )
        }

    /** Going or not; returns how many are going. Throws [MapException.Kind.FULL] when full. */
    suspend fun rsvp(gatheringId: String, going: Boolean): Int =
        Json.parse(rpc("rsvp", Json.obj("p_id" to gatheringId, "p_going" to going))).int ?: 0

    /** The exact venue: only returned once you've RSVP'd. */
    suspend fun venue(gatheringId: String): Venue? =
        Json.parse(rpc("gathering_details", Json.obj("p_id" to gatheringId))).list.firstOrNull()?.let { v ->
            Venue(v["venue_name"].str ?: return@let null, v["venue_lat"].double ?: 0.0, v["venue_lng"].double ?: 0.0)
        }

    private suspend fun rpc(name: String, args: Json = Json.obj()): String = api.rpc(name, args)
    private suspend fun rest(method: String, path: String, body: String? = null, prefer: String? = null): String = api.rest(method, path, body, prefer)
    private fun isoNow(): String = api.isoNow()
}
