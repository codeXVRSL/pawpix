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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.pawpixel.app.rememberFilePicker
import com.pawpixel.core.AppState
import com.pawpixel.core.Backup
import com.pawpixel.core.MINUTES_PER_DAY

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
                backupMessage = e.message
            } catch (e: Exception) {
                backupMessage = "Couldn't read that file."
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
            TextButton(onClick = app.back) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        SwitchRow("Reminders", "Notifications for care tasks.", s.remindersEnabled) { on ->
            if (on) app.repo.platform.requestNotificationPermission()
            app.launch { app.repo.setSettings(s.copy(remindersEnabled = on)) }
        }

        Text("Family sharing", fontWeight = FontWeight.Bold)
        Text(
            app.repo.family.household?.let { "You're in ${it.name} (${it.members.size} people)." }
                ?: "Care for your pets together: everyone's Done taps show on every phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { app.navigate(Screen.Family) }) { Text("Open family sharing") }

        Text("Away from home", fontWeight = FontWeight.Bold)
        if (state.isAway(app.now)) {
            Text("Care reminders are paused until ${formatDate(s.awayUntilMs, app.repo.clock)}. Your pets won't fret over care missed while you're away.")
            OutlinedButton(onClick = { app.launch { app.repo.setAway(0) } }) { Text("I'm back") }
        } else {
            Text(
                "Travelling, or a pet-sitter in charge? Pause care reminders, and your pixel pet won't fret over care it missed.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "1 day", 3 to "3 days", 7 to "1 week", 14 to "2 weeks").forEach { (days, label) ->
                    AssistChip(onClick = { app.launch { app.repo.setAway(days) } }, label = { Text("Away $label") })
                }
            }
        }

        Text("Bedtime", fontWeight = FontWeight.Bold)
        Text("Your pixel pet sleeps between these times unless something important is overdue.", style = MaterialTheme.typography.bodySmall)
        TimeStepper("Sleeps at", s.nightStart) { app.launch { app.repo.setSettings(s.copy(nightStart = it)) } }
        TimeStepper("Wakes at", s.nightEnd) { app.launch { app.repo.setSettings(s.copy(nightEnd = it)) } }

        Text("PawPixel Pro", fontWeight = FontWeight.Bold)
        Text("Your first pet is free forever. Pro (coming soon) adds more pets, AI-enhanced sprites and hand-finished sprites by a pixel artist.")
        if (app.repo.platform.isDebugBuild) {
            SwitchRow("Test build: unlock Pro features", "Only in test builds, until in-app purchases are connected.", s.pro) {
                app.launch { app.repo.setSettings(s.copy(pro = it)) }
            }
        }

        Text("Backup", fontWeight = FontWeight.Bold)
        Text(
            "Changing phones? Save a backup file (to Google Drive, Files or email) and restore it on your new phone, Android or iPhone. " +
                "It holds your pets, their pixel looks, care tasks and history.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = state.pets.isNotEmpty() && !busy, onClick = {
                busy = true; backupMessage = null
                app.launch { try { backupMessage = app.repo.exportBackup() } finally { busy = false } }
            }) { Text("Save backup file") }
            OutlinedButton(enabled = !busy, onClick = { backupMessage = null; pickBackup() }) { Text("Restore") }
        }
        backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }

        Text("Privacy", fontWeight = FontWeight.Bold)
        Text(
            "Everything stays on this phone: no account, no uploads, no tracking. (Your phone's own backup may include it, and backup files go only where you save them.) Your photo is turned into a sprite on the device, " +
                "and only a small crop is kept for your before/after card. Deleting the app deletes what's on the phone (Android's own Google backup may keep a copy until you remove it in Google Drive). " +
                "The pet map is optional: only if you join it, your pixel pets, their names and your rough area (about 1 km, never " +
                "your exact location) go to PawPixel's map server, with the Google or Apple account you sign in with. " +
                "On Android, Google's on-device pet detector (ML Kit) sends Google anonymous performance data, never your photos.",
        )
        OutlinedButton(onClick = { app.repo.platform.openUrl(PRIVACY_URL) }) { Text("Privacy policy") }
        OutlinedButton(onClick = { app.repo.platform.openUrl("mailto:$SUPPORT_EMAIL") }) { Text("Contact support") }
        TextButton(onClick = { confirmWipe = true }) { Text("Delete all my data", color = MaterialTheme.colorScheme.error) }

        Text("PawPixel $APP_VERSION", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    pendingRestore?.let { contents ->
        val names = contents.state.pets.joinToString { it.name }
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "It has ${if (contents.state.pets.size == 1) "1 pet" else "${contents.state.pets.size} pets"} ($names)" +
                        (if (contents.createdAtMs > 0) ", saved ${formatDate(contents.createdAtMs, app.repo.clock)}" else "") +
                        ". Pets, tasks and history on this phone will be replaced.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    busy = true
                    app.launch {
                        backupMessage = try {
                            val n = app.repo.restoreBackup(contents)
                            if (n == 1) "Restored 1 pet." else "Restored $n pets."
                        } catch (e: Exception) {
                            e.message ?: "Couldn't restore that backup."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Replace with backup") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete everything?") },
            text = { Text("All pets, sprites, tasks and history will be removed from this phone, and your pet map account (if you joined) from the server. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; app.launch { app.repo.deleteAllData() } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((minute - 30).mod(MINUTES_PER_DAY)) }) { Text("−30") }
        Text(formatMinute(minute), fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange((minute + 30).mod(MINUTES_PER_DAY)) }) { Text("+30") }
    }
}
