package com.pawpixel.sprite

import com.pawpixel.core.Mood
import kotlin.math.roundToInt

/**
 * Where the pet lives on screen: a floor, a soft shadow, the pet and its effects.
 * The app draws these layers with Compose; the GIF export renders them into pixels here.
 */
class StageLayout(val set: AnimationSet) {
    /** Body bounds inside the pet canvas (left, top, right, bottom). */
    val body: IntArray = Animator.opaqueBounds(set[Frame.BASE]) ?: intArrayOf(0, 0, set.width, set.height)
    val stageWidth: Int = (set.width * 2.2).toInt()
    val iconScale: Int = if (body[2] - body[0] >= 36) 2 else 1
    /** Room above the pet for hops and floating icons. */
    val headroom: Int = 20 + 8 * iconScale + (body[3] - body[1]) / 6
    val stageHeight: Int = body[3] + headroom - body[1] + 6
    /** y of the pet canvas top on the stage, so its feet stand on [floorY]. */
    val petTop: Int = stageHeight - 6 - body[3]
    val floorY: Int get() = petTop + body[3]

    fun brain(seed: Int) = PetBrain(seed, stageWidth.toDouble(), set.width, body)
}

object StageRenderer {
    val BACKGROUND = 0xFFFFF4E0.toInt()
    private val FLOOR = 0xFFFFE3B8.toInt()
    private val FLOOR_LINE = 0xFFE9C99A.toInt()

    fun render(layout: StageLayout, set: AnimationSet, pose: PetPose): PixelImage {
        val img = PixelImage(layout.stageWidth, layout.stageHeight).fill(BACKGROUND)
        for (y in layout.floorY - 2 until img.height) for (x in 0 until img.width) img[x, y] = if (y == layout.floorY - 2) FLOOR_LINE else FLOOR
        val px = pose.x.roundToInt()

        // Shadow shrinks while the pet is in the air.
        val bodyW = layout.body[2] - layout.body[0]
        val cx = px + (layout.body[0] + layout.body[2]) / 2.0
        val half = bodyW * 0.4 * (1.0 + pose.lift / 40.0).coerceIn(0.4, 1.0)
        for (x in (cx - half).roundToInt()..(cx + half).roundToInt()) {
            for (dy in 0..1) {
                val y = layout.floorY - 1 + dy
                if (img.inBounds(x, y)) img[x, y] = Argb.blend(img[x, y], 0x40000000)
            }
        }

        var sprite = set[pose.frame]
        if (pose.flip) sprite = mirror(sprite)
        img.draw(sprite, px, layout.petTop + pose.lift)

        for (e in pose.effects) {
            val icon = Icons.forEffect(e.kind).scaled(layout.iconScale)
            val ex = px + (if (pose.flip) sprite.width - e.x - icon.width else e.x).roundToInt()
            val ey = layout.petTop + pose.lift + e.y.roundToInt() - (icon.height - Icons.forEffect(e.kind).height)
            img.draw(icon, ex, ey)
        }
        return img
    }

    fun mirror(img: PixelImage): PixelImage {
        val out = PixelImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) out[img.width - 1 - x, y] = img[x, y]
        return out
    }
}

/** A short, looping, shareable GIF of the pet being itself. */
object AnimatedExport {
    const val FRAME_MS = 80

    /** [eyes] are in head pixels. */
    fun clip(art: PetArt, eyes: List<Pair<Int, Int>>, petName: String, mood: Mood = Mood.HAPPY, durationMs: Int = 4800, scale: Int = 5): ByteArray {
        val set = Chibi.build(art, eyes).forMood(mood)
        val layout = StageLayout(set)
        val brain = layout.brain(seed = petName.hashCode())
        brain.react(PetEvent.Petted, 0)
        val frames = ArrayList<PixelImage>()
        var t = 0L
        while (t < durationMs) {
            val stage = StageRenderer.render(layout, set, brain.pose(t, mood))
            watermark(stage, petName)
            frames += stage
            t += FRAME_MS
        }
        return Gif.encode(frames, FRAME_MS / 10, scale)
    }

