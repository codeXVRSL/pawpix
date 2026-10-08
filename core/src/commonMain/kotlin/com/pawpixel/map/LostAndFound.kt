package com.pawpixel.map

import com.pawpixel.core.Base64
import com.pawpixel.core.Json
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PetLook

/** A lost-pet alert as owners nearby see it in the list and on the map (photos come with [LostDetails]). */
data class LostPet(
    val id: String, val name: String, val species: String, val ears: String?, val look: PetLook?, val description: String?,
    val lastSeenLat: Double, val lastSeenLng: Double, val lastSeenAtMs: Long, val createdAtMs: Long,
    val photoCount: Int, val sightings: Int, val mine: Boolean, val distanceKm: Double,
)

/** One alert in full: the photos (JPEG bytes) and whether it has been closed. */
data class LostDetails(
    val id: String, val name: String, val species: String, val ears: String?, val look: PetLook?, val description: String?,
    val photos: List<ByteArray>, val lastSeenLat: Double, val lastSeenLng: Double, val lastSeenAtMs: Long, val createdAtMs: Long,
    val foundAtMs: Long?, val mine: Boolean,
)

/** What an owner sends when their pet goes missing. Photos are small JPEGs (the app shrinks them). */
data class LostDraft(
    val name: String, val species: String, val ears: String?, val look: String, val description: String,
    val lat: Double, val lng: Double, val lastSeenAtMs: Long, val photos: List<ByteArray>,
)

/** "I saw them": where, when, a note (how to reach the finder, if they like) and maybe a photo. */
data class Sighting(val id: String, val lat: Double, val lng: Double, val note: String?, val photo: ByteArray?, val createdAtMs: Long)

/** One of your own alerts, open or closed. */
data class MyLostPet(val id: String, val name: String, val createdAtMs: Long, val foundAtMs: Long?, val sightings: Int)

/**
 * Lost and Found on PawPixel's server (migration 0010). Raising an alert needs a sign-in, not a
 * place on the map: a lost pet is urgent. Everything else about who sees what is enforced there.
 */
class LostClient(private val api: SupabaseApi) {
    /** Raises the alert and returns its id. The server refuses a fourth open alert and oversized photos. */
    suspend fun report(draft: LostDraft): String {
        val photos = draft.photos.filter { it.size <= MAX_PHOTO_BYTES }.take(MAX_PHOTOS).map { Base64.encode(it) }
        val args = Json.obj(
            "p_name" to draft.name.trim().take(24).ifBlank { tr("Pet") },
            "p_species" to draft.species, "p_ears" to draft.ears, "p_look" to draft.look,
            "p_description" to draft.description.trim().take(300).ifBlank { null },
            "p_lat" to draft.lat, "p_lng" to draft.lng, "p_last_seen_at" to api.isoAt(draft.lastSeenAtMs),
            "p_photos" to Json.arr(photos),
        )
        return Json.parse(api.rpc("report_lost", args)).str ?: throw MapException(MapException.Kind.SERVER, "No id")
    }

    /** Open alerts within [radiusKm] of a spot, nearest first. */
    suspend fun nearby(lat: Double, lng: Double, radiusKm: Double = DEFAULT_RADIUS_KM): List<LostPet> =
        Json.parse(api.rpc("lost_nearby", Json.obj("p_lat" to lat, "p_lng" to lng, "p_radius_km" to radiusKm))).list.mapNotNull { l ->
            LostPet(
                id = l["id"].str ?: return@mapNotNull null, name = l["name"].str ?: tr("Pet"), species = l["species"].str ?: "OTHER",
                ears = l["ears"].str, look = l["look"].str?.let(PetLook::decode), description = l["description"].str?.ifBlank { null },
                lastSeenLat = l["last_seen_lat"].double ?: 0.0, lastSeenLng = l["last_seen_lng"].double ?: 0.0,
                lastSeenAtMs = l["last_seen_at"].str?.let(IsoTime::parseMs) ?: 0L, createdAtMs = l["created_at"].str?.let(IsoTime::parseMs) ?: 0L,
                photoCount = l["photo_count"].int ?: 0, sightings = l["sightings"].int ?: 0, mine = l["mine"].bool ?: false,
                distanceKm = l["distance_km"].double ?: 0.0,
            )
        }

