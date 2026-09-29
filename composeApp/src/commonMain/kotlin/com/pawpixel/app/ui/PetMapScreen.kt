package com.pawpixel.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.app.PetMapModel
import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.map.Gathering
import com.pawpixel.map.IsoTime
import com.pawpixel.map.MapArea
import com.pawpixel.map.MapException
import com.pawpixel.map.MapPet
import com.pawpixel.map.Venue
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.PetArt

private sealed interface MapPhase {
    data object Checking : MapPhase
    data object NotSetUp : MapPhase
    data object Join : MapPhase
    data class Ready(val areas: List<MapArea>) : MapPhase
}

/** Naga pilot centre, used until your own area is known. */
private const val NAGA_LAT = 13.6218
private const val NAGA_LNG = 123.1948

/**
 * The opt-in pet map: pixel pets of owners nearby (areas with 3+ owners), and public gatherings
 * whose venue appears once you say you're going. Nothing here uses your exact location.
 */
@Composable
fun PetMapScreen(app: AppScope, state: AppState) {
    val map = app.repo.map
    var phase by remember { mutableStateOf<MapPhase>(MapPhase.Checking) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        val area = map.myArea ?: run { phase = MapPhase.Join; return }
        // Keep your pets and presence fresh (presence expires after 14 days without opening the map).
        val shared = state.pets.filter { map.sharedPetIds?.contains(it.id) ?: true }
        map.join(shared, { app.repo.art(it) }, area)
        phase = MapPhase.Ready(map.client.nearbyAreas(area))
    }

    // Runs map work, turning failures into a plain message. (Join disables its button while busy.)
    fun act(block: suspend () -> Unit) {
        busy = true; message = null
        app.launch {
            try {
                block()
            } catch (e: MapException) {
                message = e.message
                if (e.kind == MapException.Kind.SIGNED_OUT) phase = MapPhase.Join
                if (e.kind == MapException.Kind.NOT_SET_UP) phase = MapPhase.NotSetUp
                app.repo.platform.log("Map: ${e.kind} ${e.message}")
            } catch (e: Exception) {
                message = e.message ?: "Something went wrong. Please try again."
                app.repo.platform.log("Map error: ${e.stackTraceToString()}")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        phase = when {
            !map.settings.isConfigured -> MapPhase.NotSetUp
            !map.client.isSignedIn || map.myArea == null -> MapPhase.Join
            else -> { act { load() }; MapPhase.Checking }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = app.back) { Text("‹ Back") }
            Text("Pet map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (phase is MapPhase.Ready) MapMenu(app, map, state, onChanged = { act { load() } }, onLeft = { phase = MapPhase.Join }, act = ::act)
        }
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        when (val p = phase) {
            MapPhase.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            MapPhase.NotSetUp -> NotSetUp()
            MapPhase.Join -> JoinMap(app, map, state, busy) { chosen ->
                act {
                    if (!map.signIn()) return@act
                    val area = map.locate() ?: throw IllegalStateException(
                        "PawPixel needs your approximate location to show your area. You can allow it in your phone's settings.",
                    )
                    map.join(chosen, { app.repo.art(it) }, area)
                    phase = MapPhase.Ready(map.client.nearbyAreas(area))
                }
            }
            is MapPhase.Ready -> ReadyMap(app, map, p.areas, act = ::act)
        }
    }
}

@Composable
private fun NotSetUp() {
    PixelCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The pet map is coming soon", fontWeight = FontWeight.Bold)
            Text("Meet other pet owners in Naga at public pet walks. The map isn't switched on in this version of the app yet.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JoinMap(app: AppScope, map: PetMapModel, state: AppState, busy: Boolean, onJoin: (List<Pet>) -> Unit) {
    var adult by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(state.pets.map { it.id }.filter { map.sharedPetIds?.contains(it) ?: true }.toSet()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PixelCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Meet pet owners near you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("• Other owners see your pixel pets and their names. Never your photos.")
                Text("• Your area shows as a square about 1 km wide. Your exact location never leaves your phone.")
                Text("• An area only appears once 3 or more owners are in it.")
                Text("• Gatherings are at public places. The venue shows after you say you're going.")
                Text("• Leave any time, and delete your map account from this screen.")
            }
        }
        Text("Pets to show", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.pets.forEach { pet ->
                FilterChip(pet.id in chosen, { chosen = if (pet.id in chosen) chosen - pet.id else chosen + pet.id }, label = { Text(pet.name) })
            }
        }
        CheckRow("I'm 18 or older", adult) { adult = it }
        CheckRow("Show my pixel pets, their names and my rough area to other PawPixel owners", consent) { consent = it }
        TextButton(onClick = { app.repo.platform.openUrl(PRIVACY_URL) }) { Text("Privacy policy") }
        Button(
            enabled = adult && consent && chosen.isNotEmpty() && !busy,
            onClick = { onJoin(state.pets.filter { it.id in chosen }) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Joining…" else "${map.signInLabel} and join") }
        Text(
            "Pilot: the map starts in Naga City. Signing in lets us remove people who break the rules.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun MapMenu(
    app: AppScope, map: PetMapModel, state: AppState,
    onChanged: () -> Unit, onLeft: () -> Unit, act: (suspend () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    Box {
        TextButton(onClick = { open = true }) { Text("More") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Update my area") }, onClick = {
                open = false
                act { map.locate() ?: throw IllegalStateException("Couldn't get your approximate location."); onChanged() }
            })
            DropdownMenuItem({ Text("Choose pets to show") }, onClick = { open = false; onLeft() })
            DropdownMenuItem({ Text("Leave the map") }, onClick = { open = false; confirm = "leave" })
            DropdownMenuItem({ Text("Delete my map account") }, onClick = { open = false; confirm = "delete" })
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "leave") "Leave the pet map?" else "Delete your map account?") },
            text = {
                Text(
                    if (what == "leave") "Your pets and area are removed from the map. You can join again later."
                    else "Your map account, pets on the map, RSVPs and blocks are deleted from the server. Your pets stay on this phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    act { if (what == "leave") map.leave() else map.deleteAccount(); onLeft() }
                }) { Text(if (what == "leave") "Leave" else "Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReadyMap(app: AppScope, map: PetMapModel, areas: List<MapArea>, act: (suspend () -> Unit) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<MapArea?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text("Nearby") })
            FilterChip(tab == 1, { tab = 1 }, label = { Text("Gatherings") })
        }
        if (tab == 0) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                val area = map.myArea
                TileMap(
                    map.settings, app.repo.platform,
                    area?.centerLat ?: NAGA_LAT, area?.centerLng ?: NAGA_LNG,
                    areas, area, onAreaTap = { selected = it },
                    modifier = Modifier.fillMaxSize(),
                )
                if (areas.isEmpty()) {
                    PixelCard(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                        Text("No areas with 3+ owners near you yet. Invite pet friends in your area to PawPixel!")
                    }
                }
                selected?.let { a -> AreaPets(app, map, a, act, onClose = { selected = null }, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
        } else {
            Gatherings(app, map, act)
        }
    }
}

@Composable
private fun AreaPets(
    app: AppScope, map: PetMapModel, area: MapArea, act: (suspend () -> Unit) -> Unit,
    onClose: () -> Unit, modifier: Modifier,
) {
    var pets by remember(area.cellId) { mutableStateOf<List<MapPet>?>(null) }
    var menuFor by remember { mutableStateOf<MapPet?>(null) }
    var reportFor by remember { mutableStateOf<MapPet?>(null) }
    var blockFor by remember { mutableStateOf<MapPet?>(null) }
    LaunchedEffect(area.cellId) { act { pets = map.client.petsInArea(area.cellId) } }
    val label = map.myArea?.let { if (it.id == area.cellId) "Your area" else null } ?: "An area near you"
    PixelCard(modifier.fillMaxWidth().padding(8.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label · ${area.pets} pets", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            val list = pets
            if (list == null) CircularProgressIndicator(Modifier.padding(8.dp))
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { pet ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
                        val img = remember(pet.id) { pet.look?.let { PetArt(it, speciesOf(pet.species), Ears.of(pet.ears)).still } }
                        SpriteView(img, Modifier.size(72.dp), animate = false)
                        Text(pet.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (pet.mine) Text("Yours", style = MaterialTheme.typography.bodySmall)
                        else Box {
                            TextButton(onClick = { menuFor = pet }) { Text("⋯") }
                            DropdownMenu(menuFor == pet, { menuFor = null }) {
                                DropdownMenuItem({ Text("Block owner") }, onClick = { menuFor = null; blockFor = pet })
                                DropdownMenuItem({ Text("Report") }, onClick = { menuFor = null; reportFor = pet })
                            }
                        }
                    }
                }
            }
        }
    }
    blockFor?.let { pet ->
        AlertDialog(
            onDismissRequest = { blockFor = null },
            title = { Text("Block ${pet.name}'s owner?") },
            text = { Text("You won't see each other's pets on the map any more.") },
            confirmButton = {
                TextButton(onClick = {
                    blockFor = null
                    act { map.client.blockOwnerOf(pet.id); pets = pets?.filterNot { it.id == pet.id } }
                }) { Text("Block", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { blockFor = null }) { Text("Cancel") } },
        )
    }
    reportFor?.let { pet -> ReportDialog(pet, onDone = { reportFor = null }) { reason, details -> act { map.client.report(pet.id, reason, details) } } }
}

private fun speciesOf(name: String) = Species.entries.firstOrNull { it.name == name } ?: Species.OTHER

@Composable
private fun ReportDialog(pet: MapPet, onDone: () -> Unit, send: (String, String?) -> Unit) {
    val reasons = listOf("spam" to "Spam or fake", "harassment" to "Harassment", "unsafe" to "Unsafe behaviour",
        "child_safety" to "Child safety", "other" to "Something else")
    var reason by remember { mutableStateOf("spam") }
    var details by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (sent) "Thanks for telling us" else "Report ${pet.name}") },
        text = {
            if (sent) Text("We'll look at it. If someone is in danger, contact the police (911 in the Philippines).")
            else Column {
                reasons.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable { reason = key }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(reason == key, { reason = key }); Text(label)
                    }
                }
                OutlinedTextField(details, { details = it.take(500) }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (sent) onDone() else { send(reason, details.ifBlank { null }); sent = true } }) {
                Text(if (sent) "OK" else "Send report")
            }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun Gatherings(app: AppScope, map: PetMapModel, act: (suspend () -> Unit) -> Unit) {
    var list by remember { mutableStateOf<List<Gathering>?>(null) }
    val venues = remember { mutableStateOf(mapOf<String, Venue>()) }
    LaunchedEffect(Unit) {
        act {
            val g = map.client.gatherings()
            list = g
            venues.value = g.filter { it.iAmGoing }.mapNotNull { x -> map.client.venue(x.id)?.let { x.id to it } }.toMap()
        }
    }
    val items = list
    when {
        items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        items.isEmpty() -> PixelCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("No gatherings yet. The first Naga pet walk will be announced here.")
        }
        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            items(items, key = { it.id }) { g ->
                PixelCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(g.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(IsoTime.parseMs(g.startsAt)?.let { formatDateTime(it, app.repo.clock) } ?: g.startsAt)
                        Text(g.areaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val left = (g.capacity - g.going).coerceAtLeast(0)
                        Text("${g.going} going · ${if (left == 0) "full" else "$left spots left"}", style = MaterialTheme.typography.bodySmall)
                        val venue = venues.value[g.id]
                        if (g.iAmGoing && venue != null) {
                            Text("Meet at: ${venue.name}", fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = {
                                app.repo.platform.openUrl("https://www.google.com/maps/search/?api=1&query=${venue.lat},${venue.lng}")
                            }) { Text("Open in maps") }
                        }
                        Button(
                            enabled = g.iAmGoing || left > 0,
                            onClick = {
                                act {
                                    val going = !g.iAmGoing
                                    val count = map.client.rsvp(g.id, going)
                                    list = list?.map { if (it.id == g.id) it.copy(iAmGoing = going, going = count) else it }
                                    venues.value = if (going) map.client.venue(g.id)?.let { venues.value + (g.id to it) } ?: venues.value
                                        else venues.value - g.id
                                }
                            },
                        ) { Text(if (g.iAmGoing) "Can't make it" else "I'm going") }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed") // 1970-01-01 was a Thursday

/** "Sat, Oct 4 · 8:00 AM" in the phone's time zone. */
fun formatDateTime(ms: Long, clock: LocalClock): String {
    val day = clock.dayIndex(ms)
    val z = day + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return "${WEEKDAYS[day.mod(7L).toInt()]}, ${MONTHS[(m - 1).toInt()]} $d · ${formatTime(ms, clock)}"
}
