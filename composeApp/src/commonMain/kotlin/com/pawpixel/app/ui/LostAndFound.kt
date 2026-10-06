package com.pawpixel.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pawpixel.app.PetMapModel
import com.pawpixel.app.Platform
import com.pawpixel.app.decodeImage
import com.pawpixel.app.rememberPhotoPicker
import com.pawpixel.core.AlbumPhoto
import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.LocationGrid
import com.pawpixel.core.Pet
import com.pawpixel.core.Species
import com.pawpixel.i18n.tr
import com.pawpixel.map.LostClient
import com.pawpixel.map.LostDetails
import com.pawpixel.map.LostDraft
import com.pawpixel.map.LostPet
import com.pawpixel.map.MapException
import com.pawpixel.map.Sighting
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PixelIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Lost and Found: the one time an owner needs the neighbourhood.
 *
 * From the pet's page, "Lost" raises an alert in one go: the pixel look and the best album photos
 * are already here, so the owner only marks where the pet was last seen and adds a note. Every
 * PawPixel owner within 15 km sees the alert on the map with the photos and can report a
 * sighting; the owner sees the sightings and closes the alert with "Safe home". A share link
 * carries the alert to Facebook and Messenger, where the search happens today.
 */

/** The map model that carries alerts: the real server, or the demo in test builds when the real one isn't set up. */
fun lostModel(app: AppScope): PetMapModel? = when {
    app.repo.map.settings.isConfigured -> app.repo.map
    app.repo.platform.isDebugBuild -> app.repo.demoMap
    else -> null
}

/** The open alert for a pet raised from this phone, whichever model carries it. */
fun lostAlertFor(app: AppScope, petId: String): String? = lostModel(app)?.alertFor(petId)

/** The pet's Lost and Found page: raise an alert, or watch the open one. */
@Composable
fun LostPanel(app: AppScope, state: AppState, pet: Pet) {
    val map = lostModel(app)
    var alertId by remember { mutableStateOf(map?.alertFor(pet.id)) }
    val id = alertId
    if (map != null && id != null) OpenAlert(app, map, pet, id, onClosed = { map.recordAlert(pet.id, null); alertId = null })
    else LostForm(app, state, pet, map, onRaised = { alertId = it })
}

