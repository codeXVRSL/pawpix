package com.pawpixel.core

/**
 * A single-file backup of everything PawPixel keeps: pets, care tasks, history, settings and each
 * pet's small face and photo crop (so its pixel pet can be redrawn). It's a JSON file the owner
 * saves wherever they like (Drive, Files, email) and restores on a new phone. No account needed.
 *
 * Pet-map sign-in is never included: that belongs to the Google/Apple account, not the file.
 */
object Backup {
    const val FORMAT = "pawpixel-backup"
    const val VERSION = 1
    /** The only files a backup may carry, so a crafted file can't write anywhere else. */
    private val FILE_PATH = Regex("sprites/([a-z0-9]{1,40})/(head|photo)\\.bin")
    /** Much larger than any real backup (a pet is ~100 KB), to refuse junk early. */
    const val MAX_BYTES = 40_000_000

    class Contents(val state: AppState, val files: Map<String, ByteArray>, val createdAtMs: Long)

    class NotABackup(message: String) : IllegalArgumentException(message)

    /** Paths of a pet's files that belong in a backup. */
    fun filesFor(petId: String) = listOf("sprites/$petId/head.bin", "sprites/$petId/photo.bin")

    fun encode(state: AppState, files: Map<String, ByteArray>, nowMs: Long): String = Json.obj(
        "format" to FORMAT,
        "version" to VERSION,
        "createdAt" to nowMs,
        "state" to Json.parse(StateCodec.encode(state)),
        "files" to Json.Obj(files.filterKeys { FILE_PATH.matches(it) }.mapValues { (_, b) -> Json.Str(Base64.encode(b)) }),
    ).stringify()

    /** Reads a backup; throws [NotABackup] with a message fit for the owner if it isn't one. */
    fun decode(text: String): Contents {
        if (text.length > MAX_BYTES) throw NotABackup("That file is too big to be a PawPixel backup.")
        val root = runCatching { Json.parse(text) }.getOrNull()
        if (root == null || root["format"].str != FORMAT) throw NotABackup("That file isn't a PawPixel backup.")
        if ((root["version"].int ?: 0) > VERSION) throw NotABackup("This backup is from a newer PawPixel. Update the app, then try again.")
        val state = StateCodec.decode(root["state"].stringify())
        val petIds = state.pets.map { it.id }.toSet()
        val files = (root["files"] as? Json.Obj)?.fields.orEmpty().mapNotNull { (path, v) ->
            val m = FILE_PATH.matchEntire(path) ?: return@mapNotNull null
            if (m.groupValues[1] !in petIds) return@mapNotNull null
            val bytes = v.str?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return@mapNotNull null
            path to bytes
        }.toMap()
        return Contents(state, files, root["createdAt"].long ?: 0L)
    }
}

/** Standard Base64 (RFC 4648, with padding). Small and dependency-free. */
object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INDEX = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            sb.append(ALPHABET[b0 ushr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < bytes.size) ALPHABET[((b1 and 15) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }.trimEnd('=')
        require(clean.all { it.code < 128 && INDEX[it.code] >= 0 }) { "not base64" }
        require(clean.length % 4 != 1) { "bad length" }
        val out = ByteArray(clean.length * 3 / 4)
        var acc = 0; var bits = 0; var o = 0
        for (c in clean) {
            acc = (acc shl 6) or INDEX[c.code]; bits += 6
            if (bits >= 8) { bits -= 8; out[o++] = (acc ushr bits).toByte(); acc = acc and ((1 shl bits) - 1) }
        }
        return out
    }
}
