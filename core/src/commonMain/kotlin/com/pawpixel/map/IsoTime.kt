package com.pawpixel.map

/** Parses server timestamps like `2026-10-04T08:00:00+00:00` or `...T08:00:00.123Z` to epoch ms. */
object IsoTime {
    private val pattern = Regex("""(\d{4})-(\d\d)-(\d\d)[T ](\d\d):(\d\d)(?::(\d\d)(?:\.(\d+))?)?(Z|[+-]\d\d(?::?\d\d)?)?""")

    fun parseMs(text: String): Long? {
        val m = pattern.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val days = daysFromCivil(g[1].toLong(), g[2].toLong(), g[3].toLong())
        val ms = g[7].padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
        var t = ((days * 24 + g[4].toLong()) * 60 + g[5].toLong()) * 60_000 + (g[6].ifEmpty { "0" }.toLong()) * 1000 + ms
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val minutes = digits.take(2).toLong() * 60 + (digits.drop(2).ifEmpty { "0" }.toLong())
            t -= sign * minutes * 60_000
        }
        return t
    }

    /** Howard Hinnant's days_from_civil. */
    private fun daysFromCivil(y0: Long, m: Long, d: Long): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}
