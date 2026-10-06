package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.core.LocalClock
import com.pawpixel.i18n.tr
import com.pawpixel.map.Gathering
import com.pawpixel.map.IsoTime
import com.pawpixel.map.MyWalk
import com.pawpixel.map.WalkDraft
import com.pawpixel.sprite.PixelIcons

/** Where the Play Store listing will be; the invite texts point here. */
const val MAP_INVITE_URL = "https://pawpixel.app/map" // TODO: the store link once the listing is live

/** What a host has typed so far; the meeting spot comes from a tap on the map. */
data class WalkForm(
    val title: String = "",
    val day: Long? = null,
    val minute: Int = 7 * 60,
    val areaLabel: String = "",
    val venueName: String = "",
    val spot: Pair<Double, Double>? = null,
    val capacity: Int = 20,
    val details: String = "",
) {
    fun draft(clock: LocalClock): WalkDraft? {
        val d = day ?: return null
        val (lat, lng) = spot ?: return null
        if (venueName.isBlank()) return null
        return WalkDraft(title, clock.at(d, minute), areaLabel, venueName, lat, lng, capacity, details)
    }
}

private val TIMES = listOf(6 * 60, 7 * 60, 8 * 60, 16 * 60, 17 * 60, 18 * 60)
private val SIZES = listOf(10, 20, 30, 50)

/**
 * "Host a walk": title, a day in the next ten days, a time, the meeting place (named, and tapped
 * on the map), how many can come, and a note. It goes to the moderator, then to everyone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostWalkDialog(
    app: AppScope, form: WalkForm, busy: Boolean, error: String?,
    onChange: (WalkForm) -> Unit, onPickSpot: () -> Unit, onSubmit: () -> Unit, onClose: () -> Unit,
) {
    val clock = app.repo.clock
    val today = clock.dayIndex(app.now)
    val days = (1..10).map { today + it }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(tr("Host a walk")) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("A walk at a public place: a plaza, a park, a pet-friendly café. We check it before it goes on the map."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(form.title, { onChange(form.copy(title = it.take(80))) }, label = { Text(tr("Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                GroupLabel(tr("Meeting place"))
                OutlinedTextField(form.venueName, { onChange(form.copy(venueName = it.take(80))) }, label = { Text(tr("Public place, e.g. Plaza Rizal fountain")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                OutlinedTextField(form.areaLabel, { onChange(form.copy(areaLabel = it.take(60))) }, label = { Text(tr("Area, as shown before RSVP (e.g. Plaza Rizal area)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostPill(if (form.spot == null) tr("Tap the spot on the map") else tr("Move the spot"), icon = PixelIcons.PIN, onClick = onPickSpot)
                    if (form.spot != null) Text(tr("Spot set"), color = Paw.palette.good, fontWeight = FontWeight.Bold)
                }
                GroupLabel(tr("Day"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    days.forEach { d -> ChoiceChip(form.day == d, { onChange(form.copy(day = d)) }, formatDay(d)) }
                }
                GroupLabel(tr("Time"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TIMES.forEach { m -> ChoiceChip(form.minute == m, { onChange(form.copy(minute = m)) }, formatMinute(m)) }
                }
                GroupLabel(tr("How many can come"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SIZES.forEach { n -> ChoiceChip(form.capacity == n, { onChange(form.copy(capacity = n)) }, n.toString()) }
                }
                OutlinedTextField(form.details, { onChange(form.copy(details = it.take(300))) }, label = { Text(tr("A note (optional)")) }, maxLines = 3, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val ready = form.draft(clock) != null && form.title.isNotBlank()
            TextButton(onClick = onSubmit, enabled = ready && !busy) { Text(if (busy) tr("Sending…") else tr("Send for approval")) }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text(tr("Cancel")) } },
    )
}

/** The host's own walks: waiting for the moderator, or already on the map. */
@Composable
fun MyWalksSection(app: AppScope, walks: List<MyWalk>, onCancel: (MyWalk) -> Unit) {
    if (walks.isEmpty()) return
    var confirm by remember { mutableStateOf<MyWalk?>(null) }
    GroupLabel(tr("Your walks"))
    walks.forEach { w ->
        SoftCard(Modifier.fillMaxWidth(), tone = if (w.approved) Tone.Good else Tone.Tonal) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(w.title, style = MaterialTheme.typography.titleMedium)
                Text(IsoTime.parseMs(w.startsAt)?.let { formatDateTime(it, app.repo.clock) } ?: w.startsAt)
                Text(tr("Meet at: {0}", w.venueName), style = MaterialTheme.typography.bodySmall)
                Text(
                    if (w.approved) tr("On the map · {0} going", w.going) else tr("Waiting for approval. We check that the place is public, then it shows to everyone."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkButton(tr("Cancel this walk"), color = MaterialTheme.colorScheme.error) { confirm = w }
            }
        }
    }
    confirm?.let { w ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(tr("Cancel {0}?", w.title)) },
            text = { Text(if (w.approved) tr("It's removed from the map, and everyone who said they're going loses the RSVP.") else tr("The proposal is withdrawn.")) },
            confirmButton = { TextButton(onClick = { confirm = null; onCancel(w) }) { Text(tr("Cancel the walk"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Keep it")) } },
        )
    }
}

/** The text that goes out when someone shares a walk: enough to come, nothing that isn't public. */
fun walkInviteText(g: Gathering, clock: LocalClock): String {
    val whenText = IsoTime.parseMs(g.startsAt)?.let { formatDateTime(it, clock) } ?: g.startsAt
    return tr("Pet walk: {0} · {1} · {2}. Say you're going on PawPixel's pet map and you'll see the meeting place: {3}", g.title, whenText, g.areaLabel, MAP_INVITE_URL)
}

/** "Join me on the map": shared from the map's menu and from the empty map. */
fun mapInviteText(petNames: List<String>): String = when (petNames.size) {
    0 -> tr("I'm on PawPixel's pet map. Put your pet on it too, and let's meet at a walk: {0}", MAP_INVITE_URL)
    1 -> tr("{0} is on PawPixel's pet map. Put your pet on it too, and let's meet at a walk: {1}", petNames[0], MAP_INVITE_URL)
    else -> tr("{0} are on PawPixel's pet map. Put your pet on it too, and let's meet at a walk: {1}", tr("{0} and {1}", petNames.dropLast(1).joinToString(", "), petNames.last()), MAP_INVITE_URL)
}
