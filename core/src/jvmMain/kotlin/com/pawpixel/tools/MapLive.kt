package com.pawpixel.tools

import com.pawpixel.core.AppState
import com.pawpixel.core.CareTask
import com.pawpixel.core.HouseholdSync
import com.pawpixel.core.Json
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.SharedData
import com.pawpixel.core.StateOps
import com.pawpixel.core.SyncPush
import com.pawpixel.core.TaskKind
import com.pawpixel.map.HouseholdClient
import com.pawpixel.core.LocationGrid
import com.pawpixel.core.Species
import com.pawpixel.map.Http
import com.pawpixel.map.HttpResponse
import com.pawpixel.map.MapClient
import com.pawpixel.map.MapException
import com.pawpixel.map.MapSettings
import com.pawpixel.map.SessionStore
import com.pawpixel.map.SharedPet
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetLook
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.system.exitProcess

/**
 * The app's own map client against a real Supabase (local `supabase start`), as four owners.
 *   SUPABASE_URL=... ANON_KEY=... SERVICE_KEY=... ./gradlew :core:mapLive
 */
fun main() {
    val url = System.getenv("SUPABASE_URL") ?: error("SUPABASE_URL")
    val anon = System.getenv("ANON_KEY") ?: error("ANON_KEY")
    val service = System.getenv("SERVICE_KEY") ?: error("SERVICE_KEY")
    val java = HttpClient.newHttpClient()
    val http = Http { r ->
        val b = HttpRequest.newBuilder(URI(r.url))
        r.headers.forEach { (k, v) -> b.header(k, v) }
        b.method(r.method, if (r.body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(r.body))
        val resp = java.send(b.build(), BodyHandlers.ofString())
        HttpResponse(resp.statusCode(), resp.body())
    }
    val settings = MapSettings(url, anon, "", "", "")
    var failures = 0
    fun check(name: String, ok: Boolean, detail: Any? = null) {
        println((if (ok) "PASS  " else "FAIL  ") + name + if (ok) "" else "  -> $detail")
        if (!ok) failures++
    }
    fun <T> run(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        return result!!.getOrThrow()
    }
    fun newOwner(): MapClient {
        val email = "kotlin-${UUID.randomUUID().toString().take(8)}@test.pawpixel"
        val body = Json.obj("email" to email, "password" to "test-pass-123", "email_confirm" to true).stringify()
        val req = HttpRequest.newBuilder(URI("$url/auth/v1/admin/users")).header("apikey", service)
            .header("Authorization", "Bearer $service").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        check("create test owner", java.send(req, BodyHandlers.ofString()).statusCode() in 200..201)
        val store = object : SessionStore { var s: String? = null; override fun load() = s; override fun save(json: String?) { s = json } }
        return MapClient(settings, http, store, System::currentTimeMillis).also { c -> run { c.signInWithPassword(email, "test-pass-123") } }
    }

    val look = PetLook.decode("1;e08a3a,f4f1ea;" + "0".repeat(40) + "1".repeat(24))!!.encode()
    val here = LocationGrid.snap(13.6218, 123.1948)  // Naga City
    val owners = List(3) { newOwner() }
    owners.forEachIndexed { i, c -> run { c.join(listOf(SharedPet("p$i", "Pet$i", "CAT", "POINTY", look)), here) } }
    check("joined", run { owners[0].hasJoined() })

    val viewer = newOwner()
    val areas = run { viewer.nearbyAreas(here) }
    check("the area with 3 owners shows", areas.singleOrNull()?.cellId == here.id && areas.single().pets == 3, areas)
    val pets = run { viewer.petsInArea(here.id) }
    check("its pets decode to drawable pixel pets", pets.size == 3 && pets.all { it.look != null }, pets)
    check("a pet from the map draws", runCatching { PetArt(pets.first().look!!, Species.CAT).still.width > 0 }.getOrDefault(false))

    run { viewer.blockOwnerOf(pets.first().id) }
    check("blocking one owner hides the area", run { viewer.nearbyAreas(here) }.isEmpty())

    run { viewer.join(emptyList(), LocationGrid.snap(10.0, 120.0)) }
    val gatherings = run { viewer.gatherings() }
    check("gatherings list reads", gatherings.isEmpty() || gatherings.all { it.title.isNotBlank() }, gatherings)

    run { owners[2].leaveMap() }
    check("leaving the map works", !run { owners[2].hasJoined() })
    run { owners[1].deleteAccount() }
    check("deleting the account signs out", !owners[1].isSignedIn)
    val signedOut = runCatching { run { owners[1].gatherings() } }.exceptionOrNull() as? MapException
    check("a deleted account can't read the map", signedOut?.kind == MapException.Kind.SIGNED_OUT, signedOut)

    // ---------- Family sharing: two phones and a stranger, through the app's own sync ----------
    val save = newOwner(); val jamaica = newOwner(); val stranger = newOwner()
    val fs = HouseholdClient(save.api); val fj = HouseholdClient(jamaica.api); val fx = HouseholdClient(stranger.api)
    val hid = run { fs.create("Mochi's family", "Save") }
    val code = run { fs.invite(hid) }
    check("invite code looks right", Regex("[A-HJKMNP-Z2-9]{8}").matches(code), code)
    val wrong = runCatching { run { fx.join("AAAA2222", "Stranger") } }.exceptionOrNull() as? MapException
    check("a wrong code is refused with a reason", wrong?.kind == MapException.Kind.REFUSED && wrong.message!!.contains("wrong"), wrong)
    repeat(10) { runCatching { run { fx.join("AAAA2222", "Stranger") } } }
    val locked = runCatching { run { fx.join(code, "Stranger") } }.exceptionOrNull() as? MapException
    check("after 10 wrong codes, even the right one waits an hour", locked?.message?.contains("wait an hour") == true, locked)
    check("joining with a typed code (dash, lower case)", run { fj.join(code.lowercase().chunked(4).joinToString("-"), "Jamaica") } == hid)
    val fam = run { fj.mine() }
    check("both see the family and its members", fam?.members?.map { it.name } == listOf("Save", "Jamaica"), fam)

    val clock = LocalClock.MANILA
    val now = System.currentTimeMillis()
    val mochi = Pet("mochi", "Mochi", Species.DOG, 0, shared = true, lookCode = look)
    val feed = CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(7 * 60, 17 * 60))
    var saveState = StateOps.complete(AppState(pets = listOf(mochi), tasks = listOf(feed)), "feed", now - 60_000, clock)
    var saveBase = SharedData(); var janBase = SharedData(); var janState = AppState()
    fun syncSave() { val r = HouseholdSync.merge(saveState, saveBase, run { fs.pull(hid) }); run { fs.push(hid, r.push) }; saveState = r.state; saveBase = r.base }
    fun syncJan() { val r = HouseholdSync.merge(janState, janBase, run { fj.pull(hid) }); run { fj.push(hid, r.push) }; janState = r.state; janBase = r.base }
    syncSave(); syncJan()
    check("Jamaica's phone gets Mochi, the tasks and Save's Done", janState.pets.singleOrNull()?.lookCode == look &&
        janState.tasks.size == 1 && janState.completions.singleOrNull()?.by == save.userId, janState)
    janState = StateOps.complete(janState, "feed", now, clock)
    syncJan(); syncSave()
    check("Save sees Jamaica's Done, marked as hers", saveState.completions.count { it.by == jamaica.userId } == 1, saveState.completions)
    janState = StateOps.undoLast(janState, "feed", mine = setOf(null, jamaica.userId))
    syncJan(); syncSave()
    check("Jamaica's undo reaches Save", saveState.completions.none { it.by == jamaica.userId }, saveState.completions)

    val peek = run { fx.pull(hid) }
    check("a stranger reads nothing of the family", peek.pets.isEmpty() && peek.tasks.isEmpty() && peek.completions.isEmpty(), peek)
    val sneak = runCatching { run { fx.push(hid, SyncPush(upsertPets = listOf(mochi.copy(name = "Hacked")))) } }.exceptionOrNull() as? MapException
    check("a stranger can't write into the family", sneak?.kind == MapException.Kind.REFUSED, sneak)
    run {
        save.api.rest("POST", "/rest/v1/household_completions", Json.obj("household_id" to hid, "id" to "spoof1", "task_id" to "feed",
            "at_ms" to now, "local_minute" to 0, "local_day" to 0, "done_by" to jamaica.userId).stringify())
    }
    check("nobody can log a Done in someone else's name", run { fs.pull(hid) }.completions.single { it.id == "spoof1" }.by == save.userId)

    run { fj.leave() }
    check("leaving works, and the family stays", run { fj.mine() } == null && run { fs.mine() }?.members?.size == 1)
    run { save.deleteAccount() }
    val gone = java.send(HttpRequest.newBuilder(URI("$url/rest/v1/households?id=eq.$hid&select=id")).header("apikey", service)
        .header("Authorization", "Bearer $service").build(), BodyHandlers.ofString()).body()
    check("deleting the last member's account deletes the family", gone.trim() == "[]", gone)

    println(if (failures == 0) "\nthe app's map client works against the real backend" else "\n$failures failed")
    exitProcess(if (failures == 0) 0 else 1)
}
