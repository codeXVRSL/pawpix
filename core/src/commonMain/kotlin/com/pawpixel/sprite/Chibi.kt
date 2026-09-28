package com.pawpixel.sprite

import com.pawpixel.core.Species
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The part of the photo that is the pet's face: centre and side as fractions of the photo's size. */
data class FaceBox(val cx: Double, val cy: Double, val side: Double) {
    /** Square in pixels of an image [w] x [h] (side is a fraction of the shorter edge). */
    fun inPixels(w: Int, h: Int): DoubleArray {
        val s = side * minOf(w, h)
        return doubleArrayOf(cx * w - s / 2, cy * h - s / 2, s, s)
    }

    /** The same square moved (and shrunk if needed) so it lies wholly inside an image of [w] x [h]. */
    fun fitIn(w: Int, h: Int): FaceBox {
        val s = side.coerceIn(0.1, 1.0)
        val short = minOf(w, h).toDouble()
        val hx = s * short / 2 / w
        val hy = s * short / 2 / h
        return FaceBox(cx.coerceIn(hx, 1 - hx), cy.coerceIn(hy, 1 - hy), s)
    }
}

/** The three main fur colours: the most common one, a lighter one (chest, paws) and its shadow. */
data class FurColors(val base: Int, val light: Int, val shade: Int)

enum class Ears(val label: String) {
    POINTY("Pointy ears"), FLOPPY("Floppy ears");
    companion object { fun of(name: String?): Ears? = entries.firstOrNull { it.name == name } }
}

/**
 * What the pet looks like, read from its face in the photo: up to three fur tones and where on
 * the face each tone goes (a coarse [GRID] x [GRID] patch map, e.g. a white muzzle or dark ears).
 */
class PetLook(val tones: List<Int>, private val patches: IntArray) {
    /** Index into [tones] of the most common fur colour. Always 0. */
    val base = 0
    /** A clearly lighter tone for chest and paws, if the pet has one. */
    val light: Int? = tones.indices.drop(1).filter { Lab.fromArgb(tones[it]).l - Lab.fromArgb(tones[0]).l > 0.12 }
        .maxByOrNull { Lab.fromArgb(tones[it]).l }

    /** Tone at a point of the face, [u] and [v] from 0 to 1. */
    fun toneAt(u: Double, v: Double): Int {
        val gx = (u * GRID).toInt().coerceIn(0, GRID - 1)
        val gy = (v * GRID).toInt().coerceIn(0, GRID - 1)
        return patches[gy * GRID + gx]
    }

