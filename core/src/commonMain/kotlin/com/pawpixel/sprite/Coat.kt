package com.pawpixel.sprite

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Body markings read from the whole photo, beyond what the face map holds: tabby stripes, white
 * socks, and patches of a second colour on the body. "From the photo" draws them on the chibi.
 * Travels in the look code as letters (T, S, P plus the patch's tone index), so a pal's or a
 * household's copy of the pet has the same coat.
 */
data class Coat(val stripes: Boolean = false, val socks: Boolean = false, val patchTone: Int? = null) {
    val isNone: Boolean get() = !stripes && !socks && patchTone == null

    fun encode(): String = buildString {
        if (stripes) append('T')
        if (socks) append('S')
        if (patchTone != null) append('P').append(patchTone)
    }

    companion object {
        val NONE = Coat()

        /** Reads [encode]'s letters; anything else is ignored (a newer build's marking reads as none). */
        fun decode(code: String): Coat {
            if (code.length > 8 || !code.all { it.isLetterOrDigit() }) return NONE
            val p = code.indexOf('P')
            val tone = if (p >= 0) code.getOrNull(p + 1)?.digitToIntOrNull()?.takeIf { it < PetLook.MAX_TONES } else null
            return Coat(stripes = 'T' in code, socks = 'S' in code, patchTone = tone)
        }
    }
}

/** Finds a [Coat] in a photo, given the pet's cut-out and where its face is. */
object CoatDetector {
    /** The raw numbers behind a decision; kept separate so the thresholds can be checked on real photos. */
    data class Measures(
        /** Share of forehead fur on a thin dark line (lightness a little below its surroundings, relative to them). */
        val stripeScore: Double,
        /** The forehead's median lightness: tabby browns, greys and oranges sit in the middle. */
        val foreheadL: Double,
        /** How much lighter the paws are than the body (OKLab lightness), or null when the paws aren't in the photo. */
        val pawLift: Double?,
        val pawL: Double?,
        /** The body's second colour: its share of the body, its lightness and colour distance from the main one. */
        val patchShare: Double?,
        val patchLightDist: Double?,
        val patchColorDist: Double?,
        val patchColor: Lab?,
    )

    const val STRIPES_AT = 0.2
    val STRIPE_L = 0.38..0.82
    const val SOCKS_LIFT = 0.17
    const val SOCKS_MIN_L = 0.72
    val PATCH_SHARE = 0.18..0.48

    /**
     * Stripes are decided from the forehead only, and only drawn on cats (see [Chibi]): fur texture and
     * a dog's coat read like lines too often, and a wrong tabby is worse than none (Studio has Tabby).
     */
    fun detect(img: PixelImage, mask: Mask, face: DoubleArray, look: PetLook, removed: Boolean): Coat {
        val m = measure(img, mask, face, bodyToo = removed)
        val stripes = m.stripeScore >= STRIPES_AT && m.foreheadL in STRIPE_L
        val socks = m.pawLift != null && m.pawL != null && m.pawLift >= SOCKS_LIFT && m.pawL >= SOCKS_MIN_L
        // A second colour on the body, not just shade (a white dog's shadow is about 0.23 darker): a different hue, or much darker or lighter.
        val patchSeen = m.patchShare != null && m.patchColor != null && m.patchShare in PATCH_SHARE &&
            ((m.patchColorDist ?: 0.0) >= 0.06 || (m.patchLightDist ?: 0.0) >= 0.3)
        val patch = if (patchSeen) {
            // Drawn in the look's own tone nearest that colour, if one is close enough.
            look.tones.indices.drop(1).minByOrNull { Lab.fromArgb(look.tones[it]).dist2(m.patchColor!!) }
                ?.takeIf { sqrt(Lab.fromArgb(look.tones[it]).dist2(m.patchColor!!)) < 0.16 }
        } else null
        return Coat(stripes, socks, patch)
    }

    /** [face] is the face square in pixels: x, y, side, side. */
    fun measure(img: PixelImage, mask: Mask, face: DoubleArray, bodyToo: Boolean): Measures {
        val w = img.width; val h = img.height
        val side = face[2]; val fx = face[0]; val fy = face[1]
        val b = mask.bounds() ?: intArrayOf(0, 0, w, h)
        val faceBottom = fy + side

        // Stripes: a thin dark line is darker than the fur a few pixels around it (fine blur vs coarse blur).
        val fine = max(1, (side / 70).toInt()); val coarse = max(fine + 2, (side / 16).toInt())
        // The forehead, between the ears and above the eyes, where a tabby has its "M".
        val x0 = (fx + side * 0.3).toInt(); val x1 = (fx + side * 0.7).toInt()
        val y0 = (fy + side * 0.14).toInt(); val y1 = (fy + side * 0.36).toInt()
        // Only the forehead and a blur's reach around it are read (this runs on every drag of the face square).
        val rx0 = (x0 - coarse - 1).coerceIn(0, w); val rx1 = (x1 + coarse + 1).coerceIn(rx0, w)
        val ry0 = (y0 - coarse - 1).coerceIn(0, h); val ry1 = (y1 + coarse + 1).coerceIn(ry0, h)
        val rw = rx1 - rx0; val rh = ry1 - ry0
        val lum = FloatArray(rw * rh) { i -> Lab.fromArgb(img[rx0 + i % rw, ry0 + i / rw]).l.toFloat() }
        val f = boxBlur(lum, rw, rh, fine); val c = boxBlur(lum, rw, rh, coarse)
        var on = 0; var total = 0
        val ls = ArrayList<Double>()
        for (y in y0 until y1) for (x in x0 until x1) {
            if (x !in 0 until w || y !in 0 until h || mask[x, y] < 0.5f) continue
            val i = (y - ry0) * rw + (x - rx0)
            total++; ls += lum[i].toDouble()
            if ((f[i] - c[i]) / (c[i] + 0.12f) < -0.08f) on++
        }
        val stripeScore = if (total < 40) 0.0 else on.toDouble() / total
        val foreheadL = if (ls.isEmpty()) 0.0 else median(ls)
        val none = Measures(stripeScore, foreheadL, null, null, null, null, null, null)
        if (!bodyToo || b[3] - faceBottom < side * 0.55) return none

        // Body and paws: the middle of the body, and the bottom tenth of the cut-out.
        val bodyLabs = ArrayList<Lab>(); val pawLabs = ArrayList<Lab>()
        val pawTop = b[3] - (b[3] - b[1]) * 0.1
        for (y in faceBottom.toInt().coerceAtLeast(0) until b[3]) for (x in b[0] until b[2]) {
            if (!insideBy(mask, x, y, 2)) continue
            val lab = Lab.fromArgb(img[x, y])
            if (y >= pawTop) pawLabs += lab
            else if (y < b[3] - (b[3] - faceBottom) * 0.3) bodyLabs += lab
        }
        if (bodyLabs.size < 60) return none
        val bodyL = median(bodyLabs.map { it.l })
        val pawL = if (pawLabs.size >= 20) percentile(pawLabs.map { it.l }, 0.6) else null

        // Two colours on the body: the share of the smaller one and how far it is from the main one.
        val sample = if (bodyLabs.size > 4000) bodyLabs.filterIndexed { i, _ -> i % (bodyLabs.size / 4000 + 1) == 0 } else bodyLabs
        val two = Quantizer.kMeans(sample, 2)
        if (two.size < 2) return none.copy(pawLift = pawL?.let { it - bodyL }, pawL = pawL)
        val n0 = sample.count { Quantizer.nearest(two, it) == 0 }
        val (main, minor) = if (n0 * 2 >= sample.size) two[0] to two[1] else two[1] to two[0]
        return none.copy(
            pawLift = pawL?.let { it - bodyL }, pawL = pawL,
            patchShare = min(n0, sample.size - n0).toDouble() / sample.size,
            patchLightDist = abs(main.l - minor.l),
            patchColorDist = sqrt((main.a - minor.a) * (main.a - minor.a) + (main.b - minor.b) * (main.b - minor.b)),
            patchColor = minor,
        )
    }

    private fun insideBy(mask: Mask, x: Int, y: Int, r: Int): Boolean {
        if (mask[x, y] < 0.5f) return false
        for ((dx, dy) in listOf(r to 0, -r to 0, 0 to r, 0 to -r)) {
            val xx = x + dx; val yy = y + dy
            if (xx !in 0 until mask.width || yy !in 0 until mask.height || mask[xx, yy] < 0.5f) return false
        }
        return true
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) {
            var s = 0f; var n = 0
            for (x in -r until w + r) {
                val add = x + r; if (add in 0 until w) { s += src[y * w + add]; n++ }
                val drop = x - r - 1; if (drop in 0 until w) { s -= src[y * w + drop]; n-- }
                if (x in 0 until w) tmp[y * w + x] = s / n
            }
        }
        for (x in 0 until w) {
            var s = 0f; var n = 0
            for (y in -r until h + r) {
                val add = y + r; if (add in 0 until h) { s += tmp[add * w + x]; n++ }
                val drop = y - r - 1; if (drop in 0 until h) { s -= tmp[drop * w + x]; n-- }
                if (y in 0 until h) out[y * w + x] = s / n
            }
        }
        return out
    }

    private fun median(v: List<Double>) = percentile(v, 0.5)
    private fun percentile(v: List<Double>, p: Double): Double = v.sorted()[((v.size - 1) * p).toInt()]
}
