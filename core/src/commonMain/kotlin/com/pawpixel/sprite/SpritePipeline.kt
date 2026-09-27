package com.pawpixel.sprite

import com.pawpixel.core.SpriteSettings
import kotlin.math.max
import kotlin.math.sqrt

data class SpriteResult(
    /** Outlined pet sprite on a transparent background, (size + 2) square. */
    val sprite: PixelImage,
    val palette: List<Int>,
    /** The square region of the photo the sprite was made from, for the before/after reveal card. */
    val photoCrop: PixelImage,
    /** True when the pet was separated from the background; false = centre crop fallback. */
    val backgroundRemoved: Boolean,
)

/**
 * Photo → pixel-art sprite, fully on device and deterministic.
 *
 * 1. Shrink the photo to a working size.
 * 2. Separate the pet from the background (native segmenter mask, or [FallbackSegmenter]).
 * 3. Crop a square around the pet.
 * 4. Area-average down to the sprite size, treating mostly-background pixels as transparent.
 * 5. Boost colour a little and sharpen, as pixel artists do, so features survive the downscale.
 * 6. Reduce to a small palette (k-means in OKLab) and remove stray single pixels.
 * 7. Add a 1px "selective" outline: each outline pixel is a darker shade of the colour it borders.
 */
object SpritePipeline {
    const val WORKING_SIZE = 384
    private const val MIN_COVERAGE = 0.03
    private const val MAX_COVERAGE = 0.97

    fun generate(photo: PixelImage, settings: SpriteSettings, nativeMask: Mask? = null): SpriteResult {
        val size = settings.size.coerceIn(16, 96)
        val colors = settings.colors.coerceIn(3, 32)
        val work = photo.fitWithin(WORKING_SIZE)

        val mask0 = nativeMask?.resized(work.width, work.height)?.let { MaskOps.cleanup(it) } ?: FallbackSegmenter.segment(work)
        val cov = mask0.coverage()
        val removed = cov in MIN_COVERAGE..MAX_COVERAGE
        val mask = if (removed) mask0 else Mask.full(work.width, work.height)
        val box = if (removed) MaskOps.squareBox(mask) else MaskOps.centerSquare(work.width, work.height)

        val cut = MaskOps.applyTo(work, mask)
        val small = downscaleKeepingFeatures(cut, box, size)
        val photoCrop = PixelImage(256, 256).fill(PHOTO_BACKDROP)
            .also { it.draw(work.resampleArea(box[0], box[1], box[2], box[3], 256, 256), 0, 0) }

        // Hard alpha: pixel art has no half-transparent edges.
        for (i in small.pixels.indices) {
            val p = small.pixels[i]
            small.pixels[i] = if (Argb.alpha(p) >= 128) Argb.withAlpha(p, 255) else 0
        }
        removeAlphaSpecks(small)
        keepMainBody(small)

        val enhanced = enhance(smooth(small), settings.vibrance)
        val (quantized, palette) = quantize(enhanced, colors)
        despeckle(quantized)
        removeOrphans(quantized)
        val sprite = if (settings.outline) outline(quantized) else pad(quantized, 1)
        return SpriteResult(sprite, palette, photoCrop, removed)
    }

    private val PHOTO_BACKDROP = 0xFFFFE3B8.toInt()

