package com.pawpixel.sprite

/**
 * Phone cameras store most photos sideways and add an EXIF "orientation" saying how to turn them.
 * Android 9+ and iOS apply it while decoding; Android 8 (still common on budget phones) doesn't, so
 * PawPixel reads it here and turns the pixels itself.
 */
object Exif {
    /** The EXIF orientation of a JPEG (1..8); 1 (upright) when there's none or the file can't be read. */
    fun orientation(jpeg: ByteArray): Int = runCatching { find(jpeg) }.getOrNull()?.takeIf { it in 1..8 } ?: 1

    private fun find(b: ByteArray): Int? {
        fun u8(o: Int) = b[o].toInt() and 0xff
        fun u16be(o: Int) = (u8(o) shl 8) or u8(o + 1)
        if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (u8(i) != 0xFF) return null
            val marker = u8(i + 1)
            if (marker == 0xFF) { i++; continue } // fill byte
            if (marker == 0xDA || marker == 0xD9) return null // image data starts: no EXIF before it
            val length = u16be(i + 2)
            if (length < 2 || i + 2 + length > b.size) return null
            if (marker == 0xE1 && length >= 16 && b.decodeToString(i + 4, i + 10) == "Exif\u0000\u0000") {
                return tiffOrientation(b, i + 10, i + 2 + length)
            }
            i += 2 + length
        }
        return null
    }

    /** Reads tag 0x0112 from IFD0 of the TIFF block at [t] (ending at [end]). */
    private fun tiffOrientation(b: ByteArray, t: Int, end: Int): Int? {
        fun u8(o: Int): Int { require(o in t until end); return b[o].toInt() and 0xff }
        val little = when {
            u8(t) == 0x49 && u8(t + 1) == 0x49 -> true   // "II"
            u8(t) == 0x4D && u8(t + 1) == 0x4D -> false  // "MM"
            else -> return null
        }
        fun u16(o: Int) = if (little) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)
        fun u32(o: Int) = if (little) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
        if (u16(t + 2) != 42) return null
        val ifd = t + u32(t + 4)
        val count = u16(ifd)
        for (e in 0 until count) {
            val entry = ifd + 2 + e * 12
            if (u16(entry) == 0x0112) return u16(entry + 8)
        }
        return null
    }

    /** [image] turned upright for EXIF [orientation] (1..8; anything else leaves it as it is). */
    fun upright(image: PixelImage, orientation: Int): PixelImage {
        if (orientation !in 2..8) return image
        val w = image.width; val h = image.height
        val swap = orientation >= 5
        val outW = if (swap) h else w
        val outH = if (swap) w else h
        val out = PixelImage(outW, outH)
        val src = image.pixels
        for (y in 0 until outH) for (x in 0 until outW) {
            // The source pixel (sx, sy) as one index: sy * w + sx.
            val i = when (orientation) {
                2 -> y * w + (w - 1 - x)
                3 -> (h - 1 - y) * w + (w - 1 - x)
                4 -> (h - 1 - y) * w + x
                5 -> x * w + y
                6 -> (h - 1 - x) * w + y
                7 -> (h - 1 - x) * w + (w - 1 - y)
                else -> x * w + (w - 1 - y) // 8
            }
            out.pixels[y * outW + x] = src[i]
        }
        return out
    }
}
