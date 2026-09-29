package com.pawpixel

import com.pawpixel.core.Json
import com.pawpixel.core.LocationGrid
import com.pawpixel.map.Http
import com.pawpixel.map.HttpRequest
import com.pawpixel.map.HttpResponse
import com.pawpixel.map.MapClient
import com.pawpixel.map.MapException
import com.pawpixel.map.MapSettings
import com.pawpixel.map.SessionStore
import com.pawpixel.map.SharedPet
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs a suspend block whose fakes never actually suspend. */
fun <T> runSync(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}

class MapClientTest {
    private val settings = MapSettings("https://x.supabase.co", "anon", "", "", "")
    private val log = ArrayList<HttpRequest>()
    private var now = 1_700_000_000_000L
    private val saved = arrayOfNulls<String>(1)
    private val store = object : SessionStore {
        override fun load() = saved[0]
        override fun save(json: String?) { saved[0] = json }
    }
    private var reply: (HttpRequest) -> HttpResponse = { HttpResponse(200, "[]") }
    private val http = Http { r -> log += r; reply(r) }
    private val signIn = """{"access_token":"tok1","refresh_token":"ref1","expires_in":3600,"user":{"id":"u1"}}"""

    private fun client(s: MapSettings = settings) = MapClient(s, http, store) { now }

    private fun signedIn(): MapClient {
        reply = { HttpResponse(200, signIn) }
        val c = client()
        runSync { c.signInWithIdToken("google", "idtok", "raw-nonce") }
        reply = { HttpResponse(200, "[]") }
        log.clear()
        return c
    }

    @Test fun notSetUpBuildsSayWhy() {
        val c = client(MapSettings("", "", "", "", ""))
        val e = runCatching { runSync { c.signInWithIdToken("google", "t", null) } }.exceptionOrNull() as MapException
        assertEquals(MapException.Kind.NOT_SET_UP, e.kind)
        assertTrue(log.isEmpty(), "nothing sent")
    }

    @Test fun signInExchangesTheProviderTokenAndRemembersTheSession() {
        val c = signedIn()
        assertTrue(c.isSignedIn)
        assertEquals("u1", c.userId)
        // A new client on the same device is still signed in.
        assertEquals("u1", client().userId)
        runSync { c.leaveMap() }
        assertEquals("Bearer tok1", log.last().headers["Authorization"])
    }

    @Test fun expiringTokensAreRefreshedAndBadOnesSignOut() {
        val c = signedIn()
        now += 3_570_000 // 30 s before expiry
        reply = { r -> if (r.url.contains("refresh_token")) HttpResponse(200, signIn.replace("tok1", "tok2")) else HttpResponse(200, "[]") }
        runSync { c.leaveMap() }
        assertTrue(log.any { it.url.endsWith("grant_type=refresh_token") && it.body!!.contains("ref1") })
        assertEquals("Bearer tok2", log.last().headers["Authorization"])
        reply = { HttpResponse(401, "{}") }
        val e = runCatching { runSync { c.leaveMap() } }.exceptionOrNull() as MapException
        assertEquals(MapException.Kind.SIGNED_OUT, e.kind)
        assertFalse(c.isSignedIn)
    }

    @Test fun joiningSendsOnlyTheGridCellNeverTheLocation() {
        val c = signedIn()
        val lat = 13.62184; val lng = 123.19481
        val cell = LocationGrid.snap(lat, lng)
        runSync { c.join(listOf(SharedPet("p1", "Mochi", "CAT", "POINTY", "1;e08a3a;" + "0".repeat(64))), cell) }
        val paths = log.map { it.method + " " + it.url.substringAfter(".co") }
        assertEquals(listOf("POST /rest/v1/map_profiles", "DELETE /rest/v1/map_pets?owner_id=eq.u1", "POST /rest/v1/map_pets", "POST /rest/v1/map_presence"), paths)
        val everything = log.joinToString { it.body.orEmpty() }
        assertFalse(everything.contains("13.62184") || everything.contains("123.19481"), "raw coordinates never leave: $everything")
        val presence = Json.parse(log.last().body!!)
        assertEquals(cell.id, presence["cell_id"].str)
        assertTrue(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ").matches(presence["updated_at"].str!!), presence["updated_at"].str!!)
        assertEquals("resolution=merge-duplicates", log.last().headers["Prefer"])
        assertTrue(log[0].body!!.contains("\"confirmed_adult\":true"))
    }

    @Test fun readsAreasPetsAndGatherings() {
        val c = signedIn()
        val look = "1;e08a3a,f4f1ea;" + "0".repeat(40) + "1".repeat(24)
        reply = { r ->
            when {
                r.url.endsWith("nearby_cells") -> HttpResponse(200, """[{"cell_id":"g1000:1:2","cell_lat":13.6,"cell_lng":123.2,"pets":5}]""")
                r.url.endsWith("pets_in_cell") -> HttpResponse(200, """[{"pet_id":"a","name":"Mochi","species":"CAT","ears":"POINTY","look":"$look","mine":false},{"pet_id":"b","name":"X","species":"DOG","ears":null,"look":"junk","mine":true}]""")
                r.url.contains("gatherings_public") -> HttpResponse(200, """[{"id":"g","title":"Walk","starts_at":"2099-01-01T08:00:00+00:00","cell_id":"c","area_label":"Plaza Rizal area","capacity":30,"going":4,"i_am_going":true}]""")
                r.url.endsWith("rsvp") -> HttpResponse(400, """{"message":"gathering is full"}""")
                else -> HttpResponse(200, "[]")
            }
        }
        val areas = runSync { c.nearbyAreas(LocationGrid.snap(13.6, 123.2)) }
        assertEquals(5, areas.single().pets)
        val pets = runSync { c.petsInArea("g1000:1:2") }
        assertEquals("Mochi", pets[0].name)
        assertTrue(pets[0].look != null && pets[1].look == null, "bad look codes are dropped, not crashed on")
        assertTrue(pets[1].mine)
        val g = runSync { c.gatherings() }.single()
        assertTrue(g.iAmGoing && g.going == 4 && g.areaLabel == "Plaza Rizal area")
        val e = runCatching { runSync { c.rsvp("g", true) } }.exceptionOrNull() as MapException
        assertEquals(MapException.Kind.FULL, e.kind)
    }

    @Test fun offlineIsReportedPlainly() {
        val c = signedIn()
        reply = { throw IllegalStateException("socket closed") }
        val e = runCatching { runSync { c.gatherings() } }.exceptionOrNull() as? MapException ?: fail("not a MapException")
        assertEquals(MapException.Kind.OFFLINE, e.kind)
    }

    @Test fun deletingTheAccountSignsOut() {
        val c = signedIn()
        runSync { c.deleteAccount() }
        assertTrue(log.single().url.endsWith("/rest/v1/rpc/delete_account"))
        assertFalse(c.isSignedIn)
        assertEquals(null, saved[0])
    }
}
