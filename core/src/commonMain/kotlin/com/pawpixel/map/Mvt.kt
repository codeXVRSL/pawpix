package com.pawpixel.map

/**
 * Mapbox Vector Tiles (the `.pbf` tiles OpenFreeMap and OpenMapTiles serve): a small protobuf
 * reader for the tile format, nothing more. A tile is a few named layers ("water", "building",
 * "transportation"...), each a list of features with tags and geometry in tile units (0..extent).
 *
 * Spec: https://github.com/mapbox/vector-tile-spec/tree/master/2.1
 */
class VectorTile(val layers: Map<String, VectorLayer>)

class VectorLayer(val name: String, val extent: Int, val features: List<VectorFeature>)

class VectorFeature(
    val type: Int,
    val tags: Map<String, Any>,
    /** Points: one entry per point; lines: one per line; polygons: one per ring (outer rings first, holes after). */
    val geometry: List<IntArray>,
) {
    val isPoint get() = type == POINT
    val isLine get() = type == LINE
    val isPolygon get() = type == POLYGON
    fun str(key: String): String? = tags[key]?.toString()

    companion object { const val POINT = 1; const val LINE = 2; const val POLYGON = 3 }
}

object Mvt {
    fun decode(bytes: ByteArray): VectorTile {
        val r = Reader(bytes, 0, bytes.size)
        val layers = LinkedHashMap<String, VectorLayer>()
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            if (tag shr 3 == 3 && tag and 7 == 2) {
                val len = r.len()
                val layer = readLayer(r.sub(len))
                layers[layer.name] = layer
                r.pos += len
            } else r.skip(tag and 7)
        }
        return VectorTile(layers)
    }

    private fun readLayer(r: Reader): VectorLayer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val rawFeatures = ArrayList<Reader>()
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                1 -> name = r.string()
                2 -> { val len = r.len(); rawFeatures += r.sub(len); r.pos += len }
                3 -> keys += r.string()
                4 -> { val len = r.len(); values += readValue(r.sub(len)); r.pos += len }
                5 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        return VectorLayer(name, extent, rawFeatures.map { readFeature(it, keys, values) })
    }

    private fun readValue(r: Reader): Any {
        var v: Any = ""
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            v = when (tag shr 3) {
                1 -> r.string()
                2 -> Float.fromBits(r.fixed32())
                3 -> Double.fromBits(r.fixed64())
                4, 5 -> r.varint()
                6 -> zigzag(r.varint())
                7 -> r.varint() != 0L
                else -> { r.skip(tag and 7); v }
            }
        }
        return v
    }

    private fun readFeature(r: Reader, keys: List<String>, values: List<Any>): VectorFeature {
        var type = 0
        val tags = LinkedHashMap<String, Any>()
        var rawGeometry: Reader? = null // decoded last: protobuf fields come in any order, and the type decides how
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            when (tag shr 3) {
                2 -> { // packed key/value index pairs
                    val len = r.len(); val end = minOf(r.end, r.pos + len)
                    while (r.pos < end) {
                        val k = r.varint().toInt(); val v = r.varint().toInt()
                        if (k >= 0 && k < keys.size && v >= 0 && v < values.size) tags[keys[k]] = values[v]
                    }
                }
                3 -> type = r.varint().toInt()
                4 -> { val len = r.len(); rawGeometry = r.sub(len); r.pos += len }
                else -> r.skip(tag and 7)
            }
        }
        return VectorFeature(type, tags, rawGeometry?.let { readGeometry(it, type) } ?: emptyList())
    }

    /** Command stream: MoveTo(1) / LineTo(2) with zigzag deltas, ClosePath(7). */
    private fun readGeometry(r: Reader, type: Int): List<IntArray> {
        val parts = ArrayList<IntArray>()
        var cur = IntArrayList()
        var x = 0; var y = 0
        fun flush() { if (cur.size >= 2) parts += cur.toArray(); cur = IntArrayList() }
        while (r.hasMore()) {
            val cmd = r.varint().toInt()
            val id = cmd and 7; val count = cmd ushr 3
            // A count beyond the bytes left is a damaged tile: stop at the data, never spin on it.
            when (id) {
                1 -> for (i in 0 until count) { // MoveTo starts a new point / line / ring
                    if (!r.hasMore()) break
                    x += zigzag(r.varint()).toInt(); y += zigzag(r.varint()).toInt()
                    if (type != VectorFeature.POINT) flush()
                    cur.add(x); cur.add(y)
                    if (type == VectorFeature.POINT) { parts += cur.toArray(); cur = IntArrayList() }
                }
                2 -> for (i in 0 until count) { if (!r.hasMore()) break; x += zigzag(r.varint()).toInt(); y += zigzag(r.varint()).toInt(); cur.add(x); cur.add(y) }
                7 -> flush()
                else -> return parts
            }
        }
        flush()
        return parts
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

    private class IntArrayList {
        var data = IntArray(32); var size = 0
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun toArray() = data.copyOf(size)
    }

    /** Reads within [pos, end); a damaged tile runs out of bytes instead of out of bounds. */
    private class Reader(val bytes: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end
        /** A length prefix, clamped to the bytes left (a huge or negative one can't move the cursor backwards). */
        fun len(): Int = varint().coerceIn(0, (end - pos).toLong()).toInt()
        fun sub(len: Int) = Reader(bytes, pos, minOf(end, pos + maxOf(0, len)))
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (pos < end) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            return result
        }
        fun string(): String { val len = varint().toInt().coerceIn(0, end - pos); val s = bytes.decodeToString(pos, pos + len); pos += len; return s }
        fun fixed32(): Int { if (end - pos < 4) { pos = end; return 0 }; var v = 0; for (i in 0 until 4) v = v or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i)); pos += 4; return v }
        fun fixed64(): Long { if (end - pos < 8) { pos = end; return 0 }; var v = 0L; for (i in 0 until 8) v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 8; return v }
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += len() // clamped to the bytes left: never wraps or runs backwards
                5 -> pos += 4
                else -> pos = end
            }
        }
    }
}

