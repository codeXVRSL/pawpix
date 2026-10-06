package com.pawpixel.app

import com.pawpixel.core.Ids
import com.pawpixel.core.Json
import com.pawpixel.core.LocationGrid
import com.pawpixel.map.Http
import com.pawpixel.map.HttpRequest
import com.pawpixel.map.HttpResponse
import com.pawpixel.map.MapSettings

/**
 * A pretend map server inside the app, for trying the pet map before the real one is set up
 * (debug builds only, "Try the demo map"). It answers the same requests the real server does,
 * from memory: owners and pixel pets around your area, a walk at Plaza Rizal, RSVPs, hosting a
 * walk (approved "by the moderator" after a short wait), reports and blocks. Nothing leaves the
 * phone, and it forgets everything when the app is closed.
 */
class DemoMapServer(private val nowMs: () -> Long) : Http {
    private val me = "demo-owner"
    private var joined = false
    private var myCell: LocationGrid.Cell? = null
    private var myPets: List<Json> = emptyList()
    private val blocked = HashSet<String>()
    private val going = HashSet<String>()
    private val walks = ArrayList<Walk>()
    private var seededAround: String? = null
    private val fakeOwners = ArrayList<FakeOwner>()

    private class FakeOwner(val id: String, val cell: LocationGrid.Cell, val pets: List<FakePet>)
    private class FakePet(val id: String, val name: String, val species: String, val ears: String, val look: String)
    private class Walk(
        val id: String, val title: String, val startsAtMs: Long, val cell: LocationGrid.Cell, val areaLabel: String,
        val venueName: String, val venueLat: Double, val venueLng: Double, val capacity: Int, val details: String?,
        val hostId: String, var approved: Boolean, val proposedAtMs: Long, var going: Int,
    )

