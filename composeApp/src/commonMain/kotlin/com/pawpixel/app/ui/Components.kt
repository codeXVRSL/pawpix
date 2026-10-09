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
import androidx.compose.foundation.layout.widthIn
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
import com.pawpixel.sprite.PixelIcon
import com.pawpixel.sprite.PixelIcons
import androidx.compose.material3.LocalContentColor
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
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
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
        text, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
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

/** A round, tonal back chevron in a screen's top bar; screen readers hear just "Back". */
@Composable
fun BackButton(app: AppScope) {
    RoundIconButton(PixelIcons.CHEVRON_LEFT, tr("Back"), onClick = app.back)
}

/**
 * One of the app's pixel icons, tinted. Drawn as rectangles on the pixel grid, so it is crisp at
 * any size; the light cells are the tint at half strength.
 */
@Composable
fun PixelIcon(icon: PixelIcon, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 20.dp) {
    val light = tint.copy(alpha = tint.alpha * 0.55f)
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val cell = this.size.width / icon.width
        // Each cell from its own pixel edge to the next: no gaps and no overlap (overlap doubles a light cell's alpha).
        fun edge(i: Int) = kotlin.math.floor(i * cell)
        for (y in 0 until icon.height) for (x in 0 until icon.width) {
            val colour = when (icon.cell(x, y)) { 1 -> tint; 2 -> light; else -> null } ?: continue
            drawRect(colour, Offset(edge(x), edge(y)), Size(edge(x + 1) - edge(x), edge(y + 1) - edge(y)))
        }
    }
}

