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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/** Crisp (nearest-neighbour) pixel sprite with an optional two-frame idle bob. */
@Composable
fun SpriteView(image: PixelImage?, modifier: Modifier = Modifier, animate: Boolean = true) {
    if (image == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium))
        return
    }
    val bitmap = remember(image) { image.toImageBitmap() }
    val bob = if (animate) {
        val t = rememberInfiniteTransition(label = "bob")
        val v by t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
            label = "bob",
        )
        if (v < 0.5f) 0f else 1f // two-frame step, like classic sprite animation
    } else 0f
    Image(
        bitmap = bitmap,
        contentDescription = null,
        filterQuality = FilterQuality.None,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer {
            // Move by exactly one sprite pixel so the bob stays on the pixel grid.
            translationY = -bob * (size.height / image.height)
        },
    )
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
