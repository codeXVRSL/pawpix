package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pawpixel.core.AppState
import com.pawpixel.core.Mood
import com.pawpixel.core.MoodEngine
import com.pawpixel.core.Pet
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.Blush
import com.pawpixel.sprite.BodyShape
import com.pawpixel.sprite.Brows
import com.pawpixel.sprite.Chest
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.Collar
import com.pawpixel.sprite.EarStyle
import com.pawpixel.sprite.EyeColor
import com.pawpixel.sprite.EyeShape
import com.pawpixel.sprite.EyeShine
import com.pawpixel.sprite.HeadShape
import com.pawpixel.sprite.Mouth
import com.pawpixel.sprite.NoseColor
import com.pawpixel.sprite.NoseShape
import com.pawpixel.sprite.Pattern
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetStyle
import com.pawpixel.sprite.PixelIcons
import com.pawpixel.sprite.TailStyle
import com.pawpixel.sprite.Whiskers

/** One group of choices in the Studio: a name, and how to read and set it on a [PetStyle]. */
private class Category<T>(
    val emoji: String,
    val title: String,
    val options: List<T>,
    val label: (T) -> String,
    val get: (PetStyle) -> T,
    val set: (PetStyle, T) -> PetStyle,
)

private val CATEGORIES: List<Category<*>> = listOf(
    Category("🐱", "Head", HeadShape.entries, { it.label }, { it.head }, { s, v -> s.copy(head = v) }),
    Category("👀", "Eyes", EyeShape.entries, { it.label }, { it.eyes }, { s, v -> s.copy(eyes = v) }),
    Category("🎨", "Eye colour", EyeColor.entries, { it.label }, { it.eyeColor }, { s, v -> s.copy(eyeColor = v) }),
    Category("✨", "Shine", EyeShine.entries, { it.label }, { it.shine }, { s, v -> s.copy(shine = v) }),
    Category("🤨", "Brows", Brows.entries, { it.label }, { it.brows }, { s, v -> s.copy(brows = v) }),
    Category("👃", "Nose", NoseShape.entries, { it.label }, { it.nose }, { s, v -> s.copy(nose = v) }),
    Category("🩷", "Nose colour", NoseColor.entries, { it.label }, { it.noseColor }, { s, v -> s.copy(noseColor = v) }),
    Category("😊", "Mouth", Mouth.entries, { it.label }, { it.mouth }, { s, v -> s.copy(mouth = v) }),
    Category("👂", "Ears", EarStyle.entries, { it.label }, { it.ears }, { s, v -> s.copy(ears = v) }),
    Category("🧸", "Body", BodyShape.entries, { it.label }, { it.body }, { s, v -> s.copy(body = v) }),
    Category("〰️", "Tail", TailStyle.entries, { it.label }, { it.tail }, { s, v -> s.copy(tail = v) }),
    Category("🐾", "Coat", Pattern.entries, { it.label }, { it.pattern }, { s, v -> s.copy(pattern = v) }),
    Category("🤍", "Chest", Chest.entries, { it.label }, { it.chest }, { s, v -> s.copy(chest = v) }),
    Category("🌸", "Blush", Blush.entries, { it.label }, { it.blush }, { s, v -> s.copy(blush = v) }),
    Category("🐈", "Whiskers", Whiskers.entries, { it.label }, { it.whiskers }, { s, v -> s.copy(whiskers = v) }),
    Category("🎀", "Collar", Collar.entries, { it.label }, { it.collar }, { s, v -> s.copy(collar = v) }),
)

/** Colour groups: fur base, light fur, markings, collar. */
private enum class ColourSlot(val title: String) { BASE("Fur"), LIGHT("Light fur"), DARK("Markings"), COLLAR("Collar colour") }

private const val COLOURS_TAB = -1

