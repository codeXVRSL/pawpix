package com.pawpixel

import com.pawpixel.core.Json
import com.pawpixel.core.LocationGrid
import com.pawpixel.map.Http
import com.pawpixel.map.HttpRequest
import com.pawpixel.map.HttpResponse
import com.pawpixel.map.MapClient
import com.pawpixel.map.MapException
import com.pawpixel.map.WalkDraft
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

    @Test fun hostingAWalkSendsTheVenueAndItsCellAndReadsItBack() {
        val c = signedIn()
        reply = { r ->
            when {
                r.url.endsWith("host_walk") -> HttpResponse(200, "\"walk1\"")
                r.url.contains("my_walks") -> HttpResponse(200, """[{"id":"walk1","title":"Sunrise walk","starts_at":"2026-10-11T22:00:00+00:00","area_label":"Plaza Rizal area","venue_name":"Plaza Rizal fountain","capacity":15,"going":1,"approved":false,"details":null}]""")
                r.url.endsWith("community_stats") -> HttpResponse(200, """[{"owners":12,"pets":19,"areas":2,"walks":1}]""")
                else -> HttpResponse(200, "[]")
            }
        }
        val id = runSync { c.hostWalk(WalkDraft("  Sunrise walk ", 1_791_194_400_000L, "", "Plaza Rizal fountain", 13.6238, 123.1851, 15, "Bring water")) }
        assertEquals("walk1", id)
        val sent = Json.parse(log.single { it.url.endsWith("host_walk") }.body!!)
        assertEquals("Sunrise walk", sent["p_title"].str)
        assertEquals("2026-10-05T10:00:00Z", sent["p_starts_at"].str)
        val cell = LocationGrid.snap(13.6238, 123.1851)
        assertEquals(cell.id, sent["p_cell_id"].str)
        assertEquals(cell.centerLat, sent["p_cell_lat"].double)
        assertEquals(13.6238, sent["p_venue_lat"].double)
        assertEquals("Near Plaza Rizal fountain", sent["p_area_label"].str, "a blank area label is made from the venue")
        val mine = runSync { c.myWalks() }.single()
        assertTrue(!mine.approved && mine.going == 1 && mine.details == null)
        val stats = runSync { c.communityStats() }
        assertEquals(19, stats.pets)
        runSync { c.cancelWalk("walk1") }
        assertTrue(log.last().url.endsWith("cancel_walk"))
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

class MapStyleTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun mapPixelsAreSortedIntoTheCartoonPalette() {
        assertEquals(com.pawpixel.map.MapStyle.WATER, com.pawpixel.map.MapStyle.paint(rgb(0xAA, 0xD3, 0xDF)), "light blue water")
        assertEquals(com.pawpixel.map.MapStyle.PARK, com.pawpixel.map.MapStyle.paint(rgb(0xD8, 0xE8, 0xC8)), "pale park green")
        assertEquals(com.pawpixel.map.MapStyle.LAND, com.pawpixel.map.MapStyle.paint(rgb(0xFB, 0xF8, 0xF3)), "cream land")
        assertEquals(com.pawpixel.map.MapStyle.ROAD, com.pawpixel.map.MapStyle.paint(rgb(0xFF, 0xFF, 0xFF)), "white road")
        assertEquals(com.pawpixel.map.MapStyle.BUILDING, com.pawpixel.map.MapStyle.paint(rgb(0xE8, 0xE6, 0xE1)), "grey building")
        assertEquals(com.pawpixel.map.MapStyle.MAIN_ROAD, com.pawpixel.map.MapStyle.paint(rgb(0xFF, 0xF0, 0xB0)), "yellow main road")
        assertEquals(com.pawpixel.map.MapStyle.HIGHWAY, com.pawpixel.map.MapStyle.paint(rgb(0xFF, 0xC0, 0xA0)), "orange highway")
        assertEquals(com.pawpixel.map.MapStyle.INK, com.pawpixel.map.MapStyle.paint(rgb(0x40, 0x40, 0x40)), "dark text")
        assertEquals(0, com.pawpixel.map.MapStyle.paint(0x10FFFFFF), "transparent stays transparent")
        val tile = com.pawpixel.sprite.PixelImage(2, 1, intArrayOf(rgb(0xAA, 0xD3, 0xDF), rgb(0xFF, 0xFF, 0xFF)))
        val out = com.pawpixel.map.MapStyle.cartoon(tile)
        assertEquals(listOf(com.pawpixel.map.MapStyle.WATER, com.pawpixel.map.MapStyle.ROAD), out.pixels.toList())
        assertEquals(rgb(0xAA, 0xD3, 0xDF), tile.pixels[0], "the original tile is untouched")
    }

    @Test fun freeVectorTilesAreTheDefaultAndABuildsOwnRasterWins() {
        val free = MapSettings("", "", "", "", "")
        assertTrue(free.usesVectorTiles && free.streetAttribution.contains("OpenFreeMap"))
        val own = MapSettings("", "", "https://tiles.example/{z}/{x}/{y}.png", "© Example", "", tileKey = "k1")
        assertTrue(!own.usesVectorTiles)
        assertEquals("https://tiles.example/{z}/{x}/{y}.png?key=k1", own.streetTileUrl)
        assertEquals("© Example", own.streetAttribution)
        assertEquals("https://t.example/{z}/{x}/{y}.png?key=abc", MapSettings("", "", "https://t.example/{z}/{x}/{y}.png?key=abc", "", "", tileKey = "k1").streetTileUrl)
    }
}

