package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.LocalClock
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.core.StateOps
import com.pawpixel.core.WeightTrend
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelIcon
import com.pawpixel.sprite.PixelIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The panels that slide up over the pet's room: Care, Health (with the Weight door), Weight,
 * Wardrobe (outfits and the Studio), Share, More (the pet's menu) and Pets (switch pets). The
 * room stays visible above, dimmed; tapping it, or Back, drops the panel.
 */
@Composable
fun PetSectionScreen(app: AppScope, state: AppState, pet: Pet, section: String) {
    val title = when (section) {
        "care" -> tr("Care"); "health" -> tr("Health"); "weight" -> tr("Weight"); "wardrobe" -> tr("Wardrobe")
        "share" -> tr("Share"); "pets" -> tr("Your pets"); "more" -> tr("More"); "album" -> tr("Album"); "lost" -> tr("Lost and Found"); else -> tr("Share")
    }
    Panel(app, state, pet, title) {
        when (section) {
            "care" -> CarePanel(app, state, pet)
            "health" -> { WeightDoor(app, state, pet); HealthSection(app, state, pet) }
            "weight" -> WeightSection(app, state, pet)
            "wardrobe" -> Wardrobe(app, pet)
            "pets" -> PetsPanel(app, state, pet)
            "more" -> MorePanel(app, state, pet)
            "album" -> AlbumPanel(app, state, pet)
            "lost" -> LostPanel(app, state, pet)
            else -> ShareSection(app, pet)
        }
    }
}

/** A sticker panel risen over the dimmed room, with a handle, a title and a close key. */
@Composable
fun Panel(app: AppScope, state: AppState, pet: Pet?, title: String, content: @Composable ColumnScope.() -> Unit) {
    val phase = phaseFor(app, state)
    val reading = pet?.let { MoodEngine.read(state, it.id, app.now, app.repo.clock) }
    val pose = remember(pet?.lookKey, reading?.mood) { if (pet != null && reading != null) app.repo.pose(pet, reading.mood) else null }
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    val edge = if (p.dark) Color(0xFF3B3150) else Color(0xFFE6D5C3)
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val closeLabel = tr("Back")
    Box(Modifier.fillMaxSize()) {
        // A strip of the room stays above the panel, the pet waiting in it.
        RoomBackdrop(phase, Modifier.fillMaxWidth().fillMaxHeight(0.2f), pixel = 4.dp, floorDepth = 26.dp) { floor ->
            if (pose != null) SpriteView(pose, Modifier.align(Alignment.BottomCenter).size(84.dp).padding(bottom = floor - 10.dp), animate = false)
        }
        Box(
            Modifier.matchParentSize().background(Color(0x33201830))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = closeLabel, onClick = app.back),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f)
                .background(cs.background, shape).border(2.dp, edge, shape)
                .padding(top = 8.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(5.dp).clip(Pill).background(edge))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopBar(app, title)
                content()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** The Weight door at the top of the Health panel: the latest weigh-in, and the chart behind it. */
@Composable
private fun WeightDoor(app: AppScope, state: AppState, pet: Pet) {
    val weights = state.weightsFor(pet.id)
    val detail = weights.lastOrNull()?.let { tr("{0} on {1}", WeightTrend.kg(it.grams), LocalClock.shortDate(it.day)) } ?: tr("Keep track of weigh-ins")
    MenuRow(PixelIcons.SCALE, tr("Weight"), detail, Candy.Sky) { app.navigate(Screen.PetSection(pet.id, "weight")) }
}

/** One line of a menu: a candy icon, a title, a detail, a chevron. */
@Composable
private fun MenuRow(icon: PixelIcon, title: String, detail: String?, toy: Toy, onClick: () -> Unit) {
    SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 12.dp, onClick = onClick, onClickLabel = title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CandyTile(icon, toy)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PixelIcon(PixelIcons.CHEVRON_RIGHT, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 14.dp)
        }
    }
}

