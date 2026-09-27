package com.pawpixel.sprite

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Foreground mask: 0.0 = background, 1.0 = pet. */
class Mask(val width: Int, val height: Int, val values: FloatArray = FloatArray(width * height)) {
    operator fun get(x: Int, y: Int) = values[y * width + x]
    operator fun set(x: Int, y: Int, v: Float) { values[y * width + x] = v }

    fun coverage(): Double = values.count { it >= 0.5f }.toDouble() / values.size

    /** Nearest-neighbour resize to match a different image size. */
    fun resized(w: Int, h: Int): Mask {
        if (w == width && h == height) return this
        val out = Mask(w, h)
        for (y in 0 until h) {
            val sy = min(height - 1, ((y + 0.5) * height / h).toInt())
            for (x in 0 until w) {
                val sx = min(width - 1, ((x + 0.5) * width / w).toInt())
                out[x, y] = this[sx, sy]
            }
        }
        return out
    }

    /** Bounding box of pixels >= 0.5 as (left, top, right exclusive, bottom exclusive), or null. */
    fun bounds(): IntArray? {
        var l = width; var t = height; var r = -1; var b = -1
        for (y in 0 until height) for (x in 0 until width) if (this[x, y] >= 0.5f) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    companion object {
        fun full(w: Int, h: Int) = Mask(w, h, FloatArray(w * h) { 1f })
    }
}

/**
 * On-device segmentation is done natively (ML Kit on Android, Vision on iOS). This fallback works
 * without either: it learns the background from the photo's border and flood-fills inward. It
 * handles plain backgrounds (walls, floors, sheets); busy backgrounds fall back to a centre crop.
 */
object FallbackSegmenter {
    private const val BORDER_CLUSTERS = 4
    /** OKLab squared distance counted as "same as background". */
    private const val BG_THRESHOLD = 0.0045
    /** Neighbour-to-neighbour step allowed while flooding (catches gradients/shadows). */
    private const val STEP_THRESHOLD = 0.0012

    fun segment(img: PixelImage): Mask {
        val w = img.width; val h = img.height
        val lab = Array(w * h) { Lab.fromArgb(img.pixels[it]) }

        val border = ArrayList<Lab>()
        for (x in 0 until w) { border += lab[x]; border += lab[(h - 1) * w + x] }
        for (y in 0 until h) { border += lab[y * w]; border += lab[y * w + w - 1] }
        val centers = Quantizer.kMeans(border, BORDER_CLUSTERS, seed = 3, iterations = 8)

        val isBg = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        fun nearBg(i: Int) = centers.any { it.dist2(lab[i]) < BG_THRESHOLD }
        fun push(i: Int) { if (!isBg[i] && nearBg(i)) { isBg[i] = true; queue[tail++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w; val y = i / w
            fun visit(j: Int) {
                if (isBg[j]) return
                if (nearBg(j) || lab[j].dist2(lab[i]) < STEP_THRESHOLD && centers.any { it.dist2(lab[j]) < BG_THRESHOLD * 4 }) {
                    isBg[j] = true; queue[tail++] = j
                }
            }
            if (x > 0) visit(i - 1); if (x < w - 1) visit(i + 1)
            if (y > 0) visit(i - w); if (y < h - 1) visit(i + w)
        }
        val mask = Mask(w, h, FloatArray(w * h) { if (isBg[it]) 0f else 1f })
        return MaskOps.cleanup(mask)
    }
}

object MaskOps {
    /** Keeps the largest blob and fills holes inside it (eyes, collars, shadows). */
    fun cleanup(mask: Mask): Mask {
        val w = mask.width; val h = mask.height
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        var best = 0; var bestSize = 0; var next = 0
        for (start in 0 until w * h) {
            if (mask.values[start] < 0.5f || label[start] != 0) continue
            next++
            var sp = 0; var size = 0
            stack[sp++] = start; label[start] = next
            while (sp > 0) {
                val i = stack[--sp]; size++
                val x = i % w; val y = i / w
                fun go(j: Int) { if (mask.values[j] >= 0.5f && label[j] == 0) { label[j] = next; stack[sp++] = j } }
                if (x > 0) go(i - 1); if (x < w - 1) go(i + 1)
                if (y > 0) go(i - w); if (y < h - 1) go(i + w)
            }
            if (size > bestSize) { bestSize = size; best = next }
        }
        if (best == 0) return mask
        // Flood the outside from the border through non-pet pixels; anything not reached is a hole.
        val outside = BooleanArray(w * h)
        var sp = 0
        fun seed(i: Int) { if (label[i] != best && !outside[i]) { outside[i] = true; stack[sp++] = i } }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            fun go(j: Int) { if (label[j] != best && !outside[j]) { outside[j] = true; stack[sp++] = j } }
            if (x > 0) go(i - 1); if (x < w - 1) go(i + 1)
            if (y > 0) go(i - w); if (y < h - 1) go(i + w)
        }
        return Mask(w, h, FloatArray(w * h) { i ->
            when {
                label[i] == best -> max(mask.values[i], 0.5f).coerceAtMost(1f)
                !outside[i] -> 1f
                else -> 0f
            }
        })
    }

    /** Square crop box around the mask with a margin, in source pixel coordinates. */
    fun squareBox(mask: Mask, marginFraction: Double = 0.06): DoubleArray {
        val b = mask.bounds() ?: intArrayOf(0, 0, mask.width, mask.height)
        val bw = (b[2] - b[0]).toDouble(); val bh = (b[3] - b[1]).toDouble()
        val side = max(bw, bh) * (1 + 2 * marginFraction)
        val cx = (b[0] + b[2]) / 2.0; val cy = (b[1] + b[3]) / 2.0
        return doubleArrayOf(cx - side / 2, cy - side / 2, side, side)
    }

    /** Centre square used when segmentation fails. */
    fun centerSquare(w: Int, h: Int): DoubleArray {
        val side = min(w, h) * 0.9
        return doubleArrayOf((w - side) / 2, (h - side) / 2, side, side)
    }

    fun applyTo(img: PixelImage, mask: Mask): PixelImage {
        val m = mask.resized(img.width, img.height)
        val out = img.copy()
        for (i in out.pixels.indices) {
            val a = (Argb.alpha(out.pixels[i]) * m.values[i]).roundToInt()
            out.pixels[i] = Argb.withAlpha(out.pixels[i], a)
        }
        return out
    }
}