class MvtTest {
    @Test fun aWrittenTileReadsBackWithItsLayersTagsAndGeometry() {
        val bytes = com.pawpixel.map.MvtWriter()
            .layer("water") { polygon(intArrayOf(0, 0, 4096, 0, 4096, 4096, 0, 4096, 0, 0), "class" to "lake") }
            .layer("transportation") {
                line(intArrayOf(10, 20, 500, 20, 500, 900), "class" to "primary")
                line(intArrayOf(0, 4000, 4096, 4000), "class" to "minor", "name" to "Magsaysay Avenue")
            }
            .layer("place") { point(2048, 2048, "name" to "Naga", "class" to "city") }
            .bytes()
        val tile = com.pawpixel.map.Mvt.decode(bytes)
        assertEquals(setOf("water", "transportation", "place"), tile.layers.keys)
        val water = tile.layers.getValue("water").features.single()
        assertTrue(water.isPolygon)
        assertEquals("lake", water.str("class"))
        assertEquals(listOf(0, 0, 4096, 0, 4096, 4096, 0, 4096, 0, 0), water.geometry.single().toList())
        val roads = tile.layers.getValue("transportation").features
        assertEquals(2, roads.size)
        assertEquals(listOf(10, 20, 500, 20, 500, 900), roads[0].geometry.single().toList())
        assertEquals("Magsaysay Avenue", roads[1].str("name"))
        assertEquals(4096, tile.layers.getValue("transportation").extent)
        val naga = tile.layers.getValue("place").features.single()
        assertTrue(naga.isPoint)
        assertEquals(listOf(2048, 2048), naga.geometry.single().toList())
        assertEquals("city", naga.str("class"))
    }

    @Test fun junkDoesNotCrashTheReader() {
        val tile = com.pawpixel.map.Mvt.decode(byteArrayOf(0x1a, 0x7f, 0x01, 0x02))
        assertTrue(tile.layers.isEmpty() || tile.layers.values.all { it.features.isEmpty() })
        assertTrue(com.pawpixel.map.Mvt.decode(ByteArray(0)).layers.isEmpty())
    }
}

class LostClientTest {
    private val settings = MapSettings("https://x.supabase.co", "anon", "", "", "")
    private val log = ArrayList<HttpRequest>()
    private val saved = arrayOfNulls<String>(1)
    private val store = object : SessionStore {
        override fun load() = saved[0]
        override fun save(json: String?) { saved[0] = json }
    }
    private var reply: (HttpRequest) -> HttpResponse = { HttpResponse(200, "[]") }
    private val http = Http { r -> log += r; reply(r) }

