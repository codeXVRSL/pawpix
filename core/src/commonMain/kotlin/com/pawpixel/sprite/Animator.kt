package com.pawpixel.sprite

import com.pawpixel.core.Mood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Every animation frame PawPixel can show. All frames share one canvas size, anchored at the feet. */
enum class Frame {
    BASE, BREATHE, BLINK,
    SQUASH, STRETCH,
    WALK_1, WALK_2, WALK_3, WALK_4,
    /** The walk with the head leaning left (going left) or right: the pet is never mirrored. */
    WALK_L_1, WALK_L_2, WALK_L_3, WALK_L_4,
    WALK_R_1, WALK_R_2, WALK_R_3, WALK_R_4,
    LOOK_LEFT, LOOK_RIGHT,
    /** Idle life: the tail swings the other way; an ear flicks. */
    TAIL_SWING, EAR_TWITCH_L, EAR_TWITCH_R,
    EAT_DOWN,
    SHAKE_1, SHAKE_2, SHAKE_3, SHAKE_4,
    SLEEP, SLEEP_BREATHE;

    companion object {
        val WALK = listOf(WALK_1, WALK_2, WALK_3, WALK_4)
        val WALK_L = listOf(WALK_L_1, WALK_L_2, WALK_L_3, WALK_L_4)
        val WALK_R = listOf(WALK_R_1, WALK_R_2, WALK_R_3, WALK_R_4)
        val SHAKE = listOf(SHAKE_1, SHAKE_2, SHAKE_3, SHAKE_4)
    }
}

/** Frames for one pet, plus where its eyes were found (for blinking). */
class AnimationSet(val frames: Map<Frame, PixelImage>, val eyes: List<Blob>, val padding: Int) {
    val width get() = frames.getValue(Frame.BASE).width
    val height get() = frames.getValue(Frame.BASE).height
    operator fun get(f: Frame): PixelImage = frames.getValue(f)
    val hasEyes get() = eyes.isNotEmpty()

    /** Same frames with the mood's lighting (night tint when sleepy, grey when sad). */
    fun forMood(mood: Mood): AnimationSet = when (mood) {
        Mood.SLEEPY -> AnimationSet(frames.mapValues { Poses.tint(it.value, 0xFF1D2B53.toInt(), 0.35) }, eyes, padding)
        Mood.SAD -> AnimationSet(frames.mapValues { Poses.desaturate(it.value, 0.65, 0.85) }, eyes, padding)
        Mood.NEEDS_MEDS -> AnimationSet(frames.mapValues { Poses.desaturate(it.value, 0.3, 0.95) }, eyes, padding)
        else -> this
    }
}

/** A connected group of pixels, e.g. one eye. */
class Blob(val pixels: List<Pair<Int, Int>>) {
    val minX = pixels.minOf { it.first }; val maxX = pixels.maxOf { it.first }
    val minY = pixels.minOf { it.second }; val maxY = pixels.maxOf { it.second }
    val cx = pixels.sumOf { it.first }.toDouble() / pixels.size
    val cy = pixels.sumOf { it.second }.toDouble() / pixels.size
    val size get() = pixels.size
}

/**
 * Turns one still sprite into a set of animation frames, the way pixel artists animate a single
 * drawing: move body parts by whole pixels, squash and stretch, and redraw only the eyes.
 *
 * Nothing here invents anatomy the photo doesn't show (no fake legs or tail), so every frame still
 * looks like the owner's real pet. The life comes from timing, which [PetBrain] handles.
 */
object Animator {