private data class Draft(
    val description: String = "",
    /** 0 just now, 1 earlier today, 2 yesterday, 3 a few days ago. */
    val whenIndex: Int = 0,
    val spot: Pair<Double, Double>? = null,
    val photos: Set<String> = emptySet(),
) {
    fun lastSeenAt(now: Long) = now - listOf(0L, 4L, 24L, 72L)[whenIndex] * 3_600_000L
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LostForm(app: AppScope, state: AppState, pet: Pet, map: PetMapModel?, onRaised: (String) -> Unit) {
    val album = state.albumFor(pet.id)
    val revision by app.repo.cardRevision.collectAsState()
    var draft by remember { mutableStateOf(Draft(photos = album.take(LostClient.MAX_PHOTOS).map { it.id }.toSet())) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    val platform = app.repo.platform

    // The spot starts at your approximate location (or your map area); the owner moves it by tapping the map.
    LaunchedEffect(Unit) {
        if (draft.spot == null) {
            locating = true
            val here = map?.myArea?.let { it.centerLat to it.centerLng } ?: runCatching { platform.approximateLocation() }.getOrNull()
            draft = draft.copy(spot = here ?: (NAGA_CENTER))
            locating = false
        }
    }

    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Is {0} missing?", pet.name), style = MaterialTheme.typography.titleLarge)
            Text(
                if (map != null) tr("Raise an alert: PawPixel owners within 15 km see {0}'s photos and pixel twin on their map and can report where they saw {0}. Nobody sees your name or your home.", pet.name)
                else tr("This version of the app isn't connected to PawPixel's server yet, so alerts to owners nearby aren't on. You can still share a notice."),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    GroupLabel(tr("What to look for"))
    OutlinedTextField(
        draft.description, { draft = draft.copy(description = it.take(300)) },
        label = { Text(tr("Colour, size, collar, how they answer to their name")) },
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, minLines = 2, maxLines = 4,
    )

    GroupLabel(tr("When were they last seen?"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(tr("Just now"), tr("Earlier today"), tr("Yesterday"), tr("A few days ago")).forEachIndexed { i, label ->
            ChoiceChip(draft.whenIndex == i, { draft = draft.copy(whenIndex = i) }, label)
        }
    }

    GroupLabel(tr("Where were they last seen?"))
    Text(tr("Tap the map to move the marker. This spot is shown to owners nearby."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val spot = draft.spot
    Box(Modifier.fillMaxWidth().height(220.dp).clip(MaterialTheme.shapes.medium).border(1.dp, Paw.palette.hairline, MaterialTheme.shapes.medium)) {
        if (spot == null) Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else TileMap(
            (map ?: app.repo.map).settings, platform, spot.first, spot.second, emptyList(), null, onAreaTap = {},
            marker = spot, onMapTap = { lat, lng -> draft = draft.copy(spot = lat to lng) }, modifier = Modifier.fillMaxWidth().height(220.dp),
        )
    }
    GhostPill(if (locating) tr("Locating…") else tr("Use my location"), icon = PixelIcons.PIN, enabled = !locating) {
        locating = true
        app.launch {
            try { platform.approximateLocation()?.let { draft = draft.copy(spot = it) } ?: run { error = tr("Couldn't get your approximate location.") } }
            finally { locating = false }
        }
    }

    if (album.isNotEmpty()) {
        GroupLabel(tr("Photos to show (up to {0})", LostClient.MAX_PHOTOS))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(album, key = { it.id }) { photo ->
                val on = photo.id in draft.photos
                val label = if (on) tr("Photo chosen") else tr("Choose photo")
                Box(
                    Modifier.border(if (on) 3.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.extraSmall)
                        .semantics { contentDescription = label },
                ) {
                    PhotoThumb(app, HealthPhoto(photo.id, revision) { app.repo.albumPhoto(photo) }, label, size = 72.dp) {
                        draft = draft.copy(photos = if (on) draft.photos - photo.id else if (draft.photos.size < LostClient.MAX_PHOTOS) draft.photos + photo.id else draft.photos)
                    }
                }
            }
        }
    } else Hint(tr("No photos in {0}'s album yet. The pixel twin is shown instead; add photos to the album any time.", pet.name))

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val whenText = listOf(tr("just now"), tr("earlier today"), tr("yesterday"), tr("a few days ago"))[draft.whenIndex]
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (map != null) PrimaryPill(if (busy) tr("Sending…") else tr("Alert owners nearby"), enabled = !busy && spot != null, big = true, icon = PixelIcons.BELL) {
            val at = spot ?: return@PrimaryPill
            busy = true; error = null
            app.launch {
                try {
                    if (!map.signIn()) return@launch
                    val art = app.repo.art(pet)
                    val photos = album.filter { it.id in draft.photos }.mapNotNull { p -> app.repo.albumPhoto(p)?.let { shrinkForAlert(platform, it) } }
                    val id = map.client.lost.report(LostDraft(
                        pet.name, pet.species.name, art?.ears?.name, art?.look?.encode() ?: "", draft.description,
                        at.first, at.second, draft.lastSeenAt(app.repo.now()), photos,
                    ))
                    map.recordAlert(pet.id, id)
                    onRaised(id)
                } catch (e: MapException) {
                    error = e.message
                } catch (e: Exception) {
                    error = e.message ?: tr("Something went wrong. Please try again.")
                } finally { busy = false }
            }
        }
        GhostPill(tr("Share a notice"), icon = PixelIcons.SHARE) {
            platform.shareText(lostShareText(pet.name, pet.species, draft.description, whenText, spot, null))
        }
    }
    Hint(tr("Tip: tell your barangay, nearby vets and shelters too, and post the notice in local groups."))
}

/** A photo for an alert: at most 640 px on its long side, re-encoded (EXIF and GPS dropped), under 120 KB. */
suspend fun shrinkForAlert(platform: Platform, bytes: ByteArray): ByteArray? {
    for ((side, quality) in listOf(640 to 72, 480 to 60, 360 to 50)) {
        val img = platform.decodePhoto(bytes, side) ?: return null
        val out = platform.encodeJpeg(img, quality) ?: return null
        if (out.size <= LostClient.MAX_PHOTO_BYTES) return out
    }
    return null
}

/** The alert as text for Messenger, Facebook or a flyer, with a map link to the spot and the share page. */
fun lostShareText(name: String, species: Species, description: String, whenText: String, spot: Pair<Double, Double>?, alertId: String?): String {
    val kind = when (species) { Species.DOG -> tr("dog"); Species.CAT -> tr("cat"); else -> tr("pet") }
    val sb = StringBuilder(tr("LOST {0}: {1}.", kind.uppercase(), name))
    if (description.isNotBlank()) sb.append(" ").append(description.trim())
    sb.append(" ").append(tr("Last seen {0}.", whenText))
    if (spot != null) sb.append(" ").append(tr("Where: {0}", "https://www.google.com/maps/search/?api=1&query=${spot.first},${spot.second}"))
    if (alertId != null) sb.append(" ").append(tr("Seen {0}? Report a sighting here: {1}", name, LostClient.shareUrl(alertId)))
    return sb.toString()
}

/** "just now", "35 min ago", "3 h ago", "2 days ago". */
fun agoText(ms: Long, now: Long): String {
    val d = (now - ms).coerceAtLeast(0)
    return when {
        d < 90_000L -> tr("just now")
        d < 3_600_000L -> tr("{0} min ago", d / 60_000)
        d < 48 * 3_600_000L -> tr("{0} h ago", d / 3_600_000)
        else -> tr("{0} days ago", d / 86_400_000L)
    }
}

/** The owner's view of their open alert: the sightings, the share, and "Safe home". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenAlert(app: AppScope, map: PetMapModel, pet: Pet, alertId: String, onClosed: () -> Unit) {
    var details by remember { mutableStateOf<LostDetails?>(null) }
    var sightings by remember { mutableStateOf<List<Sighting>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var home by remember { mutableStateOf(false) }
    val clock = app.repo.clock
    val now = app.repo.now()
    fun act(block: suspend () -> Unit) = app.launch {
        try { block() } catch (e: MapException) { error = e.message } catch (e: Exception) { error = e.message ?: tr("Something went wrong. Please try again.") }
    }
    LaunchedEffect(refresh) {
        act {
            if (!map.signIn()) return@act
            details = map.client.lost.details(alertId)
            sightings = map.client.lost.sightings(alertId)
        }
    }

    if (home) {
        Box(Modifier.fillMaxWidth()) {
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Good) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("Welcome home, {0}!", pet.name), style = MaterialTheme.typography.headlineSmall)
                    Text(tr("The alert is closed. Thank you to everyone who looked."), style = MaterialTheme.typography.bodyMedium)
                    PrimaryPill(tr("Back to {0}'s room", pet.name)) { onClosed(); app.back() }
                }
            }
            Confetti(trigger = alertId, modifier = Modifier.matchParentSize())
        }
        return
    }

    val d = details
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val pose = remember(pet.lookKey) { app.repo.art(pet)?.still }
            SpriteView(pose, Modifier.size(64.dp), animate = false, description = tr("Pixel {0}", pet.name))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Alert is on for {0}", pet.name), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (d == null) tr("Loading…") else tr("Since {0} · last seen {1} · {2} photos", agoText(d.createdAtMs, now), agoText(d.lastSeenAtMs, now), d.photos.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryPill(tr("Safe home!"), icon = PixelIcons.HOUSE, big = true) { confirm = "found" }
        GhostPill(tr("Share the alert"), icon = PixelIcons.SHARE) {
            val spot = d?.let { it.lastSeenLat to it.lastSeenLng }
            app.repo.platform.shareText(lostShareText(pet.name, pet.species, d?.description ?: "", d?.let { agoText(it.lastSeenAtMs, now) } ?: tr("recently"), spot, alertId))
        }
    }

    GroupLabel(if (sightings.isEmpty()) tr("Sightings") else tr("Sightings ({0})", sightings.size))
    if (sightings.isEmpty()) SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) {
        Text(tr("None yet. Owners nearby see the alert on their map; sightings show up here. Pull this page open again to check."), style = MaterialTheme.typography.bodyMedium)
    }
    sightings.forEach { s -> SightingCard(app, s, d, now) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostPill(tr("Check again")) { refresh++ }
        LinkButton(tr("Remove the alert"), color = MaterialTheme.colorScheme.error) { confirm = "cancel" }
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "found") tr("Is {0} safe at home?", pet.name) else tr("Remove the alert?")) },
            text = { Text(if (what == "found") tr("The alert closes and leaves everyone's map. The share link says {0} was found.", pet.name) else tr("The alert and its sightings are deleted. Use this if it was raised by mistake.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    act {
                        if (what == "found") { map.client.lost.markFound(alertId); home = true } else { map.client.lost.cancel(alertId); onClosed(); app.back() }
                    }
                }) { Text(if (what == "found") tr("Yes, safe home") else tr("Remove")) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun SightingCard(app: AppScope, s: Sighting, d: LostDetails?, now: Long) {
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val km = d?.let { LocationGrid.distanceKm(it.lastSeenLat, it.lastSeenLng, s.lat, s.lng) }
            Text(
                if (km == null) agoText(s.createdAtMs, now) else tr("{0} · {1} km from where they were last seen", agoText(s.createdAtMs, now), kmText(km)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            s.photo?.let { bytes -> AlertPhoto(bytes, tr("Sighting photo"), Modifier.size(120.dp)) }
            GhostPill(tr("Open in maps"), icon = PixelIcons.PIN) {
                app.repo.platform.openUrl("https://www.google.com/maps/search/?api=1&query=${s.lat},${s.lng}")
            }
        }
    }
}

fun kmText(km: Double): String = if (km < 1) "0." + ((km * 10).toInt()) else if (km < 10) ((km * 10).toInt() / 10.0).toString() else km.toInt().toString()

/** A JPEG from the server, decoded off the main thread. */
@Composable
fun AlertPhoto(bytes: ByteArray, description: String, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, bytes.size) { value = withContext(Dispatchers.Default) { runCatching { decodeImage(bytes) }.getOrNull() } }
    Box(modifier.clip(MaterialTheme.shapes.small).border(1.dp, Paw.palette.hairline, MaterialTheme.shapes.small).semantics { contentDescription = description }) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp)) }
    }
}

