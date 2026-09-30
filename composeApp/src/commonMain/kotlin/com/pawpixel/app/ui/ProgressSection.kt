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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.Milestones
import com.pawpixel.core.Pet
import com.pawpixel.i18n.tr

/** "100 days of care!" once, when a milestone is reached, with a card to share. */
@Composable
fun MilestoneBanner(app: AppScope, pet: Pet) {
    val days = Milestones.toCelebrate(pet) ?: return
    PixelCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🎉 ${Milestones.title(days)}!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(tr("You've looked after {0} on {1} different days.", pet.name, days) + " " + tr("That's a lot of love."))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.launch { app.repo.shareMilestone(pet, days) } }) { Text(tr("Share the card")) }
                TextButton(onClick = { app.launch { app.repo.celebrate(pet.id, days) } }) { Text(tr("Nice!")) }
            }
        }
    }
}

/** The next milestone, as a quiet line under the weekly dots. */
fun nextMilestoneLine(pet: Pet): String? = Milestones.next(pet)?.let { (at, left) ->
    when (Milestones.caredDays(pet)) {
        0 -> null
        1 -> tr("1 day of care so far · {0} to go to {1}", left, Milestones.title(at))
        else -> tr("{0} days of care so far · {1} to go to {2}", Milestones.caredDays(pet), left, Milestones.title(at))
    }
}

/**
 * Outfits the pet has earned with days of care (and the next ones, with how long to go), then the
 * Pro ones: with Pro they're worn like the others; without, a tap opens the Pro screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OutfitSection(app: AppScope, state: AppState, pet: Pet) {
    val earned = Milestones.unlocked(pet)
    val days = Milestones.caredDays(pet)
    SectionTitle(tr("Outfits"))
    Text(tr("{0} earns pixel outfits with days of care.", pet.name), style = MaterialTheme.typography.bodySmall)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.FilterChip(pet.accessory == null, { app.launch { app.repo.wear(pet, null) } }, label = { Text(tr("None")) })
        com.pawpixel.sprite.Accessory.entries.filter { !it.pro }.forEach { a ->
            if (a in earned) {
                androidx.compose.material3.FilterChip(pet.accessory == a.name, { app.launch { app.repo.wear(pet, a) } }, label = { Text(tr(a.label)) })
            } else {
                androidx.compose.material3.AssistChip(onClick = {}, enabled = false, label = { Text(tr("🔒 {0} · in {1} days", tr(a.label), a.unlockDays - days)) })
            }
        }
    }
    Text(tr("Pro outfits"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Milestones.proOutfits.forEach { a ->
            if (Milestones.canWear(state, pet, a) || pet.accessory == a.name) {
                androidx.compose.material3.FilterChip(pet.accessory == a.name, { app.launch { app.repo.wear(pet, a) } }, label = { Text(tr(a.label)) })
            } else {
                androidx.compose.material3.AssistChip(onClick = { app.navigate(Screen.Pro) }, label = { Text(tr("✨ {0} · Pro", tr(a.label))) })
            }
        }
    }
}
