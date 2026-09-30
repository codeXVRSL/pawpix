package com.pawpixel.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PawPixel's look: crisp pixel pets living inside soft, warm, rounded chrome ("soft pixel").
 * Warm cream by day, deep plum by night; one coral accent for what matters (Done, the pet's
 * mood), leaf green for good news, lavender for sleep and calm. Nothing shouts.
 *
 * Every colour with text on it meets WCAG AA (4.5:1) against its surface.
 */
object PawColors {
    // Brand
    val Cream = Color(0xFFFFF7EC)
    val Sand = Color(0xFFFFE9CF)
    val Ink = Color(0xFF2B2135)
    val Coral = Color(0xFFD9364F)
    val CoralSoft = Color(0xFFFFDCE1)
    val Leaf = Color(0xFF2E8B5A)
    val LeafSoft = Color(0xFFD5F2E1)
    val Lavender = Color(0xFF6F5FC7)
    val LavenderSoft = Color(0xFFE7E2FA)
    val Plum = Color(0xFF3A2E4A)
    val Night = Color(0xFF171224)
}

/** Colours the Material scheme has no slot for: the pet's stage and small accents. */
@Immutable
data class PawPalette(
    val dark: Boolean,
    /** Where the pet stands. */
    val floor: Color,
    val floorLine: Color,
    /** A soft glow behind the page's top (home hero, pet page). */
    val glow: Color,
    /** Success text/dots (a task done, a synced household). */
    val good: Color,
    val goodSoft: Color,
    /** Sleep, night, calm. */
    val calm: Color,
    val calmSoft: Color,
    /** The strongest border a card ever gets. */
    val hairline: Color,
)

val LightPalette = PawPalette(
    dark = false,
    floor = Color(0xFFF6DDB8), floorLine = Color(0xFFE9C99A),
    glow = Color(0x66FFC9A3),
    good = PawColors.Leaf, goodSoft = PawColors.LeafSoft,
    calm = PawColors.Lavender, calmSoft = PawColors.LavenderSoft,
    hairline = Color(0x1F2B2135),
)

val DarkPalette = PawPalette(
    dark = true,
    floor = Color(0xFF3C3150), floorLine = Color(0xFF55476E),
    glow = Color(0x407A5CFF),
    good = Color(0xFF7BD6A3), goodSoft = Color(0xFF1F3A2C),
    calm = Color(0xFFB9ADF2), calmSoft = Color(0xFF2E2748),
    hairline = Color(0x33FFFFFF),
)

val LocalPawPalette = staticCompositionLocalOf { LightPalette }

private val Light: ColorScheme = lightColorScheme(
    primary = PawColors.Coral, onPrimary = Color.White,
    primaryContainer = PawColors.CoralSoft, onPrimaryContainer = Color(0xFF7A1225),
    secondary = PawColors.Leaf, onSecondary = Color.White,
    secondaryContainer = PawColors.LeafSoft, onSecondaryContainer = Color(0xFF0F4A2C),
    tertiary = PawColors.Lavender, onTertiary = Color.White,
    tertiaryContainer = PawColors.LavenderSoft, onTertiaryContainer = Color(0xFF2E2470),
    background = PawColors.Cream, onBackground = PawColors.Ink,
    surface = PawColors.Cream, onSurface = PawColors.Ink,
    surfaceVariant = PawColors.Sand, onSurfaceVariant = Color(0xFF6A5C78),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFBF4),
    surfaceContainer = Color(0xFFFFF3E2),
    surfaceContainerHigh = Color(0xFFFFEBD3),
    surfaceContainerHighest = Color(0xFFF9E1C6),
    outline = Color(0xFFB9AAA0), outlineVariant = Color(0xFFEBDCCB),
    error = Color(0xFFC0273B), onError = Color.White,
    errorContainer = Color(0xFFFFDAD9), onErrorContainer = Color(0xFF6E0B1A),
)

private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF8093), onPrimary = Color(0xFF4A0A18),
    primaryContainer = Color(0xFF5C1B2B), onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFF7BD6A3), onSecondary = Color(0xFF0B3A22),
    secondaryContainer = Color(0xFF1F4A33), onSecondaryContainer = Color(0xFFCFF2DD),
    tertiary = Color(0xFFB9ADF2), onTertiary = Color(0xFF2A1F63),
    tertiaryContainer = Color(0xFF3B3070), onTertiaryContainer = Color(0xFFE6E1FA),
    background = PawColors.Night, onBackground = Color(0xFFF7EEE4),
    surface = PawColors.Night, onSurface = Color(0xFFF7EEE4),
    surfaceVariant = Color(0xFF2B2340), onSurfaceVariant = Color(0xFFC9BDD6),
    surfaceContainerLowest = Color(0xFF120E1C),
    surfaceContainerLow = Color(0xFF1C1629),
    surfaceContainer = Color(0xFF221B32),
    surfaceContainerHigh = Color(0xFF2B2340),
    surfaceContainerHighest = Color(0xFF362C4E),
    outline = Color(0xFF8C7F9B), outlineVariant = Color(0xFF3C3352),
    error = Color(0xFFFF8A94), onError = Color(0xFF4F0B14),
    errorContainer = Color(0xFF6E1A24), onErrorContainer = Color(0xFFFFDAD9),
)

/** Big and round, like the pets: cards 24, sheets 28, controls are pills. */
val PawShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val Pill = CircleShape

private val base = Typography()

/** Friendly weights: display and titles heavy with tight spacing, body relaxed with tall lines. */
val PawTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    bodySmall = base.bodySmall.copy(lineHeight = 18.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
)

@Composable
fun PawTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPawPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = PawShapes,
            typography = PawTypography,
            content = content,
        )
    }
}

/** The theme's extra colours (stage floor, success, calm), next to [MaterialTheme]. */
object Paw {
    val palette: PawPalette @Composable get() = LocalPawPalette.current
    val dark: Boolean @Composable get() = LocalPawPalette.current.dark
}

/** A page's soft top glow: a warm halo that fades into the background (lavender at night). */
@Composable
fun heroGlow(): Brush {
    val p = Paw.palette
    val bg = MaterialTheme.colorScheme.background
    return Brush.verticalGradient(0f to p.glow.compositeOverBackground(bg), 1f to bg)
}

private fun Color.compositeOverBackground(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

/** Text style shortcuts used across screens. */
val Typography.petName: TextStyle get() = headlineLarge
