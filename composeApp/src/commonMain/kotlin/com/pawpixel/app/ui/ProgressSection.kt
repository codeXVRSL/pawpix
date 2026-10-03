package com.pawpixel.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pawpixel.core.Milestones
import com.pawpixel.core.Pet
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcons
import com.pawpixel.sprite.Accessory

/** "100 days of care!" once, when a milestone is reached, with confetti and a card to share. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MilestoneBanner(app: AppScope, pet: Pet) {
    val days = Milestones.toCelebrate(pet) ?: return
    // Confetti falls once per milestone shown, never again for the same one.
    val party = remember(pet.id, days) { "${pet.id}:$days" }
    Box(Modifier.fillMaxWidth()) {
        SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelIcon(PixelIcons.PARTY, size = 20.dp)
                    Text("${Milestones.title(days)}!", style = MaterialTheme.typography.titleLarge)
                }
                Text(tr("You've looked after {0} on {1} different days.", pet.name, days) + " " + tr("That's a lot of love."), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrimaryPill(tr("Share the card")) { app.launch { app.repo.shareMilestone(pet, days) } }
                    LinkButton(tr("Nice!"), color = MaterialTheme.colorScheme.onPrimaryContainer) { app.launch { app.repo.celebrate(pet.id, days) } }
                }
            }
        }
        Confetti(party, Modifier.matchParentSize())
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

/** Outfits the pet has earned with days of care (and the next ones, with how long to go). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutfitSection(app: AppScope, pet: Pet) {
    val earned = Milestones.unlocked(pet)
    val days = Milestones.caredDays(pet)
    Hint(tr("{0} earns pixel outfits with days of care. Nothing to buy.", pet.name))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip(pet.accessory == null, { app.launch { app.repo.wear(pet, null) } }, tr("None"))
        Accessory.entries.forEach { a ->
            if (a in earned) {
                ChoiceChip(pet.accessory == a.name, { app.launch { app.repo.wear(pet, a) } }, tr(a.label))
            } else {
                ChoiceChip(false, {}, tr("{0} · in {1} days", tr(a.label), a.unlockDays - days), enabled = false, icon = PixelIcons.LOCK)
            }
        }
    }
}
