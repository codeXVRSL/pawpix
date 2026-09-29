package com.pawpixel.core

/**
 * Minimal JSON tree, writer and parser. PawPixel's saved state and widget snapshot are small, and
 * keeping the core free of dependencies means it builds anywhere (Android, iOS, JVM tools, tests).
 */
sealed class Json {
    data object Null : Json()
    data class Bool(val value: Boolean) : Json()
    data class Num(val value: Double) : Json()
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()

    operator fun get(key: String): Json = (this as? Obj)?.fields?.get(key) ?: Null

    val str: String? get() = (this as? Str)?.value
    val long: Long? get() = (this as? Num)?.value?.toLong()
    val int: Int? get() = (this as? Num)?.value?.toInt()
    val double: Double? get() = (this as? Num)?.value
    val bool: Boolean? get() = (this as? Bool)?.value
    val list: List<Json> get() = (this as? Arr)?.items ?: emptyList()

    fun stringify(): String = StringBuilder().also { write(it) }.toString()

    private fun write(sb: StringBuilder) {
        when (this) {
            Null -> sb.append("null")
            is Bool -> sb.append(value)
            is Num -> {
                val v = value
                if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 9.0E15) sb.append(v.toLong()) else sb.append(v)
            }
            is Str -> quote(value, sb)
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { i, j -> if (i > 0) sb.append(','); j.write(sb) }
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k, sb); sb.append(':'); v.write(sb)
                }
                sb.append('}')
            }
        }
    }

    companion object {
        fun obj(vararg pairs: Pair<String, Any?>): Obj = Obj(linkedMapOf<String, Json>().apply {
            for ((k, v) in pairs) put(k, of(v))
        })

        fun arr(items: List<Any?>): Arr = Arr(items.map { of(it) })

        fun of(v: Any?): Json = when (v) {
            null -> Null
            is Json -> v
            is Boolean -> Bool(v)
            is Int -> Num(v.toDouble())
            is Long -> Num(v.toDouble())
            is Double -> Num(v)
            is Float -> Num(v.toDouble())
            is String -> Str(v)
            is List<*> -> Arr(v.map { of(it) })
            is Map<*, *> -> Obj(v.entries.associate { (k, x) -> k.toString() to of(x) })
            else -> Str(v.toString())
        }

        private fun quote(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ') {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else sb.append(c)
                }
            }
            sb.append('"')
        }

        fun parse(text: String): Json = Parser(text).parseDocument()

        /** Deeper than anything PawPixel writes or its server sends. */
        const val MAX_DEPTH = 64
    }

    private class Parser(private val s: String) {
        private var i = 0
        /** Arrays and objects open right now: a hostile or broken reply can't nest deep enough to overflow the stack. */
        private var depth = 0

        fun parseDocument(): Json {
            val v = value()
            ws()
            if (i != s.length) fail("trailing characters")
            return v
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON: $msg at $i")

        private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun value(): Json {
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{', '[' -> {
                    if (++depth > MAX_DEPTH) fail("nested too deep")
                    (if (c == '{') obj() else arr()).also { depth-- }
                }
                '"' -> Str(string())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> if (c == '-' || c.isDigit()) number() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Json): Json {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        private fun number(): Json {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return Num(s.substring(start, i).toDoubleOrNull() ?: fail("bad number"))
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): Json {
            i++
            val items = ArrayList<Json>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return Arr(items) }
            while (true) {
                items += value()
                ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return Arr(items); else -> fail("expected , or ]") }
            }
        }

        private fun obj(): Json {
            i++
            val fields = LinkedHashMap<String, Json>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return Obj(fields) }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val k = string()
                ws()
                if (i >= s.length || s[i++] != ':') fail("expected :")
                fields[k] = value()
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return Obj(fields); else -> fail("expected , or }") }
            }
        }
    }
}
