package com.pawpixel.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pawpixel.app.toImageBitmap
import com.pawpixel.core.LocalClock
import com.pawpixel.i18n.tr
import com.pawpixel.sprite.PixelImage
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------
// Motion: Material 3 Expressive's spring tokens, hand-rolled so the stable Material 3 works.
// Spatial springs (position, size) may overshoot; effect springs (colour, alpha) never do.
// ---------------------------------------------------------------------------------------------

/** Quick, bouncy: a button pressed, a heart appearing (M3 Expressive "fast spatial"). */
fun <T> fastSpatial() = spring<T>(dampingRatio = 0.6f, stiffness = 800f)

/** Everyday layout movement (M3 Expressive "default spatial"). */
fun <T> defaultSpatial() = spring<T>(dampingRatio = 0.8f, stiffness = 380f)

/** Colour and alpha: critically damped, never bounces. */
fun <T> fastEffects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

/** Shrinks slightly while pressed and springs back: every pill and card gets it. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, down: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) down else 1f, fastSpatial(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

// ---------------------------------------------------------------------------------------------
// Sprites
// ---------------------------------------------------------------------------------------------

/**
 * Crisp (nearest-neighbour) pixel sprite with an optional two-frame idle bob. [description] is what a
 * screen reader says (the pet's name and mood, see [com.pawpixel.core.MoodEngine.describe]); null
 * when the text next to it already says it all.
 */
@Composable
fun SpriteView(image: PixelImage?, modifier: Modifier = Modifier, animate: Boolean = true, description: String? = null) {
    if (image == null) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium))
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

/** The owner uses a big font (Android's largest sizes, iOS's accessibility sizes): side-by-side rows stack. */
@Composable
fun largeText(): Boolean = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f

// ---------------------------------------------------------------------------------------------
// Text
// ---------------------------------------------------------------------------------------------

/** A section title ("Care", "Health"), marked as a heading so screen readers can jump between sections. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** A bold label over a group of settings or choices, also a heading for screen readers. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 4.dp).semantics { heading() },
    )
}

/** The title in a screen's top bar ("Settings", "Pet map"). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}

/** A quiet explanatory line under a title or a control. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, align: TextAlign? = null) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier, textAlign = align)
}

// ---------------------------------------------------------------------------------------------
// Bars and buttons
// ---------------------------------------------------------------------------------------------

/** A round, tonal "‹" in a screen's top bar; screen readers hear just "Back". */
@Composable
fun BackButton(app: AppScope) {
    RoundIconButton("‹", tr("Back"), onClick = app.back)
}

/** A 44dp round tonal button with a single glyph (an emoji or a symbol) and a spoken [label]. */
@Composable
fun RoundIconButton(glyph: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction, down = 0.9f)
            .size(44.dp)
            .clip(Pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph, fontSize = if (glyph.length == 1 && glyph[0].code < 0x2000) 26.sp else 20.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** A screen's top bar: back, a title that never squeezes, and up to a few actions on the right. */
@Composable
fun TopBar(app: AppScope?, title: String?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (app != null) { BackButton(app); Spacer(Modifier.width(10.dp)) }
        if (title != null) ScreenTitle(title, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}

/** The main action: a 52dp coral pill that springs when pressed. */
@Composable
fun PrimaryPill(
    text: String, modifier: Modifier = Modifier, enabled: Boolean = true, big: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction, shape = Pill,
        contentPadding = PaddingValues(horizontal = if (big) 28.dp else 22.dp, vertical = 0.dp),
        modifier = modifier.pressScale(interaction).heightIn(min = if (big) 56.dp else 48.dp),
    ) { Text(text, style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge, maxLines = 1) }
}

/** A secondary action on a soft tinted pill. */
@Composable
fun TonalPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick, enabled = enabled, interactionSource = interaction, shape = Pill,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
        modifier = modifier.pressScale(interaction).heightIn(min = 48.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}

/** A light action: a pill with only a hairline. */
@Composable
fun GhostPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick, enabled = enabled, interactionSource = interaction, shape = Pill,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        modifier = modifier.pressScale(interaction).heightIn(min = 44.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}

/** A plain text action ("Undo", "Edit"), coloured like a link. */
@Composable
fun LinkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = Pill, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One choice among a few (dog/cat, daily/weekly): a pill that fills when chosen. */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .pressScale(interaction)
            .heightIn(min = 40.dp)
            .clip(Pill)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) fg else fg.copy(alpha = 0.6f), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

