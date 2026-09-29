package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawpixel.core.AppState
import com.pawpixel.core.Mood
import com.pawpixel.core.Pet
import com.pawpixel.i18n.tr
import com.pawpixel.map.Household
import com.pawpixel.map.MapException

/**
 * Sharing pets with your household: care for the same pets together, and everyone's Done taps show
 * on every phone ("Fed by Jamaica · 7:02 AM"), so nobody feeds twice. Start a household and send
 * an invite code, or join with one.
 *
 * @param sharePetId came from a pet's "Share with your household": that pet is shared once you're in one.
 * @param join came to enter a code: joining comes first.
 */
@Composable
fun FamilyScreen(app: AppScope, state: AppState, sharePetId: String?, join: Boolean) {
    val family = app.repo.family
    val status by family.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(family.isSignedIn) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var autoShared by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Household.Member?>(null) }
    val sharePet = sharePetId?.let { state.pet(it) }

    fun act(block: suspend () -> Unit) {
        busy = true; message = null
        app.launch {
            try {
                block()
            } catch (e: MapException) {
                message = e.message?.let { tr(it) }
                if (e.kind == MapException.Kind.SIGNED_OUT) signedIn = false
            } catch (e: Exception) {
                message = e.message?.let { tr(it) } ?: tr("Something went wrong. Please try again.")
                app.repo.platform.log("Household error: ${e.stackTraceToString()}")
            } finally {
                busy = false
                signedIn = family.isSignedIn
            }
        }
    }

    LaunchedEffect(Unit) { if (family.isSetUp && family.isSignedIn) act { family.refresh(); family.sync() } }
    // Came from "Share with your household" while already in one: share the pet right away.
    LaunchedEffect(status.household?.id) {
        val pet = sharePet ?: return@LaunchedEffect
        if (status.household != null && !pet.shared && !autoShared) { autoShared = true; act { family.share(pet, true) } }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = app.back) { Text(tr("‹ Back")) }
            Text(tr("Your household"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        status.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val h = status.household
        when {
            !family.isSetUp -> PixelCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Coming soon"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        tr(
                            "Sharing with your household isn't available yet: this build isn't connected to PawPixel's server. " +
                                "Everything else works on this phone as usual.",
                        ),
                    )
                }
            }
            !signedIn -> SignInCard(app, sharePet, busy) {
                act { if (family.signIn()) { signedIn = true; family.refresh(); family.sync() } }
            }
            h == null -> StartOrJoin(
                app, sharePet, busy, joinFirst = join,
                onStart = { you ->
                    autoShared = true
                    act {
                        family.create(you)
                        sharePet?.let { family.share(it, true) }
                        inviteCode = family.invite()
                    }
                },
                onJoin = { code, you -> act { family.join(code, you) } },
            )
            else -> {
                val iAmOwner = h.ownerId != null && h.ownerId == family.myUserId
                inviteCode?.let { InviteCard(app, it, state.pets.filter { p -> p.shared }) }
                MembersCard(app, h, status.syncing, status.lastSyncMs, status.error, busy, iAmOwner,
                    onRemove = { removing = it }, onSync = { act { family.refresh(); family.sync() } })

                Text(tr("Pets you care for together"), fontWeight = FontWeight.Bold)
                if (state.pets.isEmpty()) Text(tr("Pets your household shares appear here after the next sync."))
                state.pets.forEach { pet ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pose = remember(pet.id, pet.spriteVersion, pet.species) { app.repo.pose(pet, Mood.CONTENT) }
                        SpriteView(pose, Modifier.size(48.dp), animate = false)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(pet.name, fontWeight = FontWeight.Bold)
                            Text(
                                if (pet.shared) tr("Shared: care, health and who did it") else tr("Only on this phone"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            pet.shared, enabled = !busy, onCheckedChange = { on -> act { family.share(pet, on) } },
                            modifier = Modifier.semantics { contentDescription = tr("Share {0}", pet.name) },
                        )
                    }
                }

                if (inviteCode == null) {
                    Text(tr("Invite someone"), fontWeight = FontWeight.Bold)
                    Text(tr("Send a code to the people you care for your pets with (up to 8 in a household)."), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !busy, onClick = { act { inviteCode = family.invite() } }) { Text(tr("New invite code")) }
                }
                Column {
                    if (iAmOwner) {
                        TextButton(enabled = !busy, onClick = { act { family.revokeInvites(); inviteCode = null; message = tr("All invite codes are cancelled.") } }) {
                            Text(tr("Cancel all invite codes"))
                        }
                    }
                    TextButton(enabled = !busy, onClick = { confirmLeave = true }) { Text(tr("Leave {0}", h.name), color = MaterialTheme.colorScheme.error) }
                    if (iAmOwner) {
                        TextButton(enabled = !busy, onClick = { confirmStop = true }) { Text(tr("Stop sharing for everyone"), color = MaterialTheme.colorScheme.error) }
                    }
                }
                Text(
                    tr(
                        "What's shared: pet names, their pixel looks, care tasks, health dates and who tapped Done. Never photos " +
                            "(card photos stay on your phone) and never your location. Reminder settings stay your own.",
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    }

    removing?.let { m ->
        ConfirmDialog(
            title = tr("Remove {0}?", m.name),
            text = tr("{0} stops seeing your household's pets and taps. Their phone keeps its own copy, no longer shared.", m.name),
            confirm = tr("Remove"), onDismiss = { removing = null },
        ) { act { family.removeMember(m.userId) } }
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = tr("Leave the household?"),
            text = tr("Shared pets stay on this phone with their history, no longer shared. The others keep theirs."),
            confirm = tr("Leave"), onDismiss = { confirmLeave = false },
        ) { act { family.leave(); inviteCode = null } }
    }
    if (confirmStop) {
        ConfirmDialog(
            title = tr("Stop sharing for everyone?"),
            text = tr("The household ends for everyone. Each phone keeps its own copy of the pets and their history, no longer shared."),
            confirm = tr("Stop sharing"), onDismiss = { confirmStop = false },
        ) { act { family.stopSharing(); inviteCode = null } }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Why a household helps, with the pet (if you came from its page) looking on. */
@Composable
private fun Pitch(app: AppScope, pet: Pet?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (pet != null) {
            val pose = remember(pet.id, pet.spriteVersion) { app.repo.pose(pet, Mood.HAPPY) }
            SpriteView(pose, Modifier.size(72.dp), animate = false)
        }
        Column(Modifier.weight(1f).padding(start = if (pet != null) 10.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                pet?.let { tr("Care for {0} together", it.name) } ?: tr("Care for your pets together"),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            )
            Text(tr("When someone at home feeds or walks your pet, it shows on everyone's phone and widget, with who did it. No more double breakfasts."))
        }
    }
}

@Composable
private fun SignInCard(app: AppScope, pet: Pet?, busy: Boolean, onSignIn: () -> Unit) {
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pitch(app, pet)
            Text(
                tr(
                    "Sharing needs an account, so PawPixel's server can pass your Done taps between phones. " +
                        "Everything else keeps working on this phone without one.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(enabled = !busy, onClick = onSignIn) { Text(tr(app.repo.map.signInLabel)) }
        }
    }
}

@Composable
private fun StartOrJoin(
    app: AppScope, pet: Pet?, busy: Boolean, joinFirst: Boolean,
    onStart: (String) -> Unit, onJoin: (String, String) -> Unit,
) {
    var you by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val named = you.isNotBlank()
    val start = @Composable {
        PixelCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Start a household"), fontWeight = FontWeight.Bold)
                Text(tr("You'll get a code to send to the people you care for your pets with."), style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && named, onClick = { onStart(you.trim()) }) { Text(tr("Create household")) }
            }
        }
    }
    val join = @Composable {
        PixelCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Join a household"), fontWeight = FontWeight.Bold)
                Text(tr("Got a code from someone at home? Enter it here, and their pets appear on this phone."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    code, { code = it.uppercase().take(12) }, label = { Text(tr("Invite code")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Button(enabled = !busy && named && code.count { it.isLetterOrDigit() } >= 8, onClick = { onJoin(code, you.trim()) }) { Text(tr("Join")) }
            }
        }
    }
    PixelCard(Modifier.fillMaxWidth()) { Pitch(app, pet) }
    OutlinedTextField(
        you, { you = it.take(24) }, label = { Text(tr("Your name")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(tr("What your household sees, e.g. \"Fed by {0}\"", you.trim().ifEmpty { "Jamaica" })) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
    )
    if (joinFirst) { join(); start() } else { start(); join() }
}

/** A fresh invite code: big and easy to read out, copy or send. */
@Composable
private fun InviteCard(app: AppScope, code: String, sharedPets: List<Pet>) {
    val pretty = code.chunked(4).joinToString("-")
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Your invite code"), fontWeight = FontWeight.Bold)
            Text(
                pretty, fontFamily = FontFamily.Monospace, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                modifier = Modifier.semantics { contentDescription = tr("Invite code {0}", code.toList().joinToString(" ")) },
            )
            Text(tr("Works for 7 days, for up to 8 people in a household."), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(pretty)); copied = true }) { Text(if (copied) tr("Copied ✓") else tr("Copy")) }
                Button(onClick = { app.repo.platform.shareText(inviteText(sharedPets, pretty)) }) { Text(tr("Share")) }
            }
        }
    }
}

