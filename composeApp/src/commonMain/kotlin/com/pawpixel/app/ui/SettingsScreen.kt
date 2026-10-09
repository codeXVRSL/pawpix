package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.app.rememberFilePicker
import com.pawpixel.core.AppState
import com.pawpixel.core.Backup
import com.pawpixel.core.MINUTES_PER_DAY
import com.pawpixel.core.Units
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcons

const val SUPPORT_EMAIL = "support@pawpixel.app" // TODO: replace with your real support address before release
const val APP_VERSION = "1.0.0"
const val PRIVACY_URL = "https://pawpixel.app/privacy" // TODO: publish docs/PRIVACY.md here

/** A settings group: an icon, a title, and its rows in one soft card. */
@Composable
private fun Group(icon: com.pawpixel.sprite.PixelIcon, title: String, content: @Composable () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(icon, size = 36.dp)
                GroupLabel(title, Modifier.padding(top = 0.dp))
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(app: AppScope, state: AppState) {
    val s = state.settings
    var confirmWipe by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Backup.Contents?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupOk by remember { mutableStateOf(true) } // success (green) or a failure (red)
    var busy by remember { mutableStateOf(false) }
    val pickBackup = rememberFilePicker { bytes ->
        if (bytes == null) return@rememberFilePicker
        // Read and check it (off the main thread) before asking to replace anything.
        busy = true
        app.launch {
            try {
                pendingRestore = app.repo.readBackup(bytes)
            } catch (e: Backup.NotABackup) {
                backupMessage = e.message?.let { tr(it) }; backupOk = false
            } catch (e: Exception) {
                backupMessage = tr("Couldn't read that file."); backupOk = false
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .imePadding() // keeps the focused field and buttons above the keyboard
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar(app, tr("Settings"))

        Group(PixelIcons.BELL, tr("Reminders")) {
            SwitchRow(tr("Reminders"), tr("Notifications for care tasks."), s.remindersEnabled) { on ->
                if (on) app.repo.platform.requestNotificationPermission()
                app.launch { app.repo.editSettings { it.copy(remindersEnabled = on) } }
            }
            if (s.remindersEnabled) BackgroundTipCard(app.repo.platform)
        }

        Group(PixelIcons.GLOBE, tr("Language")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The language names themselves stay as they are, so anyone can find their own.
                (listOf("" to tr("Phone's language")) + com.pawpixel.i18n.Lang.entries.map { it.code to it.label }).forEach { (code, label) ->
                    ChoiceChip(s.language == code, { app.launch { app.repo.editSettings { it.copy(language = code) } } }, label)
                }
            }
            Hint(tr("Filipino translations are new: tell us if something sounds off."))
        }

        Group(PixelIcons.SCALE, tr("Units")) {
            val country = app.repo.platform.systemCountry()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "" to tr("Phone's country ({0})", Units.autoLabel(country)),
                    Units.METRIC to Units.label(miles = false, pounds = false),
                    Units.IMPERIAL to Units.label(miles = true, pounds = true),
                ).forEach { (code, label) ->
                    ChoiceChip(s.units == code, { app.launch { app.repo.editSettings { it.copy(units = code) } } }, label)
                }
            }
            Hint(tr("Walks, distances on the map and weigh-ins. Weights are kept in grams, so switching loses nothing."))
        }

        Group(PixelIcons.PEOPLE, tr("Household")) {
            val household = app.repo.family.household
            Text(
                household?.let { if (it.members.size == 1) tr("You're in {0} (just you so far).", it.name) else tr("You're in {0} ({1} people).", it.name, it.members.size) }
                    ?: tr("Care for your pets together: everyone's Done taps show on every phone."),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (household != null) {
                TonalPill(tr("Household settings")) { app.navigate(Screen.Family()) }
            } else {
                TonalPill(tr("Join a household")) { app.navigate(Screen.Family(join = true)) }
            }
        }

        Group(PixelIcons.BAG, tr("Away from home")) {
            if (state.isAway(app.now)) {
                Text(tr("Care reminders are paused until {0}. Your pets won't fret over care missed while you're away.", formatDate(s.awayUntilMs, app.repo.clock)), style = MaterialTheme.typography.bodyMedium)
                PrimaryPill(tr("I'm back")) { app.launch { app.repo.setAway(0) } }
            } else {
                Text(
                    tr("Travelling, or a pet-sitter in charge? Pause care reminders, and your pixel pet won't fret over care it missed."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Away 1 day", 3 to "Away 3 days", 7 to "Away 1 week", 14 to "Away 2 weeks").forEach { (days, label) ->
                        ChoiceChip(false, { app.launch { app.repo.setAway(days) } }, tr(label), role = Role.Button)
                    }
                }
            }
        }

        Group(PixelIcons.MOON, tr("Bedtime")) {
            Text(tr("Your pixel pet sleeps between these times unless something important is overdue."), style = MaterialTheme.typography.bodyMedium)
            // The step (not the new time) is applied to the current value, so two quick taps move it twice.
            TimeStepper(tr("Sleeps at"), s.nightStart) { m -> val step = m - s.nightStart; app.launch { app.repo.editSettings { it.copy(nightStart = (it.nightStart + step).mod(24 * 60)) } } }
            TimeStepper(tr("Wakes at"), s.nightEnd) { m -> val step = m - s.nightEnd; app.launch { app.repo.editSettings { it.copy(nightEnd = (it.nightEnd + step).mod(24 * 60)) } } }
        }

        Group(PixelIcons.STAR, tr("PawPixel Pro")) {
            Text(tr("Your first pet is free forever. Pro (coming soon) adds more pets, AI-enhanced sprites and hand-finished sprites by a pixel artist."), style = MaterialTheme.typography.bodyMedium)
            if (app.repo.platform.isDebugBuild) {
                SwitchRow(tr("Test build: unlock Pro features"), tr("Only in test builds, until in-app purchases are connected."), s.pro) {
                    app.launch { app.repo.editSettings { st -> st.copy(pro = it) } }
                }
            }
        }

        Group(PixelIcons.SAVE, tr("Backup")) {
            Text(
                tr(
                    "Changing phones? Save a backup file (to Google Drive, Files or email) and restore it on your new phone, Android or iPhone. " +
                        "It holds your pets, their pixel looks, care tasks and history.",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalPill(tr("Save backup file"), enabled = state.pets.isNotEmpty() && !busy) {
                    busy = true; backupMessage = null
                    app.launch { try { backupMessage = app.repo.exportBackup()?.let { tr(it) }; backupOk = false } finally { busy = false } }
                }
                GhostPill(tr("Restore"), enabled = !busy) { backupMessage = null; pickBackup() }
            }
            backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (backupOk) Paw.palette.good else MaterialTheme.colorScheme.error) }
        }

        Group(PixelIcons.LOCK, tr("Privacy")) {
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
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostPill(tr("Privacy policy")) { app.repo.platform.openUrl(PRIVACY_URL) }
                GhostPill(tr("Contact support")) { app.repo.platform.openUrl("mailto:$SUPPORT_EMAIL") }
            }
            LinkButton(tr("Delete all my data"), color = MaterialTheme.colorScheme.error) { confirmWipe = true }
        }

        Hint("PawPixel $APP_VERSION", Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(16.dp))
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
                            backupOk = true
                            if (n == 1) tr("Restored 1 pet.") else tr("Restored {0} pets.", n)
                        } catch (e: Exception) {
                            backupOk = false
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

@Composable
private fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    val time = formatMinute(minute)
    val earlier = tr("{0}: 30 minutes earlier than {1}", label, time)
    val later = tr("{0}: 30 minutes later than {1}", label, time)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        RoundIconButton("−", earlier) { onChange((minute - 30).mod(MINUTES_PER_DAY)) }
        Text(time, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        RoundIconButton("+", later) { onChange((minute + 30).mod(MINUTES_PER_DAY)) }
    }
}