    private fun watermark(img: PixelImage, name: String) {
        val ink = 0xFFB08A5A.toInt()
        val label = "PAWPIXEL"
        PixelFont.draw(img, label, img.width - PixelFont.textWidth(label, 1) - 2, img.height - PixelFont.H - 1, 1, ink)
        val shown = PixelFont.fit(name.uppercase(), 1, img.width / 2)
        PixelFont.draw(img, shown, 2, img.height - PixelFont.H - 1, 1, ink)
    }
}

/** Minimal animated GIF89a encoder (global palette, LZW, infinite loop). */
object Gif {
    /** Frames are enlarged by [scale] (nearest neighbour) while encoding, so big GIFs need little memory. */
    fun encode(frames: List<PixelImage>, delayCs: Int, scale: Int = 1): ByteArray {
        require(frames.isNotEmpty() && scale >= 1)
        val fw = frames[0].width; val fh = frames[0].height
        val w = fw * scale; val h = fh * scale
        // Palette: the most common colours across all frames (pixel art rarely exceeds 256).
        val counts = HashMap<Int, Int>()
        for (f in frames) for (p in f.pixels) { val c = p or (0xff shl 24); counts[c] = (counts[c] ?: 0) + 1 }
        val palette = counts.entries.sortedByDescending { it.value }.take(256).map { it.key }
        val index = HashMap<Int, Int>().apply { palette.forEachIndexed { i, c -> put(c, i) } }
        val labPalette = if (counts.size > 256) palette.map { Lab.fromArgb(it) } else emptyList()

        val out = Png.Bytes()
        out.add("GIF89a".encodeToByteArray())
        le16(out, w); le16(out, h)
        out.byte(0xF7); out.byte(0); out.byte(0) // global colour table, 256 entries
        for (i in 0 until 256) {
            val c = palette.getOrElse(i) { 0 }
            out.byte(Argb.r(c)); out.byte(Argb.g(c)); out.byte(Argb.b(c))
        }
        // Loop forever (NETSCAPE2.0 application extension)
        out.byte(0x21); out.byte(0xFF); out.byte(11); out.add("NETSCAPE2.0".encodeToByteArray())
        out.byte(3); out.byte(1); le16(out, 0); out.byte(0)

        for (f in frames) {
            out.byte(0x21); out.byte(0xF9); out.byte(4); out.byte(0x04); le16(out, delayCs); out.byte(0); out.byte(0)
            out.byte(0x2C); le16(out, 0); le16(out, 0); le16(out, w); le16(out, h); out.byte(0)
            val small = ByteArray(fw * fh) { i ->
                val c = f.pixels[i] or (0xff shl 24)
                (index[c] ?: Quantizer.nearest(labPalette, Lab.fromArgb(c)).also { index[c] = it }).toByte()
            }
            val px = if (scale == 1) small else ByteArray(w * h) { i -> small[(i / w / scale) * fw + (i % w) / scale] }
            out.byte(8)
            val data = lzw(px, 8)
            var o = 0
            while (o < data.size) {
                val n = minOf(255, data.size - o)
                out.byte(n); out.add(data.copyOfRange(o, o + n)); o += n
            }
            out.byte(0)
        }
        out.byte(0x3B)
        return out.toByteArray()
    }

    private fun le16(out: Png.Bytes, v: Int) { out.byte(v and 0xff); out.byte((v shr 8) and 0xff) }

    /** Variable-width LZW as GIF expects (codes up to 12 bits, clear when the table fills). */
    fun lzw(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        val dict = HashMap<Int, Int>()
        val bytes = Png.Bytes()
        var acc = 0L; var nbits = 0
        fun write(code: Int) {
            acc = acc or (code.toLong() shl nbits); nbits += codeSize
            while (nbits >= 8) { bytes.byte((acc and 0xff).toInt()); acc = acc ushr 8; nbits -= 8 }
        }
        write(clear)
        if (indices.isEmpty()) { write(eoi); if (nbits > 0) bytes.byte(acc.toInt()); return bytes.toByteArray() }
        var prefix = indices[0].toInt() and 0xff
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xff
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            write(prefix)
            if (next < 4096) {
                dict[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                write(clear)
                dict.clear(); next = eoi + 1; codeSize = minCodeSize + 1
            }
            prefix = k
        }
        write(prefix)
        write(eoi)
        if (nbits > 0) bytes.byte((acc and 0xff).toInt())
        return bytes.toByteArray()
    }
}
