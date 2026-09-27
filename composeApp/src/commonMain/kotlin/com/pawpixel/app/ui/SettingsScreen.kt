package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.pawpixel.core.AppState
import com.pawpixel.core.MINUTES_PER_DAY

const val SUPPORT_EMAIL = "support@pawpixel.app" // TODO: replace with your real support address before release
const val PRIVACY_URL = "https://pawpixel.app/privacy" // TODO: publish docs/PRIVACY.md here

@Composable
fun SettingsScreen(app: AppScope, state: AppState) {
    val s = state.settings
    var confirmWipe by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
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

        Text("Bedtime", fontWeight = FontWeight.Bold)
        Text("Your pixel pet sleeps between these times unless something important is overdue.", style = MaterialTheme.typography.bodySmall)
        TimeStepper("Sleeps at", s.nightStart) { app.launch { app.repo.setSettings(s.copy(nightStart = it)) } }
        TimeStepper("Wakes at", s.nightEnd) { app.launch { app.repo.setSettings(s.copy(nightEnd = it)) } }

        Text("PawPixel Pro", fontWeight = FontWeight.Bold)
        Text("Your first pet is free forever. Pro (coming soon) adds more pets, AI-enhanced sprites and hand-finished sprites by a pixel artist.")
        SwitchRow("Beta: unlock Pro features", "For testing only, until in-app purchases are connected.", s.pro) {
            app.launch { app.repo.setSettings(s.copy(pro = it)) }
        }

        Text("Privacy", fontWeight = FontWeight.Bold)
        Text(
            "Everything stays on this phone: no account, no uploads, no tracking. Your photo is turned into a sprite on the device, " +
                "and only a small crop is kept for your before/after card. Deleting the app deletes everything.",
        )
        OutlinedButton(onClick = { app.repo.platform.openUrl(PRIVACY_URL) }) { Text("Privacy policy") }
        OutlinedButton(onClick = { app.repo.platform.openUrl("mailto:$SUPPORT_EMAIL") }) { Text("Contact support") }
        TextButton(onClick = { confirmWipe = true }) { Text("Delete all my data", color = MaterialTheme.colorScheme.error) }

        Text("PawPixel 0.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete everything?") },
            text = { Text("All pets, sprites, tasks and history will be removed from this phone. This can't be undone.") },
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