    companion object {
        const val GRID = 8
        private val DEFAULT = 0xFFB07A4A.toInt()

        fun from(face: PixelImage): PetLook {
            // 1. Median colour (by lightness) of each patch: small dark eyes/nose don't win a patch.
            val cells = arrayOfNulls<Lab>(GRID * GRID)
            for (gy in 0 until GRID) for (gx in 0 until GRID) {
                val x0 = gx * face.width / GRID; val x1 = max(x0 + 1, (gx + 1) * face.width / GRID)
                val y0 = gy * face.height / GRID; val y1 = max(y0 + 1, (gy + 1) * face.height / GRID)
                val labs = ArrayList<Lab>()
                for (y in y0 until y1) for (x in x0 until x1) if (Argb.alpha(face[x, y]) > 0) labs += Lab.fromArgb(face[x, y])
                val area = (x1 - x0) * (y1 - y0)
                if (labs.size * 2 >= area && labs.isNotEmpty()) cells[gy * GRID + gx] = labs.sortedBy { it.l }[labs.size / 2]
            }
            val known = cells.filterNotNull()
            if (known.isEmpty()) return PetLook(listOf(DEFAULT), IntArray(GRID * GRID))

            // 2. Up to three tones (k-means), merging ones that are only lighting differences.
            var centres = kMeans(known, 3)
            while (true) {
                var pair: Pair<Int, Int>? = null; var best = Double.MAX_VALUE
                for (i in centres.indices) for (j in i + 1 until centres.size) {
                    val d = sqrt(centres[i].first.dist2(centres[j].first))
                    if (d < best) { best = d; pair = i to j }
                }
                val small = centres.indices.firstOrNull { centres[it].second < known.size * 0.1 }
                val (i, j) = when {
                    pair != null && best < 0.13 -> pair
                    small != null && centres.size > 1 -> small to centres.indices.filter { it != small }
                        .minBy { centres[it].first.dist2(centres[small].first) }
                    else -> break
                }
                val (a, na) = centres[i]; val (b, nb) = centres[j]
                val n = na + nb
                val merged = Lab((a.l * na + b.l * nb) / n, (a.a * na + b.a * nb) / n, (a.b * na + b.b * nb) / n) to n
                centres = centres.filterIndexed { k, _ -> k != i && k != j } + merged
            }
            centres = centres.sortedByDescending { it.second }
            val labs = centres.map { it.first }

            // 3. Assign each patch its tone; unknown patches get the main one; drop lone specks.
            var grid = IntArray(GRID * GRID) { k -> cells[k]?.let { c -> labs.indices.minBy { labs[it].dist2(c) } } ?: 0 }
            grid = despeckle(grid)
            val tones = labs.map { l ->
                // A touch more colour, as pixel artists do.
                Lab(max(l.l, 0.3), l.a * 1.12, l.b * 1.12).toArgb()
            }
            return PetLook(tones, grid)
        }

        private fun despeckle(g: IntArray): IntArray {
            val out = g.copyOf()
            for (y in 0 until GRID) for (x in 0 until GRID) {
                val me = g[y * GRID + x]
                val counts = HashMap<Int, Int>()
                var same = 0
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val xx = x + dx; val yy = y + dy
                    if (xx !in 0 until GRID || yy !in 0 until GRID) continue
                    val t = g[yy * GRID + xx]
                    if (t == me) same++ else counts[t] = (counts[t] ?: 0) + 1
                }
                if (same == 0 && counts.isNotEmpty()) out[y * GRID + x] = counts.maxBy { it.value }.key
            }
            return out
        }

        private fun kMeans(points: List<Lab>, k: Int): List<Pair<Lab, Int>> {
            // Farthest-point start, deterministic.
            val c = ArrayList<Lab>()
            c += points.sortedBy { it.l }[points.size / 2]
            while (c.size < k) {
                val far = points.maxBy { p -> c.minOf { it.dist2(p) } }
                if (c.minOf { it.dist2(far) } < 1e-6) break
                c += far
            }
            var assign = IntArray(points.size)
            repeat(12) {
                assign = IntArray(points.size) { i -> c.indices.minBy { c[it].dist2(points[i]) } }
                for (j in c.indices) {
                    val mine = points.filterIndexed { i, _ -> assign[i] == j }
                    if (mine.isNotEmpty()) c[j] = Lab(mine.sumOf { it.l } / mine.size, mine.sumOf { it.a } / mine.size, mine.sumOf { it.b } / mine.size)
                }
            }
            return c.indices.map { j -> c[j] to assign.count { it == j } }.filter { it.second > 0 }
        }
    }
}

/** Everything needed to draw a pet: its face (a colour source only), species and ear shape. */
class PetArt(val head: PixelImage, val species: Species, ears: Ears? = null) {
    val ears: Ears = ears ?: if (species == Species.CAT) Ears.POINTY else Ears.FLOPPY
    val look: PetLook = PetLook.from(head)
    val fur: FurColors get() {
        val base = look.tones[0]
        val light = look.light?.let { look.tones[it] } ?: Chibi.ramp(base).hi
        return FurColors(base, light, Chibi.ramp(base).shade)
    }
    /** The standing pose, for widgets, poses and the reveal card. */
    val still: PixelImage by lazy { Chibi.compose(this, Chibi.Pose()) }
}

