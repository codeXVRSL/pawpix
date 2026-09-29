package com.pawpixel.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.pawpixel.app.toImageBitmap
import com.pawpixel.core.LocalClock
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelImage

object PawColors {
    val Cream = Color(0xFFFFF4E0)
    val Sand = Color(0xFFFFE3B8)
    val Ink = Color(0xFF2B2135)
    val Berry = Color(0xFFE8374E)
    val Leaf = Color(0xFF3A9D6A)
    val Night = Color(0xFF1D2B53)
    val Plum = Color(0xFF3A2E4A)
}

private val Light: ColorScheme = lightColorScheme(
    primary = PawColors.Berry, onPrimary = Color.White,
    secondary = PawColors.Leaf, onSecondary = Color.White,
    background = PawColors.Cream, onBackground = PawColors.Ink,
    surface = PawColors.Cream, onSurface = PawColors.Ink,
    surfaceVariant = PawColors.Sand, onSurfaceVariant = PawColors.Plum,
    outline = PawColors.Ink,
)

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF6B7F), onPrimary = PawColors.Ink,
    secondary = Color(0xFF6FD39C), onSecondary = PawColors.Ink,
    background = Color(0xFF1A1423), onBackground = PawColors.Cream,
    surface = Color(0xFF1A1423), onSurface = PawColors.Cream,
    surfaceVariant = PawColors.Plum, onSurfaceVariant = PawColors.Sand,
    outline = PawColors.Sand,
)

@Composable
fun PawTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp),
        ),
        content = content,
    )
}

/**
 * Crisp (nearest-neighbour) pixel sprite with an optional two-frame idle bob. [description] is what a
 * screen reader says (the pet's name and mood, see [com.pawpixel.core.MoodEngine.describe]); null
 * when the text next to it already says it all.
 */
@Composable
fun SpriteView(image: PixelImage?, modifier: Modifier = Modifier, animate: Boolean = true, description: String? = null) {
    if (image == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium))
        return
    }
    val bitmap = remember(image) { image.toImageBitmap() }
    // Read only while drawing the layer: the bob moves the picture without recomposing the screen every frame.
    val bob = if (animate) {
        rememberInfiniteTransition(label = "bob").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
            label = "bob",
        )
    } else null
    Image(
        bitmap = bitmap,
        contentDescription = description,
        filterQuality = FilterQuality.None,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer {
            // Two-frame step, like classic sprite animation; one sprite pixel, so it stays on the pixel grid.
            val up = if ((bob?.value ?: 0f) < 0.5f) 0f else 1f
            translationY = -up * (size.height / image.height)
        },
    )
}

/** A section title, marked as a heading so screen readers can jump between sections. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = modifier.semantics { heading() })
}

/** A bold label over a group of settings or choices, also a heading for screen readers. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontWeight = FontWeight.Bold, modifier = modifier.semantics { heading() })
}

/** The title in a screen's top bar ("Settings", "Pet map"). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = modifier.semantics { heading() })
}

/** "‹ Back" in a screen's top bar; screen readers hear just "Back". */
@Composable
fun BackButton(app: AppScope) {
    TextButton(onClick = app.back, modifier = Modifier.semantics { contentDescription = tr("Back") }) { Text(tr("‹ Back")) }
}

/** Chunky pixel-style card. */
@Composable
fun PixelCard(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable () -> Unit) {
    Box(
        modifier
            .border(3.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .background(color, MaterialTheme.shapes.medium)
            .padding(14.dp),
    ) { content() }
}

fun formatTime(ms: Long, clock: LocalClock): String = formatMinute(clock.minuteOfDay(ms))

fun formatMinute(minute: Int): String {
    val h = minute / 60; val m = minute % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
}

fun relativeDay(ms: Long, now: Long, clock: LocalClock): String {
    val d = clock.dayIndex(ms) - clock.dayIndex(now)
    val t = formatTime(ms, clock)
    return when (d) {
        0L -> tr("today {0}", t); 1L -> tr("tomorrow {0}", t); -1L -> tr("yesterday {0}", t)
        in 2L..6L -> tr("in {0} days, {1}", d, t)
        else -> LocalClock.shortDate(clock.dayIndex(ms))
    }
}

/** "Sep 29, 2026" */
fun formatDate(ms: Long, clock: LocalClock): String = LocalClock.shortDate(clock.dayIndex(ms))
