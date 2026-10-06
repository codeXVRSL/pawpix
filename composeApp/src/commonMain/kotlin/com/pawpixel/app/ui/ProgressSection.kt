package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * The month's challenge, read from what's already logged: a title, the goal, a bar, and a cheer
 * once it's done. Nothing to join; it's the same goal for everyone with the app this month.
 */
@Composable
fun ChallengeCard(app: AppScope, state: com.pawpixel.core.AppState, pet: Pet) {
    val clock = app.repo.clock
    val challenge = com.pawpixel.core.Challenges.current(app.now, clock, pet.species)
    val done = com.pawpixel.core.Challenges.progress(state, pet.id, challenge, clock)
    val finished = challenge.isDone(done)
    val daysLeft = com.pawpixel.core.Challenges.daysLeft(challenge, app.now, clock)
    val desc = tr("{0} challenge: {1}. {2}.", com.pawpixel.core.Challenges.monthName(challenge.month), challenge.goal, challenge.progressText(done))
    SoftCard(Modifier.fillMaxWidth(), tone = if (finished) Tone.Good else Tone.Surface) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = desc }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelIcon(if (finished) PixelIcons.PARTY else PixelIcons.STAR, size = 18.dp)
                Text(tr("{0} challenge", com.pawpixel.core.Challenges.monthName(challenge.month)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(if (finished) tr("Done!") else if (daysLeft == 1) tr("Last day") else tr("{0} days left", daysLeft), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(challenge.title, style = MaterialTheme.typography.titleMedium)
            Text(challenge.goal, style = MaterialTheme.typography.bodySmall)
            val track = MaterialTheme.colorScheme.surfaceContainerHighest
            val fill = if (finished) Paw.palette.good else MaterialTheme.colorScheme.primary
            Box(Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.small).background(track)) {
                Box(Modifier.fillMaxWidth(challenge.fraction(done).coerceAtLeast(if (done > 0) 0.04f else 0f)).fillMaxHeight().background(fill))
            }
            Text(
                if (finished) tr("{0} did it. Same time next month!", pet.name) else challenge.progressText(done),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
