package com.pawpixel.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pawpixel.core.TaskKind
import com.pawpixel.sprite.PixelIcon

/*
 * PawPixel's own chrome: the "toy box". Everything you can press is a chunky key with a hard lip
 * under it (a darker shade of its own colour) that squashes flat when pressed, the way Duolingo's
 * and Pou's buttons do; panels are outlined stickers with the same lip; meters are segmented pixel
 * bars; every kind of care has its own candy colour. Nothing here looks like a stock component.
 */

/** A face colour and the lip under it (a darker shade of the same hue). */
@androidx.compose.runtime.Immutable
data class Toy(val face: Color, val lip: Color, val ink: Color = Color(0xFF2B2135))

/** A darker shade for a lip: towards the ink, keeping the hue. */
fun Color.lip(amount: Float = 0.22f): Color = Color(
    red * (1 - amount) + 0.17f * amount, green * (1 - amount) + 0.13f * amount, blue * (1 - amount) + 0.21f * amount, alpha,
)

/** The candy colours of the toy box, one per kind of care, so a bowl is always butter and water always sky. */
object Candy {
    val Butter = Toy(Color(0xFFFFCF5C), Color(0xFFD9A52E))
    val Sky = Toy(Color(0xFF7FCBEC), Color(0xFF3F97BD))
    val Leaf = Toy(Color(0xFF9BD98A), Color(0xFF5EA64C))
    val Coral = Toy(Color(0xFFFF8E7E), Color(0xFFD9574A))
    val Lavender = Toy(Color(0xFFBDAFF3), Color(0xFF8C7BE0))
    val Pink = Toy(Color(0xFFFFA9C9), Color(0xFFD96E9A))
    val Mint = Toy(Color(0xFF93E3C7), Color(0xFF53B898))
    val Peach = Toy(Color(0xFFFFC7A3), Color(0xFFE0905F))

    fun forKind(kind: TaskKind): Toy = when (kind) {
        TaskKind.FEED -> Butter
        TaskKind.WATER -> Sky
        TaskKind.WALK -> Leaf
        TaskKind.PLAY -> Coral
        TaskKind.MEDS -> Lavender
        TaskKind.GROOM -> Pink
        TaskKind.LITTER -> Mint
        TaskKind.VACCINE -> Lavender
        TaskKind.DEWORM -> Mint
        TaskKind.FLEA_TICK -> Leaf
        TaskKind.VET -> Sky
    }
}

/** The lip under every pressable thing. */
val LIP: Dp = 4.dp
val TOY_SHAPE: Shape = RoundedCornerShape(14.dp)

/**
 * A chunky key: a face over a hard lip; pressing squashes the face onto the lip. The outline is
 * the ink (or none). Reserves [lipHeight] below itself so the lip never overlaps the next thing.
 */
