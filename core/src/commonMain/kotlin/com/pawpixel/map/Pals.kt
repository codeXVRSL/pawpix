package com.pawpixel.map

import com.pawpixel.core.Base64
import com.pawpixel.core.Json
import com.pawpixel.sprite.PetLook

/** A pal's pixel pet: name and look, never a photo or a place. */
data class PalPet(val palId: String, val petId: String, val name: String, val species: String, val ears: String?, val look: PetLook?)

/** One pal: their id and the pets they show (an empty list until they share any). */
data class Pal(val id: String, val pets: List<PalPet>, val sinceMs: Long)

/** A pal's moment: one small photo of their pet with a caption, for pals only, gone after two days. [photo] is the JPEG (empty if none came). */
data class Moment(val palId: String, val petName: String, val caption: String, val photo: ByteArray, val atMs: Long) {
    /** Your own, shown back so you can see what's up and take it down. */
    fun isMine(myId: String?) = myId != null && palId == myId
}

/** A treat (pat, ball) a pal's pet sent one of yours. */
data class Treat(val id: String, val toPetId: String, val fromPet: String, val kind: String, val atMs: Long)

/**
 * Pals on PawPixel's server (migration 0012): a circle of up to 20 friends who see each other's
 * pixel pets and send them treats. No feed, no followers; either side can unpal.
 */
class PalClient(private val api: SupabaseApi) {
    /** Your own code to hand to friends (made the first time). */
    suspend fun myCode(): String = Json.parse(api.rpc("my_pal_code")).str ?: throw MapException(MapException.Kind.SERVER, "No code")

    /** Becomes pals with the code's owner; returns their id. A wrong code counts against ten an hour. */
    suspend fun add(code: String): String =
        Json.parse(api.rpc("add_pal_tracked", Json.obj("p_code" to code.trim().uppercase()))).str
            ?: throw MapException(MapException.Kind.SERVER, com.pawpixel.i18n.tr("no pal with that code"))

    /** Replaces your code: the old one stops working for anyone who had it. */
    suspend fun newCode(): String = Json.parse(api.rpc("new_pal_code")).str ?: throw MapException(MapException.Kind.SERVER, "No code")

    suspend fun remove(palId: String) { api.rpc("remove_pal", Json.obj("p_user" to palId)) }

    /** Replaces the pets your pals see. */
    suspend fun setPets(pets: List<SharedPet>) {
        api.rpc("set_pal_pets", Json.obj("p_pets" to Json.arr(pets.take(10).map {
            Json.obj("local_id" to it.localId, "name" to it.name, "species" to it.species, "ears" to it.ears, "look" to it.look)
        })))
    }

    suspend fun list(): List<Pal> {
        val rows = Json.parse(api.rpc("pals_list")).list
        return rows.groupBy { it["pal_id"].str ?: "" }.filterKeys { it.isNotEmpty() }.map { (id, group) ->
            Pal(id, group.mapNotNull { r ->
                PalPet(id, r["pet_id"].str ?: return@mapNotNull null, r["name"].str ?: "Pet", r["species"].str ?: "OTHER", r["ears"].str, r["look"].str?.let(PetLook::decode))
            }, group.first()["since"].str?.let(IsoTime::parseMs) ?: 0L)
        }
    }

    suspend fun sendTreat(palId: String, toPetId: String, fromPetName: String, kind: String = "treat") {
        api.rpc("send_treat", Json.obj("p_to_user" to palId, "p_to_pet" to toPetId, "p_from_pet" to fromPetName, "p_kind" to kind))
    }

    /** Treats your pets got this week, newest first. */
    suspend fun inbox(): List<Treat> = Json.parse(api.rpc("treats_inbox")).list.mapNotNull { t ->
        Treat(t["id"].str ?: return@mapNotNull null, t["to_pet"].str ?: "", t["from_pet"].str ?: "", t["kind"].str ?: "treat", t["created_at"].str?.let(IsoTime::parseMs) ?: 0L)
    }

    /** Shares today's moment (replacing the last one). The photo must already be a small JPEG (see [MAX_MOMENT_BYTES]). */
    suspend fun setMoment(petName: String, caption: String, photo: ByteArray) {
        require(photo.size in 1..MAX_MOMENT_BYTES) { "photo too big" }
        api.rpc("set_moment", Json.obj("p_pet_name" to petName, "p_caption" to caption.take(80), "p_photo" to Base64.encode(photo)))
    }

    suspend fun clearMoment() { api.rpc("clear_moment") }

    /** Your pals' moments from the last two days (and your own), newest first. */
    suspend fun moments(): List<Moment> = Json.parse(api.rpc("pals_moments")).list.mapNotNull { m ->
        Moment(
            m["pal_id"].str ?: return@mapNotNull null, m["pet_name"].str ?: "A pet", m["caption"].str ?: "",
            m["photo"].str?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: ByteArray(0), m["updated_at"].str?.let(IsoTime::parseMs) ?: 0L,
        )
    }

    companion object {
        const val MAX_PALS = 20
        /** 64 KB a moment (90,000 base64 characters on the server). */
        const val MAX_MOMENT_BYTES = 64_000
        val KINDS = listOf("treat", "pat", "ball")
    }
}