/** "Join Chelsea's care on PawPixel: code ABCD-2345" */
fun inviteText(sharedPets: List<Pet>, code: String): String {
    val names = sharedPets.map { it.name }
    return when (names.size) {
        0 -> tr("Join my household on PawPixel: code {0}", code)
        1 -> tr("Join {0}'s care on PawPixel: code {1}", names[0], code)
        else -> tr("Join {0}'s care on PawPixel: code {1}", tr("{0} and {1}", names.dropLast(1).joinToString(", "), names.last()), code)
    }
}

@Composable
private fun MembersCard(
    app: AppScope, h: Household, syncing: Boolean, lastSyncMs: Long, error: String?, busy: Boolean, iAmOwner: Boolean,
    onRemove: (Household.Member) -> Unit, onSync: () -> Unit,
) {
    val me = app.repo.family.myUserId
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            h.members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name + if (m.userId == me) " " + tr("(you)") else "", fontWeight = FontWeight.Medium)
                        if (m.userId == h.ownerId) Text(tr("Started the household"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (iAmOwner && m.userId != me) TextButton(enabled = !busy, onClick = { onRemove(m) }) { Text(tr("Remove")) }
                }
            }
            if (h.members.size == 1) Text(tr("Just you so far. Send an invite code to someone at home."), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    syncLine(syncing, lastSyncMs, error, app), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(enabled = !busy && !syncing, onClick = onSync) { Text(tr("Sync now")) }
            }
        }
    }
}

private fun syncLine(syncing: Boolean, lastMs: Long, error: String?, app: AppScope): String = when {
    syncing -> tr("Syncing…")
    error != null -> tr("Not synced: {0} Your changes wait on this phone.", tr(error))
    lastMs == 0L -> tr("Not synced yet")
    app.now - lastMs < 90_000 -> tr("Synced just now")
    else -> tr("Synced {0}", relativeDay(lastMs, app.now, app.repo.clock))
}
