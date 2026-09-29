package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.app.Platform
import com.pawpixel.i18n.tr

/**
 * Shown once, in Settings, when reminders scheduled on this phone never arrived: many phones sold in
 * the Philippines stop apps in the background unless the owner allows them. Says so plainly and
 * opens the right setting.
 */
@Composable
fun BackgroundTipCard(platform: Platform) {
    var tip by remember { mutableStateOf(platform.backgroundTip()) }
    val shown = tip ?: return
    PixelCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Some reminders didn't arrive"), fontWeight = FontWeight.Bold)
            Text(tr("Your {0} phone may be closing PawPixel in the background, so reminders can come late or not at all. To fix it:", shown.brand))
            Text(shown.steps, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { platform.openBackgroundSettings() }) { Text(tr("Open settings")) }
                TextButton(onClick = { platform.dismissBackgroundTip(); tip = null }) { Text(tr("Got it")) }
            }
        }
    }
}
