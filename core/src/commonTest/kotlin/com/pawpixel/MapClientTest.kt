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

    private fun signedInWithApple(): MapClient {
        reply = { HttpResponse(200, signIn) }
        val c = client()
        runSync { c.signInWithIdToken("apple", "idtok", "raw-nonce") }
        reply = { HttpResponse(200, "{}") }
        log.clear()
        return c
    }

    @Test fun appleSignInHandsTheAuthorizationCodeToTheServer() {
        val c = signedInWithApple()
        runSync { c.storeAppleAuthorizationCode("code-1") }
        val r = log.single()
        assertTrue(r.url.endsWith("/functions/v1/apple-revoke"))
        assertEquals("Bearer tok1", r.headers["Authorization"])
        assertEquals("store", com.pawpixel.core.Json.parse(r.body!!)["action"].str)
        assertEquals("code-1", com.pawpixel.core.Json.parse(r.body!!)["code"].str)
    }

    @Test fun deletingAnAppleAccountGoesThroughTheRevokingFunction() {
        val c = signedInWithApple()
        // The provider survives the app restarting (it's in the saved session).
        val restarted = client()
        reply = { HttpResponse(200, """{"deleted":true,"revoked":true}""") }
        runSync { restarted.deleteAccount() }
        assertEquals(listOf("/functions/v1/apple-revoke"), log.map { it.url.removePrefix("https://x.supabase.co") })
        assertEquals("delete_account", com.pawpixel.core.Json.parse(log[0].body!!)["action"].str)
        assertEquals("Bearer tok1", log[0].headers["Authorization"])
        assertEquals("anon", log[0].headers["apikey"])
        assertFalse(restarted.isSignedIn)
        assertTrue(c.isSignedIn, "only the client that deleted forgets in memory; the store is cleared")
        assertEquals(null, saved[0])
    }

    @Test fun ifTheRevokingFunctionFailsTheAccountIsStillDeleted() {
        val c = signedInWithApple()
        reply = { r -> if (r.url.contains("/functions/")) HttpResponse(500, """{"error":"Server error"}""") else HttpResponse(200, "") }
        runSync { c.deleteAccount() }
        assertTrue(log.last().url.endsWith("/rest/v1/rpc/delete_account"))
        assertFalse(c.isSignedIn)
        // Offline for the function: the same.
        val d = signedInWithApple()
        reply = { r -> if (r.url.contains("/functions/")) throw IllegalStateException("timeout") else HttpResponse(200, "") }
        runSync { d.deleteAccount() }
        assertTrue(log.last().url.endsWith("/rest/v1/rpc/delete_account"))
        // Not deployed (404), or refused (401): the session isn't dropped before the account is deleted.
        for (status in listOf(404, 401)) {
            val e = signedInWithApple()
            reply = { r -> if (r.url.contains("/functions/")) HttpResponse(status, """{"message":"nope"}""") else HttpResponse(204, "") }
            runSync { e.deleteAccount() }
            assertEquals(listOf("/functions/v1/apple-revoke", "/rest/v1/rpc/delete_account"), log.map { it.url.removePrefix("https://x.supabase.co") }, "status $status")
            assertEquals("Bearer tok1", log[1].headers["Authorization"])
            assertFalse(e.isSignedIn)
        }
    }

    @Test fun aFailedCodeHandOffKeepsTheSession() {
        val c = signedInWithApple()
        reply = { HttpResponse(401, """{"error":"Sign in first"}""") }
        val e = runCatching { runSync { c.storeAppleAuthorizationCode("code-1") } }.exceptionOrNull() as MapException
        assertEquals(MapException.Kind.REFUSED, e.kind)
        assertEquals("Sign in first", e.message)
        assertTrue(c.isSignedIn)
    }

    @Test fun refreshingTheSessionKeepsTheProvider() {
        val c = signedInWithApple()
        now += 3_570_000
        reply = { r -> if (r.url.contains("refresh_token")) HttpResponse(200, signIn.replace("tok1", "tok2")) else HttpResponse(200, "") }
        runSync { c.leaveMap() }
        log.clear()
        runSync { client().deleteAccount() }
        assertTrue(log.first().url.endsWith("/functions/v1/apple-revoke"))
    }
}

class MapMathTest {
    @Test fun sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", com.pawpixel.map.Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", com.pawpixel.map.Sha256.hex("abc"))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            com.pawpixel.map.Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
        assertEquals(32, com.pawpixel.map.Sha256.newNonce().length)
    }

    @Test fun mercatorRoundTripsAndPicksTiles() {
        val m = com.pawpixel.map.WebMercator
        val z = 15
        val x = m.x(123.1948, z); val y = m.y(13.6218, z)
        assertTrue(kotlin.math.abs(m.lng(x, z) - 123.1948) < 1e-9 && kotlin.math.abs(m.lat(y, z) - 13.6218) < 1e-9)
        assertEquals(27597, (x / 256).toInt()) // Naga City tile column at zoom 15: (123.1948+180)/360 * 2^15
        assertEquals("https://t/15/1/2.png?k=1", m.tileUrl("https://t/{z}/{x}/{y}.png?k=1", 15, 1, 2))
    }
}

class IsoTimeTest {
    @Test fun parsesServerTimestamps() {
        val t = com.pawpixel.map.IsoTime
        assertEquals(0L, t.parseMs("1970-01-01T00:00:00Z"))
        assertEquals(4070908800000L, t.parseMs("2099-01-01T00:00:00+00:00"))
        assertEquals(t.parseMs("2026-10-04T00:00:00Z"), t.parseMs("2026-10-04T08:00:00+08:00"))
        assertEquals(1759536000123L, t.parseMs("2025-10-04T00:00:00.123456Z"))
        assertEquals(null, t.parseMs("next sunday"))
    }
}