enum class Tone { Surface, Tonal, Accent, Good, Calm }

/**
 * A soft, rounded card: the page's building block. [Tone.Surface] is the lightest (a card on the
 * page), [Tone.Tonal] a warmer tint, [Tone.Accent] coral, [Tone.Good] green, [Tone.Calm] lavender.
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Surface,
    shape: Shape = MaterialTheme.shapes.large,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val p = Paw.palette
    val (bg, fg) = when (tone) {
        // By day a white card floats on the cream; by night cards are lifted a step above the plum.
        Tone.Surface -> (if (p.dark) cs.surfaceContainerLow else cs.surfaceContainerLowest) to cs.onSurface
        Tone.Tonal -> (if (p.dark) cs.surfaceContainerHigh else cs.surfaceContainer) to cs.onSurface
        Tone.Accent -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.Good -> cs.secondaryContainer to cs.onSecondaryContainer
        Tone.Calm -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    val interaction = remember { MutableInteractionSource() }
    val base = if (onClick != null) modifier.pressScale(interaction, down = 0.985f) else modifier
    Surface(
        base.border(1.dp, p.hairline, shape),
        shape = shape, color = bg, contentColor = fg,
        shadowElevation = if (tone == Tone.Surface && !p.dark) 1.dp else 0.dp,
    ) {
        val inner = if (onClick != null) Modifier.clickable(interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick) else Modifier
        Box(inner.padding(padding)) { content() }
    }
}

/** Kept for older call sites: the same soft card. */
@Composable
fun PixelCard(modifier: Modifier = Modifier, color: Color? = null, content: @Composable () -> Unit) {
    SoftCard(modifier, tone = if (color == null) Tone.Tonal else Tone.Surface, content = content)
}

