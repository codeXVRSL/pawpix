package com.pawpixel.map

import com.pawpixel.core.Json
import com.pawpixel.sprite.PetLook

/** A pal's pixel pet: name and look, never a photo or a place. */
data class PalPet(val palId: String, val petId: String, val name: String, val species: String, val ears: String?, val look: PetLook?)

/** One pal: their id and the pets they show (an empty list until they share any). */
data class Pal(val id: String, val pets: List<PalPet>, val sinceMs: Long)

/** A treat (pat, ball) a pal's pet sent one of yours. */
data class Treat(val id: String, val toPetId: String, val fromPet: String, val kind: String, val atMs: Long)

/**
 * Pals on PawPixel's server (migration 0012): a circle of up to 20 friends who see each other's
 * pixel pets and send them treats. No feed, no followers; either side can unpal.
 */
class PalClient(private val api: SupabaseApi) {
    /** Your own code to hand to friends (made the first time). */
    suspend fun myCode(): String = Json.parse(api.rpc("my_pal_code")).str ?: throw MapException(MapException.Kind.SERVER, "No code")

    /** Becomes pals with the code's owner; returns their id. Refused with the server's reason otherwise. */
    suspend fun add(code: String): String =
        Json.parse(api.rpc("add_pal", Json.obj("p_code" to code.trim().uppercase()))).str ?: throw MapException(MapException.Kind.SERVER, "No pal")

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

    companion object {
        const val MAX_PALS = 20
        val KINDS = listOf("treat", "pat", "ball")
    }
}