/**
 * The Pet Studio: your pixel pet, live, and every way to change how it's drawn. Each option is
 * shown as your own pet wearing it, so choosing is seeing. Nothing here costs anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(app: AppScope, state: AppState, pet: Pet) {
    val base = remember(pet.lookKey) { app.repo.art(pet) }
    val saved = remember(pet.style) { pet.style?.let { PetStyle.decode(it) } ?: PetStyle.DEFAULT }
    var style by remember(saved) { mutableStateOf(saved) }
    var tab by remember { mutableIntStateOf(0) }
    var colourSlot by remember { mutableStateOf(ColourSlot.BASE) }
    var shuffles by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val dirty = style != saved
    val phase = phaseFor(app, state)
    val art = remember(base, style) { base?.let { PetArt(it.look, it.species, it.ears, it.accessory, style) } }

    val save: () -> Unit = {
        if (!saving) {
            saving = true
            app.launch {
                try { app.repo.restyle(pet, style); app.back() } finally { saving = false }
            }
        }
    }
    val leave: () -> Unit = { if (dirty) confirmLeave = true else app.back() }

    Column(Modifier.fillMaxSize().background(heroGlow()).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIconButton(PixelIcons.CHEVRON_LEFT, tr("Back"), onClick = leave)
                ScreenTitle(tr("Pet Studio"), Modifier.weight(1f))
                RoundIconButton(PixelIcons.DICE, tr("Shuffle: a random look")) {
                    shuffles++
                    style = PetStyle.random(pet.id.hashCode() + shuffles).copy(furBase = style.furBase, furLight = style.furLight, furDark = style.furDark)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                PrimaryPill(tr("Save look"), enabled = dirty && !saving && art != null, onClick = save)
            }

            // The pet, alive, in today's sky.
            SoftCard(Modifier.fillMaxWidth(), tone = Tone.Surface, padding = 0.dp) {
                Box(Modifier.fillMaxWidth()) {
                    LivePet(
                        art, pet.eyes, Mood.HAPPY, seed = pet.id.hashCode(), modifier = Modifier.fillMaxWidth(),
                        description = MoodEngine.describe(pet.name, Mood.HAPPY), phase = phase,
                        onPetted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    )
                    if (dirty) {
                        StatusPill(tr("Unsaved look"), MaterialTheme.colorScheme.primary, tone = Tone.Surface, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Category rail.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CATEGORIES.forEachIndexed { i, c -> ChoiceChip(tab == i, { tab = i }, tr(c.title)) }
            ChoiceChip(tab == COLOURS_TAB, { tab = COLOURS_TAB }, tr("Colours"), icon = PixelIcons.SPARKLE)
        }
        Spacer(Modifier.height(10.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab == COLOURS_TAB) {
                ColourPanel(style, colourSlot, onSlot = { colourSlot = it }) { style = it }
            } else {
                val category = CATEGORIES[tab]
                OptionGrid(category, style, base) { style = it; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LinkButton(tr("Back to the photo's look"), enabled = style != PetStyle.DEFAULT, color = MaterialTheme.colorScheme.onSurfaceVariant) { style = PetStyle.DEFAULT }
            }
            Hint(tr("Your choices travel with {0} to your household and the pet map. Nothing to buy.", pet.name))
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(tr("Keep this look?")) },
            text = { Text(tr("{0}'s new look isn't saved yet.", pet.name)) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text(tr("Save look")) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; app.back() }) { Text(tr("Discard")) } },
        )
    }
}

/** Every option of a category as a small picture of this pet wearing it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionGrid(category: Category<T>, style: PetStyle, base: PetArt?, onPick: (PetStyle) -> Unit) {
    val current = category.get(style)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
        for (option in category.options) {
            val styled = category.set(style, option)
            val selected = option == current
            // Cheap: a 41x34 drawing per option, redrawn only when the style changes.
            val thumb = remember(styled, base) { base?.let { Chibi.compose(PetArt(it.look, it.species, it.ears, it.accessory, styled), Chibi.Pose()) } }
            OptionTile(tr(category.label(option)), selected, onClick = { onPick(styled) }) {
                SpriteView(thumb, Modifier.size(56.dp), animate = false)
            }
        }
    }
}

@Composable
private fun OptionTile(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    val toy = Candy.Coral
    Pressable(
        Modifier.semantics { this.selected = selected },
        face = if (selected) toy.face else if (p.dark) cs.surfaceContainerHigh else Color.White,
        lip = if (selected) toy.lip else if (p.dark) Color(0xFF3B3150) else Color(0xFFE6D5C3),
        outline = if (selected) toy.lip else if (p.dark) Color(0xFF3B3150) else Color(0xFFE6D5C3),
        shape = MaterialTheme.shapes.medium, role = Role.RadioButton, onClick = onClick,
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Column(Modifier.width(70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
            Text(
                label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (selected) toy.ink else cs.onSurfaceVariant,
            )
        }
    }
}

/** Fur, light fur, markings and collar colours as swatches; "as the photo" first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourPanel(style: PetStyle, slot: ColourSlot, onSlot: (ColourSlot) -> Unit, onChange: (PetStyle) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColourSlot.entries.forEach { s -> ChoiceChip(slot == s, { onSlot(s) }, tr(s.title)) }
    }
    val current = when (slot) { ColourSlot.BASE -> style.furBase; ColourSlot.LIGHT -> style.furLight; ColourSlot.DARK -> style.furDark; ColourSlot.COLLAR -> style.collarColor }
    fun set(c: Int?): PetStyle = when (slot) {
        ColourSlot.BASE -> style.copy(furBase = c); ColourSlot.LIGHT -> style.copy(furLight = c)
        ColourSlot.DARK -> style.copy(furDark = c); ColourSlot.COLLAR -> style.copy(collarColor = c)
    }
    val swatches = if (slot == ColourSlot.COLLAR) PetStyle.COLLAR_SWATCHES else PetStyle.FUR_SWATCHES
    Hint(
        when (slot) {
            ColourSlot.BASE -> tr("The main fur colour. \"Natural\" keeps what the photo gave.")
            ColourSlot.LIGHT -> tr("Chest and paws, when the coat has a lighter tone.")
            ColourSlot.DARK -> tr("Masks, spots and stripes use this.")
            ColourSlot.COLLAR -> tr("Shows when a collar is on.")
        },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatch(null, current == null, tr("Natural")) { onChange(set(null)) }
        swatches.forEach { c -> Swatch(c, current == c, null) { onChange(set(c)) } }
    }
}

@Composable
private fun Swatch(argb: Int?, selected: Boolean, label: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val cs = MaterialTheme.colorScheme
    val fill = argb?.let { Color(it) } ?: cs.surfaceContainerHigh
    val name = label ?: tr("Colour {0}", (argb!! and 0xFFFFFF).toString(16).padStart(6, '0'))
    Box(
        Modifier
            .pressScale(interaction, down = 0.9f)
            .size(44.dp)
            .clip(Pill)
            .background(fill)
            .border(if (selected) 3.dp else 1.dp, if (selected) cs.primary else Paw.palette.hairline, Pill)
            .clickable(interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurface, maxLines = 1)
    }
}
