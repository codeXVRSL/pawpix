package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawpixel.app.PetMapModel
import com.pawpixel.core.AppState
import com.pawpixel.core.Species
import com.pawpixel.i18n.tr
import com.pawpixel.map.MapException
import com.pawpixel.map.Moment
import com.pawpixel.map.Pal
import com.pawpixel.map.PalClient
import com.pawpixel.map.PalPet
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PixelIcons

/**
 * Pals: a circle of up to 20 friends, not a feed. Hand a friend your code; their pixel pets visit
 * your pet's room and you can send theirs a treat. Nobody outside the circle sees anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PalsScreen(app: AppScope, state: AppState) {
    val map = lostModel(app)
    var code by remember { mutableStateOf<String?>(null) }
    var pals by remember { mutableStateOf(map?.pals ?: emptyList()) }
    var entry by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var good by remember { mutableStateOf(false) } // the message is good news (green), not an error
    var treatFor by remember { mutableStateOf<PalPet?>(null) }
    var moments by remember { mutableStateOf<List<Moment>>(emptyList()) }
    var sharing by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    fun act(quiet: Boolean = false, block: suspend () -> Unit) {
        busy = true
        if (!quiet) { message = null; good = false } // a background refresh keeps the message the last action left
        app.launch {
            try { block() } catch (e: MapException) { message = e.message } catch (e: Exception) { message = e.message ?: tr("Something went wrong. Please try again.") }
            finally { busy = false }
        }
    }
    if (map != null) LaunchedEffect(refresh) {
        act(quiet = true) {
            pals = map.refreshPals(state.pets) { app.repo.art(it) }
            code = map.client.pals.myCode()
            moments = map.client.pals.moments()
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(app, if (map?.isDemo == true) tr("Pals (demo)") else tr("Pals"), Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (map == null) {
                SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tr("Pals are coming soon"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("A small circle of friends whose pixel pets visit your pet's room. This version of the app isn't connected to PawPixel's server yet."))
                    }
                }
                return@Column
            }
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Calm) {
                Text(tr("Up to {0} friends, by code only. Pals see your pixel pets and their names, and only the moments you choose to share: never your place or your care. Their pets drop by your room; send theirs a treat.", PalClient.MAX_PALS), style = MaterialTheme.typography.bodyMedium)
            }
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Your pal code"), style = MaterialTheme.typography.titleMedium)
                    val c = code
                    Text(
                        c ?: "······", fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                        modifier = Modifier.semantics { contentDescription = if (c == null) tr("Loading…") else tr("Pal code {0}", c.toList().joinToString(" ")) },
                    )
                    Text(tr("Give it to a friend with PawPixel. It never expires; unpal anyone any time."), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        LinkButton(tr("New code"), enabled = c != null && !busy) { act { code = map.client.pals.newCode(); message = tr("Your old code no longer works."); good = true } }
                        GhostPill(tr("Copy"), enabled = c != null) { c?.let { clipboard.setText(AnnotatedString(it)) } }
                        PrimaryPill(tr("Share"), enabled = c != null, icon = PixelIcons.SHARE) {
                            c?.let { app.repo.platform.shareText(tr("Be my pal on PawPixel: open More → Pals and enter my code {0}. Your pixel pet will visit mine!", it)) }
                        }
                    }
                }
            }
            GroupLabel(tr("Add a pal"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(entry, { entry = it.uppercase().take(6) }, label = { Text(tr("Their code")) }, singleLine = true, modifier = Modifier.weight(1f))
                PrimaryPill(tr("Add"), enabled = entry.length == 6 && !busy) {
                    val typed = entry
                    act { map.client.pals.add(typed); entry = ""; refresh++; message = tr("You're pals now."); good = true }
                }
            }
            message?.let { Text(it, color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (pals.isNotEmpty() || moments.isNotEmpty()) {
                GroupLabel(tr("Moments"))
                MomentsRow(app, map, moments, pals, onShare = { sharing = true }, onClear = { act { map.client.pals.clearMoment(); moments = map.client.pals.moments() } })
            }

            GroupLabel(if (pals.isEmpty()) tr("Your pals") else tr("Your pals ({0})", pals.size))
            if (pals.isEmpty()) SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) {
                Text(tr("No pals yet. Share your code with one friend: their pixel pet will be on your rug tomorrow."), style = MaterialTheme.typography.bodyMedium)
            }
            pals.forEach { pal -> PalCard(app, map, pal, onTreat = { treatFor = it }, onRemove = { act { map.client.pals.remove(pal.id); refresh++ } }) }
        }
    }
    if (sharing && map != null) ShareMomentDialog(app, state, onClose = { sharing = false }) { petName, caption, photo ->
        sharing = false
        act { map.client.pals.setMoment(petName, caption, photo); moments = map.client.pals.moments(); message = tr("Shared with your pals for two days."); good = true }
    }
    treatFor?.let { pet ->
        TreatDialog(app, state, pet, onClose = { treatFor = null }) { kind, fromName ->
            treatFor = null
            act { map!!.client.pals.sendTreat(pet.palId, pet.petId, fromName, kind); message = tr("Sent to {0}!", pet.name); good = true }
        }
    }
}

/**
 * Moments: one photo a day each, for pals only, gone after two days. Yours first (with "Take it
 * down"), then your pals' newest first. A moment that came without a photo shows the pixel pet.
 */