    override suspend fun send(request: HttpRequest): HttpResponse {
        val path = request.url.substringAfter("://").substringAfter('/').let { "/$it" }
        val body = request.body?.let { runCatching { Json.parse(it) }.getOrNull() } ?: Json.Null
        fun ok(json: String = "[]") = HttpResponse(200, json)
        return when {
            path.startsWith("/auth/v1/token") -> ok(Json.obj("access_token" to "demo", "refresh_token" to "demo", "expires_in" to 86_400, "user" to Json.obj("id" to me)).stringify())
            path.startsWith("/rest/v1/map_profiles") && request.method == "GET" -> ok(if (joined) """[{"user_id":"$me"}]""" else "[]")
            path.startsWith("/rest/v1/map_profiles") -> { joined = true; ok() }
            path.startsWith("/rest/v1/map_pets") && request.method == "DELETE" -> { myPets = emptyList(); ok() }
            path.startsWith("/rest/v1/map_pets") -> { myPets = body.list; ok() }
            path.startsWith("/rest/v1/map_presence") -> {
                val lat = body["cell_lat"].double ?: NAGA_LAT; val lng = body["cell_lng"].double ?: NAGA_LNG
                myCell = LocationGrid.snap(lat, lng); seedAround(myCell!!); ok()
            }
            path.endsWith("/rpc/leave_map") -> { joined = false; myPets = emptyList(); myCell = null; ok() }
            path.endsWith("/rpc/delete_account") -> { joined = false; myPets = emptyList(); myCell = null; going.clear(); walks.removeAll { it.hostId == me }; ok() }
            path.endsWith("/rpc/nearby_cells") -> {
                val cell = myCell ?: LocationGrid.snap(body["p_cell_lat"].double ?: NAGA_LAT, body["p_cell_lng"].double ?: NAGA_LNG)
                seedAround(cell)
                ok(Json.arr(cellsWithPets().map { (c, n) -> Json.obj("cell_id" to c.id, "cell_lat" to c.centerLat, "cell_lng" to c.centerLng, "pets" to n) }).stringify())
            }
            path.endsWith("/rpc/pets_in_cell") -> {
                val id = body["p_cell_id"].str
                val owners = visibleOwners().filter { it.cell.id == id }
                val mine = if (myCell?.id == id) myPets.map { p -> Json.obj("pet_id" to "mine-" + p["local_id"].str, "name" to p["name"].str, "species" to p["species"].str, "ears" to p["ears"].str, "look" to p["look"].str, "mine" to true) } else emptyList()
                val theirs = owners.flatMap { o -> o.pets.map { p -> Json.obj("pet_id" to p.id, "name" to p.name, "species" to p.species, "ears" to p.ears, "look" to p.look, "mine" to false) } }
                ok(Json.arr((mine + theirs).shuffled()).stringify())
            }
            path.endsWith("/rpc/block_pet_owner") -> { fakeOwners.firstOrNull { o -> o.pets.any { it.id == body["p_pet_id"].str } }?.let { blocked += it.id }; ok() }
            path.endsWith("/rpc/report_pet") -> ok()
            path.startsWith("/rest/v1/gatherings_public") -> { approveWaiting(); ok(Json.arr(walks.filter { it.approved }.sortedBy { it.startsAtMs }.map(::publicJson)).stringify()) }
            path.endsWith("/rpc/rsvp") -> {
                val w = walks.firstOrNull { it.id == body["p_id"].str } ?: return HttpResponse(404, """{"message":"no such gathering"}""")
                if (body["p_going"].bool == true) {
                    if (w.id !in going && w.going >= w.capacity) return HttpResponse(400, """{"message":"gathering is full"}""")
                    if (going.add(w.id)) w.going++
                } else if (going.remove(w.id)) w.going--
                ok(w.going.toString())
            }
            path.endsWith("/rpc/gathering_details") -> {
                val w = walks.firstOrNull { it.id == body["p_id"].str && it.approved && (it.id in going || it.hostId == me) }
                ok(if (w == null) "[]" else Json.arr(listOf(Json.obj("venue_name" to w.venueName, "venue_lat" to w.venueLat, "venue_lng" to w.venueLng))).stringify())
            }
            path.endsWith("/rpc/host_walk") -> {
                if (!joined) return HttpResponse(403, """{"message":"join the map first"}""")
                if (walks.count { it.hostId == me && !it.approved } >= 3) return HttpResponse(400, """{"message":"you already have 3 walks waiting for approval"}""")
                val cell = LocationGrid.snap(body["p_cell_lat"].double ?: NAGA_LAT, body["p_cell_lng"].double ?: NAGA_LNG)
                val w = Walk(
                    Ids.newId(), body["p_title"].str ?: "Pet walk", com.pawpixel.map.IsoTime.parseMs(body["p_starts_at"].str ?: "") ?: nowMs(), cell,
                    body["p_area_label"].str ?: "", body["p_venue_name"].str ?: "", body["p_venue_lat"].double ?: cell.centerLat, body["p_venue_lng"].double ?: cell.centerLng,
                    body["p_capacity"].int ?: 20, body["p_details"].str, me, approved = false, proposedAtMs = nowMs(), going = 1,
                )
                walks += w; going += w.id
                ok("\"${w.id}\"")
            }
            path.startsWith("/rest/v1/my_walks") -> {
                approveWaiting()
                ok(Json.arr(walks.filter { it.hostId == me }.sortedBy { it.startsAtMs }.map { w ->
                    Json.obj("id" to w.id, "title" to w.title, "starts_at" to iso(w.startsAtMs), "area_label" to w.areaLabel, "venue_name" to w.venueName,
                        "capacity" to w.capacity, "going" to w.going, "approved" to w.approved, "details" to w.details)
                }).stringify())
            }
            path.endsWith("/rpc/cancel_walk") -> { walks.removeAll { it.id == body["p_id"].str && it.hostId == me }; ok() }
            path.endsWith("/rpc/community_stats") -> {
                val owners = visibleOwners()
                ok(Json.arr(listOf(Json.obj(
                    "owners" to owners.size + (if (joined) 1 else 0), "pets" to owners.sumOf { it.pets.size } + myPets.size,
                    "areas" to cellsWithPets().size, "walks" to walks.count { it.approved && it.startsAtMs > nowMs() },
                ))).stringify())
            }
            else -> ok()
        }
    }

    /** The demo moderator approves a proposal after a short while, so both states can be seen. */
    private fun approveWaiting() { for (w in walks) if (!w.approved && nowMs() - w.proposedAtMs > APPROVE_AFTER_MS) w.approved = true }

    private fun visibleOwners() = fakeOwners.filter { it.id !in blocked }

    /** Areas with 3+ owners (you count in yours), with their pet counts. */
    private fun cellsWithPets(): List<Pair<LocationGrid.Cell, Int>> =
        (visibleOwners().groupBy { it.cell.id }).mapNotNull { (_, owners) ->
            val cell = owners.first().cell
            val mineHere = myCell?.id == cell.id
            val count = owners.size + (if (mineHere) 1 else 0)
            if (count < 3) null else cell to (owners.sumOf { it.pets.size } + (if (mineHere) myPets.size else 0))
        }

