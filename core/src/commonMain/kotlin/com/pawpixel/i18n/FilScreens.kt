package com.pawpixel.i18n

/**
 * Filipino for app screens (filled in with the screens' own words): home, pet page, care and health
 * rows, progress/weight, and the task editor. Should be reviewed by a native speaker before release.
 */
object FilScreens {
    val map: Map<String, String> = mapOf(
        // Common buttons
        "‹ Back" to "‹ Bumalik",
        "OK" to "OK",
        "Cancel" to "Kanselahin",
        "Save" to "I-save",
        "Edit" to "I-edit",
        "Delete" to "Burahin",
        "Done" to "Done",
        "Undo" to "Ibalik",
        "Close" to "Isara",
        "Remove" to "Alisin",
        "Replace" to "Palitan",
        "Show" to "Ipakita",
        "Hide" to "Itago",
        "Got it" to "Sige",
        "Not now" to "Mamaya na",
        "Name" to "Pangalan",
        "Nice!" to "Ayos!",

        // Home
        "Pet map" to "Mapa ng alaga",
        "Settings" to "Settings",
        "Turn your pet into pixel art" to "Gawing pixel art ang alaga mo",
        "Your pixel pet lives on your home screen and gets hungry, restless or sleepy based on the real care you give. Done a task? Tap it and watch them cheer up." to
            "Nakatira ang pixel pet mo sa home screen at nagugutom, naiinip o inaantok depende sa totoong pag-aalaga mo. Tapos na ang isang gawain? I-tap ito at panoorin siyang sumaya.",
        "Choose a photo" to "Pumili ng litrato",
        "+ Add another pet" to "+ Magdagdag ng isa pang alaga",
        "More pets with Pro" to "Mas maraming alaga sa Pro",
        "Your first pet is free forever. Extra pets are part of PawPixel Pro, which is coming soon." to
            "Libre habambuhay ang una mong alaga. Kasama sa PawPixel Pro ang mga dagdag na alaga, na malapit nang dumating.",
        "Next: {0} {1} · {2}" to "Susunod: {0} {1} · {2}",
        "Put your pet on your home screen" to "Ilagay ang alaga mo sa home screen",
        "See their mood at a glance and tap Done right from the widget." to
            "Makita agad ang mood niya at i-tap ang Done mismo sa widget.",
        "Touch and hold your home screen, tap Edit → Add Widget, search PawPixel, and pick a size." to
            "Pindutin nang matagal ang home screen, i-tap ang Edit → Add Widget, hanapin ang PawPixel, at pumili ng laki.",
        "Touch and hold your home screen, tap Widgets, find PawPixel, and drag it in." to
            "Pindutin nang matagal ang home screen, i-tap ang Widgets, hanapin ang PawPixel, at i-drag ito papasok.",
        "Add widget" to "Idagdag ang widget",

        // Pet page
        "Happiness {0} of 5" to "Saya: {0} sa 5",
        "Tap {0} to give pets" to "I-tap si {0} para lambingin",
        "Shared with {0}" to "Naka-share sa {0}",
        "Cared for with {0}" to "Kasamang nag-aalaga: {0}",
        "👪 Care for {0} together with family" to "👪 Alagaan si {0} kasama ang pamilya",
        "Care" to "Alaga",
        "No care tasks yet. Add feeding, walks or medicine so {0}'s mood can follow real care." to
            "Wala pang gawain. Magdagdag ng pagpapakain, paglalakad o gamot para sumunod ang mood ni {0} sa totoong pag-aalaga.",
        "+ Add care task" to "+ Magdagdag ng gawain",
        "Share & sprite" to "I-share at sprite",
        "Couldn't make the animation. Please try again." to "Hindi nagawa ang animation. Pakisubukan ulit.",
        "Making GIF…" to "Ginagawa ang GIF…",
        "Share animation" to "I-share ang animation",
        "Before/after" to "Before/after",
        "Edit look: photo, face, ears" to "Baguhin ang itsura: litrato, mukha, tainga",
        "Delete {0}" to "Burahin si {0}",
        "Delete {0}?" to "Burahin si {0}?",
        "This removes the sprite, tasks and history from this phone. It can't be undone." to
            "Mabubura ang sprite, mga gawain at history sa phone na ito. Hindi na ito maibabalik.",
        "Your family keeps their copy of {0}, no longer shared." to
            "Mananatili sa pamilya mo ang kopya nila ni {0}, pero hindi na ito shared.",
        "Edit pet" to "I-edit ang alaga",

        // Care task rows
        "Waiting since {0}" to "Naghihintay mula {0}",
        "Done · next {0}" to "Done · susunod {0}",
        "All done today ✓" to "Tapos na lahat ngayon ✓",
        "Next {0}" to "Susunod {0}",
        "(every {0} days)" to "(tuwing {0} araw)",
        "Done by {0} · {1}" to "Ginawa ni {0} · {1}",
        "Adjusted to your routine" to "Inayon sa routine mo",

        // Health
        "Health" to "Kalusugan",
        "{0} is {1}." to "Si {0} ay {1}.",
        "Keep track of {0}'s anti-rabies shot, other vaccines, deworming, tick & flea care and vet check-ups." to
            "Subaybayan ang bakuna kontra rabies ni {0}, iba pang bakuna, purga, pangontra sa garapata at pulgas, at check-up sa vet.",
        "PawPixel reminds you a few days before each is due." to "Ipapaalala ng PawPixel ilang araw bago ang bawat isa.",
        "+ Add health reminders" to "+ Magdagdag ng paalala sa kalusugan",
        "+ Add health item" to "+ Magdagdag sa kalusugan",
        "The first-year plan follows common Philippine schedules. If {0} missed a dose, ask your vet how to catch up; tap Edit to change anything." to
            "Sumusunod ang plano sa unang taon sa karaniwang iskedyul sa Pilipinas. Kung may nalaktawang dose si {0}, tanungin ang vet mo kung paano humabol; i-tap ang Edit para baguhin.",
        "Schedules are typical for adult pets in the Philippines. Your vet's advice comes first: tap Edit to change them." to
            "Karaniwang iskedyul ito para sa mga adult na alaga sa Pilipinas. Mas mahalaga ang payo ng vet mo: i-tap ang Edit para baguhin.",
        "Use this birthday" to "Gamitin ang birthday na ito",
        "Adult / not sure" to "Adult na / hindi sigurado",
        "When was {0} born? A guess is fine." to "Kailan ipinanganak si {0}? Puwede ang hula.",
        "Couldn't read that photo. Try another one." to "Hindi mabasa ang litratong iyan. Subukan ang iba.",
        "First-year series: dose {0} of {1}" to "Serye sa unang taon: dose {0} sa {1}",
        "Last: {0}" to "Huli: {0}",
        "Not recorded yet" to "Wala pang naitala",
        "then {0}" to "tapos {0}",
        "📷 View card" to "📷 Tingnan ang card",
        "📷 Add card photo" to "📷 Magdagdag ng litrato ng card",
        "When was it done?" to "Kailan ito ginawa?",
        "Opening…" to "Binubuksan…",
        "Photo of {0}'s card for {1}" to "Litrato ng card ni {0} para sa {1}",
        "The photo couldn't be opened." to "Hindi mabuksan ang litrato.",
        "Kept only on this phone (and in your backups)." to "Nasa phone na ito lang (at sa mga backup mo).",

        // Rabies and local help
        "Rabies rules and where to get shots" to "Mga patakaran sa rabies at saan magpabakuna",
        "The Anti-Rabies Act (RA 9482) asks every dog owner to:" to "Ayon sa Anti-Rabies Act (RA 9482), dapat ang bawat may-ari ng aso ay:",
        "have the dog vaccinated against rabies every year, and keep the card" to "ipabakuna ang aso kontra rabies taun-taon, at itago ang card",
        "register the dog with the city or municipality" to "iparehistro ang aso sa lungsod o munisipyo",
        "keep it on a leash outside the home" to "itali ito kapag nasa labas ng bahay",
        "report a bite within 24 hours and help the person get treated" to "i-report ang kagat sa loob ng 24 oras at tulungang magamot ang nakagat",
        "If someone is bitten or scratched" to "Kung may nakagat o nakalmot",
        "Wash the wound with soap and running water for 15 minutes, then go to the nearest Animal Bite Treatment Center the same day." to
            "Hugasan ang sugat ng sabon at umaagos na tubig nang 15 minuto, saka pumunta sa pinakamalapit na Animal Bite Treatment Center sa araw ding iyon.",
        "Watch the pet for 14 days." to "Bantayan ang alaga nang 14 na araw.",
        "In Naga City" to "Sa Naga City",
        "Anti-rabies shots for pets 3 months and older (₱75 walk-in in the city's 2023 list; ask for current fees)." to
            "Bakuna kontra rabies para sa alagang 3 buwan pataas (₱75 walk-in sa listahan ng lungsod noong 2023; itanong ang kasalukuyang bayad).",
        "Free consultations; deworming and spay/neuter services." to "Libreng konsulta; may purga at kapon (spay/neuter).",
        "Free rabies drives are usually held in March, Rabies Awareness Month." to
            "Karaniwang may libreng bakuna kontra rabies tuwing Marso, ang Rabies Awareness Month.",

        // "When was it done?" choices
        "Today" to "Ngayon",
        "Yesterday" to "Kahapon",
        "A week ago" to "Isang linggo na",
        "A month ago" to "Isang buwan na",
        "3 months ago" to "3 buwan na",
        "6 months ago" to "6 na buwan na",
        "A year ago" to "Isang taon na",

        // Repeat labels
        "daily" to "araw-araw",
        "weekly" to "linggo-linggo",
        "every 2 weeks" to "tuwing 2 linggo",
        "monthly" to "buwan-buwan",
        "every 3 months" to "tuwing 3 buwan",
        "every 6 months" to "tuwing 6 na buwan",
        "yearly" to "taun-taon",
        "every {0} days" to "tuwing {0} araw",
        "Daily" to "Araw-araw",
        "Every 2 days" to "Tuwing 2 araw",
        "Weekly" to "Linggo-linggo",
        "Every 2 weeks" to "Tuwing 2 linggo",
        "Monthly" to "Buwan-buwan",
        "Every 3 months" to "Tuwing 3 buwan",
        "Every 6 months" to "Tuwing 6 na buwan",
        "Yearly" to "Taun-taon",

        // Progress and weight
        "You've looked after {0} on {1} different days." to "Inalagaan mo si {0} sa {1} magkakaibang araw.",
        "That's a lot of love." to "Ang daming pagmamahal niyan.",
        "Share the card" to "I-share ang card",
        "{0} days of care so far · {1} to go to {2}" to "{0} araw ng pag-aalaga na · {1} pa bago ang {2}",
        "Weight" to "Timbang",
        "Weigh {0} now and then (a bathroom scale works: weigh yourself holding them, then subtract)." to
            "Timbangin si {0} paminsan-minsan (puwede ang timbangan sa banyo: magtimbang habang buhat siya, saka ibawas ang timbang mo).",
        "on {0}" to "noong {0}",
        "+ Add today's weight" to "+ Ilagay ang timbang ngayon",
        "Weight over the last {0} weigh-ins, from {1} to {2}" to "Timbang sa huling {0} pagtimbang, mula {1} hanggang {2}",
        "{0}'s weight" to "Timbang ni {0}",
        "Kilograms, e.g. 4.2" to "Kilo, hal. 4.2",

        // Task editor
        "New health item" to "Bagong item sa kalusugan",
        "New care task" to "Bagong gawain",
        "Edit health item" to "I-edit ang item sa kalusugan",
        "Edit care task" to "I-edit ang gawain",
        "Reminder time on the day" to "Oras ng paalala sa araw na iyon",
        "Times" to "Mga oras",
        "+ Add a time" to "+ Magdagdag ng oras",
        "Repeat" to "Ulitin",
        "Last done" to "Huling ginawa",
        "Never / not sure" to "Hindi pa / hindi sigurado",
        "It'll show as due now. Tap Done once it's given." to "Lalabas itong kailangan na ngayon. I-tap ang Done kapag naibigay na.",
        "The next one is counted from then." to "Doon bibilangin ang susunod.",
        "Reminders" to "Mga paalala",
        "A heads-up 3 days before, and on the day." to "Paalala 3 araw bago, at sa mismong araw.",
        "Get a notification when it's time." to "Makakuha ng notification kapag oras na.",
        "Learn my routine" to "Sundan ang routine ko",
        "Moves reminders toward when you actually do this (up to 2 hours)." to
            "Inuusog ang paalala sa oras na talagang ginagawa mo ito (hanggang 2 oras).",
        "Exact time" to "Eksaktong oras",
        "Remind at the exact minute. Good for medicine. Android may ask for permission." to
            "Magpaalala sa eksaktong minuto. Mainam para sa gamot. Baka humingi ng permiso ang Android.",
        "Delete task" to "Burahin ang gawain",
    )
}