/**
 * Writes a vector tile (tests and the preview harness, which have no network): the same format,
 * one layer at a time. Tags are strings only.
 */
class MvtWriter {
    private val out = ArrayList<Byte>()

    fun layer(name: String, extent: Int = 4096, build: LayerWriter.() -> Unit): MvtWriter {
        val l = LayerWriter(name, extent).apply(build)
        val body = l.bytes()
        tag(3, 2); varint(body.size.toLong()); out.addAll(body.toList())
        return this
    }

    fun bytes(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
    private fun varint(v: Long) { var n = v; while (true) { val b = (n and 0x7F).toInt(); n = n ushr 7; if (n == 0L) { out.add(b.toByte()); return } else out.add((b or 0x80).toByte()) } }

    class LayerWriter(private val name: String, private val extent: Int) {
        private val keys = ArrayList<String>()
        private val values = ArrayList<String>()
        private val features = ArrayList<ByteArray>()

        fun point(x: Int, y: Int, vararg tags: Pair<String, String>) = feature(VectorFeature.POINT, listOf(intArrayOf(x, y)), tags)
        fun line(points: IntArray, vararg tags: Pair<String, String>) = feature(VectorFeature.LINE, listOf(points), tags)
        fun polygon(ring: IntArray, vararg tags: Pair<String, String>) = feature(VectorFeature.POLYGON, listOf(ring), tags)

        fun feature(type: Int, parts: List<IntArray>, tags: Array<out Pair<String, String>>) {
            val f = ByteList()
            if (tags.isNotEmpty()) {
                val packed = ByteList()
                for ((k, v) in tags) {
                    var ki = keys.indexOf(k); if (ki < 0) { keys += k; ki = keys.size - 1 }
                    var vi = values.indexOf(v); if (vi < 0) { values += v; vi = values.size - 1 }
                    packed.varint(ki.toLong()); packed.varint(vi.toLong())
                }
                f.tag(2, 2); f.varint(packed.size.toLong()); f.addAll(packed)
            }
            f.tag(3, 0); f.varint(type.toLong())
            val g = ByteList()
            var x = 0; var y = 0
            for (part in parts) {
                val n = part.size / 2
                g.varint(((1 shl 3) or 1).toLong()); g.zig(part[0] - x); g.zig(part[1] - y); x = part[0]; y = part[1]
                if (n > 1) {
                    g.varint(((n - 1 shl 3) or 2).toLong())
                    for (i in 1 until n) { g.zig(part[2 * i] - x); g.zig(part[2 * i + 1] - y); x = part[2 * i]; y = part[2 * i + 1] }
                }
                if (type == VectorFeature.POLYGON) g.varint(((1 shl 3) or 7).toLong())
            }
            f.tag(4, 2); f.varint(g.size.toLong()); f.addAll(g)
            features += f.toByteArray()
        }

        fun bytes(): ByteArray {
            val l = ByteList()
            l.tag(15, 0); l.varint(2)
            l.tag(1, 2); l.string(name)
            for (f in features) { l.tag(2, 2); l.varint(f.size.toLong()); l.addAll(f) }
            for (k in keys) { l.tag(3, 2); l.string(k) }
            for (v in values) { val vb = ByteList(); vb.tag(1, 2); vb.string(v); l.tag(4, 2); l.varint(vb.size.toLong()); l.addAll(vb) }
            l.tag(5, 0); l.varint(extent.toLong())
            return l.toByteArray()
        }
    }

    private class ByteList {
        private val list = ArrayList<Byte>()
        val size get() = list.size
        fun add(b: Byte) = list.add(b)
        fun addAll(o: ByteList) = list.addAll(o.list)
        fun addAll(b: ByteArray) = list.addAll(b.toList())
        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun varint(v: Long) { var n = v; while (true) { val b = (n and 0x7F).toInt(); n = n ushr 7; if (n == 0L) { add(b.toByte()); return } else add((b or 0x80).toByte()) } }
        fun zig(v: Int) = varint(((v.toLong() shl 1) xor (v.toLong() shr 31)))
        fun string(s: String) { val b = s.encodeToByteArray(); varint(b.size.toLong()); addAll(b) }
        fun toByteArray() = list.toByteArray()
    }
}