    /** The alert with its photos; null when it's gone or closed (unless it's yours). */
    suspend fun details(id: String): LostDetails? {
        val l = Json.parse(api.rpc("lost_details", Json.obj("p_id" to id))).list.firstOrNull() ?: return null
        return LostDetails(
            id = l["id"].str ?: return null, name = l["name"].str ?: tr("Pet"), species = l["species"].str ?: "OTHER",
            ears = l["ears"].str, look = l["look"].str?.let(PetLook::decode), description = l["description"].str?.ifBlank { null },
            photos = l["photos"].list.mapNotNull { p -> p.str?.let { runCatching { Base64.decode(it) }.getOrNull() } },
            lastSeenLat = l["last_seen_lat"].double ?: 0.0, lastSeenLng = l["last_seen_lng"].double ?: 0.0,
            lastSeenAtMs = l["last_seen_at"].str?.let(IsoTime::parseMs) ?: 0L, createdAtMs = l["created_at"].str?.let(IsoTime::parseMs) ?: 0L,
            foundAtMs = l["found_at"].str?.let(IsoTime::parseMs), mine = l["mine"].bool ?: false,
        )
    }

    suspend fun reportSighting(lostId: String, lat: Double, lng: Double, note: String?, photo: ByteArray? = null): String {
        val args = Json.obj(
            "p_lost_id" to lostId, "p_lat" to lat, "p_lng" to lng, "p_note" to note?.trim()?.take(300)?.ifBlank { null },
            "p_photo" to photo?.takeIf { it.size <= MAX_PHOTO_BYTES }?.let(Base64::encode),
        )
        return Json.parse(api.rpc("report_sighting", args)).str ?: throw MapException(MapException.Kind.SERVER, "No id")
    }

    /** The sightings on your own alert, newest first. */
    suspend fun sightings(lostId: String): List<Sighting> =
        Json.parse(api.rpc("lost_sightings_for", Json.obj("p_lost_id" to lostId))).list.mapNotNull { s ->
            Sighting(
                id = s["id"].str ?: return@mapNotNull null, lat = s["lat"].double ?: 0.0, lng = s["lng"].double ?: 0.0,
                note = s["note"].str?.ifBlank { null }, photo = s["photo"].str?.let { runCatching { Base64.decode(it) }.getOrNull() },
                createdAtMs = s["created_at"].str?.let(IsoTime::parseMs) ?: 0L,
            )
        }

    /** Safe home: closes the alert. */
    suspend fun markFound(id: String) { api.rpc("mark_found", Json.obj("p_id" to id)) }

    /** Removes the alert (raised by mistake). */
    suspend fun cancel(id: String) { api.rpc("cancel_lost", Json.obj("p_id" to id)) }

    suspend fun mine(): List<MyLostPet> =
        Json.parse(api.rest("GET", "/rest/v1/my_lost_pets?select=*")).list.mapNotNull { l ->
            MyLostPet(
                id = l["id"].str ?: return@mapNotNull null, name = l["name"].str ?: "", createdAtMs = l["created_at"].str?.let(IsoTime::parseMs) ?: 0L,
                foundAtMs = l["found_at"].str?.let(IsoTime::parseMs), sightings = l["sightings"].int ?: 0,
            )
        }

    companion object {
        const val MAX_PHOTOS = 3
        /** 120 KB a photo (160,000 base64 characters on the server). */
        const val MAX_PHOTO_BYTES = 120_000
        const val DEFAULT_RADIUS_KM = 15.0
        /** The share link: a page that shows the alert to anyone, no app needed (web/lost.html). */
        const val SHARE_URL_BASE = "https://pawpixel.app/lost#"
        fun shareUrl(id: String) = SHARE_URL_BASE + id
    }
}