/**
 * One lost pet, opened from the map: photos, what to look for, how far and how long ago, and
 * "I saw them". Shown over whichever tab the owner is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LostPetSheet(app: AppScope, map: PetMapModel, lost: LostPet, onClose: () -> Unit) {
    var details by remember(lost.id) { mutableStateOf<LostDetails?>(null) }
    var sighting by remember { mutableStateOf(false) }
    var thanks by remember { mutableStateOf(false) }
    val now = app.repo.now()
    LaunchedEffect(lost.id) { runCatching { details = map.client.lost.details(lost.id) } }
    val d = details
    val species = Species.entries.firstOrNull { it.name == lost.species } ?: Species.OTHER
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SoftCard(Modifier.fillMaxWidth().padding(12.dp), tone = Tone.Surface) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val img = remember(lost.id) { lost.look?.let { PetArt(it, species, Ears.of(lost.ears)).still } }
                    SpriteView(img, Modifier.size(64.dp), animate = false, description = tr("Pixel {0}", lost.name))
                    Column(Modifier.weight(1f)) {
                        Text(tr("LOST: {0}", lost.name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            tr("Last seen {0} · {1} km from your area", agoText(lost.lastSeenAtMs, now), kmText(lost.distanceKm)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinkButton(tr("Close"), onClick = onClose)
                }
                lost.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (lost.photoCount > 0) {
                    if (d == null) CircularProgressIndicator(Modifier.padding(8.dp))
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.photos.size) { i -> AlertPhoto(d.photos[i], tr("Photo {0} of {1}", i + 1, lost.name), Modifier.size(120.dp)) }
                    }
                }
                if (lost.sightings > 0) Text(tr("{0} sightings reported", lost.sightings), style = MaterialTheme.typography.labelMedium)
                if (thanks) SoftCard(Modifier.fillMaxWidth(), tone = Tone.Good) {
                    Text(tr("Thank you. The owner sees your sighting right away."), fontWeight = FontWeight.Bold)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!lost.mine) PrimaryPill(tr("I saw them"), icon = PixelIcons.PIN) { sighting = true }
                    GhostPill(tr("Share"), icon = PixelIcons.SHARE) {
                        app.repo.platform.shareText(lostShareText(lost.name, species, lost.description ?: "", agoText(lost.lastSeenAtMs, now), lost.lastSeenLat to lost.lastSeenLng, lost.id))
                    }
                    GhostPill(tr("Open in maps")) {
                        app.repo.platform.openUrl("https://www.google.com/maps/search/?api=1&query=${lost.lastSeenLat},${lost.lastSeenLng}")
                    }
                }
            }
        }
    }
    if (sighting) SightingDialog(app, map, lost, onClose = { sighting = false }, onSent = { sighting = false; thanks = true })
}

/** "I saw them": your approximate location (or the last-seen spot), a note, maybe a photo. */
@Composable
private fun SightingDialog(app: AppScope, map: PetMapModel, lost: LostPet, onClose: () -> Unit, onSent: () -> Unit) {
    var note by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var where by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val platform = app.repo.platform
    LaunchedEffect(Unit) { where = runCatching { platform.approximateLocation() }.getOrNull() ?: (lost.lastSeenLat to lost.lastSeenLng) }
    val pick = rememberPhotoPicker { bytes -> if (bytes != null) app.launch { photo = shrinkForAlert(platform, bytes) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("You saw {0}?", lost.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Your approximate location is sent as the spot, so the owner knows where to look. Add how to reach you if you'd like a call."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(note, { note = it.take(300) }, label = { Text(tr("What you saw, when, how to reach you (optional)")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostPill(if (photo == null) tr("Add a photo") else tr("Photo added"), icon = PixelIcons.CAMERA) { pick() }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && where != null, onClick = {
                val at = where ?: return@TextButton
                busy = true; error = null
                app.launch {
                    try {
                        if (!map.signIn()) return@launch
                        map.client.lost.reportSighting(lost.id, at.first, at.second, note, photo)
                        onSent()
                    } catch (e: MapException) { error = e.message } catch (e: Exception) { error = e.message ?: tr("Something went wrong. Please try again.") }
                    finally { busy = false }
                }
            }) { Text(if (busy) tr("Sending…") else tr("Send sighting")) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
}

/** The Lost pets tab on the map: every open alert within 15 km, nearest first. */
@Composable
fun LostList(app: AppScope, lost: List<LostPet>, onOpen: (LostPet) -> Unit) {
    val now = app.repo.now()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
            Text(tr("Pets reported missing near you. Tap one to see the photos and report a sighting. Your own pet goes missing? Open their page and tap Lost."), style = MaterialTheme.typography.bodyMedium)
        }
        if (lost.isEmpty()) SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) { Text(tr("No lost pets reported near you. Good."), style = MaterialTheme.typography.bodyMedium) }
        lost.forEach { l ->
            val species = Species.entries.firstOrNull { it.name == l.species } ?: Species.OTHER
            val label = tr("Lost pet: {0}", l.name)
            SoftCard(Modifier.fillMaxWidth(), tone = if (l.mine) Tone.Accent else Tone.Surface, onClick = { onOpen(l) }, onClickLabel = label) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val img = remember(l.id) { l.look?.let { PetArt(it, species, Ears.of(l.ears)).still } }
                    SpriteView(img, Modifier.size(56.dp), animate = false, description = tr("Pixel {0}", l.name))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text((if (l.mine) tr("Yours") + " · " else "") + l.name, style = MaterialTheme.typography.titleMedium)
                        Text(tr("Last seen {0} · {1} km away", agoText(l.lastSeenAtMs, now), kmText(l.distanceKm)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        l.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
                    PixelIcon(PixelIcons.CHEVRON_RIGHT, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
                }
            }
        }
    }
}

private val NAGA_CENTER = 13.6218 to 123.1948
