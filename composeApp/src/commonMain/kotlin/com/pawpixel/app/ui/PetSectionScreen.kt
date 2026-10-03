package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.Pet
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pages behind the pet's four doors: Health, Weight, Wardrobe (outfits and the Studio) and
 * Share. Each is its own screen, so the pet's page stays short and each of these has room.
 */
@Composable
fun PetSectionScreen(app: AppScope, state: AppState, pet: Pet, section: String) {
    val title = when (section) {
        "health" -> tr("Health"); "weight" -> tr("Weight"); "wardrobe" -> tr("Wardrobe"); else -> tr("Share")
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopBar(app, title)
        when (section) {
            "health" -> HealthSection(app, state, pet)
            "weight" -> WeightSection(app, state, pet)
            "wardrobe" -> Wardrobe(app, pet)
            else -> ShareSection(app, pet)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Wardrobe(app: AppScope, pet: Pet) {
    OutfitSection(app, pet)
    SectionTitle(tr("Look"))
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Accent, onClick = { app.navigate(Screen.Studio(pet.id)) }, onClickLabel = tr("Open the Pet Studio")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(PixelIcons.SPARKLE, tone = Tone.Surface)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Pet Studio"), style = MaterialTheme.typography.titleMedium)
                Text(tr("Eyes, ears, coat, colours and more: 70+ ways to make {0} yours.", pet.name), style = MaterialTheme.typography.bodySmall)
            }
            PixelIcon(PixelIcons.CHEVRON_RIGHT, size = 16.dp)
        }
    }
    GhostPill(tr("Edit look: photo, face, ears"), icon = PixelIcons.CAMERA) { app.navigate(Screen.RemakeSprite(pet.id)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareSection(app: AppScope, pet: Pet) {
    val art = remember(pet.lookKey) { app.repo.art(pet) }
    var makingGif by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    Hint(tr("Show {0} off: a looping animation, or a before-and-after card with the photo.", pet.name))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryPill(
            if (makingGif) tr("Making GIF…") else tr("Share animation"),
            enabled = !makingGif && art != null, icon = PixelIcons.SHARE,
            onClick = {
                val s = art ?: return@PrimaryPill
                makingGif = true
                shareError = null
                app.launch {
                    try {
                        val gif = withContext(Dispatchers.Default) {
                            runCatching { app.repo.animationGif(pet, s) }
                                .onFailure { app.repo.platform.log("GIF failed: ${it.stackTraceToString()}") }
                                .getOrNull()
                        }
                        if (gif != null) {
                            app.repo.platform.log("GIF ready: ${gif.size} bytes")
                            app.repo.shareGif(pet, gif)
                        } else {
                            shareError = tr("Couldn't make the animation. Please try again.")
                        }
                    } finally {
                        makingGif = false
                    }
                }
            },
        )
        // A pet from a family member's phone has no photo here, so no before/after card.
        val hasPhoto = remember(pet.id, pet.spriteVersion) { app.repo.photoCrop(pet.id) != null }
        if (hasPhoto) TonalPill(tr("Before/after"), icon = PixelIcons.CAMERA) { app.launch { app.repo.shareReveal(pet) } }
    }
    shareError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    SectionTitle(tr("Household"))
    val household = app.repo.family.household
    if (pet.shared && household != null) {
        val others = household.members.filter { it.userId != app.repo.family.myUserId }.joinToString { it.name }
        Hint(if (others.isEmpty()) tr("Shared with {0}", household.name) else tr("Cared for with {0}", others))
        TonalPill(tr("Your household"), icon = PixelIcons.PEOPLE) { app.navigate(Screen.Family()) }
    } else {
        Hint(tr("Care for {0} together with a partner or family: every Done shows on everyone's phone.", pet.name))
        TonalPill(tr("Share with your household"), icon = PixelIcons.PEOPLE) { app.navigate(Screen.Family(sharePetId = pet.id)) }
    }
}
