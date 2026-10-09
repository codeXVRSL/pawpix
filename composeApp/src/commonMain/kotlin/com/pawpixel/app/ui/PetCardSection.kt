package com.pawpixel.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawpixel.core.Pet
import com.pawpixel.core.QrCode
import com.pawpixel.i18n.tr
import com.pawpixel.map.CardMessage
import com.pawpixel.map.MapException
import com.pawpixel.map.PetCardClient
import com.pawpixel.map.PetCardDraft
import com.pawpixel.sprite.PixelIcons
import kotlin.math.floor

/** A QR code drawn as crisp pixels, quiet zone included, on white whatever the theme. */
@Composable
fun QrView(text: String, modifier: Modifier = Modifier, size: Dp = 160.dp, description: String = tr("QR code")) {
    val code = remember(text) { QrCode.encode(text) }
    Box(modifier.size(size).background(Color.White).semantics { contentDescription = description }) {
        if (code != null) Canvas(Modifier.size(size)) {
            val n = code.size + 8 // four modules of quiet zone each side
            val cell = floor(this.size.width / n)
            val off = (this.size.width - cell * n) / 2 + cell * 4
            for (r in 0 until code.size) for (c in 0 until code.size) {
                if (code.isDark(r, c)) drawRect(PawColors.Ink, Offset(off + c * cell, off + r * cell), Size(cell, cell))
            }
        }
    }
}

/**
 * The Pet ID card: a page per pet that a collar tag's QR code opens (pixel twin, name, a note for
 * the finder, a message box that reaches the owner in PawPixel). The owner prints the QR; the
 * phone number stays private.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PetCardSection(app: AppScope, pet: Pet) {
    val map = lostModel(app)
    var cardId by remember { mutableStateOf(map?.cardFor(pet.id)) }
    var note by remember { mutableStateOf("") }
    var chip by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<CardMessage>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val now = app.repo.now()

    SectionTitle(tr("Pet ID card"))
    if (map == null) {
        Hint(tr("A tag whose QR code opens {0}'s page, where a finder can message you without seeing your number. Needs PawPixel's server, which this version isn't connected to yet.", pet.name))
        return
    }
    fun save() {
        busy = true; error = null
        app.launch {
            try {
                if (!map.signIn()) return@launch
                val art = app.repo.art(pet)
                val id = map.client.cards.upsert(PetCardDraft(pet.id, pet.name, pet.species.name, art?.ears?.name, art?.look?.encode() ?: "", note, chip))
                map.recordCard(pet.id, id)
                cardId = id; editing = false; refresh++
            } catch (e: MapException) { error = e.message } catch (e: Exception) { error = e.message ?: tr("Something went wrong. Please try again.") }
            finally { busy = false }
        }
    }
    val id = cardId
    if (id != null) {
        LaunchedEffect(id, refresh) {
            runCatching {
                if (map.signIn()) {
                    val mine = map.client.cards.mine().firstOrNull { it.id == id }
                    if (mine != null && !editing) { note = mine.note ?: ""; chip = mine.microchip ?: "" }
                    messages = map.client.cards.messages(id)
                }
            }
        }
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QrView(PetCardClient.url(id), size = 128.dp, description = tr("QR code for {0}'s ID card", pet.name))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val pose = remember(pet.lookKey) { app.repo.art(pet)?.still }
                        SpriteView(pose, Modifier.size(56.dp), animate = false, description = tr("Pixel {0}", pet.name))
                        Text(pet.name, fontWeight = FontWeight.Bold)
                        Text(tr("Scan to reach my owner"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (note.isNotBlank() && !editing) Text(note, style = MaterialTheme.typography.bodyMedium)
                if (chip.isNotBlank() && !editing) Text(tr("Microchip {0}", chip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (editing) {
                    OutlinedTextField(note, { note = it.take(200) }, label = { Text(tr("Note for the finder (friendly? on medication? your vet?)")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3)
                    OutlinedTextField(chip, { chip = it.take(40) }, label = { Text(tr("Microchip number (optional)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editing) PrimaryPill(if (busy) tr("Saving…") else tr("Save card"), enabled = !busy) { save() }
                    else TonalPill(tr("Edit note"), icon = PixelIcons.PENCIL) { editing = true }
                    TonalPill(tr("Print the tag"), icon = PixelIcons.SHARE) { app.repo.platform.openUrl(PetCardClient.url(id) + "&print") }
                    GhostPill(tr("Share link")) { app.repo.platform.shareText(tr("{0}'s PawPixel ID card: {1}", pet.name, PetCardClient.url(id))) }
                    LinkButton(tr("Remove card"), color = MaterialTheme.colorScheme.error) {
                        app.launch {
                            try {
                                if (map.signIn()) { map.client.cards.remove(pet.id); map.recordCard(pet.id, null); cardId = null; messages = null }
                            } catch (e: Exception) { error = e.message ?: tr("Something went wrong. Please try again.") }
                        }
                    }
                }
            }
        }
        val list = messages
        if (list != null) {
            GroupLabel(if (list.isEmpty()) tr("Messages from finders") else tr("Messages from finders ({0})", list.size))
            if (list.isEmpty()) Hint(tr("None yet. Whoever scans the tag can write to you here; they see {0}'s page, never your number.", pet.name))
            list.forEach { m ->
                SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(agoText(m.createdAtMs, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                        m.contact?.let { Text(tr("Reach them: {0}", it), fontWeight = FontWeight.Bold) }
                    }
                }
            }
            GhostPill(tr("Check again")) { refresh++ }
        }
    } else {
        Hint(tr("A tag for the collar: whoever scans its QR code sees {0}'s pixel twin, your note, and a box to message you through PawPixel. Your number stays private.", pet.name))
        if (editing) {
            OutlinedTextField(note, { note = it.take(200) }, label = { Text(tr("Note for the finder (friendly? on medication? your vet?)")) }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3)
            OutlinedTextField(chip, { chip = it.take(40) }, label = { Text(tr("Microchip number (optional)")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editing) PrimaryPill(if (busy) tr("Making…") else tr("Make the card"), enabled = !busy, icon = PixelIcons.CHECK) { save() }
            else TonalPill(tr("Make an ID card"), icon = PixelIcons.SHIELD) { editing = true }
        }
    }
    Box(Modifier.padding(top = 4.dp))
}
