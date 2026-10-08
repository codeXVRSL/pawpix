package com.pawpixel.i18n

/** Filipino for units (km or miles, kg or lb), the country-aware health notes, and the monthly challenge. */
internal object FilScreens9 {
    val map: Map<String, String> = mapOf(
        // Units
        "Units" to "Mga sukat",
        "Phone's country ({0})" to "Bansa ng telepono ({0})",
        "Walks, distances on the map and weigh-ins. Weights are kept in grams, so switching loses nothing." to
            "Mga lakad, distansya sa mapa, at timbang. Naka-gramo ang naka-save na timbang, kaya walang mawawala kapag nagpalit.",
        "miles & lb" to "milya & lb",
        "miles & kg" to "milya & kg",
        "km & kg" to "km & kg",
        "{0} mi" to "{0} mi",
        "{0} km" to "{0} km",
        "{0} miles" to "{0} milya",
        "{0} since {1}" to "{0} mula {1}",
        "{0}{1} since {2}" to "{0}{1} mula noong {2}",
        "Pounds, e.g. 9.3" to "Libra (lb), hal. 9.3",
        "Subtract 1 {0}" to "Bawasan ng 1 {0}",
        "Subtract 0.1 {0}" to "Bawasan ng 0.1 {0}",
        "Add 0.1 {0}" to "Dagdagan ng 0.1 {0}",
        "Add 1 {0}" to "Dagdagan ng 1 {0}",
        "{0} steps · about {1}" to "{0} hakbang · mga {1}",
        "1 walk · {0} min · about {1}" to "1 lakad · {0} min · mga {1}",
        "{0} walks · {1} min · about {2}" to "{0} lakad · {1} min · mga {2}",
        "• Your area shows as a square about {0} wide. Your exact location never leaves your phone." to
            "• Lumalabas ang lugar mo bilang parisukat na mga {0} ang lapad. Hindi kailanman lumalabas sa telepono mo ang eksaktong lokasyon mo.",
        "Raise an alert: PawPixel owners within {1} see {0}'s photos and pixel twin on their map and can report where they saw {0}. Nobody sees your name or your home." to
            "Mag-alerto: makikita ng mga may-ari ng PawPixel sa loob ng {1} ang mga litrato at pixel na kambal ni {0} sa mapa nila at maaari nilang iulat kung saan nila nakita si {0}. Walang makakakita ng pangalan mo o ng bahay mo.",
        "{0} · {1} from where they were last seen" to "{0} · {1} mula sa huling kinakitaan",
        "Last seen {0} · {1} from your area" to "Huling nakita {0} · {1} mula sa lugar mo",
        "Last seen {0} · {1} away" to "Huling nakita {0} · {1} ang layo",
        "We'll look at it. If someone is in danger, contact the police." to "Titingnan namin. Kung may nasa panganib, tumawag sa pulis.",

        // Moments for pals
        "Up to {0} friends, by code only. Pals see your pixel pets and their names, and only the moments you choose to share: never your place or your care. Their pets drop by your room; send theirs a treat." to
            "Hanggang {0} kaibigan, sa code lang. Nakikita ng mga pal ang mga pixel na alaga mo at ang pangalan nila, at ang mga moment lang na pinili mong ibahagi: hindi kailanman ang lugar mo o ang pag-aalaga mo. Dumadalaw ang mga alaga nila sa kuwarto mo; padalhan ng treat ang kanila.",
        "Moments" to "Mga moment",
        "Shared with your pals for two days." to "Ibinahagi sa mga pal mo sa loob ng dalawang araw.",
        "A photo of the day for your pals and nobody else. It's gone after two days; no likes, no comments." to
            "Isang litrato ng araw para sa mga pal mo at wala nang iba. Nawawala pagkatapos ng dalawang araw; walang like, walang comment.",
        "From your pals in the last two days. Yours is gone after two days; no likes, no comments." to
            "Mula sa mga pal mo sa nakaraang dalawang araw. Nawawala ang sa iyo pagkatapos ng dalawang araw; walang like, walang comment.",
        "Your moment: {0}" to "Ang moment mo: {0}",
        "Take it down" to "Tanggalin",
        "Share another" to "Magbahagi ng iba",
        "Share a moment" to "Magbahagi ng moment",
        "{0}'s moment: {1}" to "Moment ni {0}: {1}",
        "A pet" to "Isang alaga",
        "The photo to share" to "Ang litratong ibabahagi",
        "Pick a photo" to "Pumili ng litrato",
        "Another photo" to "Ibang litrato",
        "Or from {0}'s album" to "O mula sa album ni {0}",
        "Album photo" to "Litrato sa album",
        "Caption (optional)" to "Caption (opsyonal)",
        "Only your pals see it, for two days. The photo is shrunk on your phone first; its location data is dropped." to
            "Mga pal mo lang ang makakakita, sa loob ng dalawang araw. Pinaliliit muna ang litrato sa telepono mo; tinatanggal ang location data nito.",

        // Health items outside the Philippines
        "Rabies vaccine" to "Bakuna kontra rabies",
        "DAPP vaccine" to "DAPP na bakuna",
        "DHPP vaccine" to "DHPP na bakuna",
        "DHP vaccine" to "DHP na bakuna",
        "C5 vaccine" to "C5 na bakuna",
        "F3 vaccine" to "F3 na bakuna",
        "FeLV vaccine" to "FeLV na bakuna",
        "Leptospirosis vaccine" to "Bakuna kontra leptospirosis",
        "Flea & tick prevention" to "Pangontra sa pulgas at garapata",

        // Health notes outside the Philippines
        "the AAHA and AAFP vaccination guidelines" to "ang AAHA at AAFP vaccination guidelines",
        "the BSAVA vaccination guidance" to "ang BSAVA vaccination guidance",
        "the AVA vaccination policy" to "ang AVA vaccination policy",
        "the WSAVA vaccination guidelines" to "ang WSAVA vaccination guidelines",
        "The first-year plan follows {1}. If {0} missed a dose, ask your vet how to catch up; tap Edit to change anything." to
            "Sumusunod ang unang-taong plano sa {1}. Kung may nalaktawang dose si {0}, itanong sa vet kung paano hahabol; i-tap ang Edit para baguhin ang kahit ano.",
        "That photo couldn't be read. Try another." to "Hindi mabasa ang litratong iyon. Sumubok ng iba.",
        "Schedules are typical for adult dogs and cats. Your vet's advice comes first: tap Edit to change them." to
            "Karaniwang iskedyul ito para sa adult na aso at pusa. Mas mahalaga ang payo ng vet mo: i-tap ang Edit para baguhin.",

        // Monthly challenge
        "{0} challenge" to "Hamon ng {0}",
        "{0} challenge: {1}. {2}." to "Hamon ng {0}: {1}. {2}.",
        "Done!" to "Tapos na!",
        "Last day" to "Huling araw",
        "{0} days left" to "{0} araw na lang",
        "{0} did it. Same time next month!" to "Nagawa ni {0}. Sa susunod na buwan ulit!",
        "Log care on {0} days in {1}" to "Mag-log ng alaga sa {0} araw ngayong {1}",
        "Take {0} walks with the app in {1}" to "Maglakad nang {0} beses gamit ang app ngayong {1}",
        "Walk {0} together in {1}" to "Maglakad nang {0} nang magkasama ngayong {1}",
        "Add {0} photos to the album in {1}" to "Magdagdag ng {0} litrato sa album ngayong {1}",
        "{0} of {1} days" to "{0} sa {1} araw",
        "{0} of {1} walks" to "{0} sa {1} lakad",
        "{0} of {1}" to "{0} sa {1}",
        "{0} of {1} photos" to "{0} sa {1} litrato",
        "Fresh start" to "Bagong simula",
        "Show the love" to "Ipakita ang pagmamahal",
        "Spring in your step" to "Masiglang hakbang",
        "Every day counts" to "Bawat araw mahalaga",
        "Miles of smiles" to "Lakad at ngiti",
        "Summer snapshots" to "Mga kuha ng tag-init",
        "Steady as we go" to "Tuloy-tuloy lang",
        "Walkies month" to "Buwan ng paglalakad",
        "Long walk home" to "Mahabang lakad pauwi",
        "Spooky streak" to "Sunod-sunod na alaga",
        "Thankful snaps" to "Mga kuha ng pasasalamat",
        "Winter walkies" to "Lakad ng Disyembre",
        "January" to "Enero", "February" to "Pebrero", "March" to "Marso", "April" to "Abril", "May" to "Mayo", "June" to "Hunyo",
        "July" to "Hulyo", "August" to "Agosto", "September" to "Setyembre", "October" to "Oktubre", "November" to "Nobyembre", "December" to "Disyembre",
    )
}