    private fun signedIn(): MapClient {
        reply = { HttpResponse(200, """{"access_token":"tok1","refresh_token":"ref1","expires_in":3600,"user":{"id":"u1"}}""") }
        val c = MapClient(settings, http, store) { 1_700_000_000_000L }
        runSync { c.signInWithIdToken("google", "idtok", "raw-nonce") }
        reply = { HttpResponse(200, "[]") }
        log.clear()
        return c
    }

    @Test fun anAlertCarriesTheLookTheSpotAndSmallPhotosAsBase64() {
        val c = signedIn()
        reply = { HttpResponse(200, "\"lost-1\"") }
        val photo = ByteArray(10) { it.toByte() }
        val big = ByteArray(com.pawpixel.map.LostClient.MAX_PHOTO_BYTES + 1)
        val id = runSync {
            c.lost.report(com.pawpixel.map.LostDraft("Kape", "DOG", "FLOPPY", "3;abc;000", "  Brown, red collar  ", 13.62, 123.19, 1_700_000_000_000L - 3_600_000L, listOf(photo, big)))
        }
        assertEquals("lost-1", id)
        val body = Json.parse(log.single().body!!)
        assertTrue(log.single().url.endsWith("/rest/v1/rpc/report_lost"))
        assertEquals("Kape", body["p_name"].str)
        assertEquals("Brown, red collar", body["p_description"].str)
        assertEquals(13.62, body["p_lat"].double)
        assertEquals("2023-11-14T21:13:20Z", body["p_last_seen_at"].str)
        assertEquals(listOf(com.pawpixel.core.Base64.encode(photo)), body["p_photos"].list.map { it.str }, "the oversized photo is left out")
    }

    @Test fun nearbyAlertsAndTheirDetailsParse() {
        val c = signedIn()
        reply = { r ->
            if (r.url.endsWith("lost_nearby")) HttpResponse(200, """[{"id":"l1","name":"Kape","species":"DOG","ears":"FLOPPY","look":"1;2b2430;${"0".repeat(64)}","description":"Brown aspin",
                "last_seen_lat":13.63,"last_seen_lng":123.2,"last_seen_at":"2026-10-06T01:00:00+00:00","created_at":"2026-10-06T02:00:00+00:00","photo_count":2,"sightings":1,"mine":false,"distance_km":0.8}]""")
            else HttpResponse(200, """[{"id":"l1","name":"Kape","species":"DOG","ears":"FLOPPY","look":"","description":null,"photos":["AQID"],
                "last_seen_lat":13.63,"last_seen_lng":123.2,"last_seen_at":"2026-10-06T01:00:00+00:00","created_at":"2026-10-06T02:00:00+00:00","found_at":null,"mine":true}]""")
        }
        val near = runSync { c.lost.nearby(13.62, 123.19) }
        assertEquals(1, near.size)
        assertEquals("Kape", near[0].name); assertEquals(2, near[0].photoCount); assertEquals(0.8, near[0].distanceKm)
        assertTrue(near[0].look != null, "the look code decodes to a pixel pet")
        assertEquals(com.pawpixel.map.IsoTime.parseMs("2026-10-06T01:00:00Z"), near[0].lastSeenAtMs)
        val d = runSync { c.lost.details("l1") }!!
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), d.photos.single().toList())
        assertTrue(d.mine); assertEquals(null, d.foundAtMs)
        val args = Json.parse(log.last().body!!)
        assertEquals("l1", args["p_id"].str)
    }

    @Test fun aSightingSafeHomeAndCancelHitTheRightCalls() {
        val c = signedIn()
        reply = { HttpResponse(200, "\"s1\"") }
        runSync { c.lost.reportSighting("l1", 13.631, 123.2, "  near the market  ", null) }
        val s = Json.parse(log.last().body!!)
        assertEquals("near the market", s["p_note"].str); assertEquals("l1", s["p_lost_id"].str)
        reply = { HttpResponse(200, "") }
        runSync { c.lost.markFound("l1"); c.lost.cancel("l2") }
        assertTrue(log[log.size - 2].url.endsWith("/rpc/mark_found") && log.last().url.endsWith("/rpc/cancel_lost"))
        assertEquals("l2", Json.parse(log.last().body!!)["p_id"].str)
    }
}