/** A 40dp round tonal button with a pixel icon and a spoken [label]. */
@Composable
fun RoundIconButton(icon: PixelIcon, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Paw.palette
    Pressable(
        modifier.semantics { contentDescription = label }, face = if (p.dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White,
        lip = p.edge, outline = p.edge, enabled = enabled,
        shape = Pill, onClickLabel = label, onClick = onClick,
    ) { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { PixelIcon(icon, tint = MaterialTheme.colorScheme.onSurface, size = 18.dp) } }
}

/** The same button with a text glyph ("+", "−"): for steppers, where a sign reads better than an icon. */
@Composable
fun RoundIconButton(glyph: String, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = Paw.palette
    Pressable(
        modifier.semantics { contentDescription = label }, face = if (p.dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White,
        lip = p.edge, outline = p.edge, enabled = enabled,
        shape = Pill, onClickLabel = label, onClick = onClick,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Text(
                glyph, fontSize = if (glyph.length == 1 && glyph[0].code < 0x2000) 22.sp else 16.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/** A screen's top bar: back, a title that never squeezes, and up to a few actions on the right. */
@Composable
fun TopBar(app: AppScope?, title: String?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (app != null) { BackButton(app); Spacer(Modifier.width(10.dp)) }
        if (title != null) ScreenTitle(title, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}

/** The main action: the coral key. */
@Composable
fun PrimaryPill(
    text: String, modifier: Modifier = Modifier, enabled: Boolean = true, big: Boolean = false, icon: PixelIcon? = null,
    onClick: () -> Unit,
) = ToyButton(text, modifier, style = ToyStyle.Primary, enabled = enabled, big = big, icon = icon, onClick = onClick)

/** A secondary action: a white key. */
@Composable
fun TonalPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: PixelIcon? = null, onClick: () -> Unit) =
    ToyButton(text, modifier, style = ToyStyle.Secondary, enabled = enabled, icon = icon, onClick = onClick)

/** A quiet action ("+ Add care task"): a sand key. */
@Composable
fun GhostPill(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: PixelIcon? = null, onClick: () -> Unit) =
    ToyButton(text, modifier, style = ToyStyle.Quiet, enabled = enabled, icon = icon, onClick = onClick)

/** A plain text action ("Undo", "Edit"), coloured like a link. */
@Composable
fun LinkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.primary, icon: PixelIcon? = null, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 36.dp), shape = Pill, contentPadding = PaddingValues(horizontal = 10.dp)) {
        val c = if (enabled) color else MaterialTheme.colorScheme.onSurfaceVariant
        if (icon != null) { PixelIcon(icon, tint = c, size = 14.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

/** One choice among a few (dog/cat, daily/weekly): a small key, coral when chosen. */
@Composable
fun ChoiceChip(
    selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    /** [Role.Button] for a chip that acts rather than chooses (a "+15" stepper). */
    role: Role = Role.RadioButton,
    icon: PixelIcon? = null,
) = ToyChip(selected, onClick, label, modifier, enabled, role, icon)

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
    // A sticker: a face, a 2dp edge and a 4dp lip in a darker shade of the same hue.
    val (face, lip, fg) = when (tone) {
        Tone.Surface -> Triple(if (p.dark) cs.surfaceContainerHigh else Color.White, p.edge, cs.onSurface)
        Tone.Tonal -> Triple(if (p.dark) cs.surfaceContainerHighest else PawColors.Sand, if (p.dark) Color(0xFF4A3F63) else Color(0xFFE2C39E), cs.onSurface)
        Tone.Accent -> Triple(cs.primaryContainer, if (p.dark) Color(0xFF8A2F42) else Color(0xFFF2A9B6), cs.onPrimaryContainer)
        Tone.Good -> Triple(cs.secondaryContainer, if (p.dark) Color(0xFF2E6A48) else Color(0xFF9FD9B8), cs.onSecondaryContainer)
        Tone.Calm -> Triple(cs.tertiaryContainer, if (p.dark) Color(0xFF574A9A) else Color(0xFFC4B9F0), cs.onTertiaryContainer)
    }
    ToyPanel(modifier, face = face, lip = lip, outline = lip, shape = shape, padding = padding, onClick = onClick, onClickLabel = onClickLabel) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides fg) { content() }
    }
}

/** Kept for older call sites: the same soft card. */
@Composable
fun PixelCard(modifier: Modifier = Modifier, color: Color? = null, content: @Composable () -> Unit) {
    SoftCard(modifier, tone = if (color == null) Tone.Tonal else Tone.Surface, content = content)
}

/** A 40dp rounded tile holding a pixel icon: the icon of a care task or a setting. */
@Composable
fun IconTile(icon: PixelIcon, modifier: Modifier = Modifier, tone: Tone = Tone.Tonal, size: Dp = 40.dp) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.Surface -> cs.surfaceContainerLow to cs.onSurface; Tone.Tonal -> cs.surfaceContainerHigh to cs.onSurface
        Tone.Accent -> cs.primaryContainer to cs.onPrimaryContainer; Tone.Good -> cs.secondaryContainer to cs.onSecondaryContainer
        Tone.Calm -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    Box(modifier.size(size).clip(RoundedCornerShape(size / 3)).background(bg), contentAlignment = Alignment.Center) {
        PixelIcon(icon, tint = fg, size = size / 2)
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
    val p = Paw.palette
    Row(
        modifier.clip(Pill).background(bg).border(2.dp, if (p.dark) Color(0xFF4A3F63) else PawColors.StickerEdge, Pill).padding(start = 10.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(Pill).background(dot))
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
fun Hearts(score: Int, modifier: Modifier = Modifier, heart: Dp = 18.dp, description: String? = null) {
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
                    Modifier.size(18.dp).clip(Pill)
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

fun formatMinute(minute: Int): String = LocalClock.formatMinute(minute)

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

/** The widest a page of text and buttons gets: on a tablet, pages and panels sit centred at this width. */
val READABLE_WIDTH = 640.dp

/** A page at [READABLE_WIDTH] at most, centred: full width on a phone, a comfortable column on a tablet. */
@Composable
fun Readable(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = READABLE_WIDTH).fillMaxSize()) { content() }
    }
}
