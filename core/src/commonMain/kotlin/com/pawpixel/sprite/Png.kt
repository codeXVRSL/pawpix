package com.pawpixel.sprite

/**
 * PNG encoder (8-bit RGBA) with a small built-in DEFLATE compressor (LZ77 + fixed Huffman codes).
 * Pixel art compresses very well with this, and it keeps the core free of platform image APIs, so
 * the same bytes are written on Android, iOS and in tests.
 */
object Png {
    fun encode(img: PixelImage): ByteArray {
        val w = img.width; val h = img.height
        val raw = ByteArray(h * (1 + w * 4))
        var p = 0
        for (y in 0 until h) {
            raw[p++] = 0 // filter: none
            for (x in 0 until w) {
                val c = img[x, y]
                raw[p++] = (c shr 16).toByte(); raw[p++] = (c shr 8).toByte()
                raw[p++] = c.toByte(); raw[p++] = (c ushr 24).toByte()
            }
        }
        val out = Bytes()
        out.add(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = Bytes().apply { int(w); int(h); byte(8); byte(6); byte(0); byte(0); byte(0) }
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", zlib(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: Bytes, type: String, data: ByteArray) {
        out.int(data.size)
        val typeBytes = type.encodeToByteArray()
        out.add(typeBytes); out.add(data)
        var crc = crc32(0xffffffff.toInt(), typeBytes)
        crc = crc32(crc, data)
        out.int(crc.inv())
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xedb88320.toInt() xor (c ushr 1)) else c ushr 1 }
        c
    }

    private fun crc32(start: Int, data: ByteArray): Int {
        var c = start
        for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
        return c
    }

    fun zlib(data: ByteArray): ByteArray {
        val out = Bytes()
        out.byte(0x78); out.byte(0x9C)
        out.add(Deflate.compress(data))
        var a = 1; var b = 0
        for (x in data) { a = (a + (x.toInt() and 0xff)) % 65521; b = (b + a) % 65521 }
        out.int((b shl 16) or a)
        return out.toByteArray()
    }

    internal class Bytes {
        private var buf = ByteArray(1024)
        var size = 0; private set
        private fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
        fun byte(v: Int) { ensure(1); buf[size++] = v.toByte() }
        fun int(v: Int) { byte(v ushr 24); byte(v ushr 16); byte(v ushr 8); byte(v) }
        fun add(b: ByteArray) { ensure(b.size); b.copyInto(buf, size); size += b.size }
        fun toByteArray() = buf.copyOf(size)
    }
}

/** Single-block DEFLATE with fixed Huffman codes and a hash-chain LZ77 matcher. */
internal object Deflate {
    private const val WINDOW = 32768
    private const val MAX_MATCH = 258
    private const val MIN_MATCH = 3
    private const val MAX_CHAIN = 48
    private const val HASH_SIZE = 1 shl 15

    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    private class BitWriter {
        val bytes = Png.Bytes()
        private var acc = 0L
        private var n = 0
        /** Writes [count] bits of [value], least significant first. */
        fun bits(value: Int, count: Int) {
            acc = acc or ((value.toLong() and ((1L shl count) - 1)) shl n)
            n += count
            while (n >= 8) { bytes.byte((acc and 0xff).toInt()); acc = acc ushr 8; n -= 8 }
        }
        /** Writes a Huffman code, most significant bit first. */
        fun code(code: Int, len: Int) {
            var rev = 0
            for (i in 0 until len) rev = rev or (((code ushr i) and 1) shl (len - 1 - i))
            bits(rev, len)
        }
        fun flush(): ByteArray { if (n > 0) bits(0, 8 - n); return bytes.toByteArray() }
    }

    private fun literal(w: BitWriter, sym: Int) {
        when {
            sym < 144 -> w.code(0x30 + sym, 8)
            sym < 256 -> w.code(0x190 + (sym - 144), 9)
            sym < 280 -> w.code(sym - 256, 7)
            else -> w.code(0xC0 + (sym - 280), 8)
        }
    }

    fun compress(data: ByteArray): ByteArray {
        val w = BitWriter()
        w.bits(1, 1) // BFINAL
        w.bits(1, 2) // BTYPE = fixed Huffman
        val head = IntArray(HASH_SIZE) { -1 }
        val prev = IntArray(data.size.coerceAtLeast(1))
        fun hash(i: Int): Int {
            val v = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)
            return (v * -0x61c88647 ushr 17) and (HASH_SIZE - 1)
        }
        fun insert(i: Int) {
            if (i + MIN_MATCH > data.size) return
            val h = hash(i); prev[i] = head[h]; head[h] = i
        }
        var i = 0
        while (i < data.size) {
            var bestLen = 0; var bestDist = 0
            if (i + MIN_MATCH <= data.size) {
                var cand = head[hash(i)]
                var chain = 0
                val maxLen = minOf(MAX_MATCH, data.size - i)
                while (cand >= 0 && i - cand <= WINDOW && chain < MAX_CHAIN) {
                    var l = 0
                    while (l < maxLen && data[cand + l] == data[i + l]) l++
                    if (l > bestLen) { bestLen = l; bestDist = i - cand; if (l == maxLen) break }
                    cand = prev[cand]; chain++
                }
            }
            if (bestLen >= MIN_MATCH) {
                var li = 0
                while (li < LEN_BASE.size - 1 && LEN_BASE[li + 1] <= bestLen) li++
                literal(w, 257 + li)
                if (LEN_EXTRA[li] > 0) w.bits(bestLen - LEN_BASE[li], LEN_EXTRA[li])
                var di = 0
                while (di < DIST_BASE.size - 1 && DIST_BASE[di + 1] <= bestDist) di++
                w.code(di, 5)
                if (DIST_EXTRA[di] > 0) w.bits(bestDist - DIST_BASE[di], DIST_EXTRA[di])
                for (k in 0 until bestLen) insert(i + k)
                i += bestLen
            } else {
                literal(w, data[i].toInt() and 0xff)
                insert(i)
                i++
            }
        }
        literal(w, 256)
        return w.flush()
    }
}
