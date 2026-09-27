package com.pawpixel.sprite

import com.pawpixel.core.Species
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** The part of the photo that is the pet's face: centre and side as fractions of the photo's size. */
data class FaceBox(val cx: Double, val cy: Double, val side: Double) {
    /** Square in pixels of an image [w] x [h] (side is a fraction of the shorter edge). */
    fun inPixels(w: Int, h: Int): DoubleArray {
        val s = side * minOf(w, h)
        return doubleArrayOf(cx * w - s / 2, cy * h - s / 2, s, s)
    }
}

/** Fur colours sampled from the pet's face, used to paint the drawn body. */
data class FurColors(val base: Int, val light: Int, val shade: Int) {
    companion object {
        fun from(head: PixelImage): FurColors {
            val counts = HashMap<Int, Int>()
            for (p in head.pixels) if (Argb.alpha(p) > 0) counts[p] = (counts[p] ?: 0) + 1
            if (counts.isEmpty()) return FurColors(0xFFB07A4A.toInt(), 0xFFE8C9A0.toInt(), 0xFF7A4E2C.toInt())
            val total = counts.values.sum()
            val byCount = counts.entries.sortedByDescending { it.value }
            // Ignore the very darkest colours when choosing fur (eyes, nose), unless the pet is dark all over.
            val darkest = byCount.minOf { Lab.fromArgb(it.key).l }
            val candidates = byCount.filter { Lab.fromArgb(it.key).l > darkest + 0.06 }.ifEmpty { byCount }
            val base = candidates.first().key
            val baseLab = Lab.fromArgb(base)
            val common = candidates.filter { it.value >= total * 0.06 }
            val lightest = common.maxByOrNull { Lab.fromArgb(it.key).l }?.key ?: base
            val light = if (Lab.fromArgb(lightest).l - baseLab.l > 0.08) lightest
                else Lab((baseLab.l + 0.14).coerceAtMost(0.97), baseLab.a * 0.8, baseLab.b * 0.8).toArgb()
            val shade = Lab(baseLab.l * 0.78, baseLab.a * 1.05, baseLab.b * 1.05).toArgb()
            return FurColors(base, light, shade)
        }
    }
}

/** Everything needed to draw a pet: its pixelated face and species. */
class PetArt(val head: PixelImage, val species: Species) {
    val fur: FurColors = FurColors.from(head)
    /** The standing pose, outlined, for widgets, poses and the reveal card. */
    val still: PixelImage by lazy { Chibi.compose(this, Chibi.Pose()) }
}

/**
 * Full-body "chibi" pets: the owner's real pet face (pixelated from the photo) on a drawn pixel
 * body painted in the pet's own fur colours. The body is drawn procedurally at the head's scale,
 * so it has real legs to walk with, a tail to wag, and a curled-up sleeping pose.
 */
object Chibi {

    data class Pose(
        /** Lift per leg in pixels: front-left, front-right, back-left, back-right. */
        val legs: IntArray = IntArray(4),
        val breathe: Int = 0,
        /** Tail swing: -1 (left), 0, 1 (right). */
        val tail: Int = 0,
        val headDx: Int = 0,
        val headDy: Int = 0,
        val bob: Int = 0,
        val lying: Boolean = false,
        val closedHead: PixelImage? = null,
    )

    // Region ids painted before colouring
    private const val EMPTY = 0
    private const val BODY = 1
    private const val SHADE = 2
    private const val LIGHT = 3
    private const val HEAD = 4

    fun canvasSize(h: Int): Pair<Int, Int> = (h + 2 * ceil(h * 0.62).toInt()) to (ceil(h * 1.66).toInt() + 2)