    /**
     * Area-average downscale that keeps small dark features (eyes, nose, stripes). Plain averaging
     * washes a 3-pixel eye into the fur around it; pixel artists instead keep the eye. When a cell
     * holds pixels much darker than its average, the result leans toward that dark colour.
     */
    fun downscaleKeepingFeatures(img: PixelImage, box: DoubleArray, size: Int): PixelImage {
        val out = PixelImage(size, size)
        val step = box[2] / size
        val labs = ArrayList<Lab>()
        for (oy in 0 until size) for (ox in 0 until size) {
            val x0 = (box[0] + ox * step).toInt(); val x1 = maxOf(x0 + 1, (box[0] + (ox + 1) * step).toInt())
            val y0 = (box[1] + oy * step).toInt(); val y1 = maxOf(y0 + 1, (box[1] + (oy + 1) * step).toInt())
            labs.clear()
            var total = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                total++
                if (!img.inBounds(x, y)) continue
                val p = img[x, y]
                if (Argb.alpha(p) >= 128) labs += Lab.fromArgb(p)
            }
            if (total == 0 || labs.size * 2 < total) continue
            var ml = 0.0; var ma = 0.0; var mb = 0.0
            for (p in labs) { ml += p.l; ma += p.a; mb += p.b }
            val n = labs.size
            val mean = Lab(ml / n, ma / n, mb / n)
            labs.sortBy { it.l }
            val k = maxOf(1, n / 5)
            var dl = 0.0; var da = 0.0; var db = 0.0
            for (i in 0 until k) { dl += labs[i].l; da += labs[i].a; db += labs[i].b }
            val dark = Lab(dl / k, da / k, db / k)
            val t = ((mean.l - dark.l - 0.12) / 0.2).coerceIn(0.0, 1.0) * 0.75
            out[ox, oy] = Lab(mean.l + (dark.l - mean.l) * t, mean.a + (dark.a - mean.a) * t, mean.b + (dark.b - mean.b) * t).toArgb()
        }
        return out
    }

    /** Edge-preserving smoothing: averages only with similar neighbours, so fur noise fades but edges stay. */
    fun smooth(img: PixelImage, threshold: Double = 0.006): PixelImage {
        val w = img.width; val h = img.height
        val lab = Array(w * h) { Lab.fromArgb(img.pixels[it]) }
        val out = img.copy()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (Argb.alpha(img.pixels[i]) == 0) continue
            var l = 0.0; var a = 0.0; var b = 0.0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h) continue
                val j = yy * w + xx
                if (Argb.alpha(img.pixels[j]) == 0 || lab[j].dist2(lab[i]) > threshold) continue
                l += lab[j].l; a += lab[j].a; b += lab[j].b; n++
            }
            out.pixels[i] = Lab(l / n, a / n, b / n).toArgb()
        }
        return out
    }

    /**
     * Mode filter on the quantised sprite: a pixel that disagrees with nearly all 8 neighbours takes
     * their majority colour. The darkest palette colour is protected because it carries eyes and nose.
     */
    fun despeckle(img: PixelImage) {
        val src = img.copy()
        val darkest = src.pixels.filter { Argb.alpha(it) > 0 }.distinct().minByOrNull { Lab.fromArgb(it).l } ?: return
        val counts = HashMap<Int, Int>()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = src[x, y]
            if (Argb.alpha(c) == 0 || c == darkest) continue
            counts.clear()
            var same = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (!src.inBounds(xx, yy)) continue
                val q = src[xx, yy]
                if (Argb.alpha(q) == 0) continue
                if (q == c) same++
                counts[q] = (counts[q] ?: 0) + 1
            }
            val best = counts.maxByOrNull { it.value } ?: continue
            if (same <= 1 && best.key != c && best.value >= 5) img[x, y] = best.key
        }
    }

    /** Colour boost + unsharp mask on lightness, in OKLab. */
    fun enhance(img: PixelImage, vibrance: Double): PixelImage {
        val w = img.width; val h = img.height
        val lab = Array(w * h) { Lab.fromArgb(img.pixels[it]) }
        val opaque = BooleanArray(w * h) { Argb.alpha(img.pixels[it]) > 0 }
        val out = img.copy()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!opaque[i]) continue
            var sum = 0.0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && opaque[yy * w + xx]) { sum += lab[yy * w + xx].l; n++ }
            }
            val p = lab[i]
            val l = (p.l + 0.6 * (p.l - sum / n)).coerceIn(0.0, 1.0)
            // Contrast around mid-grey plus chroma boost.
            val lc = (0.55 + (l - 0.55) * 1.08).coerceIn(0.0, 1.0)
            out.pixels[i] = Lab(lc, p.a * vibrance, p.b * vibrance).toArgb()
        }
        return out
    }

    fun quantize(img: PixelImage, colors: Int): Pair<PixelImage, List<Int>> {
        val idx = img.pixels.indices.filter { Argb.alpha(img.pixels[it]) > 0 }
        if (idx.isEmpty()) return img.copy() to emptyList()
        val points = idx.map { Lab.fromArgb(img.pixels[it]) }
        val centers = Quantizer.kMeans(points, colors)
        val out = PixelImage(img.width, img.height)
        val paletteArgb = centers.map { it.toArgb() }
        for ((n, i) in idx.withIndex()) out.pixels[i] = paletteArgb[Quantizer.nearest(centers, points[n])]
        return out to paletteArgb.distinct()
    }

    /** Removes detached bits (a stray paw of another pet, a leaf) so only the largest shape remains. */
    fun keepMainBody(img: PixelImage) {
        val w = img.width; val h = img.height
        val label = IntArray(w * h)
        var best = 0; var bestSize = 0; var next = 0
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (Argb.alpha(img.pixels[start]) == 0 || label[start] != 0) continue
            next++
            var sp = 0; var size = 0
            stack[sp++] = start; label[start] = next
            while (sp > 0) {
                val i = stack[--sp]; size++
                val x = i % w; val y = i / w
                for ((dx, dy) in N4) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx
                    if (label[j] == 0 && Argb.alpha(img.pixels[j]) > 0) { label[j] = next; stack[sp++] = j }
                }
            }
            if (size > bestSize) { bestSize = size; best = next }
        }
        if (next <= 1) return
        for (i in img.pixels.indices) if (label[i] != 0 && label[i] != best) img.pixels[i] = 0
    }

    /** Drops lone opaque pixels and fills lone transparent holes. */
    fun removeAlphaSpecks(img: PixelImage) {
        val src = img.copy()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val opaque = Argb.alpha(src[x, y]) > 0
            var same = 0; var total = 0; var fillFrom = 0
            for ((dx, dy) in N4) {
                val xx = x + dx; val yy = y + dy
                total++
                val o = src.inBounds(xx, yy) && Argb.alpha(src[xx, yy]) > 0
                if (o == opaque) same++
                if (o) fillFrom = src[xx, yy]
            }
            if (same == 0) img[x, y] = if (opaque) 0 else fillFrom
        }
    }

    /** Replaces a pixel whose colour matches none of its 4 neighbours with the most common neighbour colour. */
    fun removeOrphans(img: PixelImage) {
        val src = img.copy()
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = src[x, y]
            if (Argb.alpha(c) == 0) continue
            val neigh = N4.mapNotNull { (dx, dy) ->
                val xx = x + dx; val yy = y + dy
                if (src.inBounds(xx, yy) && Argb.alpha(src[xx, yy]) > 0) src[xx, yy] else null
            }
            if (neigh.size >= 3 && neigh.none { it == c }) {
                img[x, y] = neigh.groupingBy { it }.eachCount().maxBy { it.value }.key
            }
        }
    }

    fun pad(img: PixelImage, p: Int): PixelImage =
        PixelImage(img.width + 2 * p, img.height + 2 * p).also { it.draw(img, p, p) }

    /** Selective outline: transparent pixels touching the sprite become a darkened version of what they touch. */
    fun outline(img: PixelImage): PixelImage {
        val out = pad(img, 1)
        val src = out.copy()
        for (y in 0 until out.height) for (x in 0 until out.width) {
            if (Argb.alpha(src[x, y]) > 0) continue
            val neigh = N4.mapNotNull { (dx, dy) ->
                val xx = x + dx; val yy = y + dy
                if (src.inBounds(xx, yy) && Argb.alpha(src[xx, yy]) > 0) src[xx, yy] else null
            }
            if (neigh.isEmpty()) continue
            val darkest = neigh.minBy { Lab.fromArgb(it).l }
            out[x, y] = darken(darkest)
        }
        return out
    }

    fun darken(c: Int): Int {
        val p = Lab.fromArgb(c)
        val chroma = sqrt(p.a * p.a + p.b * p.b)
        val scale = if (chroma > 1e-6) 0.75 else 1.0
        return Lab(max(0.08, p.l * 0.42), p.a * scale, p.b * scale).toArgb()
    }

    private val N4 = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}
