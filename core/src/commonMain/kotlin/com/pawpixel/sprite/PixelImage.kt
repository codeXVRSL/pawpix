package com.pawpixel.sprite

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A simple ARGB (0xAARRGGBB) image. Platform code converts to/from Bitmap/CGImage. */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }
    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun copy() = PixelImage(width, height, pixels.copyOf())

    fun fill(argb: Int): PixelImage { pixels.fill(argb); return this }

    /** Alpha-composites [src] over this image at (dx, dy). */
    fun draw(src: PixelImage, dx: Int, dy: Int) {
        for (y in 0 until src.height) {
            val ty = y + dy
            if (ty !in 0 until height) continue
            for (x in 0 until src.width) {
                val tx = x + dx
                if (tx !in 0 until width) continue
                val s = src[x, y]
                val a = s ushr 24
                if (a == 0) continue
                this[tx, ty] = if (a == 255) s else Argb.blend(this[tx, ty], s)
            }
        }
    }

    /** Nearest-neighbour integer upscale: keeps pixel art crisp. */
    fun scaled(factor: Int): PixelImage {
        require(factor >= 1)
        if (factor == 1) return copy()
        val out = PixelImage(width * factor, height * factor)
        for (y in 0 until out.height) {
            val sy = y / factor
            for (x in 0 until out.width) out[x, y] = this[x / factor, sy]
        }
        return out
    }

    /** Area-averaging resample of a region (fractional bounds allowed) to [outW]x[outH]. */
    fun resampleArea(srcX: Double, srcY: Double, srcW: Double, srcH: Double, outW: Int, outH: Int): PixelImage {
        val out = PixelImage(outW, outH)
        val sx = srcW / outW
        val sy = srcH / outH
        for (oy in 0 until outH) {
            val y0 = srcY + oy * sy
            val y1 = y0 + sy
            for (ox in 0 until outW) {
                val x0 = srcX + ox * sx
                val x1 = x0 + sx
                var a = 0.0; var r = 0.0; var g = 0.0; var b = 0.0; var area = 0.0
                var yy = kotlin.math.floor(y0).toInt()
                while (yy < y1) {
                    val hy = min(y1, yy + 1.0) - max(y0, yy.toDouble())
                    if (hy > 0) {
                        var xx = kotlin.math.floor(x0).toInt()
                        while (xx < x1) {
                            val wx = min(x1, xx + 1.0) - max(x0, xx.toDouble())
                            if (wx > 0) {
                                val w = wx * hy
                                area += w
                                if (inBounds(xx, yy)) {
                                    val p = this[xx, yy]
                                    val pa = (p ushr 24) / 255.0 * w
                                    a += pa
                                    r += ((p shr 16) and 0xff) * pa
                                    g += ((p shr 8) and 0xff) * pa
                                    b += (p and 0xff) * pa
                                }
                            }
                            xx++
                        }
                    }
                    yy++
                }
                out[ox, oy] = if (a <= 1e-9) 0 else Argb.of(
                    (a / area * 255).roundToInt(), (r / a).roundToInt(), (g / a).roundToInt(), (b / a).roundToInt(),
                )
            }
        }
        return out
    }

    /** Downscales so the longest side is at most [maxSide], keeping aspect ratio. */
    fun fitWithin(maxSide: Int): PixelImage {
        val longest = max(width, height)
        if (longest <= maxSide) return copy()
        val f = maxSide.toDouble() / longest
        val w = max(1, (width * f).roundToInt())
        val h = max(1, (height * f).roundToInt())
        return resampleArea(0.0, 0.0, width.toDouble(), height.toDouble(), w, h)
    }
}

object Argb {
    fun of(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun rgb(hex: Int): Int = hex or (0xff shl 24)
    fun alpha(c: Int) = c ushr 24
    fun r(c: Int) = (c shr 16) and 0xff
    fun g(c: Int) = (c shr 8) and 0xff
    fun b(c: Int) = c and 0xff
    fun withAlpha(c: Int, a: Int) = (c and 0x00ffffff) or (a.coerceIn(0, 255) shl 24)

    /** Source-over blend of [src] onto [dst]. */
    fun blend(dst: Int, src: Int): Int {
        val sa = alpha(src) / 255.0
        val da = alpha(dst) / 255.0
        val oa = sa + da * (1 - sa)
        if (oa <= 0.0) return 0
        fun ch(s: Int, d: Int) = ((s * sa + d * da * (1 - sa)) / oa).roundToInt()
        return of((oa * 255).roundToInt(), ch(r(src), r(dst)), ch(g(src), g(dst)), ch(b(src), b(dst)))
    }

    /** Linear mix of opaque colours, keeping [a]'s alpha. */
    fun mix(a: Int, b: Int, t: Double): Int = of(
        alpha(a),
        (r(a) + (r(b) - r(a)) * t).roundToInt(),
        (g(a) + (g(b) - g(a)) * t).roundToInt(),
        (this.b(a) + (this.b(b) - this.b(a)) * t).roundToInt(),
    )
}

/** OKLab: a perceptual colour space, so palettes and distances match what eyes see. */
data class Lab(val l: Double, val a: Double, val b: Double) {
    fun dist2(o: Lab): Double { val dl = l - o.l; val da = a - o.a; val db = b - o.b; return dl * dl + da * da + db * db }

    fun toArgb(alpha: Int = 255): Int {
        val l_ = l + 0.3963377774 * a + 0.2158037573 * b
        val m_ = l - 0.1055613458 * a - 0.0638541728 * b
        val s_ = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l_ * l_ * l_; val m3 = m_ * m_ * m_; val s3 = s_ * s_ * s_
        val r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        val g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        val bb = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
        return Argb.of(alpha, toSrgb(r), toSrgb(g), toSrgb(bb))
    }

    companion object {
        fun fromArgb(c: Int): Lab {
            val r = toLinear(Argb.r(c)); val g = toLinear(Argb.g(c)); val b = toLinear(Argb.b(c))
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return Lab(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        private fun toLinear(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        private fun toSrgb(v: Double): Int {
            val c = v.coerceIn(0.0, 1.0)
            val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
            return (s * 255).roundToInt()
        }
    }
}

/** Lossless compact binary form of a [PixelImage] ("PPX1", width, height, ARGB ints), for app storage. */
object RawImage {
    private val MAGIC = byteArrayOf(0x50, 0x50, 0x58, 0x31)

    fun encode(img: PixelImage): ByteArray {
        val out = ByteArray(12 + img.pixels.size * 4)
        MAGIC.copyInto(out)
        putInt(out, 4, img.width); putInt(out, 8, img.height)
        for (i in img.pixels.indices) putInt(out, 12 + i * 4, img.pixels[i])
        return out
    }

    fun decode(bytes: ByteArray): PixelImage? {
        if (bytes.size < 12 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val w = getInt(bytes, 4); val h = getInt(bytes, 8)
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || bytes.size != 12 + w * h * 4) return null
        return PixelImage(w, h, IntArray(w * h) { getInt(bytes, 12 + it * 4) })
    }

    private fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    private fun getInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
}
