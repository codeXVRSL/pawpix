package com.pawpixel.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.MAX_WBITS
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream

/** gzip, with the system's zlib: map tiles sometimes arrive packed. */
@OptIn(ExperimentalForeignApi::class)
object Gzip {
    fun inflate(bytes: ByteArray): ByteArray? {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return null
        return runCatching {
            memScoped {
                val stream = alloc<z_stream>()
                if (inflateInit2(stream.ptr, 16 + MAX_WBITS) != Z_OK) return null
                val out = ArrayList<Byte>(bytes.size * 4)
                val chunk = ByteArray(64 * 1024)
                var result: ByteArray? = null
                bytes.usePinned { inPin ->
                    stream.next_in = inPin.addressOf(0).reinterpret()
                    stream.avail_in = bytes.size.convert()
                    while (true) {
                        val code = chunk.usePinned { outPin ->
                            stream.next_out = outPin.addressOf(0).reinterpret()
                            stream.avail_out = chunk.size.convert()
                            val c = inflate(stream.ptr, Z_NO_FLUSH)
                            val produced = chunk.size - stream.avail_out.toInt()
                            for (i in 0 until produced) out.add(chunk[i])
                            c
                        }
                        if (code == Z_STREAM_END) { result = out.toByteArray(); break }
                        if (code != Z_OK && code != Z_BUF_ERROR) break
                        if (code == Z_BUF_ERROR && stream.avail_in.toInt() == 0) break
                    }
                }
                inflateEnd(stream.ptr)
                result
            }
        }.getOrNull()
    }
}
