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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawpixel.core.AppState
import com.pawpixel.i18n.tr
import com.pawpixel.map.MapException

/**
 * Family sharing: care for the same pets together. Everyone's Done taps show on every phone, so
 * nobody feeds twice. Start a family and send an invite code, or join with one.
 */
@Composable
fun FamilyScreen(app: AppScope, state: AppState) {
    val family = app.repo.family
    val status by family.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(family.isSignedIn) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<com.pawpixel.map.Household.Member?>(null) }

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
                app.repo.platform.log("Family error: ${e.stackTraceToString()}")
            } finally {
                busy = false
                signedIn = family.isSignedIn
            }
        }
    }

    LaunchedEffect(Unit) { if (family.isSetUp && family.isSignedIn) act { family.refresh(); family.sync() } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = app.back) { Text(tr("‹ Back")) }
            Text(tr("Family sharing"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val h = status.household
        when {
            !family.isSetUp -> Text(
                tr(
                    "Family sharing is coming soon: this build isn't connected to PawPixel's server yet. " +
                        "Everything else works on this phone as usual.",
                ),
            )
            !signedIn -> SignInCard(app, busy) {
                act { if (family.signIn()) { signedIn = true; family.refresh(); family.sync() } }
            }
            h == null -> StartOrJoin(busy, onStart = { familyName, you -> act { family.create(familyName, you); inviteCode = family.invite() } },
                onJoin = { code, you -> act { family.join(code, you) } })
            else -> {
                PixelCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val iAmOwner = h.ownerId != null && h.ownerId == family.myUserId
                        h.members.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "• ${m.name}" + (if (m.userId == family.myUserId) tr(" (you)") else "") + (if (m.userId == h.ownerId) tr(" · started the family") else ""),
                                    modifier = Modifier.weight(1f),
                                )
                                if (iAmOwner && m.userId != family.myUserId) {
                                    TextButton(enabled = !busy, onClick = { removing = m }) { Text(tr("Remove")) }
                                }
                            }
                        }
                        Text(syncLine(status.syncing, status.lastSyncMs, status.error, app), style = MaterialTheme.typography.bodySmall,
                            color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(enabled = !busy && !status.syncing, onClick = { act { family.refresh(); family.sync() } }) { Text(tr("Sync now")) }
                    }
                }

                Text(tr("Pets you care for together"), fontWeight = FontWeight.Bold)
                if (state.pets.isEmpty()) Text(tr("Pets your family shares appear here after the next sync."))
                state.pets.forEach { pet ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pose = remember(pet.id, pet.spriteVersion, pet.species) { app.repo.pose(pet, com.pawpixel.core.Mood.CONTENT) }
                        SpriteView(pose, Modifier.size(48.dp), animate = false)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(pet.name, fontWeight = FontWeight.Bold)
                            Text(
                                if (pet.shared) tr("Shared: care, health and records sync") else tr("Only on this phone"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(pet.shared, enabled = !busy, onCheckedChange = { on -> act { family.share(pet, on) } })
                    }
                }

                Text(tr("Invite someone"), fontWeight = FontWeight.Bold)
                Text(
                    tr(
                        "Send a code to the people you care for your pets with (up to 8). It works for 7 days. " +
                            "They sign in, tap Join with a code, and see your shared pets.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                val code = inviteCode
                if (code == null) {
                    Button(enabled = !busy, onClick = { act { inviteCode = family.invite() } }) { Text(tr("Get an invite code")) }
                } else {
                    InviteCode(app, code, h.name)
                }
                if (h.ownerId == family.myUserId) {
                    TextButton(enabled = !busy, onClick = { act { family.revokeInvites(); inviteCode = null; message = tr("All invite codes are cancelled.") } }) {
                        Text(tr("Cancel all invite codes"))
                    }
                }

                TextButton(onClick = { confirmLeave = true }) { Text(tr("Leave {0}", h.name), color = MaterialTheme.colorScheme.error) }
                Text(
                    tr(
                        "What's shared: pet names, their pixel looks, care tasks, health dates and who tapped Done. Never photos " +
                            "(card photos stay on your phone) and never your location. Reminder settings stay your own.",
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(tr("Remove {0}?", m.name)) },
            text = { Text(tr("{0} stops seeing your family's pets and taps. Their phone keeps its own copy, no longer shared.", m.name)) },
            confirmButton = {
                TextButton(onClick = { removing = null; act { family.removeMember(m.userId) } }) { Text(tr("Remove"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("Cancel")) } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(tr("Leave the family?")) },
            text = { Text(tr("Shared pets stay on this phone with their history, no longer shared. The others keep theirs.")) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; act { family.leave(); inviteCode = null } }) {
                    Text(tr("Leave"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun SignInCard(app: AppScope, busy: Boolean, onSignIn: () -> Unit) {
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Care for your pets together"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                tr(
                    "When someone at home feeds or walks your pet, it shows on everyone's phone and widget, " +
                        "with who did it. No more double breakfasts.",
                ),
            )
            Text(
                tr(
                    "Family sharing needs an account, so PawPixel's server can pass your Done taps between phones. " +
                        "Everything else keeps working on this phone without one.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(enabled = !busy, onClick = onSignIn) { Text(tr(app.repo.map.signInLabel)) }
        }
    }
}

@Composable
private fun StartOrJoin(busy: Boolean, onStart: (String, String) -> Unit, onJoin: (String, String) -> Unit) {
    var you by remember { mutableStateOf("") }
    var familyName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    OutlinedTextField(you, { you = it.take(24) }, label = { Text(tr("Your name (what your family sees)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("New family"), fontWeight = FontWeight.Bold)
            OutlinedTextField(familyName, { familyName = it.take(40) }, label = { Text(tr("Family name, e.g. The Cruz home")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && you.isNotBlank(), onClick = { onStart(familyName.ifBlank { tr("Our family") }, you) }) { Text(tr("Start a family")) }
        }
    }
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Join with a code"), fontWeight = FontWeight.Bold)
            OutlinedTextField(
                code, { code = it.uppercase().take(12) }, label = { Text(tr("Invite code")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            Button(enabled = !busy && you.isNotBlank() && code.count { it.isLetterOrDigit() } >= 8, onClick = { onJoin(code, you) }) { Text(tr("Join")) }
        }
    }
    if (you.isBlank()) Text(tr("Add your name first."), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InviteCode(app: AppScope, code: String, familyName: String) {
    val pretty = code.chunked(4).joinToString("-")
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(pretty, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(tr("Valid for 7 days"), style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                app.repo.platform.shareText(
                    tr(
                        "Join {0} on PawPixel, so we can care for our pets together. " +
                            "Open PawPixel → Family sharing → Join with a code: {1}",
                        familyName, pretty,
                    ),
                )
            }) { Text(tr("Share the code")) }
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