@Composable
private fun MomentsRow(app: AppScope, map: PetMapModel, moments: List<Moment>, pals: List<Pal>, onShare: () -> Unit, onClear: () -> Unit) {
    val myId = map.client.userId
    val mine = moments.firstOrNull { it.isMine(myId) }
    val theirs = moments.filter { !it.isMine(myId) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (theirs.isEmpty()) tr("A photo of the day for your pals and nobody else. It's gone after two days; no likes, no comments.")
            else tr("From your pals in the last two days. Yours is gone after two days; no likes, no comments."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "mine") {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (mine == null) 190.dp else 132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mine != null) {
                        MomentPhoto(mine, pals, tr("Your moment: {0}", mine.caption.ifBlank { mine.petName }))
                        Text(mine.caption.ifBlank { mine.petName }, style = MaterialTheme.typography.bodySmall, maxLines = 2, textAlign = TextAlign.Center)
                        LinkButton(tr("Take it down"), color = MaterialTheme.colorScheme.error, onClick = onClear)
                        LinkButton(tr("Share another"), onClick = onShare)
                    } else {
                        Box(
                            Modifier.size(132.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium),
                            contentAlignment = Alignment.Center,
                        ) { PixelIcon(PixelIcons.CAMERA, size = 32.dp) }
                        PrimaryPill(tr("Share a moment"), icon = PixelIcons.CAMERA, onClick = onShare)
                    }
                }
            }
            items(theirs, key = { it.palId }) { m ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MomentPhoto(m, pals, tr("{0}'s moment: {1}", m.petName, m.caption))
                    Text(m.petName, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(m.caption.ifBlank { agoText(m.atMs, app.now) }, style = MaterialTheme.typography.bodySmall, maxLines = 2, textAlign = TextAlign.Center)
                    Text(agoText(m.atMs, app.now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The moment's photo, or the pal's pixel pet when it came without one. */
@Composable
private fun MomentPhoto(m: Moment, pals: List<Pal>, description: String) {
    if (m.photo.isNotEmpty()) AlertPhoto(m.photo, description, Modifier.size(132.dp))
    else {
        val pet = pals.firstOrNull { it.id == m.palId }?.pets?.firstOrNull { it.name == m.petName } ?: pals.firstOrNull { it.id == m.palId }?.pets?.firstOrNull()
        val species = Species.entries.firstOrNull { it.name == pet?.species } ?: Species.OTHER
        val img = remember(m.palId, pet?.petId) { pet?.look?.let { PetArt(it, species, Ears.of(pet.ears)).still } }
        SpriteView(img, Modifier.size(132.dp), animate = false, description = description)
    }
}

/** Pick a photo (the album or the phone), a pet, a caption; it's shrunk on the phone before it goes anywhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareMomentDialog(app: AppScope, state: AppState, onClose: () -> Unit, onShare: (String, String, ByteArray) -> Unit) {
    val platform = app.repo.platform
    val pets = state.pets.filter { !it.remembered }
    var petName by remember { mutableStateOf(pets.firstOrNull()?.name ?: tr("A pet")) }
    var caption by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var shrinking by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    fun shrink(bytes: ByteArray) = app.launch {
        shrinking = true; trouble = null
        try { photo = shrinkPhoto(platform, bytes, PalClient.MAX_MOMENT_BYTES, 480); if (photo == null) trouble = tr("That photo couldn't be read. Try another.") }
        catch (e: Exception) { trouble = tr("That photo couldn't be read. Try another.") }
        finally { shrinking = false }
    }
    val pick = com.pawpixel.app.rememberPhotoPicker { bytes -> if (bytes != null) shrink(bytes) }
    val album = pets.firstOrNull { it.name == petName }?.let { state.albumFor(it.id) }?.take(6) ?: emptyList()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Share a moment")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pets.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pets.forEach { p -> ChoiceChip(petName == p.name, { petName = p.name; photo = null }, p.name) }
                }
                val p = photo
                if (p != null) AlertPhoto(p, tr("The photo to share"), Modifier.size(120.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostPill(if (p == null) tr("Pick a photo") else tr("Another photo"), icon = PixelIcons.CAMERA, enabled = !shrinking) { pick() }
                }
                if (album.isNotEmpty() && p == null) {
                    Text(tr("Or from {0}'s album", petName), style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(album, key = { it.id }) { a ->
                            // A small decoded thumbnail; the original is read only when it's chosen (off the main thread).
                            PhotoThumb(app, HealthPhoto(a.id, 0) { app.repo.albumPhoto(a) }, a.caption.ifBlank { tr("Album photo") }, size = 64.dp) {
                                app.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { app.repo.albumPhoto(a) }?.let { shrink(it) } }
                            }
                        }
                    }
                }
                trouble?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(caption, { caption = it.take(80) }, label = { Text(tr("Caption (optional)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(tr("Only your pals see it, for two days. The photo is shrunk on your phone first; its location data is dropped."), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = photo != null && !shrinking, onClick = { photo?.let { onShare(petName, caption.trim(), it) } }) { Text(tr("Share")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
}

@Composable
private fun PalCard(app: AppScope, map: PetMapModel, pal: Pal, onTreat: (PalPet) -> Unit, onRemove: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pal.pets.isEmpty()) tr("A pal (no pets shared yet)") else tr("{0}'s owner", pal.pets.joinToString(" & ") { it.name }),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                LinkButton(tr("Unpal"), color = MaterialTheme.colorScheme.error, onClick = onRemove)
            }
            if (pal.pets.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pal.pets, key = { it.petId }) { pet ->
                    val species = Species.entries.firstOrNull { it.name == pet.species } ?: Species.OTHER
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp)) {
                        val img = remember(pet.petId) { pet.look?.let { PetArt(it, species, Ears.of(pet.ears)).still } }
                        SpriteView(img, Modifier.size(72.dp), animate = false, description = tr("Pixel {0}", pet.name))
                        Text(pet.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        GhostPill(tr("Treat"), icon = PixelIcons.BOWL) { onTreat(pet) }
                    }
                }
            }
        }
    }
}

/** Which of your pets sends what: a treat, a pat or a ball. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TreatDialog(app: AppScope, state: AppState, to: PalPet, onClose: () -> Unit, onSend: (String, String) -> Unit) {
    var kind by remember { mutableStateOf("treat") }
    var from by remember { mutableStateOf(state.pets.firstOrNull { !it.remembered }?.name ?: tr("A pal")) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Send {0} something", to.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(kind == "treat", { kind = "treat" }, tr("A treat"))
                    ChoiceChip(kind == "pat", { kind = "pat" }, tr("A pat"))
                    ChoiceChip(kind == "ball", { kind = "ball" }, tr("A ball"))
                }
                if (state.pets.size > 1) {
                    GroupLabel(tr("From"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.pets.filter { !it.remembered }.forEach { p -> ChoiceChip(from == p.name, { from = p.name }, p.name) }
                    }
                }
                Text(tr("It shows up in {0}'s room as a speech bubble.", to.name), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSend(kind, from) }) { Text(tr("Send")) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text(tr("Cancel")) } },
    )
}

/** "Biscuit sent Chelsea a ball!" */
fun treatText(fromPet: String, toPet: String, kind: String): String = when (kind) {
    "pat" -> tr("{0} sent {1} a pat!", fromPet, toPet)
    "ball" -> tr("{0} sent {1} a ball!", fromPet, toPet)
    else -> tr("{0} sent {1} a treat!", fromPet, toPet)
}