    /**
     * @param eyePoints eye positions in sprite pixels, tapped by the owner in the sprite maker.
     * Automatic eye-finding proved unreliable on real fur (stripes and shadows look like eyes), and a
     * blink in the wrong place is worse than none, so without taps the pet simply doesn't blink.
     */
    fun build(sprite: PixelImage, eyePoints: List<Pair<Int, Int>>? = null): AnimationSet {
        val n = max(sprite.width, sprite.height)
        val pad = max(4, n / 8 + 2)
        val base = PixelImage(sprite.width + 2 * pad, sprite.height + 2 * pad).also { it.draw(sprite, pad, pad) }
        val box = opaqueBounds(base) ?: intArrayOf(0, 0, base.width, base.height)
        val bodyH = box[3] - box[1]
        val unit = max(1, n / 32) // "one pixel" of motion, scaled for big sprites

        val eyes = eyePoints.orEmpty().mapNotNull { (x, y) -> eyeAt(base, x + pad, y + pad, n) }
        val blink = if (eyes.isEmpty()) base.copy() else closeEyes(base, eyes)
        val frames = LinkedHashMap<Frame, PixelImage>()
        frames[Frame.BASE] = base
        frames[Frame.BREATHE] = stretchRows(base, box[1] + (bodyH * 0.55).toInt(), unit)
        frames[Frame.BLINK] = blink
        frames[Frame.SQUASH] = scaleFromFeet(base, box, 1.10, 0.90)
        frames[Frame.STRETCH] = scaleFromFeet(base, box, 0.93, 1.08)

        // Walk: legs (bottom quarter) shuffle, body bobs.
        val legsFrom = box[1] + (bodyH * 0.75).toInt()
        frames[Frame.WALK_1] = base
        frames[Frame.WALK_2] = shift(shiftRows(base, legsFrom, box[3], unit), 0, -unit)
        frames[Frame.WALK_3] = base
        frames[Frame.WALK_4] = shift(shiftRows(base, legsFrom, box[3], -unit), 0, -unit)

        // Look: head (top 45%) turns a pixel or two.
        val headTo = box[1] + (bodyH * 0.45).toInt()
        frames[Frame.LOOK_LEFT] = shiftRows(base, 0, headTo, -unit)
        frames[Frame.LOOK_RIGHT] = shiftRows(base, 0, headTo, unit)
        // A photo sprite has no drawn tail or ears to move: these frames repeat ones it has.
        Frame.WALK_L.forEachIndexed { i, f -> frames[f] = shiftRows(frames.getValue(Frame.WALK[i]), 0, headTo, -unit) }
        Frame.WALK_R.forEachIndexed { i, f -> frames[f] = shiftRows(frames.getValue(Frame.WALK[i]), 0, headTo, unit) }
        frames[Frame.TAIL_SWING] = base
        frames[Frame.EAR_TWITCH_L] = base
        frames[Frame.EAR_TWITCH_R] = base

        // Eat: head dips toward the bowl.
        frames[Frame.EAT_DOWN] = dipTop(base, box[1] + (bodyH * 0.5).toInt(), unit + (if (n >= 40) 1 else 0))

        // Wet-dog shake: a travelling wave through the body.
        Frame.SHAKE.forEachIndexed { i, f -> frames[f] = wave(base, box, unit + 1, i * PI / 2) }

        frames[Frame.SLEEP] = blink
        frames[Frame.SLEEP_BREATHE] = stretchRows(blink, box[1] + (bodyH * 0.55).toInt(), unit)
        return AnimationSet(frames, eyes, pad)
    }

    // ---------- Eyes ----------

    /** Converts the owner's taps, stored as fractions of the sprite size, to sprite pixels. */
    fun eyePixels(sprite: PixelImage, eyes: List<Pair<Double, Double>>): List<Pair<Int, Int>> =
        eyes.map { (fx, fy) -> (fx * sprite.width).toInt().coerceIn(0, sprite.width - 1) to (fy * sprite.height).toInt().coerceIn(0, sprite.height - 1) }

    /** The sprite with its eyes closed (used for the sleeping widget pose). */
    fun closedEyes(sprite: PixelImage, eyes: List<Pair<Int, Int>>): PixelImage {
        val blobs = eyes.mapNotNull { (x, y) -> eyeAt(sprite, x, y, maxOf(sprite.width, sprite.height)) }
        return if (blobs.isEmpty()) sprite else closeEyes(sprite, blobs)
    }

