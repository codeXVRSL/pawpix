package com.pawpixel.sprite

/** What the room is decorated for, by the calendar: live-ops without a server. */
enum class Season {
    NONE, HALLOWEEN, CHRISTMAS, VALENTINES;

    companion object {
        /** Oct 24 to 31 Halloween; Dec 15 to Jan 6 Christmas; Feb 12 to 14 Valentine's. */
        fun forDate(month: Int, day: Int): Season = when {
            month == 10 && day >= 24 -> HALLOWEEN
            (month == 12 && day >= 15) || (month == 1 && day <= 6) -> CHRISTMAS
            month == 2 && day in 12..14 -> VALENTINES
            else -> NONE
        }
    }
}