/** A 44dp rounded tile holding an emoji: the icon of a care task or a setting. */
@Composable
fun IconTile(emoji: String, modifier: Modifier = Modifier, tone: Tone = Tone.Tonal, size: Dp = 44.dp) {
    val cs = MaterialTheme.colorScheme
    val bg = when (tone) {
        Tone.Surface -> cs.surfaceContainerLow; Tone.Tonal -> cs.surfaceContainerHigh
        Tone.Accent -> cs.primaryContainer; Tone.Good -> cs.secondaryContainer; Tone.Calm -> cs.tertiaryContainer
    }
    Box(modifier.size(size).clip(RoundedCornerShape(size / 3)).background(bg), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.5f).sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** A small pill of text with a coloured dot: a mood, a status. */
@Composable
fun StatusPill(text: String, dot: Color, modifier: Modifier = Modifier, tone: Tone = Tone.Tonal) {
    val cs = MaterialTheme.colorScheme
    val bg = when (tone) {
        Tone.Surface -> cs.surfaceContainerLowest; Tone.Tonal -> cs.surfaceContainerHigh
        Tone.Accent -> cs.primaryContainer; Tone.Good -> cs.secondaryContainer; Tone.Calm -> cs.tertiaryContainer
    }
    Row(
        modifier.clip(Pill).background(bg).padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(Pill).background(dot))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------
// Pixel ornaments: hearts, week dots, confetti
// ---------------------------------------------------------------------------------------------

private val HEART = listOf(".xx.xx.", "xxxxxxx", "xxxxxxx", ".xxxxx.", "..xxx..", "...x...")

/**
 * Five pixel hearts for a 0..100 happiness [score] (each heart is 20 points; a half-filled heart
 * for the rest). The fill animates when the score changes, so a Done tap visibly adds love.
 */
@Composable
fun Hearts(score: Int, modifier: Modifier = Modifier, heart: Dp = 22.dp, description: String? = null) {
    val filled by animateFloatAsState((score.coerceIn(0, 100) / 20f), defaultSpatial(), label = "hearts")
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val gap = heart / 4
    Canvas(
        modifier.width(heart * 5 + gap * 4).height(heart * 6 / 7)
            .semantics { if (description != null) contentDescription = description },
    ) {
        val px = heart.toPx() / 7f
        for (i in 0 until 5) {
            val left = i * (heart.toPx() + gap.toPx())
            val amount = (filled - i).coerceIn(0f, 1f)
            HEART.forEachIndexed { y, row ->
                row.forEachIndexed { x, c ->
                    if (c != 'x') return@forEachIndexed
                    // Fills left to right, column by column.
                    val lit = (x + 0.5f) / 7f <= amount
                    drawRect(if (lit) on else off, Offset(left + x * px, y * px), Size(px + 0.5f, px + 0.5f))
                }
            }
        }
    }
}

/** The last 7 days as round dots, today last, with weekday initials underneath. */
@Composable
fun WeekDots(week: List<Boolean>, todayWeekday: Int, modifier: Modifier = Modifier) {
    val names = listOf(tr("Mon"), tr("Tue"), tr("Wed"), tr("Thu"), tr("Fri"), tr("Sat"), tr("Sun"))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        week.forEachIndexed { i, cared ->
            val weekday = (todayWeekday - (week.size - 1 - i)).mod(7)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val today = i == week.lastIndex
                Box(
                    Modifier.size(22.dp).clip(Pill)
                        .background(if (cared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                        .then(if (today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, Pill) else Modifier),
                )
                Text(names[weekday], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private class Particle(val x: Float, val vx: Float, val vy: Float, val size: Float, val color: Color, val spin: Float)

/**
 * Pixel confetti falling over its parent for about 1.6 seconds, once per new [trigger] (null
 * shows nothing). Kept short and skippable, so it stays a treat rather than wallpaper.
 */
@Composable
fun Confetti(trigger: Any?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val colors = remember(cs) { listOf(cs.primary, cs.secondary, cs.tertiary, Color(0xFFFFC85C), cs.primaryContainer) }
    var t by remember { mutableStateOf(-1f) }
    val particles = remember(trigger) {
        val r = Random(trigger.hashCode())
        List(56) { Particle(r.nextFloat(), (r.nextFloat() - 0.5f) * 0.25f, 0.5f + r.nextFloat() * 0.7f, 5f + r.nextFloat() * 5f, colors[r.nextInt(colors.size)], r.nextFloat() * 6f) }
    }
    LaunchedEffect(trigger) {
        if (trigger == null) { t = -1f; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            t = (now - start) / 1600f
            if (t >= 1f) { t = -1f; break }
        }
    }
    if (t < 0f) return
    Canvas(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val px = 1.dp.toPx()
        for (p in particles) {
            val y = (t * p.vy * 1.4f - 0.1f) * size.height
            val x = (p.x + t * p.vx) * size.width
            val alpha = (1f - (t - 0.7f) / 0.3f).coerceIn(0f, 1f)
            val s = p.size * px
            val phase = ((t * 4f + p.spin) % 1f) // a flip: the square narrows and widens
            val w = s * (0.3f + 0.7f * kotlin.math.abs(phase - 0.5f) * 2f)
            drawRect(p.color.copy(alpha = alpha), Offset(x - w / 2, y), Size(w, s))
        }
    }
}

/** A short-lived burst of pixel hearts rising from a Done button, once per new [trigger]. */
@Composable
fun HeartBurst(trigger: Any?, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    var t by remember { mutableStateOf(-1f) }
    val seeds = remember(trigger) { val r = Random(trigger.hashCode()); List(7) { Triple(r.nextFloat(), 0.6f + r.nextFloat() * 0.6f, r.nextFloat()) } }
    LaunchedEffect(trigger) {
        if (trigger == null) { t = -1f; return@LaunchedEffect }
        val start = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            t = (now - start) / 900f
            if (t >= 1f) { t = -1f; break }
        }
    }
    if (t < 0f) return
    Canvas(modifier.clearAndSetSemantics {}) {
        val px = 2.dp.toPx()
        for ((sx, speed, wobble) in seeds) {
            val y = size.height - t * speed * size.height * 1.2f
            val x = sx * size.width + kotlin.math.sin((t * 6f + wobble * 6f).toDouble()).toFloat() * 6.dp.toPx()
            val alpha = (1f - t).coerceIn(0f, 1f)
            HEART.forEachIndexed { hy, row -> row.forEachIndexed { hx, c -> if (c == 'x') drawRect(color.copy(alpha = alpha), Offset(x + hx * px, y + hy * px), Size(px, px)) } }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Time formatting
// ---------------------------------------------------------------------------------------------

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

/** 0 = Monday … 6 = Sunday for a local day index (day 0, Jan 1 1970, was a Thursday). */
fun weekdayOf(dayIndex: Long): Int = ((dayIndex + 3) % 7).toInt()

/** "Good morning", by the hour. */
fun greeting(minuteOfDay: Int): String = when (minuteOfDay / 60) {
    in 5..11 -> tr("Good morning")
    in 12..17 -> tr("Good afternoon")
    else -> tr("Good evening")
}

internal fun Float.px(): Int = roundToInt()
