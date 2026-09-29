package com.pawpixel.core

/**
 * Notices reminders the phone never delivered. Many Android phones (Oppo, Realme, Vivo, Xiaomi...)
 * stop apps in the background, and their alarms go with them. The platform remembers what it
 * scheduled and which alarms went off (even ones skipped because the care was already done); a
 * reminder whose time is well past without its alarm ever going off was lost.
 *
 * Only counted when the list is replaced (the app or a background task ran again), and forgotten
 * after a restart, when the phone clears every app's alarms anyway (and they are planned again).
 */
object ReminderDelivery {
    /** Android may hold an ordinary alarm back for a while in battery saving; later than this is lost. */
    const val GRACE_MS = 2 * HOUR_MS
    /** Lost reminders before PawPixel suggests the phone's background setting. One could be a fluke. */
    const val TIP_AFTER = 2

    /** Scheduled reminders as "id@at" pairs, compact enough for preferences. */
    fun encode(scheduled: List<Pair<Int, Long>>): String = scheduled.joinToString(",") { (id, at) -> "$id@$at" }

    fun decode(text: String?): List<Pair<Int, Long>> =
        text.orEmpty().split(',').mapNotNull { part ->
            val id = part.substringBefore('@').toIntOrNull() ?: return@mapNotNull null
            val at = part.substringAfter('@', "").toLongOrNull() ?: return@mapNotNull null
            id to at
        }

    /** How many of [scheduled] should have gone off by [nowMs] but never did ([fired]: ids that did). */
    fun lost(scheduled: List<Pair<Int, Long>>, fired: Set<Int>, nowMs: Long): Int =
        scheduled.count { (id, at) -> at <= nowMs - GRACE_MS && id !in fired }
}