    /** Owners in your area and the ones around it, and a walk nearby, made once per area. */
    private fun seedAround(cell: LocationGrid.Cell) {
        if (seededAround == cell.id) return
        seededAround = cell.id
        fakeOwners.clear()
        val step = LocationGrid.DEFAULT_CELL_KM / 111.32
        val around = listOf(
            cell to 4, LocationGrid.snap(cell.centerLat + step, cell.centerLng) to 3,
            LocationGrid.snap(cell.centerLat, cell.centerLng + step * 1.5) to 5, LocationGrid.snap(cell.centerLat - step, cell.centerLng - step) to 2,
        )
        var i = 0
        for ((c, n) in around) repeat(n) {
            val pets = List(if (i % 3 == 0) 2 else 1) { k -> val p = PETS[(i * 2 + k) % PETS.size]; FakePet("fake-$i-$k", p.first, p.second, p.third, LOOKS[(i + k) % LOOKS.size]) }
            fakeOwners += FakeOwner("owner-$i", c, pets); i++
        }
        if (walks.none { it.hostId != me }) {
            val plaza = LocationGrid.snap(cell.centerLat, cell.centerLng)
            walks += Walk(
                "demo-walk", "Sunday pet walk at Plaza Rizal", nextSundayMs(), plaza, "Plaza Rizal area", "Plaza Rizal fountain",
                plaza.centerLat + 0.002, plaza.centerLng - 0.003, 30, "Bring water and a leash. We stop at the fountain for photos.",
                "owner-0", approved = true, proposedAtMs = 0, going = 9,
            )
        }
    }

    private fun nextSundayMs(): Long {
        val day = nowMs().floorDiv(86_400_000L)
        val sunday = day + ((3 - day.mod(7L) + 7) % 7).let { if (it == 0L) 7 else it } // 1970-01-01 was a Thursday
        return sunday * 86_400_000L - 8 * 3_600_000L + 7 * 3_600_000L // 7:00 AM Manila
    }

    private fun publicJson(w: Walk) = Json.obj(
        "id" to w.id, "title" to w.title, "starts_at" to iso(w.startsAtMs), "cell_id" to w.cell.id, "cell_lat" to w.cell.centerLat, "cell_lng" to w.cell.centerLng,
        "area_label" to w.areaLabel, "capacity" to w.capacity, "details" to w.details, "going" to w.going, "i_am_going" to (w.id in going), "i_am_host" to (w.hostId == me),
    )

    private fun iso(ms: Long): String {
        val days = ms.floorDiv(86_400_000L); val rem = ms - days * 86_400_000L
        val (y, m, d) = com.pawpixel.core.LocalClock.civil(days)
        fun p(n: Long, w: Int = 2) = n.toString().padStart(w, '0')
        return "${p(y.toLong(), 4)}-${p(m.toLong())}-${p(d.toLong())}T${p(rem / 3_600_000)}:${p(rem / 60_000 % 60)}:${p(rem / 1000 % 60)}Z"
    }

    companion object {
        const val NAGA_LAT = 13.6218
        const val NAGA_LNG = 123.1948
        const val APPROVE_AFTER_MS = 20_000L
        /** Settings that route the map client to this server; the "test account" sign-in skips Google. */
        val SETTINGS = MapSettings("https://demo.pawpixel.local", "demo", "", "", "", testEmail = "demo@pawpixel.app", testPassword = "demo")
        private val PETS = listOf(
            Triple("Biscuit", "DOG", "FLOPPY"), Triple("Tala", "CAT", "POINTY"), Triple("Mochi", "CAT", "POINTY"), Triple("Kape", "DOG", "FLOPPY"),
            Triple("Luna", "CAT", "POINTY"), Triple("Bantay", "DOG", "FLOPPY"), Triple("Choco", "DOG", "FLOPPY"), Triple("Ube", "CAT", "POINTY"), Triple("Mingming", "CAT", "POINTY"),
        )
        private val LOOKS = listOf(
            "1;d9a441,f4f1ea;" + "0".repeat(40) + "1".repeat(24),
            "1;2b2430;" + "0".repeat(64),
            "1;f4f1ea,7e4823;" + "1100000011000000" + "0".repeat(48),
            "1;898f9a,f4f1ea;" + "0".repeat(40) + "1".repeat(24),
            "1;b07a4a,f4f1ea;" + "0".repeat(48) + "1".repeat(16),
            "1;f2b84b;" + "0".repeat(64),
        )
    }
}