    /**
     * The eye under a tap: starts from the darkest pixel near the tap and grows through pixels of
     * similar darkness, within a small radius so it can't leak into dark fur.
     */
    fun eyeAt(img: PixelImage, tapX: Int, tapY: Int, spriteSize: Int): Blob? {
        var sx = -1; var sy = -1; var sl = 2.0
        for (dy in -2..2) for (dx in -2..2) {
            val x = tapX + dx; val y = tapY + dy
            if (!img.inBounds(x, y) || Argb.alpha(img[x, y]) == 0) continue
            val l = Lab.fromArgb(img[x, y]).l
            if (l < sl) { sl = l; sx = x; sy = y }
        }
        if (sx < 0) return null
        val radius = max(1, spriteSize / 14)
        val limit = sl + 0.12
        val px = ArrayList<Pair<Int, Int>>()
        val seen = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(sx to sy) }
        seen += sx to sy
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            px += x to y
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx; val ny = y + dy
                val p = nx to ny
                if (p in seen || abs(nx - sx) > radius || abs(ny - sy) > radius) continue
                seen += p
                if (img.inBounds(nx, ny) && Argb.alpha(img[nx, ny]) > 0 && Lab.fromArgb(img[nx, ny]).l <= limit) queue.add(p)
            }
        }
        return Blob(px)
    }

    /** Paints eyes over with the surrounding fur, then draws a closed-eyelid line. */
    fun closeEyes(img: PixelImage, eyes: List<Blob>): PixelImage {
        val out = img.copy()
        for (eye in eyes) {
            val inEye = eye.pixels.toSet()
            val fur = HashMap<Int, Int>()
            for ((x, y) in eye.pixels) for (dy in -2..2) for (dx in -2..2) {
                val xx = x + dx; val yy = y + dy
                if (!img.inBounds(xx, yy) || (xx to yy) in inEye) continue
                val c = img[xx, yy]
                if (Argb.alpha(c) > 0) fur[c] = (fur[c] ?: 0) + 1
            }
            // The lightest common neighbour colour reads as an eyelid (skip outline/dark pixels).
            val lid = fur.entries.sortedByDescending { it.value }.take(3).maxByOrNull { Lab.fromArgb(it.key).l }?.key ?: continue
            val lash = eye.pixels.minBy { Lab.fromArgb(img[it.first, it.second]).l }.let { img[it.first, it.second] }
            for ((x, y) in eye.pixels) out[x, y] = lid
            val lineY = (eye.minY + eye.maxY + 1) / 2
            for (x in eye.minX..eye.maxX) if (out.inBounds(x, lineY)) out[x, lineY] = lash
        }
        return out
    }

    // ---------- Pixel transforms ----------

    fun opaqueBounds(img: PixelImage): IntArray? {
        var l = img.width; var t = img.height; var r = -1; var b = -1
        for (y in 0 until img.height) for (x in 0 until img.width) if (Argb.alpha(img[x, y]) > 0) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    /** Moves everything above [splitY] up by [by] pixels, repeating the split row: a breath. */
    fun stretchRows(img: PixelImage, splitY: Int, by: Int): PixelImage {
        val out = PixelImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val sy = if (y >= splitY) y else minOf(splitY, y + by)
            out[x, y] = img[x, sy]
        }
        return out
    }

    /** Shifts rows [from, to) horizontally by [dx]. */
    fun shiftRows(img: PixelImage, from: Int, to: Int, dx: Int): PixelImage {
        val out = img.copy()
        for (y in from.coerceAtLeast(0) until to.coerceAtMost(img.height)) for (x in 0 until img.width) {
            val sx = x - dx
            out[x, y] = if (sx in 0 until img.width) img[sx, y] else 0
        }
        return out
    }

    fun shift(img: PixelImage, dx: Int, dy: Int): PixelImage {
        val out = PixelImage(img.width, img.height)
        out.draw(img, dx, dy)
        return out
    }

    /** Head dip: rows above [neckY] slide down by [by], covering the neck. */
    fun dipTop(img: PixelImage, neckY: Int, by: Int): PixelImage {
        val out = img.copy()
        for (y in 0 until minOf(img.height, neckY + by)) for (x in 0 until img.width) {
            val sy = y - by
            val head = if (sy >= 0) img[x, sy] else 0
            // Above the neck the head replaces everything; over the neck it only covers, never cuts holes.
            if (y < neckY || Argb.alpha(head) > 0) out[x, y] = head
        }
        return out
    }

    /** Nearest-neighbour scale around the bottom-centre of the body (feet stay on the floor). */
    fun scaleFromFeet(img: PixelImage, box: IntArray, sx: Double, sy: Double): PixelImage {
        val out = PixelImage(img.width, img.height)
        val cx = (box[0] + box[2]) / 2.0
        val bottom = box[3].toDouble()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val srcX = ((x + 0.5 - cx) / sx + cx - 0.5).roundToInt()
            val srcY = ((y + 0.5 - bottom) / sy + bottom - 0.5).roundToInt()
            if (img.inBounds(srcX, srcY)) out[x, y] = img[srcX, srcY]
        }
        return out
    }

    /** Horizontal sine wave through the body, strongest at the top (feet planted). */
    fun wave(img: PixelImage, box: IntArray, amplitude: Int, phase: Double): PixelImage {
        val out = PixelImage(img.width, img.height)
        val bodyH = (box[3] - box[1]).coerceAtLeast(1)
        for (y in 0 until img.height) {
            val rel = ((box[3] - y).toDouble() / bodyH).coerceIn(0.0, 1.0)
            val dx = (amplitude * rel * sin(y * 0.55 + phase)).roundToInt()
            for (x in 0 until img.width) {
                val s = x - dx
                if (s in 0 until img.width) out[x, y] = img[s, y]
            }
        }
        return out
    }
}
