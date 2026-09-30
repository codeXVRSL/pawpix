package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.app.rememberFilePicker
import com.pawpixel.core.AppState
import com.pawpixel.core.Backup
import com.pawpixel.core.MINUTES_PER_DAY
import com.pawpixel.i18n.tr

const val SUPPORT_EMAIL = "support@pawpixel.app" // TODO: replace with your real support address before release
const val APP_VERSION = "1.0.0"
const val PRIVACY_URL = "https://pawpixel.app/privacy" // TODO: publish docs/PRIVACY.md here

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: AppScope, state: AppState) {
    val s = state.settings
    var confirmWipe by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Backup.Contents?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pickBackup = rememberFilePicker { bytes ->
        if (bytes == null) return@rememberFilePicker
        // Read and check it (off the main thread) before asking to replace anything.
        busy = true
        app.launch {
            try {
                pendingRestore = app.repo.readBackup(bytes)
            } catch (e: Backup.NotABackup) {
                backupMessage = e.message?.let { tr(it) }
            } catch (e: Exception) {
                backupMessage = tr("Couldn't read that file.")
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .imePadding() // keeps the focused field and buttons above the keyboard
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(app)
            ScreenTitle(tr("Settings"), Modifier.weight(1f))
        }

        SwitchRow(tr("Reminders"), tr("Notifications for care tasks."), s.remindersEnabled) { on ->
            if (on) app.repo.platform.requestNotificationPermission()
            app.launch { app.repo.setSettings(s.copy(remindersEnabled = on)) }
        }
        if (s.remindersEnabled) BackgroundTipCard(app.repo.platform)

        GroupLabel(tr("Language"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The language names themselves stay as they are, so anyone can find their own.
            listOf("" to tr("Phone's language"), "en" to "English", "fil" to "Filipino").forEach { (code, label) ->
                FilterChip(
                    selected = s.language == code,
                    onClick = { app.launch { app.repo.setSettings(s.copy(language = code)) } },
                    label = { Text(label) },
                )
            }
        }
        Text(tr("Filipino translations are new: tell us if something sounds off."), style = MaterialTheme.typography.bodySmall)

        GroupLabel(tr("Household"))
        val household = app.repo.family.household
        Text(
            household?.let { if (it.members.size == 1) tr("You're in {0} (just you so far).", it.name) else tr("You're in {0} ({1} people).", it.name, it.members.size) }
                ?: tr("Care for your pets together: everyone's Done taps show on every phone."),
            style = MaterialTheme.typography.bodySmall,
        )
        if (household != null) {
            OutlinedButton(onClick = { app.navigate(Screen.Family()) }) { Text(tr("Household settings")) }
        } else {
            OutlinedButton(onClick = { app.navigate(Screen.Family(join = true)) }) { Text(tr("Join a household")) }
        }

        GroupLabel(tr("Away from home"))
        if (state.isAway(app.now)) {
            Text(tr("Care reminders are paused until {0}. Your pets won't fret over care missed while you're away.", formatDate(s.awayUntilMs, app.repo.clock)))
            OutlinedButton(onClick = { app.launch { app.repo.setAway(0) } }) { Text(tr("I'm back")) }
        } else {
            Text(
                tr("Travelling, or a pet-sitter in charge? Pause care reminders, and your pixel pet won't fret over care it missed."),
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Away 1 day", 3 to "Away 3 days", 7 to "Away 1 week", 14 to "Away 2 weeks").forEach { (days, label) ->
                    AssistChip(onClick = { app.launch { app.repo.setAway(days) } }, label = { Text(tr(label)) })
                }
            }
        }

        GroupLabel(tr("Bedtime"))
        Text(tr("Your pixel pet sleeps between these times unless something important is overdue."), style = MaterialTheme.typography.bodySmall)
        TimeStepper(tr("Sleeps at"), s.nightStart) { app.launch { app.repo.setSettings(s.copy(nightStart = it)) } }
        TimeStepper(tr("Wakes at"), s.nightEnd) { app.launch { app.repo.setSettings(s.copy(nightEnd = it)) } }

        GroupLabel(tr("PawPixel Pro"))
        Text(
            when {
                s.pro -> tr("You have PawPixel Pro. Thank you!")
                s.proPending -> tr("Waiting for your payment for PawPixel Pro.")
                else -> tr("Your first pet is free forever. Pro is a one-time purchase for more pets and Pro outfits.")
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { app.navigate(Screen.Pro) }) { Text(tr("About PawPixel Pro")) }
        app.repo.platform.store.test?.let { TestStoreSwitches(app, it) }

        GroupLabel(tr("Backup"))
        Text(
            tr(
                "Changing phones? Save a backup file (to Google Drive, Files or email) and restore it on your new phone, Android or iPhone. " +
                    "It holds your pets, their pixel looks, care tasks and history.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = state.pets.isNotEmpty() && !busy, onClick = {
                busy = true; backupMessage = null
                app.launch { try { backupMessage = app.repo.exportBackup()?.let { tr(it) } } finally { busy = false } }
            }) { Text(tr("Save backup file")) }
            OutlinedButton(enabled = !busy, onClick = { backupMessage = null; pickBackup() }) { Text(tr("Restore")) }
        }
        backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }

        GroupLabel(tr("Privacy"))
        Text(
            tr(
                "Everything stays on this phone: no account, no uploads, no tracking. (Your phone's own backup may include it, and backup files go only where you save them.) Your photo is turned into a sprite on the device, " +
                    "and only a small crop is kept for your before/after card. Deleting the app deletes what's on the phone (Android's own Google backup may keep a copy until you remove it in Google Drive). " +
                    "The pet map is optional: only if you join it, your pixel pets, their names and your rough area (about 1 km, never " +
                    "your exact location) go to PawPixel's map server, with the Google or Apple account you sign in with. " +
                    "Sharing with your household is optional too: only then, the pets you share (name, pixel look, care and health " +
                    "schedules), who did each task and the name you show go to PawPixel's server, for your household only. Never photos. " +
                    "On Android, Google's on-device pet detector (ML Kit) sends Google anonymous performance data, never your photos.",
            ),
        )
        OutlinedButton(onClick = { app.repo.platform.openUrl(PRIVACY_URL) }) { Text(tr("Privacy policy")) }
        OutlinedButton(onClick = { app.repo.platform.openUrl("mailto:$SUPPORT_EMAIL") }) { Text(tr("Contact support")) }
        TextButton(onClick = { confirmWipe = true }) { Text(tr("Delete all my data"), color = MaterialTheme.colorScheme.error) }

        Text("PawPixel $APP_VERSION", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    pendingRestore?.let { contents ->
        val names = contents.state.pets.joinToString { it.name }
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(tr("Restore this backup?")) },
            text = {
                val count = if (contents.state.pets.size == 1) tr("1 pet") else tr("{0} pets", contents.state.pets.size)
                val saved = if (contents.createdAtMs > 0) tr(", saved {0}", formatDate(contents.createdAtMs, app.repo.clock)) else ""
                Text(tr("It has {0} ({1}){2}. Pets, tasks and history on this phone will be replaced.", count, names, saved))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    busy = true
                    app.launch {
                        backupMessage = try {
                            val n = app.repo.restoreBackup(contents)
                            if (n == 1) tr("Restored 1 pet.") else tr("Restored {0} pets.", n)
                        } catch (e: Exception) {
                            e.message?.let { tr(it) } ?: tr("Couldn't restore that backup.")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(tr("Replace with backup")) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(tr("Cancel")) } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(tr("Delete everything?")) },
            text = { Text(tr("All pets, sprites, tasks and history will be removed from this phone, and your pet map account (if you joined) from the server. This can't be undone.")) },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; app.launch { app.repo.deleteAllData() } }) {
                    Text(tr("Delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(tr("Cancel")) } },
        )
    }
}

/** Test builds only: a pretend store, to walk through buying Pro (and paying later) without paying. */
@Composable
private fun TestStoreSwitches(app: AppScope, test: com.pawpixel.app.TestStore) {
    // The pretend store keeps its own state: redraw these switches after each change.
    var revision by remember { mutableStateOf(0) }
    key(revision) {
        SwitchRow(tr("Test build: pretend store"), tr("Buy Pro without paying. Only in test builds."), test.enabled) {
            test.enabled = it; revision++
            app.launch { app.repo.pro.storeSwitched() }
        }
        if (test.enabled) {
            SwitchRow(tr("Test build: pay later"), tr("The next purchase waits for a cash payment."), test.payLater) { test.payLater = it; revision++ }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { test.paymentArrives() }, label = { Text(tr("Payment arrives")) })
                AssistChip(onClick = { test.refund() }, label = { Text(tr("Refund")) })
            }
        }
    }
}

@Composable
private fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    val time = formatMinute(minute)
    val earlier = tr("{0}: 30 minutes earlier than {1}", label, time)
    val later = tr("{0}: 30 minutes later than {1}", label, time)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((minute - 30).mod(MINUTES_PER_DAY)) }, modifier = Modifier.semantics { contentDescription = earlier }) { Text("−30") }
        Text(time, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        OutlinedButton(onClick = { onChange((minute + 30).mod(MINUTES_PER_DAY)) }, modifier = Modifier.semantics { contentDescription = later }) { Text("+30") }
    }
}
