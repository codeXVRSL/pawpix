package com.pawpixel.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
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
import com.pawpixel.sprite.PixelIcon
import com.pawpixel.sprite.PixelIcons

// The app's shell: the floating dock at the bottom of the home screen, the chunky care tiles under
// the pet, and the glassy buttons that sit over the pet's sky.

/** One door of the [FloatingDock]: an icon, a short word, where it goes. */
class DockItem(val icon: PixelIcon, val label: String, val selected: Boolean = false, val onClick: () -> Unit)

/**
 * A floating pill of three or four doors (pets, the map, the household, settings), the way 2026's
 * apps keep their main places one thumb away. It sits in the page's flow, never over the content.
 */
@Composable
fun FloatingDock(items: List<DockItem>, modifier: Modifier = Modifier, compact: Boolean = false, onRoom: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    // At the biggest fonts the words no longer fit four across: the dock shows icons only, each
    // still spoken by name.
    val iconsOnly = largeText()
    // Over the room the dock is a white sticker whatever the phone's theme, like the rest of the HUD.
    val dark = p.dark && !onRoom
    val face = if (dark) cs.surfaceContainerHigh else Color.White
    val lip = if (dark) Color(0xFF3B3150) else Color(0xFFE6D5C3)
    val ink = if (dark) cs.onSurfaceVariant else Color(0xFF6E6287)
    ToyPanel(modifier, face = face, lip = lip, outline = lip, shape = Pill, padding = 5.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (item in items) {
                val toy = Candy.Coral
                Pressable(
                    Modifier.semantics { selected = item.selected; if (iconsOnly) contentDescription = item.label },
                    face = if (item.selected) toy.face else Color.Transparent, lip = if (item.selected) toy.lip else Color.Transparent,
                    shape = Pill, lipHeight = 3.dp, role = Role.Tab, onClickLabel = item.label, onClick = item.onClick,
                ) {
                    Column(
                        Modifier.widthIn(min = if (iconsOnly) 48.dp else if (compact) 56.dp else 64.dp).height(50.dp).padding(horizontal = if (compact) 6.dp else 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        PixelIcon(item.icon, tint = if (item.selected) toy.ink else ink, size = if (iconsOnly) 24.dp else 20.dp)
                        if (!iconsOnly) Text(
                            item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (item.selected) toy.ink else ink, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A key over the pet's room (back, edit): a white sticker key, readable on any wall. */
@Composable
fun GlassButton(icon: PixelIcon, label: String, modifier: Modifier = Modifier, night: Boolean = false, onClick: () -> Unit) {
    Pressable(
        modifier.semantics { contentDescription = label }, face = Color.White, lip = Color(0xFFE6D5C3), outline = Color(0xFFE6D5C3),
        shape = Pill, onClickLabel = label, onClick = onClick,
    ) { Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { PixelIcon(icon, tint = Color(0xFF2B2135), size = 18.dp) } }
}

/** A key of text over the room (the Edit action). */
@Composable
fun GlassPill(text: String, label: String, modifier: Modifier = Modifier, night: Boolean = false, onClick: () -> Unit) {
    Pressable(
        modifier.semantics { contentDescription = label }, face = Color.White, lip = Color(0xFFE6D5C3), outline = Color(0xFFE6D5C3),
        shape = Pill, onClickLabel = label, onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.labelMedium, color = Color(0xFF2B2135), modifier = Modifier.clearAndSetSemantics {}) } }
}

/**
 * A door on the pet's page (Health, Weight, Wardrobe, Share): an icon tile, a title and one line of
 * what's inside, in a card that opens its own screen. Four of them make a 2x2 grid.
 */
@Composable
fun DoorTile(icon: PixelIcon, title: String, detail: String, modifier: Modifier = Modifier, toy: Toy = Candy.Peach, onClick: () -> Unit) {
    val p = Paw.palette
    val face = if (p.dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White
    val lip = if (p.dark) Color(0xFF3B3150) else Color(0xFFE6D5C3)
    ToyPanel(modifier, face = face, lip = lip, outline = lip, padding = 14.dp, onClick = onClick, onClickLabel = title) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(toy.face).border(2.dp, toy.lip, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                PixelIcon(icon, tint = toy.ink, size = 20.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
