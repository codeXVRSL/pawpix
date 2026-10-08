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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.unit.dp
import com.pawpixel.app.PetMapModel
import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.core.Units
import com.pawpixel.i18n.tr
import com.pawpixel.map.Gathering
import com.pawpixel.sprite.PixelIcons
import com.pawpixel.map.MyWalk
import com.pawpixel.map.CommunityStats
import com.pawpixel.map.IsoTime
import com.pawpixel.map.MapArea
import com.pawpixel.map.MapException
import com.pawpixel.map.MapPet
import com.pawpixel.map.LostPet
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
fun PetMapScreen(app: AppScope, state: AppState, map: PetMapModel = app.repo.map) {
    // Debug builds can switch to the in-app demo map when the real one isn't set up.
    var active by remember { mutableStateOf(map) }
    PetMapBody(app, state, active, onDemo = { active = app.repo.demoMap })
}

@Composable
private fun PetMapBody(app: AppScope, state: AppState, map: PetMapModel, onDemo: () -> Unit) {
    var phase by remember(map) { mutableStateOf<MapPhase>(MapPhase.Checking) }
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
                message = e.message ?: tr("Something went wrong. Please try again.")
                app.repo.platform.log("Map error: ${e.stackTraceToString()}")
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(map) {
        phase = when {
            !map.settings.isConfigured -> MapPhase.NotSetUp
            !map.client.isSignedIn || map.myArea == null -> MapPhase.Join
            else -> { act { load() }; MapPhase.Checking }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(app, if (map.isDemo) tr("Pet map (demo)") else tr("Pet map"), Modifier.padding(horizontal = 16.dp)) {
            if (phase is MapPhase.Ready) MapMenu(app, map, state, onChanged = { act { load() } }, onLeft = { phase = MapPhase.Join }, act = ::act)
        }
        if (map.isDemo) Text(
            tr("Demo: pretend owners and walks, on this phone only. Nothing is sent anywhere."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        when (val p = phase) {
            MapPhase.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            MapPhase.NotSetUp -> NotSetUp(if (app.repo.platform.isDebugBuild) onDemo else null)
            MapPhase.Join -> JoinMap(app, map, state, busy) { chosen ->
                act {
                    if (!map.signIn()) return@act
                    val area = map.locate() ?: throw IllegalStateException(
                        tr("PawPixel needs your approximate location to show your area. You can allow it in your phone's settings."),
                    )
                    map.join(chosen, { app.repo.art(it) }, area)
                    phase = MapPhase.Ready(map.client.nearbyAreas(area))
                }
            }
            is MapPhase.Ready -> ReadyMap(app, map, state, p.areas, act = ::act)
        }
    }
}

@Composable
private fun NotSetUp(onDemo: (() -> Unit)?) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("The pet map is coming soon"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Meet other pet owners in Naga at public pet walks. The map isn't switched on in this version of the app yet."))
            }
        }
        if (onDemo != null) SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Test build: try the demo map"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Pretend owners, pixel pets and walks around Naga, all on this phone: join, host a walk, RSVP, report. Nothing is sent anywhere."), style = MaterialTheme.typography.bodyMedium)
                PrimaryPill(tr("Try the demo map"), icon = PixelIcons.PIN, onClick = onDemo)
            }
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
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Meet pet owners near you"), style = MaterialTheme.typography.titleLarge)
                Text(tr("• Other owners see your pixel pets and their names. Never your photos."))
                Text(tr("• Your area shows as a square about {0} wide. Your exact location never leaves your phone.", Units.distance(1.0)))
                Text(tr("• An area only appears once 3 or more owners are in it."))
                Text(tr("• Gatherings are at public places. The venue shows after you say you're going."))
                Text(tr("• Leave any time, and delete your map account from this screen."))
            }
        }
        GroupLabel(tr("Pets to show"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.pets.forEach { pet ->
                ChoiceChip(pet.id in chosen, { chosen = if (pet.id in chosen) chosen - pet.id else chosen + pet.id }, pet.name)
            }
        }
        CheckRow(tr("I'm 18 or older"), adult) { adult = it }
        CheckRow(tr("Show my pixel pets, their names and my rough area to other PawPixel owners"), consent) { consent = it }
        LinkButton(tr("Privacy policy")) { app.repo.platform.openUrl(PRIVACY_URL) }
        PrimaryPill(
            if (busy) tr("Joining…") else tr("{0} and join", map.signInLabel),
            enabled = adult && consent && chosen.isNotEmpty() && !busy, big = true, modifier = Modifier.fillMaxWidth(),
        ) { onJoin(state.pets.filter { it.id in chosen }) }
        Hint(tr("Pilot: the map starts in Naga City. Signing in lets us remove people who break the rules."))
    }
}