@Composable
fun Pressable(
    modifier: Modifier = Modifier,
    face: Color,
    lip: Color,
    outline: Color? = null,
    shape: Shape = TOY_SHAPE,
    lipHeight: Dp = LIP,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val drop by animateDpAsState(if (pressed && enabled) lipHeight else 0.dp, fastEffects(), label = "lip")
    val direction = LocalLayoutDirection.current
    // One box draws both lip and face, so a full-width key has a full-width face (two stacked boxes would not).
    Box(
        modifier
            .drawBehind {
                val lipPx = lipHeight.toPx()
                val dropPx = drop.toPx()
                val stroke = 2.dp.toPx()
                inset(0f, lipPx, 0f, 0f) { drawOutline(shape.createOutline(size, direction, this), lip) }
                inset(0f, dropPx, 0f, lipPx - dropPx) {
                    drawOutline(shape.createOutline(size, direction, this), face)
                    if (outline != null) inset(stroke / 2) { drawOutline(shape.createOutline(size, direction, this), outline, style = Stroke(stroke)) }
                }
            }
            .padding(bottom = lipHeight)
            .offset(y = drop)
            .let { if (onClick != null) it.clickable(interaction, indication = null, enabled = enabled, role = role, onClickLabel = onClickLabel, onClick = onClick) else it }
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** How a [ToyButton] looks: the coral key, a white key, a quiet sand key, or any candy colour. */
sealed class ToyStyle {
    data object Primary : ToyStyle()
    data object Secondary : ToyStyle()
    data object Quiet : ToyStyle()
    data class Colored(val toy: Toy) : ToyStyle()
}

@Composable
fun ToyButton(
    text: String, modifier: Modifier = Modifier, style: ToyStyle = ToyStyle.Primary, enabled: Boolean = true, big: Boolean = false,
    icon: PixelIcon? = null, onClickLabel: String? = null, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    val (face, lip, fg, outline) = when (style) {
        ToyStyle.Primary -> listOf(cs.primary, cs.primary.lip(0.3f), cs.onPrimary, null)
        ToyStyle.Secondary -> listOf(if (p.dark) cs.surfaceContainerHigh else Color.White, if (p.dark) Color(0xFF3B3150) else Color(0xFFE6D5C3), cs.onSurface, if (p.dark) Color(0xFF3B3150) else Color(0xFFE6D5C3))
        ToyStyle.Quiet -> listOf(if (p.dark) cs.surfaceContainerHigh else PawColors.Sand, if (p.dark) Color(0xFF3B3150) else Color(0xFFE9C9A6), cs.onSurface, null)
        is ToyStyle.Colored -> listOf(style.toy.face, style.toy.lip, style.toy.ink, null)
    }
    val alpha = if (enabled) 1f else 0.45f
    Pressable(
        modifier, face = (face as Color).copy(alpha = alpha), lip = (lip as Color).copy(alpha = alpha), outline = outline as Color?,
        shape = Pill, enabled = enabled, onClickLabel = onClickLabel, onClick = onClick,
        contentPadding = PaddingValues(horizontal = if (big) 26.dp else 18.dp),
    ) {
        Row(Modifier.heightIn(min = if (big) 50.dp else 42.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { PixelIcon(icon, tint = (fg as Color).copy(alpha = alpha), size = 16.dp); Spacer(Modifier.width(8.dp)) }
            Text(text, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, color = (fg as Color).copy(alpha = alpha), maxLines = 1)
        }
    }
}

/** An outlined sticker panel with a lip: the page's building block. */
@Composable
fun ToyPanel(
    modifier: Modifier = Modifier, face: Color, lip: Color, outline: Color = lip, shape: Shape = MaterialTheme.shapes.large,
    padding: Dp = 16.dp, onClick: (() -> Unit)? = null, onClickLabel: String? = null, content: @Composable BoxScope.() -> Unit,
) {
    Pressable(
        modifier, face = face, lip = lip, outline = outline, shape = shape, onClick = onClick, onClickLabel = onClickLabel,
        contentPadding = PaddingValues(padding), contentAlignment = Alignment.TopStart, content = content,
    )
}

/**
 * A segmented pixel meter: how much of something is left (fullness after a meal, time until the
 * next walk). Filled segments in [toy]'s face colour with its lip as the edge; empty ones sand.
 */
@Composable
fun Meter(fraction: Float, toy: Toy, modifier: Modifier = Modifier, segments: Int = 8, height: Dp = 12.dp) {
    val p = Paw.palette
    val empty = if (p.dark) Color(0xFF3B3150) else Color(0xFFF1E3D1)
    val edge = if (p.dark) Color(0xFF5A4E73) else Color(0xFFCDB9A2)
    val filled = (fraction.coerceIn(0f, 1f) * segments + 0.3f).toInt().coerceIn(0, segments)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        for (i in 0 until segments) {
            val x = i * (w + gap)
            val lit = i < filled
            drawRoundRect(if (lit) toy.lip else edge, Offset(x, 0f), Size(w, size.height), r)
            drawRoundRect(if (lit) toy.face else empty, Offset(x, 0f), Size(w, size.height - 2.dp.toPx()), r)
        }
    }
}

/** One choice among a few: a small key that is a candy colour when chosen. */
@Composable
fun ToyChip(
    selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    role: Role = Role.RadioButton, icon: PixelIcon? = null, toy: Toy? = null,
) {
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    val chosen = toy ?: Toy(cs.primary, cs.primary.lip(0.3f), cs.onPrimary)
    val face = if (selected) chosen.face else if (p.dark) cs.surfaceContainerHigh else Color.White
    val lip = if (selected) chosen.lip else if (p.dark) Color(0xFF3B3150) else Color(0xFFE6D5C3)
    val fg = if (selected) chosen.ink else cs.onSurface
    val alpha = if (enabled) 1f else 0.5f
    Pressable(
        modifier,
        face = face.copy(alpha = alpha), lip = lip.copy(alpha = alpha), outline = if (selected) null else lip.copy(alpha = alpha), shape = Pill, lipHeight = 3.dp,
        enabled = enabled, role = role, onClick = onClick, contentPadding = PaddingValues(start = if (icon != null) 10.dp else 14.dp, end = 14.dp),
    ) {
        Row(Modifier.heightIn(min = 34.dp).semantics { if (role == Role.RadioButton) this.selected = selected }, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { PixelIcon(icon, tint = fg.copy(alpha = alpha), size = 14.dp); Spacer(Modifier.width(6.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = alpha), maxLines = 1)
        }
    }
}
