package com.pawpixel.map

import com.pawpixel.core.CareTask
import com.pawpixel.core.Completion
import com.pawpixel.core.Json
import com.pawpixel.core.Pet
import com.pawpixel.core.SharedData
import com.pawpixel.core.Species
import com.pawpixel.core.SyncPush
import com.pawpixel.core.TaskKind

/** A family sharing pets, and who's in it. [ownerId] can remove people and cancel invites. */
data class Household(val id: String, val name: String, val members: List<Member>, val ownerId: String? = null) {
    data class Member(val userId: String, val name: String)
    fun nameOf(userId: String?): String? = members.firstOrNull { it.userId == userId }?.name
}

/**
 * Family sharing on PawPixel's server (supabase/migrations/0004). Members of a household can read
 * and change only that household's pets, tasks and care records; the server enforces it.
 * Photos never go: pets travel as their name, species, ears and pixel look code.
 */
class HouseholdClient(private val api: SupabaseApi) {
    val isSignedIn: Boolean get() = api.isSignedIn
    val userId: String? get() = api.userId

    /** Your household, or null if you're not in one. */
    suspend fun mine(): Household? {
        val me = api.userId ?: return null
        val row = Json.parse(api.rest("GET", "/rest/v1/household_members?user_id=eq.$me&select=household_id,households(name,owner_id)")).list.firstOrNull()
            ?: return null
        val id = row["household_id"].str ?: return null
        val members = Json.parse(api.rest("GET", "/rest/v1/household_members?household_id=eq.$id&select=user_id,display_name&order=joined_at")).list
            .mapNotNull { m -> Household.Member(m["user_id"].str ?: return@mapNotNull null, m["display_name"].str ?: "Family member") }
        return Household(id, row["households"]["name"].str ?: "Family", members, row["households"]["owner_id"].str)
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
            ?: throw MapException(MapException.Kind.REFUSED, "That invite code is wrong or has expired. Check it with the person who sent it.")

    /** The family's owner removes someone (they keep their own copies of the pets). */
    suspend fun removeMember(userId: String) { api.rpc("remove_member", Json.obj("p_user" to userId)) }

    /** The owner cancels every open invite code. */
    suspend fun revokeInvites() { api.rpc("revoke_invites") }

    suspend fun leave() { api.rpc("leave_household") }

    /** Everything the family shares, as the server has it now. */
    suspend fun pull(householdId: String): SharedData {
        val h = "household_id=eq.$householdId"
        val pets = Json.parse(api.rest("GET", "/rest/v1/household_pets?$h&select=*")).list.mapNotNull(::petFrom)
        val tasks = Json.parse(api.rest("GET", "/rest/v1/household_tasks?$h&select=*")).list.mapNotNull(::taskFrom)
        val completions = ArrayList<Completion>()
        var offset = 0
        while (true) { // the server returns at most 1000 rows at a time
            val page = Json.parse(api.rest("GET", "/rest/v1/household_completions?$h&select=*&order=at_ms,id&limit=$PAGE&offset=$offset")).list
            completions += page.mapNotNull(::completionFrom)
            if (page.size < PAGE) break
            offset += PAGE
        }
        return SharedData(pets, tasks, completions)
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
        for (chunk in push.deleteCompletionIds.chunked(100)) api.rest("DELETE", "/rest/v1/household_completions?household_id=eq.$householdId&id=in.(${chunk.joinToString(",")})")
        for (chunk in push.deleteTaskIds.chunked(100)) api.rest("DELETE", "/rest/v1/household_tasks?household_id=eq.$householdId&id=in.(${chunk.joinToString(",")})")
        for (chunk in push.deletePetIds.chunked(100)) api.rest("DELETE", "/rest/v1/household_pets?household_id=eq.$householdId&id=in.(${chunk.joinToString(",")})")
    }

    companion object {
        const val PAGE = 1000
        /** Invite codes: 8 letters/digits without look-alikes (no 0/O, 1/I/L). */
        const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** Accepts "abcd-2345", " ABCD 2345 " etc. */
        fun normalizeCode(code: String): String = code.uppercase().filter { it in CODE_ALPHABET }

        fun petJson(h: String, p: Pet) = Json.obj(
            "household_id" to h, "id" to p.id, "name" to p.name.take(24), "species" to p.species.name, "ears" to p.ears,
            "look" to (p.lookCode ?: ""), "eyes" to p.eyes.map { (x, y) -> listOf(x, y) }, "birth_day" to p.birthDay, "created_at_ms" to p.createdAtMs,
        )

        fun taskJson(h: String, t: CareTask) = Json.obj(
            "household_id" to h, "id" to t.id, "pet_id" to t.petId, "kind" to t.kind.name, "title" to t.title.take(30),
            "slots" to t.slots, "every_days" to t.everyDays, "anchor_day" to t.anchorDay, "series" to t.series,
            "adaptive" to t.adaptive, "created_at_ms" to t.createdAtMs,
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
