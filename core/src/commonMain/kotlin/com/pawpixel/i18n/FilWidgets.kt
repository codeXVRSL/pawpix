package com.pawpixel.i18n

/**
 * Filipino for the widgets' pet picker, notification channels and the background-running tip. Phone
 * menu names stay in English, as most phones here show them. Should be reviewed by a native speaker.
 */
object FilWidgets {
    val map: Map<String, String> = mapOf(
        // Widget pet picker
        "Which pet should this widget show?" to "Aling alaga ang ipapakita ng widget na ito?",
        "Whoever needs you most" to "Kung sino ang pinakakailangan ka",
        "Shows the pet whose care is due" to "Ipinapakita ang alagang oras na ng pag-aalaga",

        // Notification channels (Android settings)
        "Care reminders" to "Mga paalala sa pag-aalaga",
        "Feeding, walks, water and other everyday care." to "Pagpapakain, lakad, tubig at iba pang araw-araw na pag-aalaga.",
        "Medicine and health" to "Gamot at kalusugan",
        "Medicine doses, vaccines, deworming and vet visits." to "Mga dosis ng gamot, bakuna, pampurga at pagpapatingin sa vet.",

        // Phones that stop apps in the background
        "Some reminders didn't arrive" to "May mga paalalang hindi dumating",
        "Your {0} phone may be closing PawPixel in the background, so reminders can come late or not at all. To fix it:" to
            "Maaaring isinasara ng {0} phone mo ang PawPixel sa background, kaya puwedeng mahuli o hindi dumating ang mga paalala. Para maayos ito:",
        "Open settings" to "Buksan ang settings",
        "Turn on Autostart for PawPixel, then in Settings → Apps → PawPixel → Battery saver, choose “No restrictions”." to
            "I-on ang Autostart para sa PawPixel, tapos sa Settings → Apps → PawPixel → Battery saver, piliin ang “No restrictions”.",
        "In Settings → Apps → PawPixel → Battery usage, turn on “Allow background activity” and “Allow auto launch”." to
            "Sa Settings → Apps → PawPixel → Battery usage, i-on ang “Allow background activity” at “Allow auto launch”.",
        "In Settings → Battery → Background power consumption, allow PawPixel. Turn on Autostart for it too." to
            "Sa Settings → Battery → Background power consumption, payagan ang PawPixel. I-on din ang Autostart nito.",
        "In Settings → Battery → App launch, set PawPixel to “Manage manually” and allow all three." to
            "Sa Settings → Battery → App launch, itakda ang PawPixel sa “Manage manually” at payagan ang tatlo.",
        "In Settings → Apps → PawPixel → Battery, choose “Unrestricted”." to
            "Sa Settings → Apps → PawPixel → Battery, piliin ang “Unrestricted”.",
        "In Settings → Apps → PawPixel → Battery, allow it to run in the background." to
            "Sa Settings → Apps → PawPixel → Battery, payagan itong tumakbo sa background.",
    )
}