/** The pet's menu: edit the pet, another pet, the map, the household. */
@Composable
private fun MorePanel(app: AppScope, state: AppState, pet: Pet) {
    var renaming by remember { mutableStateOf(false) }
    var showProDialog by remember { mutableStateOf(false) }
    var remembering by remember { mutableStateOf(false) }
    val photos = state.albumFor(pet.id).size
    MenuRow(
        PixelIcons.CAMERA, tr("Album"),
        if (photos == 0) tr("Photos of {0}, kept for good", pet.name) else tr("{0} photos, kept for good", photos), Candy.Coral,
    ) { app.navigate(Screen.PetSection(pet.id, "album")) }
    if (!pet.remembered) MenuRow(
        PixelIcons.BELL, if (lostAlertFor(app, pet.id) != null) tr("Lost alert is on") else tr("Lost?"),
        if (lostAlertFor(app, pet.id) != null) tr("Sightings, share, safe home") else tr("Alert pet owners nearby"), Candy.Coral,
    ) { app.navigate(Screen.PetSection(pet.id, "lost")) }
    MenuRow(PixelIcons.PENCIL, tr("Edit {0}", pet.name), tr("Name, type and birthday"), Candy.Peach) { renaming = true }
    MenuRow(PixelIcons.PLUS, tr("Add another pet"), tr("From a photo"), Candy.Butter) {
        if (StateOps.canAddPet(state)) app.navigate(Screen.CreatePet) else showProDialog = true
    }
    MenuRow(PixelIcons.PIN, tr("Pet map"), tr("Pet owners and walks near you"), Candy.Leaf) { app.navigate(Screen.PetMap) }
    val household = app.repo.family.household
    MenuRow(
        PixelIcons.PEOPLE, tr("Family"),
        if (household != null) household.name else tr("Care for your pets together"), Candy.Lavender,
    ) { app.navigate(Screen.Family()) }
    MenuRow(
        PixelIcons.STAR, if (pet.remembered) tr("Remembered") else tr("In loving memory"),
        if (pet.remembered) tr("Since {0}", LocalClock.shortDate(pet.rememberedDay ?: 0)) else tr("If {0} has passed away", pet.name), Candy.Lavender,
    ) { remembering = true }
    if (renaming) RenameDialog(app, pet) { renaming = false }
    if (remembering) RememberDialog(app, pet) { remembering = false }
    if (showProDialog) {
        AlertDialog(
            onDismissRequest = { showProDialog = false },
            title = { Text(tr("More pets with Pro")) },
            text = { Text(tr("Your first pet is free forever. Extra pets are part of PawPixel Pro, which is coming soon.")) },
            confirmButton = { TextButton(onClick = { showProDialog = false }) { Text(tr("OK")) } },
        )
    }
}

/** Switch pets: each pet's sprite and mood; the chosen one's room is home. */
@Composable
private fun PetsPanel(app: AppScope, state: AppState, current: Pet) {
    var showProDialog by remember { mutableStateOf(false) }
    state.pets.forEach { pet ->
        val reading = MoodEngine.read(state, pet.id, app.now, app.repo.clock)
        val pose = remember(pet.lookKey, reading.mood) { app.repo.pose(pet, reading.mood) }
        val label = tr("Open {0}'s page", pet.name)
        SoftCard(
            Modifier.fillMaxWidth(), tone = if (pet.id == current.id) Tone.Accent else Tone.Surface, padding = 10.dp,
            onClick = { app.showPet(pet.id); app.back() }, onClickLabel = label,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpriteView(pose, Modifier.size(56.dp), animate = false, description = MoodEngine.describe(pet.name, reading.mood))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(pet.name, style = MaterialTheme.typography.titleMedium)
                    Text(reading.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pet.id == current.id) PixelIcon(PixelIcons.CHECK, tint = MaterialTheme.colorScheme.primary, size = 16.dp)
            }
        }
    }
    GhostPill(tr("+ Add another pet")) { if (StateOps.canAddPet(state)) app.navigate(Screen.CreatePet) else showProDialog = true }
    if (showProDialog) {
        AlertDialog(
            onDismissRequest = { showProDialog = false },
            title = { Text(tr("More pets with Pro")) },
            text = { Text(tr("Your first pet is free forever. Extra pets are part of PawPixel Pro, which is coming soon.")) },
            confirmButton = { TextButton(onClick = { showProDialog = false }) { Text(tr("OK")) } },
        )
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