/**
 * The pixel pet, drawn entirely in one hand-made chibi style: big round head (about half its
 * height), stubby legs, a tail, big glossy eyes. Only the colours and markings come from the photo,
 * so every pet looks like part of the same game while still looking like *your* pet.
 *
 * Fixed 41 x 34 canvas, light from the top-left, three-tone shading per fur colour, a soft
 * coloured outline instead of black.
 */
object Chibi {
    const val WIDTH = 41
    const val HEIGHT = 34

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
        val eyesClosed: Boolean = false,
    )

    data class Ramp(val hi: Int, val mid: Int, val shade: Int, val deep: Int)

    fun ramp(c: Int): Ramp {
        val p = Lab.fromArgb(c)
        // Shadows shift cooler, highlights warmer: the classic pixel-art ramp.
        val hi = Lab(min(0.97, p.l + 0.075), p.a * 0.95, p.b * 0.95 + 0.012)
        // Light fur (white, cream) gets a softer shadow so it doesn't turn grey.
        val sh = Lab(max(0.1, max(p.l * 0.8 - 0.03, p.l - 0.13)), p.a * 1.05, p.b * 1.05 - 0.02)
        val dp = Lab(max(0.07, p.l * 0.6 - 0.05), p.a * 1.05, p.b * 1.05 - 0.035)
        return Ramp(hi.toArgb(), c, sh.toArgb(), dp.toArgb())
    }

    // Parts, for the inner lines
    private const val NONE = 0
    private const val BODY = 1
    private const val HEAD = 2
    private const val LEG = 3
    private const val TAIL = 4

    private const val EYE_DARK = 0xFF2A1E2E.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val PINK = 0xFFF09AA8.toInt()
    private const val NOSE_DOG = 0xFF33242A.toInt()

    fun compose(art: PetArt, pose: Pose): PixelImage {
        val look = art.look
        val ramps = look.tones.map { ramp(it) }
        val baseR = ramps[0]
        val lightR = look.light?.let { ramps[it] }
        val cat = art.species == Species.CAT
        val w = WIDTH; val h = HEIGHT
        val col = IntArray(w * h)
        val part = IntArray(w * h)

        fun put(x: Int, y: Int, c: Int, p: Int) { if (x in 0 until w && y in 0 until h) { col[y * w + x] = c; part[y * w + x] = p } }
        fun partAt(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) part[y * w + x] else NONE

        /** Filled ellipse (superellipse with exponent [e]); [paint] gets normalised coordinates. */
        fun blob(cx: Double, cy: Double, rx: Double, ry: Double, p: Int, e: Double = 2.0, paint: (Int, Int, Double, Double) -> Int) {
            for (y in (cy - ry - 1).toInt()..(cy + ry + 1).toInt()) for (x in (cx - rx - 1).toInt()..(cx + rx + 1).toInt()) {
                val nx = (x + 0.5 - cx) / rx; val ny = (y + 0.5 - cy) / ry
                if (abs(nx).pow(e) + abs(ny).pow(e) <= 1.0) put(x, y, paint(x, y, nx, ny), p)
            }
        }
        /** Light from the top-left. [rim]: shadow only this far out from the centre (0 = anywhere). */
        fun shaded(r: Ramp, nx: Double, ny: Double, hiAt: Double = 0.7, shAt: Double = 0.55, rim: Double = 0.0): Int = when {
            nx * 0.45 + ny * 0.9 > shAt && nx * nx + ny * ny > rim -> r.shade
            -nx * 0.55 - ny * 0.85 > hiAt -> r.hi
            else -> r.mid
        }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, c: (Int, Int) -> Int, p: Int) {
            for (y in y0..y1) for (x in x0..x1) put(x, y, c(x, y), p)
        }

        val cx = 20.5
        val oy = pose.bob
        val light = lightR ?: baseR
        val pawR = lightR ?: baseR

        // ---------- Head geometry (shared by standing and lying) ----------
        val hcx = cx + pose.headDx
        val hcy = 12.6 + oy + pose.headDy + (if (pose.lying) 8.0 else 0.0)
        val hrx = 10.6; val hry = 8.4
        fun faceTone(x: Int, y: Int): Int = look.toneAt((x + 0.5 - (hcx - hrx)) / (2 * hrx), (y + 0.5 - (hcy - hry)) / (2 * hry))

        if (!pose.lying) {
            val bcy = 25.0 + oy - pose.breathe * 0.3
            val brx = 8.2; val bry = 5.6 + pose.breathe * 0.4
            val feet = 32
            // Back legs peeking out at the sides, in shadow.
            for ((i, lx) in listOf(12, 26).withIndex()) {
                val bottom = feet - pose.legs[2 + i]
                rect(lx, 27 + oy, lx + 2, bottom, { _, y -> if (y >= bottom - 1) pawR.shade else baseR.shade }, LEG)
            }
            // Tail, behind the body on the right.
            tail(cat, pose.tail, 27.5, 25.5 + oy, baseR, lightR, ::put)
            blob(cx, bcy, brx, bry, BODY) { _, _, nx, ny -> shaded(baseR, nx, ny, hiAt = 0.75, shAt = 0.5) }
            // Light chest, if the pet has a light tone.
            if (lightR != null) blob(cx, bcy - 1.0, 3.6, 3.4, BODY) { _, _, nx, ny -> if (ny > 0.55) lightR.mid else if (ny < -0.5 && nx < 0) lightR.hi else lightR.mid }
            // Front legs.
            for ((i, lx) in listOf(15, 23).withIndex()) {
                val bottom = feet - pose.legs[i]
                rect(lx, 27 + oy, lx + 2, bottom, { x, y ->
                    when {
                        y >= bottom - 1 -> if (x == lx + 2) pawR.shade else pawR.mid
                        x == lx + 2 -> baseR.shade
                        else -> baseR.mid
                    }
                }, LEG)
                // Rounded toes.
                put(lx + 1, bottom, if (Argb.alpha(col[bottom * w + lx + 1]) > 0) col[bottom * w + lx + 1] else pawR.mid, LEG)
            }
        } else {
            val bcy = 27.8 - pose.breathe * 0.3
            tail(cat, 0, 29.5, 29.0, baseR, lightR, ::put, lying = true)
            blob(cx, bcy, 11.2, 4.8 + pose.breathe * 0.4, BODY, e = 2.3) { _, _, nx, ny -> shaded(baseR, nx, ny, hiAt = 0.8, shAt = 0.5) }
            for (px in listOf(15.5, 25.5)) blob(px, 31.6, 2.2, 1.3, LEG) { _, _, nx, _ -> if (nx > 0.5) pawR.shade else pawR.mid }
        }

        // ---------- Ears behind/around the head ----------
        val earToneL = ramps[look.toneAt(0.2, 0.1)]
        val earToneR = ramps[look.toneAt(0.8, 0.1)]
        val pointy = art.ears == Ears.POINTY
        if (pointy) {
            for (side in listOf(-1, 1)) {
                val r = if (side < 0) earToneL else earToneR
                // Triangle: apex, and two base points on the head's top edge.
                val ax = hcx + side * (if (cat) 8.4 else 7.9); val ay = hcy - 11.8
                val b1x = hcx + side * 10.4; val b1y = hcy - 4.2
                val b2x = hcx + side * 3.4; val b2y = hcy - 7.6
                for (y in (ay - 1).toInt()..(b1y + 1).toInt()) for (x in (hcx - 13).toInt()..(hcx + 13).toInt()) {
                    val px = x + 0.5; val py = y + 0.5
                    if (!inTri(px, py, ax, ay, b1x, b1y, b2x, b2y)) continue
                    // Inner ear: a smaller triangle towards the middle.
                    val inner = inTri(px, py, ax + side * -0.3, ay + 2.6, b1x - side * 2.2, b1y - 0.4, b2x + side * 1.8, b2y + 0.4)
                    val outerEdge = if (side < 0) px < (ax + b1x) / 2 + 0.5 else px > (ax + b1x) / 2 - 0.5
                    put(x, y, when {
                        inner -> Argb.mix(PINK, r.mid, if (cat) 0.35 else 0.55)
                        side > 0 && outerEdge -> r.shade
                        else -> r.mid
                    }, HEAD)
                }
            }
        }

        // ---------- Head ----------
        blob(hcx, hcy, hrx, hry, HEAD, e = 2.4) { x, y, nx, ny -> shaded(ramps[faceTone(x, y)], nx, ny, hiAt = 0.8, shAt = 0.62, rim = 0.6) }

        // Muzzle: a lighter, rounder snout for dogs; a small soft one for cats.
        val mcy = hcy + 3.7
        val (mrx, mry) = if (cat) 3.0 to 1.9 else 3.9 to 2.5
        blob(hcx, mcy, mrx, mry, HEAD) { x, y, nx, ny ->
            val r = ramps[faceTone(x, y)]
            if (ny > 0.55 && nx > -0.2) r.mid else r.hi
        }

        if (!pointy) {
            // Floppy ears: attached at the top of the head, hanging down past the cheeks.
            for (side in listOf(-1, 1)) {
                val r = if (side < 0) earToneL else earToneR
                val n = 12
                for (k in 0..n) {
                    val t = k.toDouble() / n
                    val px = hcx + side * (7.4 + 3.2 * t - 0.8 * t * t)
                    val py = hcy - 6.9 + 9.2 * t
                    val rad = 1.4 + 1.3 * sqrt(t)
                    for (y in (py - rad - 1).toInt()..(py + rad + 1).toInt()) for (x in (px - rad - 1).toInt()..(px + rad + 1).toInt()) {
                        val dx = x + 0.5 - px; val dy = y + 0.5 - py
                        if (dx * dx + dy * dy > rad * rad) continue
                        val c = when {
                            t > 0.8 && dy > rad * 0.2 -> r.deep
                            dx * side > rad * 0.35 -> r.deep
                            dx * side < -rad * 0.3 && t < 0.5 -> r.mid
                            else -> r.shade
                        }
                        put(x, y, c, HEAD)
                    }
                }
            }
        }

        // ---------- Face ----------
        val ex = listOf((hcx - 6.0).toInt(), (hcx + 5.0).toInt())
        val eyTop = (hcy - 0.6).toInt()
        // Dark fur round the eyes: coloured irises (both eyes alike) so they still show.
        val darkEyes = ex.map { Lab.fromArgb(ramps[faceTone(it, eyTop + 1)].mid).l }.average() < 0.42
        for (x0 in ex) {
            if (pose.eyesClosed) {
                // Content, closed eyes: a little downward curve.
                put(x0, eyTop + 1, EYE_DARK, HEAD); put(x0 + 1, eyTop + 1, EYE_DARK, HEAD)
                put(x0 - 1, eyTop, ramps[faceTone(x0 - 1, eyTop)].deep, HEAD); put(x0 + 2, eyTop, ramps[faceTone(x0 + 2, eyTop)].deep, HEAD)
            } else {
                val iris = if (darkEyes) (if (cat) 0xFFB8D24A.toInt() else 0xFFC98A3C.toInt()) else EYE_DARK
                for (dy in 0..2) for (dx in 0..1) put(x0 + dx, eyTop + dy, iris, HEAD)
                if (iris != EYE_DARK) put(x0 + 1, eyTop + 1, EYE_DARK, HEAD)
                put(x0, eyTop, WHITE, HEAD)
            }
        }
        // Blush.
        for (x0 in listOf(ex[0] - 2, ex[1] + 2)) {
            val y = eyTop + 3
            for (dx in 0..1) { val i = y * w + x0 + dx; if (i in col.indices && part[i] == HEAD) col[i] = Argb.mix(col[i], PINK, 0.45) }
        }
        // Nose and mouth.
        val nx0 = (hcx - 0.5).toInt(); val ny0 = (mcy - 1.2).toInt()
        val noseC = if (cat) Argb.mix(PINK, 0xFFB0506A.toInt(), 0.35) else NOSE_DOG
        put(nx0 - 1, ny0, noseC, HEAD); put(nx0, ny0, noseC, HEAD); put(nx0 + 1, ny0, noseC, HEAD); put(nx0, ny0 + 1, noseC, HEAD)
        if (!cat) put(nx0 - 1, ny0, 0xFF6A5560.toInt(), HEAD) // tiny shine on the nose
        val mouthC = ramps[faceTone(nx0, ny0 + 2)].deep
        put(nx0 - 1, ny0 + 2, mouthC, HEAD); put(nx0 + 1, ny0 + 2, mouthC, HEAD)

        // ---------- Inner lines: under the chin, between legs and body ----------
        val out = col.copyOf()
        for (y in 1 until h) for (x in 0 until w) {
            val i = y * w + x
            if (part[i] == NONE || part[i] == HEAD) continue
            if (partAt(x, y - 1) == HEAD) out[i] = ramp(col[i]).let { Argb.mix(it.deep, it.shade, 0.3) }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (part[i] != LEG) continue
            // Gap between a leg and the next part of the same colour.
            if ((partAt(x - 1, y) == LEG) != (partAt(x + 1, y) == LEG) && partAt(x - 1, y) != NONE && partAt(x + 1, y) != NONE) { /* keep */ }
        }

        // ---------- Outline: a deep version of the fur next to it, never pure black ----------
        val img = PixelImage(w, h, out)
        val res = img.copy()
        for (y in 0 until h) for (x in 0 until w) {
            if (Argb.alpha(img[x, y]) > 0) continue
            var best: Int? = null; var bestL = 9.0
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val xx = x + dx; val yy = y + dy
                if (!img.inBounds(xx, yy) || Argb.alpha(img[xx, yy]) == 0) continue
                val l = Lab.fromArgb(img[xx, yy]).l
                if (l < bestL) { bestL = l; best = img[xx, yy] }
            }
            if (best != null) res[x, y] = outlineOf(best, baseR)
        }
        return res
    }

    private fun outlineOf(c: Int, base: Ramp): Int {
        val p = Lab.fromArgb(Argb.mix(c, base.mid, 0.5))
        return Lab(max(0.13, min(0.3, p.l * 0.38)), p.a * 0.7, p.b * 0.7 - 0.02).toArgb()
    }

    private fun inTri(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Boolean {
        fun s(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) = (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3)
        val d1 = s(px, py, ax, ay, bx, by); val d2 = s(px, py, bx, by, cx, cy); val d3 = s(px, py, cx, cy, ax, ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0; val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    private fun tail(cat: Boolean, swing: Int, bx: Double, by: Double, r: Ramp, light: Ramp?, put: (Int, Int, Int, Int) -> Unit, lying: Boolean = false) {
        val pts: List<DoubleArray> = if (lying) {
            (0..12).map { k ->
                val t = k / 12.0
                // Along the ground, the tip curling up.
                doubleArrayOf(bx + t * 6.0, by + 1.8 - t * t * t * 4.5, (if (cat) 1.25 else 1.55) * (1 - 0.3 * t), t)
            }
        } else {
            val tipX = bx + (if (cat) 7.0 else 5.0) + swing * 2.0
            val tipY = by - (if (cat) 11.0 else 6.5)
            val cX = bx + (if (cat) 8.0 else 5.5); val cY = by + 0.5
            val n = 16
            (0..n).map { k ->
                val t = k.toDouble() / n
                doubleArrayOf(
                    (1 - t) * (1 - t) * bx + 2 * (1 - t) * t * cX + t * t * tipX,
                    (1 - t) * (1 - t) * by + 2 * (1 - t) * t * cY + t * t * tipY,
                    (if (cat) 1.25 else 1.75) * (1 - 0.35 * t), t,
                )
            }
        }
        for (p in pts) {
            val (px, py, rad, t) = listOf(p[0], p[1], p[2], p[3])
            for (y in (py - rad - 1).toInt()..(py + rad + 1).toInt()) for (x in (px - rad - 1).toInt()..(px + rad + 1).toInt()) {
                val dx = x + 0.5 - px; val dy = y + 0.5 - py
                if (dx * dx + dy * dy > rad * rad) continue
                val ramp = if (!cat && t > 0.78 && light != null) light else r
                put(x, y, if (dx > rad * 0.25) ramp.shade else ramp.mid, TAIL)
            }
        }
    }

    /** The full animation set for a pet. [eyes] is ignored (kept for older callers): the eyes are drawn. */
    @Suppress("UNUSED_PARAMETER")
    fun build(art: PetArt, eyes: List<Pair<Int, Int>> = emptyList()): AnimationSet {
        fun c(p: Pose) = compose(art, p)
        val base = c(Pose())
        val pad = 5
        fun padded(img: PixelImage) = PixelImage(img.width + 2 * pad, img.height + 2 * pad).also { it.draw(img, pad, pad) }

        val frames = LinkedHashMap<Frame, PixelImage>()
        val baseP = padded(base)
        val box = Animator.opaqueBounds(baseP) ?: intArrayOf(0, 0, baseP.width, baseP.height)
        frames[Frame.BASE] = baseP
        frames[Frame.BREATHE] = padded(c(Pose(breathe = 1, tail = 1)))
        frames[Frame.BLINK] = padded(c(Pose(eyesClosed = true)))
        frames[Frame.SQUASH] = Animator.scaleFromFeet(baseP, box, 1.08, 0.9)
        frames[Frame.STRETCH] = Animator.scaleFromFeet(baseP, box, 0.94, 1.07)
        frames[Frame.WALK_1] = padded(c(Pose(legs = intArrayOf(1, 0, 0, 1), tail = 1)))
        frames[Frame.WALK_2] = padded(c(Pose(bob = -1, tail = 0)))
        frames[Frame.WALK_3] = padded(c(Pose(legs = intArrayOf(0, 1, 1, 0), tail = -1)))
        frames[Frame.WALK_4] = padded(c(Pose(bob = -1, tail = 0)))
        frames[Frame.LOOK_LEFT] = padded(c(Pose(headDx = -1, tail = -1)))
        frames[Frame.LOOK_RIGHT] = padded(c(Pose(headDx = 1, tail = 1)))
        frames[Frame.EAT_DOWN] = padded(c(Pose(headDy = 2, tail = 1)))
        Frame.SHAKE.forEachIndexed { i, f -> frames[f] = Animator.wave(baseP, box, 2, i * kotlin.math.PI / 2) }
        frames[Frame.SLEEP] = padded(c(Pose(lying = true, eyesClosed = true)))
        frames[Frame.SLEEP_BREATHE] = padded(c(Pose(lying = true, breathe = 1, eyesClosed = true)))
        return AnimationSet(frames, emptyList(), pad)
    }

    /** A still of the pet curled up asleep, for the sleeping widget pose. */
    @Suppress("UNUSED_PARAMETER")
    fun sleeping(art: PetArt, eyes: List<Pair<Int, Int>> = emptyList()): PixelImage = compose(art, Pose(lying = true, eyesClosed = true))
}
