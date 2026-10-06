package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
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
    var treatFor by remember { mutableStateOf<PalPet?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    fun act(block: suspend () -> Unit) {
        busy = true; message = null
        app.launch {
            try { block() } catch (e: MapException) { message = e.message } catch (e: Exception) { message = e.message ?: tr("Something went wrong. Please try again.") }
            finally { busy = false }
        }
    }
    if (map != null) LaunchedEffect(refresh) {
        act {
            pals = map.refreshPals(state.pets) { app.repo.art(it) }
            code = map.client.pals.myCode()
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
                Text(tr("Up to {0} friends, by code only. Pals see your pixel pets and their names, never your photos, your place or your care. Their pets drop by your room; send theirs a treat.", PalClient.MAX_PALS), style = MaterialTheme.typography.bodyMedium)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    act { map.client.pals.add(typed); entry = ""; refresh++; message = tr("You're pals now.") }
                }
            }
            message?.let { Text(it, color = if (it.startsWith(tr("You're pals"))) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            GroupLabel(if (pals.isEmpty()) tr("Your pals") else tr("Your pals ({0})", pals.size))
            if (pals.isEmpty()) SoftCard(Modifier.fillMaxWidth(), tone = Tone.Tonal) {
                Text(tr("No pals yet. Share your code with one friend: their pixel pet will be on your rug tomorrow."), style = MaterialTheme.typography.bodyMedium)
            }
            pals.forEach { pal -> PalCard(app, map, pal, onTreat = { treatFor = it }, onRemove = { act { map.client.pals.remove(pal.id); refresh++ } }) }
        }
    }
    treatFor?.let { pet ->
        TreatDialog(app, state, pet, onClose = { treatFor = null }) { kind, fromName ->
            treatFor = null
            act { map!!.client.pals.sendTreat(pet.palId, pet.petId, fromName, kind); message = tr("Sent to {0}!", pet.name) }
        }
    }
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
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
