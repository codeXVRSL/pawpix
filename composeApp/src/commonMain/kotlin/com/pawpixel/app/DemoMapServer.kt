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
    private val lost = ArrayList<Lost>()
    private val cards = LinkedHashMap<String, Card>()   // local pet id -> card
    private val pals = LinkedHashMap<String, Long>()     // pal id -> since
    private var palPets: List<Json> = emptyList()
    private val treats = ArrayList<Json>()
    private var treatedAtMs = 0L
    private var myMoment: Json? = null
    private val cardMessages = ArrayList<CardMsg>()
    private val sightings = ArrayList<Seen>()

    private class FakeOwner(val id: String, val cell: LocationGrid.Cell, val pets: List<FakePet>)
    private class FakePet(val id: String, val name: String, val species: String, val ears: String, val look: String)
    private class Lost(
        val id: String, val name: String, val species: String, val ears: String?, val look: String, val description: String?,
        val photos: List<String>, val lat: Double, val lng: Double, val lastSeenAtMs: Long, val createdAtMs: Long, val ownerId: String,
        var foundAtMs: Long? = null,
    )
    private class Card(val id: String, val localId: String, var name: String, var note: String?, var microchip: String?, val madeAtMs: Long)
    private class CardMsg(val id: String, val cardId: String, val text: String, val contact: String?, val atMs: Long)
    private class Seen(val id: String, val lostId: String, val lat: Double, val lng: Double, val note: String?, val photo: String?, val atMs: Long, val reporter: String)
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
            path.endsWith("/rpc/gathering_pets") -> {
                val w = walks.firstOrNull { it.id == body["p_id"].str && it.approved } ?: return ok()
                val mine = if (w.id in going) myPets.map { p -> Json.obj("pet_id" to "mine-" + p["local_id"].str, "name" to p["name"].str, "species" to p["species"].str, "ears" to p["ears"].str, "look" to p["look"].str, "mine" to true) } else emptyList()
                val theirs = visibleOwners().take(minOf(w.going, 6)).flatMap { o -> o.pets.map { p -> Json.obj("pet_id" to p.id, "name" to p.name, "species" to p.species, "ears" to p.ears, "look" to p.look, "mine" to false) } }
                ok(Json.arr(mine + theirs).stringify())
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
            // Lost and Found (0010), same shapes as the real server.
            path.endsWith("/rpc/report_lost") -> {
                if (lost.count { it.ownerId == me && it.foundAtMs == null } >= 3) return HttpResponse(400, """{"message":"you already have 3 open alerts"}""")
                val photos = body["p_photos"].list.mapNotNull { it.str }.take(3)
                val l = Lost(
                    Ids.newId(), body["p_name"].str ?: "Pet", body["p_species"].str ?: "OTHER", body["p_ears"].str, body["p_look"].str ?: "",
                    body["p_description"].str, photos, body["p_lat"].double ?: NAGA_LAT, body["p_lng"].double ?: NAGA_LNG,
                    body["p_last_seen_at"].str?.let(com.pawpixel.map.IsoTime::parseMs) ?: nowMs(), nowMs(), me,
                )
                lost += l
                ok("\"${l.id}\"")
            }
            path.endsWith("/rpc/lost_nearby") -> {
                val lat = body["p_lat"].double ?: NAGA_LAT; val lng = body["p_lng"].double ?: NAGA_LNG
                val radius = (body["p_radius_km"].double ?: 15.0).coerceIn(1.0, 100.0)
                seedLost(LocationGrid.snap(lat, lng)); pretendSighting()
                val rows = lost.filter { it.foundAtMs == null }
                    .map { it to LocationGrid.distanceKm(lat, lng, it.lat, it.lng) }.filter { it.second <= radius }.sortedBy { it.second }
                ok(Json.arr(rows.map { (l, d) ->
                    Json.obj("id" to l.id, "name" to l.name, "species" to l.species, "ears" to l.ears, "look" to l.look, "description" to l.description,
                        "last_seen_lat" to l.lat, "last_seen_lng" to l.lng, "last_seen_at" to iso(l.lastSeenAtMs), "created_at" to iso(l.createdAtMs),
                        "photo_count" to l.photos.size, "sightings" to sightings.count { it.lostId == l.id }, "mine" to (l.ownerId == me), "distance_km" to d)
                }).stringify())
            }
            path.endsWith("/rpc/lost_details") -> {
                val l = lost.firstOrNull { it.id == body["p_id"].str && (it.ownerId == me || it.foundAtMs == null) }
                ok(if (l == null) "[]" else Json.arr(listOf(Json.obj(
                    "id" to l.id, "name" to l.name, "species" to l.species, "ears" to l.ears, "look" to l.look, "description" to l.description,
                    "photos" to Json.arr(l.photos), "last_seen_lat" to l.lat, "last_seen_lng" to l.lng, "last_seen_at" to iso(l.lastSeenAtMs),
                    "created_at" to iso(l.createdAtMs), "found_at" to l.foundAtMs?.let(::iso), "mine" to (l.ownerId == me),
                ))).stringify())
            }
            path.endsWith("/rpc/report_sighting") -> {
                val l = lost.firstOrNull { it.id == body["p_lost_id"].str && it.foundAtMs == null } ?: return HttpResponse(400, """{"message":"this alert is closed"}""")
                val s = Seen(Ids.newId(), l.id, body["p_lat"].double ?: l.lat, body["p_lng"].double ?: l.lng, body["p_note"].str, body["p_photo"].str, nowMs(), me)
                sightings += s
                ok("\"${s.id}\"")
            }
            path.endsWith("/rpc/lost_sightings_for") -> {
                pretendSighting()
                val l = lost.firstOrNull { it.id == body["p_lost_id"].str && it.ownerId == me }
                ok(Json.arr(sightings.filter { l != null && it.lostId == l.id }.sortedByDescending { it.atMs }.map { s ->
                    Json.obj("id" to s.id, "lat" to s.lat, "lng" to s.lng, "note" to s.note, "photo" to s.photo, "created_at" to iso(s.atMs))
                }).stringify())
            }
            path.endsWith("/rpc/mark_found") -> { lost.firstOrNull { it.id == body["p_id"].str && it.ownerId == me }?.let { it.foundAtMs = nowMs() }; ok() }
            path.endsWith("/rpc/cancel_lost") -> { lost.removeAll { it.id == body["p_id"].str && it.ownerId == me }; ok() }
            path.startsWith("/rest/v1/my_lost_pets") -> ok(Json.arr(lost.filter { it.ownerId == me }.sortedByDescending { it.createdAtMs }.map { l ->
                Json.obj("id" to l.id, "name" to l.name, "species" to l.species, "created_at" to iso(l.createdAtMs), "found_at" to l.foundAtMs?.let(::iso),
                    "last_seen_at" to iso(l.lastSeenAtMs), "sightings" to sightings.count { it.lostId == l.id })
            }).stringify())
            // Pals (0012): your code is DEMO22; the codes JAM1LA and BANTAY belong to pretend owners.
            path.endsWith("/rpc/my_pal_code") -> ok("\"DEMO22\"")
            path.endsWith("/rpc/new_pal_code") -> ok("\"DEMO33\"")
            path.endsWith("/rpc/add_pal_tracked") -> {
                val code = (body["p_code"].str ?: "").trim().uppercase()
                val id = when (code) { "JAM1LA" -> "owner-0"; "BANTAY" -> "owner-3"; "DEMO22", "DEMO33" -> return HttpResponse(400, """{"message":"that is your own code"}"""); else -> return ok("null") }
                pals.getOrPut(id) { nowMs() }
                ok("\"$id\"")
            }
            path.endsWith("/rpc/add_pal") -> {
                val code = (body["p_code"].str ?: "").trim().uppercase()
                val id = when (code) { "JAM1LA" -> "owner-0"; "BANTAY" -> "owner-3"; "DEMO22" -> return HttpResponse(400, """{"message":"that is your own code"}"""); else -> return HttpResponse(400, """{"message":"no pal with that code"}""") }
                pals.getOrPut(id) { nowMs() }
                ok("\"$id\"")
            }
            path.endsWith("/rpc/remove_pal") -> { pals.remove(body["p_user"].str); ok() }
            path.endsWith("/rpc/set_pal_pets") -> { palPets = body["p_pets"].list; ok() }
            path.endsWith("/rpc/pals_list") -> {
                seedAround(myCell ?: LocationGrid.snap(NAGA_LAT, NAGA_LNG))
                ok(Json.arr(pals.flatMap { (id, since) ->
                    val owner = fakeOwners.firstOrNull { it.id == id }
                    (owner?.pets ?: emptyList()).map { p -> Json.obj("pal_id" to id, "pet_id" to p.id, "name" to p.name, "species" to p.species, "ears" to p.ears, "look" to p.look, "since" to iso(since)) }
                        .ifEmpty { listOf(Json.obj("pal_id" to id, "pet_id" to null, "name" to null, "species" to null, "ears" to null, "look" to null, "since" to iso(since))) }
                }).stringify())
            }
            path.endsWith("/rpc/set_moment") -> {
                myMoment = Json.obj("pal_id" to me, "pet_name" to (body["p_pet_name"].str ?: "A pet"), "caption" to (body["p_caption"].str ?: ""), "photo" to (body["p_photo"].str ?: ""), "updated_at" to iso(nowMs()))
                ok()
            }
            path.endsWith("/rpc/clear_moment") -> { myMoment = null; ok() }
            path.endsWith("/rpc/pals_moments") -> {
                // Biscuit's owner shared a moment this morning (the demo has no photo: the screen shows the pixel pet).
                val theirs = if ("owner-0" in pals) listOf(Json.obj("pal_id" to "owner-0", "pet_name" to "Biscuit", "caption" to "Sunday nap in the sun", "photo" to "", "updated_at" to iso(nowMs() - 3 * 3_600_000L))) else emptyList()
                ok(Json.arr(listOfNotNull(myMoment) + theirs).stringify())
            }
            path.endsWith("/rpc/send_treat") -> ok()
            path.endsWith("/rpc/treats_inbox") -> {
                // A pal's pet sends the first of your pets a ball a little after you become pals.
                val first = palPets.firstOrNull()?.get("local_id")?.str
                if (pals.isNotEmpty() && first != null && treats.isEmpty() && nowMs() - pals.values.min() > SIGHTING_AFTER_MS) {
                    treats += Json.obj("id" to Ids.newId(), "to_pet" to first, "from_pet" to "Biscuit", "kind" to "ball", "created_at" to iso(nowMs()))
                    treatedAtMs = nowMs()
                }
                ok(Json.arr(treats).stringify())
            }
            // Pet ID cards (0011)
            path.endsWith("/rpc/upsert_pet_card") -> {
                val local = body["p_local_id"].str ?: ""
                val c = cards.getOrPut(local) { Card(Ids.newId(), local, body["p_name"].str ?: "Pet", null, null, nowMs()) }
                c.name = body["p_name"].str ?: c.name; c.note = body["p_note"].str; c.microchip = body["p_microchip"].str
                ok("\"${c.id}\"")
            }
            path.endsWith("/rpc/remove_pet_card") -> { cards.remove(body["p_local_id"].str)?.let { c -> cardMessages.removeAll { it.cardId == c.id } }; ok() }
            path.endsWith("/rpc/pet_card_messages_for") -> {
                pretendScan()
                ok(Json.arr(cardMessages.filter { it.cardId == body["p_id"].str }.sortedByDescending { it.atMs }.map { m ->
                    Json.obj("id" to m.id, "text" to m.text, "contact" to m.contact, "created_at" to iso(m.atMs))
                }).stringify())
            }
            path.startsWith("/rest/v1/my_pet_cards") -> ok(Json.arr(cards.values.map { c ->
                Json.obj("id" to c.id, "local_id" to c.localId, "name" to c.name, "note" to c.note, "microchip" to c.microchip, "updated_at" to iso(c.madeAtMs),
                    "messages" to cardMessages.count { it.cardId == c.id })
            }).stringify())
            else -> ok()
        }
    }

    /** Someone in the demo neighbourhood scans a tag a little after the card is made. */
    private fun pretendScan() {
        for (c in cards.values) if (nowMs() - c.madeAtMs > SIGHTING_AFTER_MS && cardMessages.none { it.cardId == c.id }) {
            cardMessages += CardMsg(Ids.newId(), c.id, "Hi! I found ${c.name} near the plaza, safe with me. Text me and I'll bring ${c.name} over.", "0917 555 0123", nowMs())
        }
    }

    /** One pretend alert near the area: a dog called Kape, last seen by the market this morning. */
    private fun seedLost(cell: LocationGrid.Cell) {
        if (lost.any { it.ownerId == "owner-2" }) return
        lost += Lost(
            "demo-lost", "Kape", "DOG", "FLOPPY", LOOKS[4], "Brown aspin, red collar, friendly but shy. Answers to Kape.", emptyList(),
            cell.centerLat + 0.006, cell.centerLng + 0.004, nowMs() - 5 * 3_600_000L, nowMs() - 4 * 3_600_000L, "owner-2",
        )
    }

    /** The demo neighbourhood answers an alert of yours with a sighting after a short while. */
    private fun pretendSighting() {
        for (l in lost) if (l.ownerId == me && l.foundAtMs == null && nowMs() - l.createdAtMs > SIGHTING_AFTER_MS && sightings.none { it.lostId == l.id && it.reporter == "owner-3" }) {
            sightings += Seen(Ids.newId(), l.id, l.lat + 0.003, l.lng - 0.002, "Saw a pet like this near the market gate, heading east. I'm at the sari-sari store if you want to call: 0917 555 0123.", null, nowMs(), "owner-3")
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
        const val SIGHTING_AFTER_MS = 15_000L
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
