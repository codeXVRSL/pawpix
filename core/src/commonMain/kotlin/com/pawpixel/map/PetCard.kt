package com.pawpixel.map

import com.pawpixel.core.Json
import com.pawpixel.i18n.tr

/** What the owner puts on a pet's ID card. */
data class PetCardDraft(val localId: String, val name: String, val species: String, val ears: String?, val look: String, val note: String, val microchip: String)

/** A message from whoever scanned the tag. */
data class CardMessage(val id: String, val text: String, val contact: String?, val createdAtMs: Long)

data class MyPetCard(val id: String, val localId: String, val name: String, val note: String?, val microchip: String?, val messages: Int)

/**
 * Pet ID cards on PawPixel's server (migration 0011): a page per pet that a tag's QR code opens,
 * with a message box that reaches the owner in the app. Same sign-in as the map.
 */
class PetCardClient(private val api: SupabaseApi) {
    /** Makes the card, or updates it; returns its id (stable for the pet, so a printed tag keeps working). */
    suspend fun upsert(draft: PetCardDraft): String {
        val args = Json.obj(
            "p_local_id" to draft.localId.take(40), "p_name" to draft.name.trim().take(24).ifBlank { tr("Pet") },
            "p_species" to draft.species, "p_ears" to draft.ears, "p_look" to draft.look,
            "p_note" to draft.note.trim().take(200).ifBlank { null }, "p_microchip" to draft.microchip.trim().take(40).ifBlank { null },
        )
        return Json.parse(api.rpc("upsert_pet_card", args)).str ?: throw MapException(MapException.Kind.SERVER, "No id")
    }

    suspend fun remove(localId: String) { api.rpc("remove_pet_card", Json.obj("p_local_id" to localId)) }

    suspend fun messages(cardId: String): List<CardMessage> =
        Json.parse(api.rpc("pet_card_messages_for", Json.obj("p_id" to cardId))).list.mapNotNull { m ->
            CardMessage(m["id"].str ?: return@mapNotNull null, m["text"].str ?: "", m["contact"].str?.ifBlank { null },
                m["created_at"].str?.let(IsoTime::parseMs) ?: 0L)
        }

    suspend fun mine(): List<MyPetCard> =
        Json.parse(api.rest("GET", "/rest/v1/my_pet_cards?select=*")).list.mapNotNull { c ->
            MyPetCard(c["id"].str ?: return@mapNotNull null, c["local_id"].str ?: "", c["name"].str ?: "", c["note"].str?.ifBlank { null },
                c["microchip"].str?.ifBlank { null }, c["messages"].int ?: 0)
        }

    companion object {
        /** The page a tag's QR code opens (web/card.html). */
        const val URL_BASE = "https://pawpixel.app/card#"
        fun url(cardId: String) = URL_BASE + cardId
    }
}
