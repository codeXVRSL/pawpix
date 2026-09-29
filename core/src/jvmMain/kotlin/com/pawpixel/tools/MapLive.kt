package com.pawpixel.tools

import com.pawpixel.core.Json
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

    println(if (failures == 0) "\nthe app's map client works against the real backend" else "\n$failures failed")
    exitProcess(if (failures == 0) 0 else 1)
}
