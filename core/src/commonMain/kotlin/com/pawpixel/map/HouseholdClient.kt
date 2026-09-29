package com.pawpixel.map

import com.pawpixel.core.CareTask
import com.pawpixel.core.Completion
import com.pawpixel.core.Json
import com.pawpixel.core.LoggedCompletion
import com.pawpixel.core.Pet
import com.pawpixel.core.RemoteChanges
import com.pawpixel.core.Species
import com.pawpixel.core.SyncPush
import com.pawpixel.core.TaskKind
import com.pawpixel.i18n.tr

/** A household sharing pets, and who's in it. [ownerId] started it: they can remove people and stop sharing. */
data class Household(val id: String, val name: String, val members: List<Member>, val ownerId: String? = null) {
    data class Member(val userId: String, val name: String)
    fun nameOf(userId: String?): String? = members.firstOrNull { it.userId == userId }?.name
}

/**
 * Households on PawPixel's server (supabase/migrations/0004 and 0006). Members of a household can
 * read and change only that household's pets, tasks and care log; the server enforces it.
 * Photos never go: pets travel as their name, species, ears and pixel look code (the colours and
 * markings the pixel pet is drawn from, see [com.pawpixel.sprite.PetLook]).
 */
class HouseholdClient(private val api: SupabaseApi) {
    val isSignedIn: Boolean get() = api.isSignedIn
    val userId: String? get() = api.userId

    /** Your household and its members (one request: the server only shows your own), or null if you're not in one. */
    suspend fun mine(): Household? {
        if (api.userId == null) return null
        val rows = Json.parse(api.rest("GET", "/rest/v1/household_members?select=household_id,user_id,display_name,households(name,owner_id)&order=joined_at")).list
        val first = rows.firstOrNull() ?: return null
        val id = first["household_id"].str ?: return null
        val members = rows.filter { it["household_id"].str == id }
            .mapNotNull { m -> Household.Member(m["user_id"].str ?: return@mapNotNull null, m["display_name"].str ?: "Member") }
        return Household(id, first["households"]["name"].str ?: "Household", members, first["households"]["owner_id"].str)
    }

    suspend fun create(name: String, yourName: String): String =
        Json.parse(api.rpc("create_household", Json.obj("p_name" to name.trim().take(40), "p_display_name" to yourName.trim().take(24)))).str
            ?: throw MapException(MapException.Kind.SERVER, "No household id")

    /** A code to invite someone (valid for 7 days, any number of family members up to the limit). */
    suspend fun invite(householdId: String): String =
        Json.parse(api.rpc("create_invite", Json.obj("p_household" to householdId))).str
            ?: throw MapException(MapException.Kind.SERVER, "No invite code")

    /** Joins with a code; returns the household id. Refused with a readable message if the code is wrong or expired. */
    suspend fun join(code: String, yourName: String): String =
        Json.parse(api.rpc("join_household", Json.obj("p_code" to normalizeCode(code), "p_display_name" to yourName.trim().take(24)))).str
            ?: throw MapException(MapException.Kind.REFUSED, tr("That invite code is wrong or has expired. Check it with the person who sent it."))

    /** The family's owner removes someone (they keep their own copies of the pets). */
    suspend fun removeMember(userId: String) { api.rpc("remove_member", Json.obj("p_user" to userId)) }

    /** The owner cancels every open invite code. */
    suspend fun revokeInvites() { api.rpc("revoke_invites") }

    /** The owner stops sharing for everyone: the household and its shared copy are deleted. */
    suspend fun deleteHousehold() { api.rpc("delete_household") }

    suspend fun leave() { api.rpc("leave_household") }

    /** What [pull] returns: the changes, and the cursor to pass next time. */
    class Pulled(val changes: RemoteChanges, val cursorMs: Long)

    /**
     * The household's pets and tasks, and the care records added or undone since [sinceMs] (the
     * server's clock, from the last pull; 0 = everything). Records of tasks this phone hasn't seen
     * yet ([knownTaskIds]) come whole, whenever they were logged.
     */
    suspend fun pull(householdId: String, sinceMs: Long = 0, knownTaskIds: Set<String> = emptySet()): Pulled {
        val h = "household_id=eq.$householdId"
        val pets = Json.parse(api.rest("GET", "/rest/v1/household_pets?$h&select=*")).list.mapNotNull(::petFrom)
        val tasks = Json.parse(api.rest("GET", "/rest/v1/household_tasks?$h&select=*")).list.mapNotNull(::taskFrom)
        val log = ArrayList<LoggedCompletion>()
        var cursor = sinceMs
        suspend fun page(filter: String) {
            var offset = 0
            while (true) { // the server returns at most 1000 rows at a time
                val rows = Json.parse(api.rest("GET", "/rest/v1/household_completions?$h$filter&select=*&order=changed_ms,id&limit=$PAGE&offset=$offset")).list
                for (r in rows) {
                    completionFrom(r)?.let { log += LoggedCompletion(it, undone = r["undone_at"] != Json.Null) }
                    cursor = maxOf(cursor, r["changed_ms"].long ?: 0)
                }
                if (rows.size < PAGE) break
                offset += PAGE
            }
        }
        if (sinceMs <= 0) {
            page("")
        } else {
            // A short look-back, so a record whose save was still finishing at the last pull isn't missed.
            page("&changed_ms=gt.${sinceMs - LOOK_BACK_MS}")
            val newTasks = tasks.map { it.id }.filter { it !in knownTaskIds }
            for (chunk in newTasks.chunked(50)) page("&task_id=in.(${chunk.joinToString(",")})")
        }
        return Pulled(RemoteChanges(pets, tasks, log), cursor)
    }

