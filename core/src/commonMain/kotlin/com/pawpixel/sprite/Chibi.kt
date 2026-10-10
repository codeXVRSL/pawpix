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
 * What the pet looks like, read from its face in the photo: up to four fur tones and where on
 * the face each tone goes (a [GRID] x [GRID] patch map: a white muzzle, dark ears, a stripe, a patch).
 */
class PetLook(val tones: List<Int>, private val patches: IntArray, val style: PetStyle = PetStyle.DEFAULT) {
    /** Index into [tones] of the most common fur colour. Always 0. */
    val base = 0
    /** A clearly lighter tone for chest and paws, if the pet has one. */
    val light: Int? = tones.indices.drop(1).filter { Lab.fromArgb(tones[it]).l - Lab.fromArgb(tones[0]).l > 0.12 }
        .maxByOrNull { Lab.fromArgb(tones[it]).l }

    /**
     * A short text form of the look (about 90 characters, 150 with a Studio style): what the pet map
     * and a household share instead of any photo. Format: `1;<tone>,<tone>,...;<64 patch digits>`,
     * tones as RGB hex; `2;...;...;<style>` when the owner styled the pet (see [PetStyle.encode]).
     */
    fun encode(): String {
        val base = tones.joinToString(",") { (it and 0xFFFFFF).toString(16).padStart(6, '0') } + ";" + patches.joinToString("") { it.toString() }
        return if (style.isDefault) "3;$base" else "4;$base;${style.encode()}"
    }

    /** The same colours and markings with another Studio style. */
    fun withStyle(s: PetStyle): PetLook = PetLook(tones, patches, s)

    /** Tone at a point of the face, [u] and [v] from 0 to 1. */
    fun toneAt(u: Double, v: Double): Int {
        val gx = (u * GRID).toInt().coerceIn(0, GRID - 1)
        val gy = (v * GRID).toInt().coerceIn(0, GRID - 1)
        return patches[gy * GRID + gx]
    }