/** One checkbox with its label: a single control for screen readers, and the whole row is the touch target. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
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
        GhostPill(tr("More")) { open = true }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(tr("Invite pet friends")) }, onClick = {
                open = false
                app.repo.platform.shareText(mapInviteText(state.pets.filter { map.sharedPetIds?.contains(it.id) ?: true }.map { it.name }))
            })
            DropdownMenuItem({ Text(tr("Update my area")) }, onClick = {
                open = false
                act { map.locate() ?: throw IllegalStateException(tr("Couldn't get your approximate location.")); onChanged() }
            })
            DropdownMenuItem({ Text(tr("Choose pets to show")) }, onClick = { open = false; onLeft() })
            DropdownMenuItem({ Text(tr("Leave the map")) }, onClick = { open = false; confirm = "leave" })
            DropdownMenuItem({ Text(tr("Delete my map account")) }, onClick = { open = false; confirm = "delete" })
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "leave") tr("Leave the pet map?") else tr("Delete your map account?")) },
            text = {
                Text(
                    if (what == "leave") tr("Your pets and area are removed from the map. You can join again later.")
                    else tr("Your map account, pets on the map, RSVPs and blocks are deleted from the server. Your pets stay on this phone."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    act { if (what == "leave") map.leave() else map.deleteAccount(); onLeft() }
                }) { Text(if (what == "leave") tr("Leave") else tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun ReadyMap(app: AppScope, map: PetMapModel, state: AppState, areas: List<MapArea>, act: (suspend () -> Unit) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<MapArea?>(null) }
    var stats by remember { mutableStateOf<CommunityStats?>(null) }
    // Walks on the map (pins) and the host's form; "placing" is the map waiting for a tap on the meeting spot.
    var walks by remember { mutableStateOf<List<Gathering>>(emptyList()) }
    // Lost pets within 15 km: red flags on the map, their own tab, and a sheet with the photos.
    var lost by remember { mutableStateOf<List<LostPet>>(emptyList()) }
    var openLost by remember { mutableStateOf<LostPet?>(null) }
    var form by remember { mutableStateOf<WalkForm?>(null) }
    var placing by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        act {
            walks = map.client.gatherings()
            stats = map.client.communityStats()
            map.myArea?.let { lost = map.client.lost.nearby(it.centerLat, it.centerLng) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        stats?.let { CommunityStrip(it) }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(tab == 0, { tab = 0 }, tr("Nearby"))
            ChoiceChip(tab == 1, { tab = 1 }, tr("Gatherings"))
            ChoiceChip(tab == 2, { tab = 2 }, if (lost.isEmpty()) tr("Lost pets") else tr("Lost pets ({0})", lost.size))
        }
        if (tab == 0 || placing) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                val area = map.myArea
                TileMap(
                    map.settings, app.repo.platform,
                    area?.centerLat ?: NAGA_LAT, area?.centerLng ?: NAGA_LNG,
                    areas, area, onAreaTap = { selected = it },
                    walks = walks.filter { it.cellLat != null && it.cellLng != null }.map { MapFlag(it.cellLat!!, it.cellLng!!, it.title) },
                    onWalkTap = { tab = 1 },
                    marker = form?.spot,
                    lost = lost.map { MapFlag(it.lastSeenLat, it.lastSeenLng, it.name, id = it.id) },
                    onLostTap = { f -> openLost = lost.firstOrNull { it.id == f.id } },
                    // The spot picked: back to the Gatherings tab, where the form reopens.
                    onMapTap = if (placing) ({ lat, lng -> form = (form ?: WalkForm()).copy(spot = lat to lng); placing = false; tab = 1 }) else null,
                    modifier = Modifier.fillMaxSize(),
                )
                if (placing) {
                    SoftCard(Modifier.align(Alignment.TopCenter).padding(12.dp), tone = Tone.Accent) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr("Tap the meeting place: a public spot like a plaza or a park gate."), style = MaterialTheme.typography.bodyMedium)
                            LinkButton(tr("Cancel"), color = MaterialTheme.colorScheme.onPrimaryContainer) { placing = false }
                        }
                    }
                } else if (areas.isEmpty()) {
                    SoftCard(Modifier.align(Alignment.TopCenter).padding(12.dp), tone = Tone.Surface) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(tr("No areas with 3+ owners near you yet. Invite pet friends in your area to PawPixel!"), style = MaterialTheme.typography.bodyMedium)
                            GhostPill(tr("Invite pet friends"), icon = PixelIcons.SHARE) {
                                app.repo.platform.shareText(mapInviteText(state.pets.filter { map.sharedPetIds?.contains(it.id) ?: true }.map { it.name }))
                            }
                        }
                    }
                }
                if (!placing) selected?.let { a -> AreaPets(app, map, a, act, onClose = { selected = null }, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
        } else if (tab == 2) {
            LostList(app, lost, onOpen = { openLost = it })
        } else {
            Gatherings(app, map, walks, act, onHost = { form = form ?: WalkForm() }, onChanged = { refresh++ })
        }
    }
    openLost?.let { LostPetSheet(app, map, it, onClose = { openLost = null; refresh++ }) }
    form?.let { f ->
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        if (!placing) HostWalkDialog(
            app, f, busy, error,
            onChange = { form = it },
            onPickSpot = { placing = true; tab = 0 },
            onSubmit = {
                val draft = f.draft(app.repo.clock) ?: return@HostWalkDialog
                busy = true; error = null
                app.launch {
                    try {
                        map.client.hostWalk(draft)
                        form = null
                        refresh++
                    } catch (e: MapException) {
                        error = e.message
                    } finally { busy = false }
                }
            },
            onClose = { form = null },
        )
    }
}

/** "128 owners · 190 pets · 6 areas · 2 walks coming up": the community, in one line. */
@Composable
private fun CommunityStrip(stats: CommunityStats) {
    Text(
        tr("{0} owners · {1} pets · {2} areas · {3} walks coming up", stats.owners, stats.pets, stats.areas, stats.walks),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
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
    val label = map.myArea?.let { if (it.id == area.cellId) tr("Your area") else null } ?: tr("An area near you")
    SoftCard(modifier.fillMaxWidth().padding(8.dp), tone = Tone.Surface) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("{0} · {1} pets", label, area.pets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LinkButton(tr("Close"), onClick = onClose)
            }
            val list = pets
            if (list == null) CircularProgressIndicator(Modifier.padding(8.dp))
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { pet ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
                        val img = remember(pet.id) { pet.look?.let { PetArt(it, speciesOf(pet.species), Ears.of(pet.ears)).still } }
                        SpriteView(img, Modifier.size(72.dp), animate = false, description = tr("Pixel {0}", pet.name))
                        Text(pet.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        if (pet.mine) Text(tr("Yours"), style = MaterialTheme.typography.bodySmall)
                        else Box {
                            val moreLabel = tr("More for {0}", pet.name)
                            TextButton(onClick = { menuFor = pet }, modifier = Modifier.semantics { contentDescription = moreLabel }) { Text("⋯") }
                            DropdownMenu(menuFor == pet, { menuFor = null }) {
                                DropdownMenuItem({ Text(tr("Block owner")) }, onClick = { menuFor = null; blockFor = pet })
                                DropdownMenuItem({ Text(tr("Report")) }, onClick = { menuFor = null; reportFor = pet })
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
            title = { Text(tr("Block {0}'s owner?", pet.name)) },
            text = { Text(tr("You won't see each other's pets on the map any more.")) },
            confirmButton = {
                TextButton(onClick = {
                    blockFor = null
                    act { map.client.blockOwnerOf(pet.id); pets = pets?.filterNot { it.id == pet.id } }
                }) { Text(tr("Block"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { blockFor = null }) { Text(tr("Cancel")) } },
        )
    }
    reportFor?.let { pet -> ReportDialog(pet, philippines = com.pawpixel.core.HealthPlan.isPhilippines(app.repo.platform.systemCountry()), onDone = { reportFor = null }) { reason, details -> act { map.client.report(pet.id, reason, details) } } }
}

private fun speciesOf(name: String) = Species.entries.firstOrNull { it.name == name } ?: Species.OTHER

@Composable
private fun ReportDialog(pet: MapPet, philippines: Boolean, onDone: () -> Unit, send: (String, String?) -> Unit) {
    val reasons = listOf("spam" to tr("Spam or fake"), "harassment" to tr("Harassment"), "unsafe" to tr("Unsafe behaviour"),
        "child_safety" to tr("Child safety"), "other" to tr("Something else"))
    var reason by remember { mutableStateOf("spam") }
    var details by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (sent) tr("Thanks for telling us") else tr("Report {0}", pet.name)) },
        text = {
            if (sent) Text(if (philippines) tr("We'll look at it. If someone is in danger, contact the police (911 in the Philippines).") else tr("We'll look at it. If someone is in danger, contact the police."))
            else Column {
                Column(Modifier.selectableGroup()) {
                    reasons.forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(reason == key, role = Role.RadioButton) { reason = key },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(reason == key, onClick = null, modifier = Modifier.padding(horizontal = 12.dp)); Text(label)
                        }
                    }
                }
                OutlinedTextField(details, { details = it.take(500) }, label = { Text(tr("Details (optional)")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (sent) onDone() else { send(reason, details.ifBlank { null }); sent = true } }) {
                Text(if (sent) tr("OK") else tr("Send report"))
            }
        },
        dismissButton = { if (!sent) TextButton(onClick = onDone) { Text(tr("Cancel")) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Gatherings(
    app: AppScope, map: PetMapModel, walks: List<Gathering>, act: (suspend () -> Unit) -> Unit,
    onHost: () -> Unit, onChanged: () -> Unit,
) {
    var list by remember { mutableStateOf<List<Gathering>?>(null) }
    var mine by remember { mutableStateOf<List<MyWalk>>(emptyList()) }
    val venues = remember { mutableStateOf(mapOf<String, Venue>()) }
    LaunchedEffect(walks) {
        act {
            val g = walks // the map fetched these already; only what's ours comes from the server here
            list = g
            mine = map.client.myWalks()
            venues.value = g.filter { it.iAmGoing || it.iAmHost }.mapNotNull { x -> map.client.venue(x.id)?.let { x.id to it } }.toMap()
        }
    }
    val items = list
    if (items == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Walks are hosted by owners like you, at public places."), style = MaterialTheme.typography.bodyMedium)
                    PrimaryPill(tr("Host a walk"), icon = PixelIcons.PLUS, onClick = onHost)
                }
            }
        }
        if (items.isEmpty()) item {
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) {
                Text(tr("No gatherings yet. The first Naga pet walk will be announced here."), style = MaterialTheme.typography.bodyMedium)
            }
        }
        // The key includes the RSVP, so the card is rebuilt (and re-announced to screen readers) when it changes.
        items(items, key = { "${it.id}:${it.iAmGoing}:${it.id in venues.value}" }) { g ->
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.title, style = MaterialTheme.typography.titleLarge)
                    Text(IsoTime.parseMs(g.startsAt)?.let { formatDateTime(it, app.repo.clock) } ?: g.startsAt)
                    Text(g.areaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    g.details?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    val left = (g.capacity - g.going).coerceAtLeast(0)
                    Text(
                        (if (g.iAmHost) tr("You're hosting") + " · " else "") +
                            tr("{0} going · {1}", g.going, if (left == 0) tr("full") else tr("{0} spots left", left)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (g.going > 0) GoingPets(map, g)
                    val venue = venues.value[g.id]
                    if ((g.iAmGoing || g.iAmHost) && venue != null) {
                        Text(tr("Meet at: {0}", venue.name), fontWeight = FontWeight.Bold)
                        GhostPill(tr("Open in maps")) {
                            app.repo.platform.openUrl("https://www.google.com/maps/search/?api=1&query=${venue.lat},${venue.lng}")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!g.iAmHost) PrimaryPill(
                            if (g.iAmGoing) tr("Can't make it") else tr("I'm going"),
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
                        )
                        GhostPill(tr("Invite a friend"), icon = PixelIcons.SHARE) { app.repo.platform.shareText(walkInviteText(g, app.repo.clock)) }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MyWalksSection(app, mine) { w -> act { map.client.cancelWalk(w.id); onChanged() } }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** The pixel pets of the owners going to a walk, in a row: a reason to go, and an ad for the maker. */
@Composable
private fun GoingPets(map: PetMapModel, g: Gathering) {
    var pets by remember(g.id, g.going) { mutableStateOf<List<MapPet>?>(null) }
    LaunchedEffect(g.id, g.going) { pets = runCatching { map.client.gatheringPets(g.id) }.getOrDefault(emptyList()) }
    val list = pets ?: return
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(tr("Going:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { pet ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                    val img = remember(pet.id) { pet.look?.let { PetArt(it, speciesOf(pet.species), Ears.of(pet.ears)).still } }
                    SpriteView(img, Modifier.size(44.dp), animate = false, description = tr("Pixel {0}", pet.name))
                    Text(pet.name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val WEEKDAYS = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed") // 1970-01-01 was a Thursday

/** "Sat, Oct 4 · 8:00 AM" in the phone's time zone. */
fun formatDateTime(ms: Long, clock: LocalClock): String = formatDay(clock.dayIndex(ms)) + " · " + formatTime(ms, clock)

/** "Sat, Oct 4" for a local day index. */
fun formatDay(day: Long): String {
    val z = day + 719468
    val era = z.floorDiv(146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return "${tr(WEEKDAYS[day.mod(7L).toInt()])}, ${tr(MONTHS[(m - 1).toInt()])} $d"
}