    /** Sends this phone's changes, parents before children (pets, tasks, records), deletions the other way. */
    suspend fun push(householdId: String, push: SyncPush) {
        val upsert = "resolution=merge-duplicates"
        if (push.upsertPets.isNotEmpty()) {
            api.rest("POST", "/rest/v1/household_pets?on_conflict=household_id,id", Json.arr(push.upsertPets.map { petJson(householdId, it) }).stringify(), upsert)
        }
        if (push.upsertTasks.isNotEmpty()) {
            api.rest("POST", "/rest/v1/household_tasks?on_conflict=household_id,id", Json.arr(push.upsertTasks.map { taskJson(householdId, it) }).stringify(), upsert)
        }
        if (push.addCompletions.isNotEmpty()) {
            api.rest("POST", "/rest/v1/household_completions?on_conflict=household_id,id",
                Json.arr(push.addCompletions.map { completionJson(householdId, it) }).stringify(), "resolution=ignore-duplicates")
        }
        // Undo marks the record undone (the server stamps when and by whom); the log keeps it.
        for (chunk in push.undoCompletionIds.chunked(100)) {
            api.rest("PATCH", "/rest/v1/household_completions?household_id=eq.$householdId&id=in.(${chunk.joinToString(",")})&undone_at=is.null",
                Json.obj("undone_at" to api.isoNow()).stringify())
        }
        for (chunk in push.deleteTaskIds.chunked(100)) api.rest("DELETE", "/rest/v1/household_tasks?household_id=eq.$householdId&id=in.(${chunk.joinToString(",")})")
        for (chunk in push.deletePetIds.chunked(100)) api.rest("DELETE", "/rest/v1/household_pets?household_id=eq.$householdId&id=in.(${chunk.joinToString(",")})")
    }

    companion object {
        const val PAGE = 1000
        /** How far back each pull looks again, in case a record's save was still finishing at the last one. */
        const val LOOK_BACK_MS = 120_000L
        /** Invite codes: 8 letters/digits without look-alikes (no 0/O, 1/I/L). */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** Accepts "abcd-2345", " ABCD 2345 " etc. */
        fun normalizeCode(code: String): String = code.uppercase().filter { it in CODE_ALPHABET }

        fun petJson(h: String, p: Pet) = Json.obj(
            "household_id" to h, "id" to p.id, "name" to p.name.take(24), "species" to p.species.name, "ears" to p.ears,
            "look" to (p.lookCode ?: ""), "accessory" to p.accessory, "eyes" to p.eyes.map { (x, y) -> listOf(x, y) }, "birth_day" to p.birthDay, "created_at_ms" to p.createdAtMs,
            "edited_at_ms" to p.editedAtMs,
        )

        fun taskJson(h: String, t: CareTask) = Json.obj(
            "household_id" to h, "id" to t.id, "pet_id" to t.petId, "kind" to t.kind.name, "title" to t.title.take(30),
            "slots" to t.slots, "every_days" to t.everyDays, "anchor_day" to t.anchorDay, "series" to t.series,
            "adaptive" to t.adaptive, "created_at_ms" to t.createdAtMs, "edited_at_ms" to t.editedAtMs,
        )

        fun completionJson(h: String, c: Completion) = Json.obj(
            "household_id" to h, "id" to c.id, "task_id" to c.taskId, "at_ms" to c.atMs, "local_minute" to c.localMinute, "local_day" to c.localDay,
        )

        private val ID = Regex("[a-z0-9]{1,40}")

        fun petFrom(j: Json): Pet? {
            val id = j["id"].str?.takeIf { ID.matches(it) } ?: return null
            return Pet(
                id = id, name = j["name"].str ?: "Pet",
                species = Species.entries.firstOrNull { it.name == j["species"].str } ?: Species.OTHER,
                createdAtMs = j["created_at_ms"].long ?: 0,
                eyes = j["eyes"].list.mapNotNull { e ->
                    val x = e.list.getOrNull(0)?.double; val y = e.list.getOrNull(1)?.double
                    if (x != null && y != null && x in 0.0..1.0 && y in 0.0..1.0) x to y else null
                }.take(2),
                ears = j["ears"].str, birthDay = j["birth_day"].long, shared = true,
                lookCode = j["look"].str?.ifEmpty { null },
                accessory = j["accessory"].str?.takeIf { com.pawpixel.sprite.Accessory.of(it) != null },
                editedAtMs = j["edited_at_ms"].long ?: 0,
            )
        }

        fun taskFrom(j: Json): CareTask? {
            val id = j["id"].str?.takeIf { ID.matches(it) } ?: return null
            val kind = TaskKind.entries.firstOrNull { it.name == j["kind"].str } ?: return null
            return CareTask(
                id = id, petId = j["pet_id"].str ?: return null, kind = kind, title = j["title"].str ?: kind.label,
                slots = j["slots"].list.mapNotNull { it.int }.filter { it in 0 until 1440 }.sorted().ifEmpty { listOf(8 * 60) },
                everyDays = (j["every_days"].int ?: 1).coerceIn(1, 365), anchorDay = j["anchor_day"].long ?: 0,
                adaptive = j["adaptive"].bool ?: true, createdAtMs = j["created_at_ms"].long ?: 0,
                series = j["series"].list.mapNotNull { it.long }.sorted(),
                editedAtMs = j["edited_at_ms"].long ?: 0,
            )
        }

        fun completionFrom(j: Json): Completion? {
            val id = j["id"].str?.takeIf { ID.matches(it) } ?: return null
            return Completion(
                taskId = j["task_id"].str ?: return null, atMs = j["at_ms"].long ?: return null,
                localMinute = j["local_minute"].int ?: 0, localDay = j["local_day"].long ?: 0, id = id, by = j["done_by"].str,
            )
        }
    }
}
