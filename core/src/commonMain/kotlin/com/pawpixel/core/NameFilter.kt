package com.pawpixel.core

/**
 * Keeps offensive words out of pet names shown to other owners on the pet map (App Store 1.2 and
 * Google Play's user-generated content policy ask for a filter). The map server applies the same
 * list (supabase/migrations/0003), so a modified app can't get around it.
 *
 * Deliberately short and conservative: common English and Filipino profanity and slurs, matched
 * after undoing easy disguises (case, repeated letters, 0→o, 1→i, 3→e, 4→a, 5→s, @→a, $→s).
 * Short words only match a whole word, so "Grape", "Petite" and "Dickens" are fine; long ones match
 * anywhere, also spaced out. Reports and moderators handle the rest.
 */
object NameFilter {
    /** Kept in sync with `blocked_words` in supabase/migrations/0003_name_filter.sql. */
    val BLOCKED = listOf(
        "fuck", "shit", "bitch", "cunt", "dick", "pussy", "whore", "slut", "nigger", "nigga", "faggot", "retard", "rape",
        "putangina", "tangina", "puta", "gago", "tarantado", "ulol", "kupal", "pokpok", "bayag", "kantot", "tite", "puke", "pekpek", "burat",
    )

    fun normalize(text: String): String {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            val c = when (ch) {
                '0' -> 'o'; '1', '!', '|' -> 'i'; '3' -> 'e'; '4', '@' -> 'a'; '5', '$' -> 's'; '7' -> 't'
                else -> ch
            }
            if (c in 'a'..'z' && (sb.isEmpty() || sb.last() != c)) sb.append(c)
        }
        return sb.toString()
    }

    /** Words this long or longer match anywhere in the name; shorter ones only as a whole word. */
    const val ANYWHERE_FROM = 6

    /** True if the name contains a blocked word (also when spelled with repeats or look-alike digits). */
    fun isBlocked(name: String): Boolean {
        val words = name.split(Regex("[\\s._\\-,/+&*~]+")).map(::normalize).filter { it.isNotEmpty() }.toSet()
        val joined = normalize(name)
        return BLOCKED.any { w ->
            val sw = squeeze(w)
            if (w.length >= ANYWHERE_FROM) joined.contains(sw) else sw in words
        }
    }

    /** The name to show other owners: the pet's own, or a neutral one if it's blocked. */
    fun forMap(name: String, species: Species): String = if (isBlocked(name)) "A ${species.label.lowercase()}" else name

    private fun squeeze(w: String) = w.fold(StringBuilder()) { sb, c -> if (sb.isEmpty() || sb.last() != c) sb.append(c) else sb }.toString()
}
