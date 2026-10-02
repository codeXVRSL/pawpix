package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The app's shell: the floating dock at the bottom of the home screen, the chunky care tiles under
// the pet, and the glassy buttons that sit over the pet's sky.

/** One door of the [FloatingDock]: an emoji, a short word, where it goes. */
class DockItem(val emoji: String, val label: String, val selected: Boolean = false, val onClick: () -> Unit)

/**
 * A floating pill of three or four doors (pets, the map, the household, settings), the way 2026's
 * apps keep their main places one thumb away. It sits in the page's flow, never over the content.
 */
@Composable
fun FloatingDock(items: List<DockItem>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    Surface(
        modifier,
        shape = Pill, color = if (p.dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest,
        shadowElevation = if (p.dark) 0.dp else 8.dp,
    ) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            for (item in items) {
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier.pressScale(interaction, down = 0.92f).clip(Pill)
                        .background(if (item.selected) cs.primaryContainer else Color.Transparent)
                        .clickable(interaction, indication = null, role = Role.Tab, onClick = item.onClick)
                        .semantics { selected = item.selected }
                        .widthIn(min = 64.dp).height(52.dp).padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(item.emoji, fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
                    Text(
                        item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (item.selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A chunky care tile (🍖 Feed), the one-tap care under the pet: coral when it's waiting, green
 * with a tick once done today, soft otherwise. 88dp wide so a thumb never misses.
 */
@Composable
fun CareTile(emoji: String, label: String, tone: Tone, done: Boolean, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    val (bg, fg) = when (tone) {
        Tone.Accent -> cs.primary to cs.onPrimary
        Tone.Good -> cs.secondaryContainer to cs.onSecondaryContainer
        else -> (if (p.dark) cs.surfaceContainerHigh else cs.surfaceContainerLowest) to cs.onSurface
    }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier.pressScale(interaction, down = 0.93f).width(88.dp).height(80.dp),
        shape = MaterialTheme.shapes.medium, color = bg, contentColor = fg,
        shadowElevation = if (!p.dark) (if (tone == Tone.Accent) 4.dp else 2.dp) else 0.dp,
    ) {
        Column(
            Modifier.clickable(interaction, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description }.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                Text(emoji, fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
                if (done) Box(Modifier.size(14.dp).clip(Pill).background(p.good), contentAlignment = Alignment.Center) {
                    Text("✓", color = Color.White, fontSize = 9.sp, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            Text(
                label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                lineHeight = 13.sp, modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** A round glass button over the pet's sky (back, edit): readable on any sky, day or night. */
@Composable
fun GlassButton(glyph: String, label: String, modifier: Modifier = Modifier, night: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.pressScale(interaction, down = 0.9f).size(40.dp).clip(Pill)
            .background(if (night) Color(0x59000000) else Color(0xBFFFFFFF))
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph, fontSize = if (glyph.length == 1 && glyph[0].code < 0x2000) 24.sp else 14.sp,
            style = MaterialTheme.typography.labelLarge, color = if (night) Color(0xFFF7EEE4) else Color(0xFF2B2135),
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** A glass pill of text over the sky (the Edit action), same glass as [GlassButton]. */
@Composable
fun GlassPill(text: String, label: String, modifier: Modifier = Modifier, night: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.pressScale(interaction, down = 0.94f).height(40.dp).clip(Pill)
            .background(if (night) Color(0x59000000) else Color(0xBFFFFFFF))
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (night) Color(0xFFF7EEE4) else Color(0xFF2B2135), modifier = Modifier.clearAndSetSemantics {})
    }
}