    /** Draws one pose. Result is outlined and always the same size for a given head size. */
    fun compose(art: PetArt, pose: Pose): PixelImage {
        val head = pose.closedHead ?: art.head
        val h = art.head.height
        val hw = art.head.width
        val (w, ch) = canvasSize(maxOf(h, hw))
        val region = IntArray(w * ch)
        val headPx = IntArray(w * ch)
        val cat = art.species == Species.CAT

        fun set(x: Int, y: Int, r: Int) { if (x in 0 until w && y in 0 until ch) region[y * w + x] = r }
        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, r: Int, shadeFrom: Double? = null) {
            for (y in (cy - ry).toInt() - 1..(cy + ry).toInt() + 1) for (x in (cx - rx).toInt() - 1..(cx + rx).toInt() + 1) {
                val dx = (x + 0.5 - cx) / rx; val dy = (y + 0.5 - cy) / ry
                if (dx * dx + dy * dy <= 1.0) {
                    val shaded = shadeFrom != null && dx * 0.9 + dy * 0.55 > shadeFrom
                    set(x, y, if (shaded) SHADE else r)
                }
            }
        }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, r: Int) { for (y in y0 until y1) for (x in x0 until x1) set(x, y, r) }
        /** A leg: straight, with the bottom corners rounded off so paws look soft. */
        fun leg(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, paw: Int, pawH: Int) {
            rect(x0, y0, x1, y1 - pawH, r)
            rect(x0, y1 - pawH, x1, y1, paw)
            if (x1 - x0 >= 3) { set(x0, y1 - 1, EMPTY); set(x1 - 1, y1 - 1, EMPTY) }
        }
        fun disc(cx: Double, cy: Double, rad: Double, r: Int) = ellipse(cx, cy, rad, rad, r)

        val cx = w / 2.0
        val headTop = 1 + pose.bob
        val feetY = (headTop + h * 1.6).roundToInt().coerceAtMost(ch - 1)
        val legW = max(3, (h * 0.16).roundToInt())

        if (!pose.lying) {
            val torsoCy = headTop + h * 1.12 - pose.breathe * 0.5
            val rx = h * 0.44
            val ry = h * 0.3 + pose.breathe * 0.5
            // Back legs (behind, darker)
            for ((i, side) in listOf(-1, 1).withIndex()) {
                val lx = (cx + side * h * 0.34 - legW / 2.0).roundToInt()
                leg(lx, torsoCy.toInt(), lx + legW, feetY - 1 - pose.legs[2 + i], SHADE, SHADE, 0)
            }
            drawTail(::disc, cx + rx * 0.82, torsoCy - ry * 0.25, h, pose.tail, cat)
            ellipse(cx, torsoCy, rx, ry, BODY, shadeFrom = 0.62)
            ellipse(cx, torsoCy + ry * 0.32, rx * 0.5, ry * 0.5, LIGHT)
            // Front legs with light paws
            for ((i, side) in listOf(-1, 1).withIndex()) {
                val lx = (cx + side * h * 0.15 - legW / 2.0).roundToInt()
                val bottom = feetY - pose.legs[i]
                leg(lx, torsoCy.toInt(), lx + legW, bottom, BODY, LIGHT, max(2, h / 10))
            }
        } else {
            val torsoCy = headTop + h * 1.36 - pose.breathe * 0.5
            val rx = h * 0.6
            val ry = h * 0.22 + pose.breathe * 0.5
            // Tail curled along the front
            val tailR = if (cat) max(1.0, h * 0.05) else max(1.3, h * 0.07)
            for (k in 0..14) {
                val a = PI * (0.05 + 0.85 * k / 14.0)
                disc(cx + cos(a) * rx * 0.95, torsoCy + sin(a) * ry * 1.25, tailR, BODY)
            }
            ellipse(cx, torsoCy, rx, ry, BODY, shadeFrom = 0.7)
            for (side in listOf(-1, 1)) ellipse(cx + side * h * 0.15, feetY - h * 0.05, h * 0.09, h * 0.06, LIGHT)
        }

        // Head on top, and where it overlaps the body, a one-pixel neck shadow under it.
        val hx = ((w - hw) / 2.0).roundToInt() + pose.headDx
        val hy = headTop + pose.headDy + (if (pose.lying) (h * 0.5).roundToInt() else 0)
        for (y in 0 until head.height) for (x in 0 until head.width) {
            val p = head[x, y]
            if (Argb.alpha(p) == 0) continue
            val tx = hx + x; val ty = hy + y
            if (tx in 0 until w && ty in 0 until ch) { region[ty * w + tx] = HEAD; headPx[ty * w + tx] = p }
        }
        for (y in 0 until ch - 1) for (x in 0 until w) {
            val i = y * w + x
            if (region[i] == HEAD && region[i + w] in setOf(BODY, LIGHT)) region[i + w] = SHADE
        }

        val img = PixelImage(w, ch)
        for (i in region.indices) img.pixels[i] = when (region[i]) {
            BODY -> art.fur.base
            SHADE -> art.fur.shade
            LIGHT -> art.fur.light
            HEAD -> headPx[i]
            else -> 0
        }
        return SpritePipeline.outline(img)
    }

    private fun drawTail(disc: (Double, Double, Double, Int) -> Unit, bx: Double, by: Double, h: Int, swing: Int, cat: Boolean) {
        val len = if (cat) h * 0.62 else h * 0.4
        val r = if (cat) max(1.0, h * 0.055) else max(1.3, h * 0.075)
        val tipX = bx + len * 0.55 + swing * h * 0.12
        val tipY = by - len * (if (cat) 0.95 else 0.75)
        val ctrlX = bx + len * (if (cat) 0.95 else 0.7)
        val ctrlY = by - len * 0.05
        val steps = (len * 1.6).toInt().coerceAtLeast(6)
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            val x = (1 - t) * (1 - t) * bx + 2 * (1 - t) * t * ctrlX + t * t * tipX
            val y = (1 - t) * (1 - t) * by + 2 * (1 - t) * t * ctrlY + t * t * tipY
            disc(x, y, r * (1.0 - 0.35 * t), if (!cat && t > 0.8) LIGHT else BODY)
        }
    }

    /** The full animation set for a pet. [eyes] are in head pixels (as tapped by the owner). */
    fun build(art: PetArt, eyes: List<Pair<Int, Int>>): AnimationSet {
        val h = art.head.height
        val blobs = eyes.mapNotNull { (x, y) -> Animator.eyeAt(art.head, x, y, h) }
        val closed = if (blobs.isEmpty()) null else Animator.closeEyes(art.head, blobs)
        val unit = max(1, h / 22)
        fun c(p: Pose) = compose(art, p)
        val base = c(Pose())
        val n = max(base.width, base.height)
        val pad = max(4, n / 10 + 2)
        fun padded(img: PixelImage) = PixelImage(img.width + 2 * pad, img.height + 2 * pad).also { it.draw(img, pad, pad) }

        val frames = LinkedHashMap<Frame, PixelImage>()
        val baseP = padded(base)
        val box = Animator.opaqueBounds(baseP) ?: intArrayOf(0, 0, baseP.width, baseP.height)
        frames[Frame.BASE] = baseP
        frames[Frame.BREATHE] = padded(c(Pose(breathe = 1, tail = 1)))
        frames[Frame.BLINK] = padded(c(Pose(closedHead = closed)))
        frames[Frame.SQUASH] = Animator.scaleFromFeet(baseP, box, 1.08, 0.9)
        frames[Frame.STRETCH] = Animator.scaleFromFeet(baseP, box, 0.94, 1.07)
        frames[Frame.WALK_1] = padded(c(Pose(legs = intArrayOf(unit, 0, 0, unit), tail = 1)))
        frames[Frame.WALK_2] = padded(c(Pose(bob = -unit, tail = 0)))
        frames[Frame.WALK_3] = padded(c(Pose(legs = intArrayOf(0, unit, unit, 0), tail = -1)))
        frames[Frame.WALK_4] = padded(c(Pose(bob = -unit, tail = 0)))
        frames[Frame.LOOK_LEFT] = padded(c(Pose(headDx = -unit, tail = -1)))
        frames[Frame.LOOK_RIGHT] = padded(c(Pose(headDx = unit, tail = 1)))
        frames[Frame.EAT_DOWN] = padded(c(Pose(headDy = max(2, h / 9), tail = 1)))
        Frame.SHAKE.forEachIndexed { i, f -> frames[f] = Animator.wave(baseP, box, unit + 1, i * PI / 2) }
        frames[Frame.SLEEP] = padded(c(Pose(lying = true, closedHead = closed ?: art.head)))
        frames[Frame.SLEEP_BREATHE] = padded(c(Pose(lying = true, breathe = 1, closedHead = closed ?: art.head)))
        return AnimationSet(frames, blobs, pad)
    }

    /** A still of the pet asleep (lying, eyes closed if marked), for the sleeping widget pose. */
    fun sleeping(art: PetArt, eyes: List<Pair<Int, Int>>): PixelImage {
        val blobs = eyes.mapNotNull { (x, y) -> Animator.eyeAt(art.head, x, y, art.head.height) }
        val closed = if (blobs.isEmpty()) art.head else Animator.closeEyes(art.head, blobs)
        return compose(art, Pose(lying = true, closedHead = closed))
    }
}