    companion object {
        const val GRID = 10
        /** Looks from older builds (formats 1 and 2) have an 8 x 8 map and at most 3 tones. */
        private const val OLD_GRID = 8
        const val MAX_TONES = 4

        /** Reads [encode]'s format (and the older 8 x 8 one); null if it isn't a valid look (a bad client). */
        fun decode(code: String): PetLook? {
            val parts = code.split(';')
            val old = parts[0] == "1" || parts[0] == "2"
            val style = when {
                parts.size == 3 && (parts[0] == "1" || parts[0] == "3") -> PetStyle.DEFAULT
                parts.size == 4 && (parts[0] == "2" || parts[0] == "4") -> PetStyle.decode(parts[3]) ?: return null
                else -> return null
            }
            val tones = parts[1].split(',').map { h ->
                if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                h.toInt(16) or (0xFF shl 24)
            }
            if (tones.isEmpty() || tones.size > (if (old) 3 else MAX_TONES)) return null
            val grid = if (old) OLD_GRID else GRID
            if (parts[2].length != grid * grid) return null
            val read = IntArray(grid * grid) { i ->
                val d = parts[2][i] - '0'
                if (d !in tones.indices) return null
                d
            }
            // An old 8 x 8 map is stretched onto today's grid (nearest cell).
            val patches = if (!old) read else IntArray(GRID * GRID) { i ->
                val gx = (i % GRID) * OLD_GRID / GRID; val gy = (i / GRID) * OLD_GRID / GRID
                read[gy * OLD_GRID + gx]
            }
            return PetLook(tones, patches, style)
        }
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

            // 2. Up to four tones (k-means), merging ones that are only lighting differences.
            var centres = kMeans(known, MAX_TONES)
            while (true) {
                var pair: Pair<Int, Int>? = null; var best = Double.MAX_VALUE
                for (i in centres.indices) for (j in i + 1 until centres.size) {
                    val d = sqrt(centres[i].first.dist2(centres[j].first))
                    if (d < best) { best = d; pair = i to j }
                }
                val small = centres.indices.firstOrNull { centres[it].second < known.size * 0.06 }
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
class PetArt(look: PetLook, val species: Species, ears: Ears? = null, val accessory: Accessory? = null, style: PetStyle? = null) {
    /** From the pet's face in the photo (its colours and markings). */
    constructor(head: PixelImage, species: Species, ears: Ears? = null, accessory: Accessory? = null, style: PetStyle? = null) :
        this(PetLook.from(head), species, ears, accessory, style)

    /** The Studio style: given here, or the one that travelled inside the look code. */
    val style: PetStyle = style ?: look.style
    val look: PetLook = look.withStyle(this.style)
    val ears: Ears = ears ?: if (species == Species.CAT || species == Species.RABBIT) Ears.POINTY else Ears.FLOPPY
    val fur: FurColors get() {
        val base = style.furBase ?: look.tones[0]
        val light = style.furLight ?: look.light?.let { look.tones[it] } ?: Chibi.ramp(base).hi
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
 * A 41 x 34 canvas (rabbits get four more rows on top for their ears), light from the top-left, three-tone shading per fur colour, a soft
 * coloured outline instead of black.
 */
object Chibi {
    const val WIDTH = 41
    const val HEIGHT = 34

    data class Pose(
        /** Lift per leg in pixels: front-left, front-right, back-left, back-right. */
        val legs: List<Int> = listOf(0, 0, 0, 0), // a List, so two equal poses are equal
        val breathe: Int = 0,
        /** Tail swing: -1 (left), 0, 1 (right). */
        val tail: Int = 0,
        val headDx: Int = 0,
        val headDy: Int = 0,
        val bob: Int = 0,
        val lying: Boolean = false,
        val eyesClosed: Boolean = false,
        /** One ear flicks up a pixel: -1 the left ear, 1 the right one. */
        val earTwitch: Int = 0,
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
        val style = art.style
        val cat = art.species == Species.CAT
        // A rabbit: a smaller head sitting lower on a round body, long ears, big back feet and a puff tail.
        val rabbit = art.species == Species.RABBIT
        // Colours: the photo's tones, with the Studio's fur colours put in their place.
        val tones = look.tones.toMutableList()
        style.furBase?.let { tones[0] = it }
        val lightIdx = look.light
        if (style.furLight != null && lightIdx != null) tones[lightIdx] = style.furLight
        val ramps = tones.map { ramp(it) }
        val baseR = ramps[0]
        // A light tone: the photo's, the owner's, or a pale version of the base when a pattern needs one.
        val lightR: Ramp? = when {
            style.furLight != null && lightIdx == null -> ramp(style.furLight)
            lightIdx != null -> ramps[lightIdx]
            else -> null
        }
        val paleR = lightR ?: ramp(Argb.mix(tones[0], WHITE, 0.72))
        // A dark tone for masks, spots and stripes.
        val darkR = ramp(style.furDark ?: baseR.deep)
        val pattern = style.pattern
        // Rabbits get four rows of headroom above the usual canvas, so long ears never touch the edge (the feet stay at the bottom).
        val top = if (rabbit) 4 else 0
        val w = WIDTH; val h = HEIGHT + top
        val col = IntArray(w * h)
        val part = IntArray(w * h)

        // Drawing coordinates are the usual 41 x 34 ones; [top] shifts them down into the canvas. Every
        // read and write by drawing coordinates goes through [at], so none forgets the shift.
        /** The canvas index of drawing point (x, y), or -1 off the canvas. */
        fun at(x: Int, y: Int): Int { val yy = y + top; return if (x in 0 until w && yy in 0 until h) yy * w + x else -1 }
        fun put(x: Int, y: Int, c: Int, p: Int) { val i = at(x, y); if (i >= 0) { col[i] = c; part[i] = p } }

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
        // Paws: light when the pet has a light tone, or always with socks or a tuxedo coat.
        val pawR = when (pattern) { Pattern.SOCKS, Pattern.TUXEDO -> paleR; else -> lightR ?: baseR }

        // ---------- Head geometry (shared by standing and lying) ----------
        val hcx = cx + pose.headDx
        val hcy = (if (rabbit) 16.2 else 12.6) + oy + pose.headDy + (if (pose.lying) (if (rabbit) 6.0 else 8.0) else 0.0)
        val (hrx0, hry0, headE) = when (style.head) {
            HeadShape.ROUND -> Triple(10.6, 8.4, 2.4)
            HeadShape.WIDE -> Triple(11.6, 8.0, 2.4)
            HeadShape.TALL -> Triple(9.8, 9.4, 2.2)
            HeadShape.CHUBBY -> Triple(11.2, 8.6, 2.9)
        }
        val hrx = if (rabbit) hrx0 * 0.9 else hrx0
        val hry = if (rabbit) hry0 * 0.86 else hry0
        fun faceU(x: Int) = (x + 0.5 - (hcx - hrx)) / (2 * hrx)
        fun faceV(y: Int) = (y + 0.5 - (hcy - hry)) / (2 * hry)
        fun faceTone(x: Int, y: Int): Int = look.toneAt(faceU(x), faceV(y))
        val eyeTopY = (hcy - 0.6).toInt()
        val eyeXs = listOf((hcx - 6.0).toInt(), (hcx + 5.0).toInt())

        /** The head's fur at a pixel, by the chosen coat pattern (or the photo's own markings). */
        fun headRamp(x: Int, y: Int): Ramp {
            val u = faceU(x) * 2 - 1; val v = faceV(y) * 2 - 1 // -1..1 across the head
            return when (pattern) {
                Pattern.AUTO -> ramps[faceTone(x, y)]
                Pattern.SOLID -> baseR
                Pattern.TUXEDO -> if (v > 0.2 && abs(u) < 0.62 - (v - 0.2) * 0.3) paleR else baseR
                Pattern.MASK -> if (v > -0.62 && v < 0.28) darkR else baseR
                Pattern.SOCKS -> baseR
                Pattern.SPOTS -> if (listOf(Triple(-0.45, -0.55, 0.26), Triple(0.5, -0.3, 0.2), Triple(0.3, 0.55, 0.18)).any { (su, sv, r) -> (u - su) * (u - su) + (v - sv) * (v - sv) < r * r }) darkR else baseR
                Pattern.TABBY -> {
                    val dx = x - hcx.toInt()
                    if (v < -0.3 && (dx == 0 || abs(dx) == 3)) darkR
                    else if (abs(u) > 0.7 && v in -0.2..0.5 && (y + (if (u < 0) 0 else 1)) % 3 == 0) darkR
                    else baseR
                }
                Pattern.PATCH -> {
                    val ex = eyeXs[1] + 1.0; val ey = eyeTopY + 1.0
                    if ((x + 0.5 - ex) * (x + 0.5 - ex) + (y + 0.5 - ey) * (y + 0.5 - ey) * 1.3 < 14.0) darkR else baseR
                }
            }
        }
        /** The body's fur at a pixel: solid, or with spots and stripes continuing from the head. */
        fun bodyRamp(x: Int, y: Int, nx: Double, ny: Double): Ramp = when (pattern) {
            Pattern.SPOTS -> if (listOf(Triple(-0.45, -0.1, 0.3), Triple(0.5, 0.35, 0.25)).any { (su, sv, r) -> (nx - su) * (nx - su) + (ny - sv) * (ny - sv) < r * r }) darkR else baseR
            Pattern.TABBY -> if (ny < 0.35 && ((x - cx.toInt()) % 4 == 0)) darkR else baseR
            Pattern.PATCH -> if ((nx + 0.45) * (nx + 0.45) + (ny + 0.2) * (ny + 0.2) < 0.12) darkR else baseR
            else -> baseR
        }

        if (!pose.lying) {
            val (brx0, bry0, bodyE) = when (style.body) {
                BodyShape.NORMAL -> Triple(8.2, 5.6, 2.0)
                BodyShape.CHUBBY -> Triple(9.6, 6.4, 2.1)
                BodyShape.SLIM -> Triple(7.0, 5.0, 2.0)
                BodyShape.FLUFFY -> Triple(9.0, 6.2, 1.7)
            }
            val bcy = (if (rabbit) 25.8 else 25.0) + oy - pose.breathe * 0.3
            val brx = if (rabbit) brx0 + 0.6 else brx0; val bry = bry0 + (if (rabbit) 0.4 else 0.0) + pose.breathe * 0.4
            val feet = 32
            if (rabbit) {
                // Big back feet, flat on the ground at the sides, and a puff of a tail.
                for ((i, fx) in listOf(12.4, 28.6).withIndex()) {
                    blob(fx, 31.2 - pose.legs[2 + i], 3.1, 1.5, LEG) { _, _, nx, ny -> if (ny > 0.35 || nx * (if (i == 0) -1 else 1) > 0.6) pawR.shade else pawR.mid }
                }
                blob(30.4 + pose.tail * 0.6, bcy + 0.2, 2.6, 2.4, TAIL) { _, _, nx, ny -> if (nx * 0.5 + ny * 0.9 > 0.55) paleR.shade else if (-nx - ny > 0.8) paleR.hi else paleR.mid }
            } else {
                // Back legs peeking out at the sides, in shadow.
                for ((i, lx) in listOf(12, 26).withIndex()) {
                    val bottom = feet - pose.legs[2 + i]
                    rect(lx, 27 + oy, lx + 2, bottom, { _, y -> if (y >= bottom - 1) pawR.shade else baseR.shade }, LEG)
                }
                // Tail, behind the body on the right.
                tail(cat, style.tail, pose.tail, 27.5, 25.5 + oy, baseR, lightR, ::put)
            }
            blob(cx, bcy, brx, bry, BODY, e = bodyE) { x, y, nx, ny -> shaded(bodyRamp(x, y, nx, ny), nx, ny, hiAt = 0.75, shAt = 0.5) }
            if (style.body == BodyShape.FLUFFY) {
                // Tufts along the top of the back.
                for (k in -3..3) put((cx + k * 2.4).toInt(), (bcy - bry - (if (k % 2 == 0) 1 else 0)).toInt(), baseR.hi, BODY)
            }
            // Chest: the photo's light tone, a light patch, a heart, or plain.
            val chestR = when (style.chest) {
                Chest.AUTO -> if (pattern == Pattern.TUXEDO) paleR else lightR
                Chest.LIGHT -> paleR
                Chest.HEART -> paleR
                Chest.PLAIN -> null
            }
            if (chestR != null) {
                if (style.chest == Chest.HEART) {
                    blob(cx - 1.4, bcy - 2.0, 1.7, 1.5, BODY) { _, _, _, _ -> chestR.mid }
                    blob(cx + 1.4, bcy - 2.0, 1.7, 1.5, BODY) { _, _, _, _ -> chestR.mid }
                    blob(cx, bcy - 0.4, 2.9, 2.0, BODY, e = 1.3) { _, _, _, ny -> if (ny > 0.6) chestR.shade else chestR.mid }
                } else {
                    blob(cx, bcy - 1.0, 3.6, 3.4, BODY) { _, _, nx, ny -> if (ny > 0.55) chestR.mid else if (ny < -0.5 && nx < 0) chestR.hi else chestR.mid }
                }
            }
            // Front legs.
            for ((i, lx) in (if (rabbit) listOf(16, 23) else listOf(15, 23)).withIndex()) {
                val bottom = feet - pose.legs[i]
                val right = lx + (if (rabbit) 1 else 2)
                rect(lx, (if (rabbit) 29 else 27) + oy, right, bottom, { x, y ->
                    when {
                        y >= bottom - 1 -> if (x == right) pawR.shade else pawR.mid
                        x == right -> baseR.shade
                        else -> baseR.mid
                    }
                }, LEG)
                // Rounded toes.
                put(lx + 1, bottom, at(lx + 1, bottom).let { i -> if (i >= 0 && Argb.alpha(col[i]) > 0) col[i] else pawR.mid }, LEG)
            }
        } else {
            val bcy = 27.8 - pose.breathe * 0.3
            val wide = when (style.body) { BodyShape.CHUBBY -> 12.2; BodyShape.SLIM -> 10.2; else -> 11.2 }
            if (rabbit) blob(31.6, 28.6, 2.5, 2.3, TAIL) { _, _, nx, ny -> if (nx * 0.5 + ny * 0.9 > 0.55) paleR.shade else paleR.mid }
            else tail(cat, style.tail, 0, 29.5, 29.0, baseR, lightR, ::put, lying = true)
            blob(cx, bcy, wide, 4.8 + pose.breathe * 0.4, BODY, e = 2.3) { x, y, nx, ny -> shaded(bodyRamp(x, y, nx, ny), nx, ny, hiAt = 0.8, shAt = 0.5) }
            for (px in listOf(15.5, 25.5)) blob(px, 31.6, 2.2, 1.3, LEG) { _, _, nx, _ -> if (nx > 0.5) pawR.shade else pawR.mid }
        }

        // ---------- Ears behind/around the head ----------
        val earToneL = if (pattern == Pattern.AUTO) ramps[look.toneAt(0.2, 0.1)] else if (pattern == Pattern.MASK) darkR else baseR
        val earToneR = if (pattern == Pattern.AUTO) ramps[look.toneAt(0.8, 0.1)] else if (pattern == Pattern.MASK) darkR else baseR
        val earStyle = when (style.ears) {
            EarStyle.AUTO -> if (art.ears == Ears.POINTY) EarStyle.POINTY else EarStyle.FLOPPY
            else -> style.ears
        }
        val pointy = earStyle == EarStyle.POINTY || earStyle == EarStyle.BIG || earStyle == EarStyle.TUFTED || earStyle == EarStyle.FOLDED
        val rabbitUpright = rabbit && earStyle != EarStyle.FLOPPY
        /** A rabbit's long ears: upright behind the head, or laid back over it when asleep (drawn after the head then, or it would hide them). */
        fun rabbitEars() {
            for (side in listOf(-1, 1)) {
                val r = if (side < 0) earToneL else earToneR
                val twitch = if (pose.earTwitch == side) 1.0 else 0.0
                val bx = hcx + side * 3.4; val by = hcy - hry + (if (pose.lying) 1.2 else 2.0)
                val len = when (earStyle) { EarStyle.BIG -> 9.8; EarStyle.ROUND -> 7.0; else -> 8.6 }
                // Asleep: both ears lie back, sloping down past the right of the head, the far one a little higher.
                val tx = if (pose.lying) bx + len * (if (side < 0) 0.95 else 0.8) else bx + side * (1.4 + twitch * 1.2)
                val ty = if (pose.lying) by + (if (side < 0) 1.4 else 4.2) else by - len + twitch * 0.8
                val n = 14
                for (k in 0..n) {
                    val t = k.toDouble() / n
                    val px = bx + (tx - bx) * t; val py = by + (ty - by) * t
                    val rad = 1.3 + 0.7 * kotlin.math.sin(kotlin.math.PI * (0.25 + 0.6 * t))
                    for (y in (py - rad - 1).toInt()..(py + rad + 1).toInt()) for (x in (px - rad - 1).toInt()..(px + rad + 1).toInt()) {
                        val dx = x + 0.5 - px; val dy = y + 0.5 - py
                        if (dx * dx + dy * dy > rad * rad) continue
                        val across = if (pose.lying) dy else dx * side // away from the ear's middle line
                        put(x, y, when {
                            // Laid back, the ear shows its outside: a shade darker than the head, with a deep lower edge.
                            pose.lying -> if (across > rad * 0.35) r.deep else if (side < 0) r.shade else r.mid
                            earStyle == EarStyle.FOLDED && t > 0.78 -> r.deep
                            earStyle == EarStyle.TUFTED && t > 0.9 -> r.deep
                            t in 0.18..0.86 && kotlin.math.abs(across) < rad * 0.42 -> Argb.mix(PINK, r.mid, 0.4)
                            across > rad * 0.45 -> r.shade
                            else -> r.mid
                        }, HEAD)
                    }
                }
            }
        }
        if (rabbitUpright) {
            if (!pose.lying) rabbitEars()
        } else if (pointy) {
            val big = earStyle == EarStyle.BIG
            val folded = earStyle == EarStyle.FOLDED
            for (side in listOf(-1, 1)) {
                val r = if (side < 0) earToneL else earToneR
                // Triangle: apex, and two base points on the head's top edge.
                val twitch = if (pose.earTwitch == side) 1.0 else 0.0
                val ax = hcx + side * ((if (cat) 8.4 else 7.9) * (if (big) 1.15 else 1.0) + twitch * 0.6)
                val ay = hcy - (if (big) 14.6 else if (folded) 9.4 else 11.8) - twitch
                val b1x = hcx + side * (if (big) 11.4 else 10.4); val b1y = hcy - 4.2
                val b2x = hcx + side * (if (big) 2.6 else 3.4); val b2y = hcy - 7.6
                for (y in (ay - 1).toInt()..(b1y + 1).toInt()) for (x in (hcx - 14).toInt()..(hcx + 14).toInt()) {
                    val px = x + 0.5; val py = y + 0.5
                    if (!inTri(px, py, ax, ay, b1x, b1y, b2x, b2y)) continue
                    // Inner ear: a smaller triangle towards the middle.
                    val inner = inTri(px, py, ax + side * -0.3, ay + 2.6, b1x - side * 2.2, b1y - 0.4, b2x + side * 1.8, b2y + 0.4)
                    val outerEdge = if (side < 0) px < (ax + b1x) / 2 + 0.5 else px > (ax + b1x) / 2 - 0.5
                    put(x, y, when {
                        folded && py < ay + 2.5 -> r.deep
                        inner -> Argb.mix(PINK, r.mid, if (cat) 0.35 else 0.55)
                        side > 0 && outerEdge -> r.shade
                        else -> r.mid
                    }, HEAD)
                }
                if (earStyle == EarStyle.TUFTED) {
                    put(ax.toInt(), (ay - 1).toInt(), r.mid, HEAD)
                    put((ax + side * 0.6).toInt(), (ay - 2).toInt(), r.mid, HEAD)
                    put((ax - side * 1.2).toInt(), (ay - 1.4).toInt(), r.hi, HEAD)
                }
            }
        } else if (earStyle == EarStyle.ROUND && !rabbit) {
            for (side in listOf(-1, 1)) {
                val r = if (side < 0) earToneL else earToneR
                val ecx = hcx + side * 8.4; val ecy = hcy - 7.2
                blob(ecx, ecy, 3.4, 3.2, HEAD) { _, _, nx, ny -> if (nx * side > 0.45 || ny > 0.6) r.shade else r.mid }
                blob(ecx + side * 0.2, ecy + 0.3, 1.6, 1.5, HEAD) { _, _, _, _ -> Argb.mix(PINK, r.mid, 0.4) }
            }
        }

        // ---------- Head ----------
        blob(hcx, hcy, hrx, hry, HEAD, e = headE) { x, y, nx, ny -> shaded(headRamp(x, y), nx, ny, hiAt = 0.8, shAt = 0.62, rim = 0.6) }
        if (rabbitUpright && pose.lying) rabbitEars()

        // Muzzle: a lighter, rounder snout for dogs; a small soft one for cats.
        val mcy = hcy + 3.7
        val (mrx, mry) = if (cat || rabbit) 3.0 to 1.9 else 3.9 to 2.5
        blob(hcx, mcy, mrx, mry, HEAD) { x, y, nx, ny ->
            val r = if (pattern == Pattern.TUXEDO) paleR else headRamp(x, y)
            if (ny > 0.55 && nx > -0.2) r.mid else r.hi
        }

        if (earStyle == EarStyle.FLOPPY) {
            // Floppy ears: attached at the top of the head, hanging down past the cheeks.
            for (side in listOf(-1, 1)) {
                val r = if (side < 0) earToneL else earToneR
                val n = 12
                // A twitch: the ear swings out and up a little, the way a floppy ear flicks.
                val twitch = if (pose.earTwitch == side) 1.0 else 0.0
                for (k in 0..n) {
                    val t = k.toDouble() / n
                    // A lop rabbit's ears hang longer, from closer together.
                    val px = hcx + side * ((if (rabbit) 6.2 else 7.4) + 3.2 * t - 0.8 * t * t + twitch * t)
                    val py = hcy - (if (rabbit) 5.6 else 6.9) + (if (rabbit) 11.0 else 9.2) * t - twitch * (1.0 + t)
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
        val ex = eyeXs
        val eyTop = eyeTopY
        // Dark fur round the eyes: coloured irises (both eyes alike) so they still show.
        val darkEyes = ex.map { Lab.fromArgb(headRamp(it, eyTop + 1).mid).l }.average() < 0.42
        val autoIris = if (darkEyes) (if (cat) 0xFFB8D24A.toInt() else 0xFFC98A3C.toInt()) else EYE_DARK
        fun closedEye(x0: Int) {
            // Content, closed eyes: a little downward curve.
            put(x0, eyTop + 1, EYE_DARK, HEAD); put(x0 + 1, eyTop + 1, EYE_DARK, HEAD)
            put(x0 - 1, eyTop, headRamp(x0 - 1, eyTop).deep, HEAD); put(x0 + 2, eyTop, headRamp(x0 + 2, eyTop).deep, HEAD)
        }
        for ((side, x0) in ex.withIndex()) {
            val inner = if (side == 0) 1 else 0 // the column nearer the nose
            if (pose.eyesClosed || (style.eyes == EyeShape.WINK && side == 0)) { closedEye(x0); continue }
            val iris = when {
                style.eyeCustom != null -> style.eyeCustom
                style.eyeColor == EyeColor.AUTO -> autoIris
                style.eyeColor == EyeColor.ODD -> if (side == 0) EyeColor.GREEN.argb else EyeColor.BLUE.argb
                else -> style.eyeColor.argb
            }
            // The eye's pixels, as (dx, dy) from its top-left.
            val cells: List<Pair<Int, Int>> = when (style.eyes) {
                EyeShape.ROUND, EyeShape.SPARKLE, EyeShape.WINK -> listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1, 0 to 2, 1 to 2)
                EyeShape.BIG -> listOf(0 to -1, 1 to -1, -1 to 0, 0 to 0, 1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 2 to 1, 0 to 2, 1 to 2)
                EyeShape.ALMOND -> if (side == 0) listOf(0 to 0, 1 to 0, 2 to 0, 1 to 1, 2 to 1, 2 to 2) else listOf(-1 to 0, 0 to 0, 1 to 0, -1 to 1, 0 to 1, -1 to 2)
                EyeShape.SLEEPY -> listOf(0 to 1, 1 to 1, 0 to 2, 1 to 2)
            }
            for ((dx, dy) in cells) put(x0 + dx, eyTop + dy, iris, HEAD)
            if (style.eyes == EyeShape.SLEEPY) { put(x0, eyTop, headRamp(x0, eyTop).deep, HEAD); put(x0 + 1, eyTop, headRamp(x0 + 1, eyTop).deep, HEAD) }
            // Pupil in a coloured iris, then the shine.
            if (iris != EYE_DARK) put(x0 + inner, eyTop + 1, EYE_DARK, HEAD)
            val shineAt = if (style.eyes == EyeShape.ALMOND) (if (side == 0) 1 to 0 else 0 to 0) else 0 to 0
            put(x0 + shineAt.first, eyTop + shineAt.second, WHITE, HEAD)
            when (if (style.eyes == EyeShape.SPARKLE) EyeShine.DOUBLE else style.shine) {
                EyeShine.SINGLE -> Unit
                EyeShine.DOUBLE -> put(x0 + 1, eyTop + 2, Argb.mix(WHITE, iris, 0.35), HEAD)
                EyeShine.STAR -> { put(x0 + 1, eyTop + 2, WHITE, HEAD); put(x0 + 1 - inner, eyTop + 1, Argb.mix(WHITE, iris, 0.5), HEAD) }
            }
            // Brows.
            val browC = headRamp(x0, eyTop - 2).deep
            when (style.brows) {
                Brows.NONE -> Unit
                Brows.SOFT -> { put(x0, eyTop - 2, browC, HEAD); put(x0 + 1, eyTop - 2, browC, HEAD) }
                Brows.WORRIED -> { put(x0 + inner, eyTop - 3, browC, HEAD); put(x0 + 1 - inner, eyTop - 2, browC, HEAD) }
                Brows.FIERCE -> { put(x0 + inner, eyTop - 2, browC, HEAD); put(x0 + 1 - inner, eyTop - 3, browC, HEAD) }
            }
        }
        // Blush.
        if (style.blush != Blush.NONE) {
            val rosy = style.blush == Blush.ROSY
            for ((side, x0) in listOf(ex[0] - (if (rosy) 3 else 2), ex[1] + 2).withIndex()) {
                val y = eyTop + 3
                for (dx in 0 until (if (rosy) 3 else 2)) {
                    val x = x0 + dx
                    val i = at(x, y)
                    if (i >= 0 && part[i] == HEAD) col[i] = Argb.mix(col[i], PINK, if (rosy) 0.75 else 0.45)
                }
            }
        }
        // Nose and mouth.
        val nx0 = (hcx - 0.5).toInt(); val ny0 = (mcy - 1.2).toInt()
        val noseC = when {
            style.noseCustom != null -> style.noseCustom
            style.noseColor == NoseColor.AUTO -> if (cat || rabbit) Argb.mix(PINK, 0xFFB0506A.toInt(), 0.35) else NOSE_DOG
            else -> style.noseColor.argb
        }
        val noseCells: List<Pair<Int, Int>> = when (style.nose) {
            NoseShape.AUTO, NoseShape.TRIANGLE -> listOf(-1 to 0, 0 to 0, 1 to 0, 0 to 1)
            NoseShape.BUTTON -> listOf(-1 to 0, 0 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)
            NoseShape.HEART -> listOf(-1 to -1, 1 to -1, -1 to 0, 0 to 0, 1 to 0, 0 to 1)
            NoseShape.WIDE -> listOf(-2 to 0, -1 to 0, 0 to 0, 1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1)
        }
        for ((dx, dy) in noseCells) put(nx0 + dx, ny0 + dy, noseC, HEAD)
        if (!cat && !rabbit && style.nose == NoseShape.AUTO && style.noseColor == NoseColor.AUTO) put(nx0 - 1, ny0, 0xFF6A5560.toInt(), HEAD) // tiny shine on the nose
        else if (style.nose != NoseShape.AUTO || style.noseColor != NoseColor.AUTO || style.noseCustom != null) put(nx0 - 1, ny0 + (if (style.nose == NoseShape.HEART) -1 else 0), Argb.mix(noseC, WHITE, 0.3), HEAD)
        val mouthC = headRamp(nx0, ny0 + 2).deep
        val my = ny0 + (if (style.nose == NoseShape.BUTTON || style.nose == NoseShape.WIDE) 3 else 2)
        when (style.mouth) {
            Mouth.SMILE -> { put(nx0 - 1, my, mouthC, HEAD); put(nx0 + 1, my, mouthC, HEAD) }
            Mouth.OPEN -> { for (dx in -1..1) put(nx0 + dx, my, mouthC, HEAD); put(nx0, my + 1, Argb.mix(PINK, 0xFFC84A6A.toInt(), 0.5), HEAD) }
            Mouth.CAT -> { put(nx0 - 2, my, mouthC, HEAD); put(nx0 + 2, my, mouthC, HEAD); put(nx0 - 1, my + 1, mouthC, HEAD); put(nx0 + 1, my + 1, mouthC, HEAD) }
            Mouth.TONGUE -> { put(nx0 - 1, my, mouthC, HEAD); put(nx0 + 1, my, mouthC, HEAD); put(nx0, my + 1, 0xFFF07A98.toInt(), HEAD); put(nx0, my + 2, 0xFFE0607F.toInt(), HEAD) }
            Mouth.CALM -> { for (dx in -1..1) put(nx0 + dx, my, mouthC, HEAD) }
        }
        // Whiskers: thin lines out from the cheeks.
        if (style.whiskers != Whiskers.NONE) {
            val len = if (style.whiskers == Whiskers.LONG) 4 else 2
            val wc = Argb.mix(baseR.deep, WHITE, 0.25)
            for (side in listOf(-1, 1)) {
                val edge = (hcx + side * (hrx - 0.5)).toInt()
                for ((k, yy) in listOf(ny0, ny0 + 2).withIndex()) {
                    for (d in 1..len) put(edge + side * d, yy + (if (k == 0) -(d / 3) else d / 3), wc, HEAD)
                }
            }
        }
        // Collar, round the neck where the head meets the body.
        if (style.collar != Collar.NONE && !pose.lying) {
            val cc = style.collarColor ?: 0xFFE8374E.toInt()
            val y = (hcy + hry).toInt()
            for (x in (cx - 5.5).toInt()..(cx + 5.5).toInt()) for (dy in 0..1) {
                val i = at(x, y + dy)
                if (i >= 0 && part[i] != NONE) { col[i] = if (dy == 1) Argb.mix(cc, EYE_DARK, 0.25) else cc }
            }
            when (style.collar) {
                Collar.BELL -> { for (dx in 0..1) for (dy in 1..2) put(cx.toInt() + dx - 1, y + dy, if (dy == 1 && dx == 0) 0xFFFFE28A.toInt() else 0xFFF6C744.toInt(), BODY) }
                Collar.BOW -> {
                    for (dx in listOf(-2, -1, 1, 2)) put(cx.toInt() + dx - 1, y + 1, cc, BODY)
                    put(cx.toInt() - 1, y + 1, Argb.mix(cc, WHITE, 0.4), BODY)
                    put(cx.toInt() - 3, y, cc, BODY); put(cx.toInt() + 1, y, cc, BODY)
                }
                else -> Unit
            }
        }

        // ---------- Inner lines: under the chin, between legs and body ----------
        val out = col.copyOf()
        for (y in 1 until h) for (x in 0 until w) {
            val i = y * w + x
            if (part[i] == NONE || part[i] == HEAD) continue
            if (part[i - w] == HEAD) out[i] = ramp(col[i]).let { Argb.mix(it.deep, it.shade, 0.3) }
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
        // An outfit earned with care goes on top, following the head.
        art.accessory?.drawOn(res, hcx, hcy + top, eyTop + top)
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

    private fun tail(cat: Boolean, style: TailStyle, swing: Int, bx: Double, by: Double, r: Ramp, light: Ramp?, put: (Int, Int, Int, Int) -> Unit, lying: Boolean = false) {
        val thick = when (style) { TailStyle.FLUFFY -> 1.6; TailStyle.STUB -> 1.2; else -> 1.0 }
        val span = if (style == TailStyle.STUB) 0.4 else 1.0
        val pts: List<DoubleArray> = if (lying) {
            (0..12).map { k ->
                val t = k / 12.0 * span
                // Along the ground, the tip curling up.
                doubleArrayOf(bx + t * 6.0, by + 1.8 - t * t * t * 4.5, (if (cat) 1.25 else 1.55) * (1 - 0.3 * t) * thick, t)
            }
        } else {
            var tipX = bx + (if (cat) 7.0 else 5.0) + swing * 2.0
            var tipY = by - (if (cat) 11.0 else 6.5)
            var cX = bx + (if (cat) 8.0 else 5.5); var cY = by + 0.5
            when (style) {
                TailStyle.CURLY -> { tipX = bx + 2.5 + swing * 1.0; tipY = by - 8.5; cX = bx + 9.5; cY = by - 3.0 }
                TailStyle.STRAIGHT -> { tipX = bx + 8.5 + swing * 1.5; tipY = by - 2.5; cX = bx + 4.5; cY = by - 0.5 }
                TailStyle.LONG -> { tipX = bx + 9.0 + swing * 2.0; tipY = by - 13.0; cX = bx + 9.0; cY = by + 0.5 }
                else -> Unit
            }
            val n = 16
            (0..n).map { k ->
                val t = k.toDouble() / n * span
                doubleArrayOf(
                    (1 - t) * (1 - t) * bx + 2 * (1 - t) * t * cX + t * t * tipX,
                    (1 - t) * (1 - t) * by + 2 * (1 - t) * t * cY + t * t * tipY,
                    (if (cat) 1.25 else 1.75) * (1 - 0.35 * t) * thick, t,
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
        // Walk: contact (legs apart, body down), passing (legs together, body up), the other contact,
        // passing. The head leans the way the pet is going, so it never has to be mirrored.
        for ((set, lean) in listOf(Frame.WALK to 0, Frame.WALK_L to -1, Frame.WALK_R to 1)) {
            val passing = padded(c(Pose(bob = -1, tail = 0, headDx = lean))) // both passing frames are the same picture
            frames[set[0]] = padded(c(Pose(legs = listOf(1, 0, 0, 1), tail = 1, headDx = lean)))
            frames[set[1]] = passing
            frames[set[2]] = padded(c(Pose(legs = listOf(0, 1, 1, 0), tail = -1, headDx = lean)))
            frames[set[3]] = passing
        }
        frames[Frame.TAIL_SWING] = padded(c(Pose(tail = -1)))
        frames[Frame.EAR_TWITCH_L] = padded(c(Pose(earTwitch = -1)))
        frames[Frame.EAR_TWITCH_R] = padded(c(Pose(earTwitch = 1)))
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
