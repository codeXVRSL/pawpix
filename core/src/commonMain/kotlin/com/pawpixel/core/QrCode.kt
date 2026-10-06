package com.pawpixel.core

/**
 * A QR code (ISO 18004) for a short text: byte mode, error correction M, versions 1 to 10
 * (up to 213 bytes), the mask chosen by the standard penalty rules. Small and dependency-free,
 * for the Pet ID card ("scan to reach my owner"), drawn as pixels like everything else here.
 */
class QrCode private constructor(val size: Int, private val dark: BooleanArray) {
    fun isDark(row: Int, col: Int): Boolean = dark[row * size + col]

    companion object {
        /** Null when the text doesn't fit version 10 (over about 200 bytes). */
        fun encode(text: String): QrCode? {
            val bytes = text.encodeToByteArray()
            val version = (1..10).firstOrNull { v -> bytes.size <= dataCapacity(v) } ?: return null
            val data = codewords(bytes, version)
            var bestScore = Int.MAX_VALUE
            // The mask is scored with the format and version bits left blank (as the standard's
            // reference implementations do), then the code is built for real with the winner.
            var bestMask = 0
            for (mask in 0..7) {
                val score = Builder(version).apply { place(data, mask, test = true) }.penalty()
                if (score < bestScore) { bestScore = score; bestMask = mask }
            }
            val b = Builder(version).apply { place(data, bestMask, test = false) }
            return QrCode(b.n, b.modules.map { it == true }.toBooleanArray())
        }

        // ---- Capacity and error correction, level M ----
        /** (block count, total codewords per block, data codewords per block), a second group where versions have one. */
        private val BLOCKS: List<IntArray> = listOf(
            intArrayOf(1, 26, 16), intArrayOf(1, 44, 28), intArrayOf(1, 70, 44), intArrayOf(2, 50, 32), intArrayOf(2, 67, 43),
            intArrayOf(4, 43, 27), intArrayOf(4, 49, 31), intArrayOf(2, 60, 38, 2, 61, 39), intArrayOf(3, 58, 36, 2, 59, 37),
            intArrayOf(4, 69, 43, 1, 70, 44),
        )
        private fun rsBlocks(version: Int): List<Pair<Int, Int>> { // (total, data) per block
            val t = BLOCKS[version - 1]
            val out = ArrayList<Pair<Int, Int>>()
            var i = 0
            while (i < t.size) { repeat(t[i]) { out += t[i + 1] to t[i + 2] }; i += 3 }
            return out
        }
        private fun dataCapacity(version: Int): Int {
            val dataBytes = rsBlocks(version).sumOf { it.second }
            val header = 4 + (if (version < 10) 8 else 16)
            return (dataBytes * 8 - header) / 8
        }

        private fun codewords(bytes: ByteArray, version: Int): IntArray {
            val blocks = rsBlocks(version)
            val total = blocks.sumOf { it.second }
            val bits = BitBuffer()
            bits.put(0b0100, 4)
            bits.put(bytes.size, if (version < 10) 8 else 16)
            for (b in bytes) bits.put(b.toInt() and 0xFF, 8)
            if (bits.length + 4 <= total * 8) bits.put(0, 4)
            while (bits.length % 8 != 0) bits.putBit(false)
            var pad = true
            while (bits.length < total * 8) { bits.put(if (pad) 0xEC else 0x11, 8); pad = !pad }
            val buf = bits.bytes()
            // Reed-Solomon per block, then interleave data codewords and EC codewords.
            var offset = 0
            val dc = ArrayList<IntArray>(); val ec = ArrayList<IntArray>()
            for ((totalCount, dataCount) in blocks) {
                val d = IntArray(dataCount) { buf[offset + it] }
                offset += dataCount
                dc += d; ec += reedSolomon(d, totalCount - dataCount)
            }
            val out = IntArray(blocks.sumOf { it.first })
            var k = 0
            for (i in 0 until dc.maxOf { it.size }) for (d in dc) if (i < d.size) out[k++] = d[i]
            for (i in 0 until ec.maxOf { it.size }) for (e in ec) if (i < e.size) out[k++] = e[i]
            return out
        }

        // ---- GF(256) with 0x11D ----
        private val EXP = IntArray(256); private val LOG = IntArray(256)
        init {
            var x = 1
            for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x >= 256) x = x xor 0x11D }
            EXP[255] = EXP[0]
        }
        private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[(LOG[a] + LOG[b]) % 255]

        private fun reedSolomon(data: IntArray, ecCount: Int): IntArray {
            var gen = intArrayOf(1)
            for (i in 0 until ecCount) {
                val next = IntArray(gen.size + 1)
                for (j in gen.indices) { next[j] = next[j] xor gen[j]; next[j + 1] = next[j + 1] xor mul(gen[j], EXP[i]) }
                gen = next
            }
            val rem = IntArray(ecCount)
            for (d in data) {
                val factor = d xor rem[0]
                for (i in 0 until ecCount - 1) rem[i] = rem[i + 1]
                rem[ecCount - 1] = 0
                if (factor != 0) for (i in 0 until ecCount) rem[i] = rem[i] xor mul(gen[i + 1], factor)
            }
            return rem
        }

        private class BitBuffer {
            private val out = ArrayList<Int>()
            var length = 0; private set
            fun put(value: Int, bits: Int) { for (i in bits - 1 downTo 0) putBit((value ushr i) and 1 == 1) }
            fun putBit(bit: Boolean) {
                if (length % 8 == 0) out += 0
                if (bit) out[length / 8] = out[length / 8] or (0x80 ushr (length % 8))
                length++
            }
            fun bytes(): IntArray = out.toIntArray()
        }

        private val ALIGN = listOf(intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50))

        private class Builder(val version: Int) {
            val n = version * 4 + 17
            /** null = not yet set (data goes there). */
            val modules = arrayOfNulls<Boolean>(n * n)
            fun get(r: Int, c: Int) = modules[r * n + c] == true
            fun set(r: Int, c: Int, v: Boolean) { modules[r * n + c] = v }

            fun place(data: IntArray, mask: Int, test: Boolean) {
                finder(0, 0); finder(n - 7, 0); finder(0, n - 7)
                for (p in ALIGN[version - 1]) for (q in ALIGN[version - 1]) {
                    if (modules[p * n + q] != null) continue
                    for (r in -2..2) for (c in -2..2) set(p + r, q + c, r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0))
                }
                for (i in 8 until n - 8) { if (modules[i * n + 6] == null) set(i, 6, i % 2 == 0); if (modules[6 * n + i] == null) set(6, i, i % 2 == 0) }
                formatInfo(mask, test)
                if (version >= 7) versionInfo(test)
                // Data, zigzag upward from the bottom-right, two columns at a time, skipping the timing column.
                var inc = -1; var row = n - 1; var bitIndex = 7; var byteIndex = 0
                var col = n - 1
                while (col > 0) {
                    if (col == 6) col--
                    while (true) {
                        for (c in 0..1) {
                            if (modules[row * n + col - c] == null) {
                                var dark = byteIndex < data.size && (data[byteIndex] ushr bitIndex) and 1 == 1
                                if (masked(mask, row, col - c)) dark = !dark
                                set(row, col - c, dark)
                                bitIndex--
                                if (bitIndex == -1) { byteIndex++; bitIndex = 7 }
                            }
                        }
                        row += inc
                        if (row < 0 || row >= n) { row -= inc; inc = -inc; break }
                    }
                    col -= 2
                }
            }

            private fun finder(row: Int, col: Int) {
                for (r in -1..7) for (c in -1..7) {
                    if (row + r !in 0 until n || col + c !in 0 until n) continue
                    val on = (r in 0..6 && (c == 0 || c == 6)) || (c in 0..6 && (r == 0 || r == 6)) || (r in 2..4 && c in 2..4)
                    set(row + r, col + c, on)
                }
            }

            private fun formatInfo(mask: Int, test: Boolean) {
                val data = (0b00 shl 3) or mask   // level M = 00
                var d = data shl 10
                while (bitLength(d) - bitLength(G15) >= 0) d = d xor (G15 shl (bitLength(d) - bitLength(G15)))
                val bits = ((data shl 10) or d) xor G15_MASK
                for (i in 0 until 15) {
                    val mod = !test && (bits shr i) and 1 == 1
                    when { i < 6 -> set(i, 8, mod); i < 8 -> set(i + 1, 8, mod); else -> set(n - 15 + i, 8, mod) }
                }
                for (i in 0 until 15) {
                    val mod = !test && (bits shr i) and 1 == 1
                    when { i < 8 -> set(8, n - i - 1, mod); i < 9 -> set(8, 15 - i, mod); else -> set(8, 15 - i - 1, mod) }
                }
                set(n - 8, 8, !test)
            }

            private fun versionInfo(test: Boolean) {
                var d = version shl 12
                while (bitLength(d) - bitLength(G18) >= 0) d = d xor (G18 shl (bitLength(d) - bitLength(G18)))
                val bits = (version shl 12) or d
                for (i in 0 until 18) {
                    val mod = !test && (bits shr i) and 1 == 1
                    set(i / 3, i % 3 + n - 11, mod)
                    set(i % 3 + n - 11, i / 3, mod)
                }
            }

            private fun masked(mask: Int, i: Int, j: Int): Boolean = when (mask) {
                0 -> (i + j) % 2 == 0
                1 -> i % 2 == 0
                2 -> j % 3 == 0
                3 -> (i + j) % 3 == 0
                4 -> (i / 2 + j / 3) % 2 == 0
                5 -> (i * j) % 2 + (i * j) % 3 == 0
                6 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0
                else -> ((i * j) % 3 + (i + j) % 2) % 2 == 0
            }

            /** The standard's four penalty rules (as the common reference implementations score them). */
            fun penalty(): Int {
                var lost = 0
                for (row in 0 until n) for (col in 0 until n) {
                    var same = 0; val dark = get(row, col)
                    for (r in -1..1) for (c in -1..1) {
                        if (r == 0 && c == 0) continue
                        if (row + r !in 0 until n || col + c !in 0 until n) continue
                        if (dark == get(row + r, col + c)) same++
                    }
                    if (same > 5) lost += 3 + same - 5
                }
                for (row in 0 until n - 1) for (col in 0 until n - 1) {
                    var count = 0
                    if (get(row, col)) count++; if (get(row + 1, col)) count++; if (get(row, col + 1)) count++; if (get(row + 1, col + 1)) count++
                    if (count == 0 || count == 4) lost += 3
                }
                for (row in 0 until n) for (col in 0 until n - 6) {
                    if (get(row, col) && !get(row, col + 1) && get(row, col + 2) && get(row, col + 3) && get(row, col + 4) && !get(row, col + 5) && get(row, col + 6)) lost += 40
                }
                for (col in 0 until n) for (row in 0 until n - 6) {
                    if (get(row, col) && !get(row + 1, col) && get(row + 2, col) && get(row + 3, col) && get(row + 4, col) && !get(row + 5, col) && get(row + 6, col)) lost += 40
                }
                var darkCount = 0
                for (i in 0 until n * n) if (modules[i] == true) darkCount++
                // The reference keeps this as a fraction; scoring in tenths keeps the same winner.
                val ratio = kotlin.math.abs(100.0 * darkCount / n / n - 50) / 5
                return lost * 10 + (ratio * 100).toInt()
            }

            private fun bitLength(v: Int): Int { var x = v; var d = 0; while (x != 0) { d++; x = x ushr 1 }; return d }
            private val G15 = (1 shl 10) or (1 shl 8) or (1 shl 5) or (1 shl 4) or (1 shl 2) or (1 shl 1) or 1
            private val G18 = (1 shl 12) or (1 shl 11) or (1 shl 10) or (1 shl 9) or (1 shl 8) or (1 shl 5) or (1 shl 2) or 1
            private val G15_MASK = (1 shl 14) or (1 shl 12) or (1 shl 10) or (1 shl 4) or (1 shl 1)
        }
    }
}
