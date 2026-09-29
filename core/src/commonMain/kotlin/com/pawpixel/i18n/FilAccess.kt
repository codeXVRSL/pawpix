package com.pawpixel.i18n

/**
 * Filipino for what screen readers say (TalkBack, VoiceOver), the reminders question on a pet's
 * page, and the few messages about saving that should never be needed.
 */
object FilAccess {
    val map: Map<String, String> = mapOf(
        // The pet, in words (a drawing only shows the mood)
        "Pixel {0}, {1}" to "Pixel na si {0}, {1}",
        "Pixel {0}" to "Pixel na si {0}",
        "happy" to "masaya",
        "doing fine" to "ayos lang",
        "hungry" to "gutom",
        "restless" to "hindi mapakali",
        "needs care" to "kailangan ng alaga",
        "sleepy" to "inaantok",
        "missing you" to "nami-miss ka",
        "Give pets" to "Lambingin",

        // What buttons do
        "Back" to "Bumalik",
        "Mark {0} done for {1}" to "Markahang tapos ang {0} para kay {1}",
        "Undo {0} for {1}" to "Ibalik ang {0} para kay {1}",
        "Record {0} for {1}" to "Itala ang {0} para kay {1}",
        "Edit {0}" to "I-edit ang {0}",
        "Edit {0}'s name, type and birthday" to "I-edit ang pangalan, uri at kaarawan ni {0}",
        "Open {0}'s page" to "Buksan ang page ni {0}",
        "Edit the weigh-in on {0}" to "I-edit ang timbang noong {0}",
        "1 hour earlier than {0}" to "1 oras bago ang {0}",
        "15 minutes earlier than {0}" to "15 minuto bago ang {0}",
        "15 minutes later than {0}" to "15 minuto pagkatapos ng {0}",
        "1 hour later than {0}" to "1 oras pagkatapos ng {0}",
        "{0}: 30 minutes earlier than {1}" to "{0}: 30 minuto bago ang {1}",
        "{0}: 30 minutes later than {1}" to "{0}: 30 minuto pagkatapos ng {1}",
        "Remove {0}" to "Alisin ang {0}",
        "More for {0}" to "Iba pa para kay {0}",
        "Make the face square smaller" to "Paliitin ang parisukat sa mukha",
        "Make the face square bigger" to "Palakihin ang parisukat sa mukha",
        "Your photo, with a square on your pet's face. Drag it to move it; the Smaller and Bigger buttons resize it." to
            "Ang litrato mo, may parisukat sa mukha ng alaga mo. I-drag ito para ilipat; binabago ng Paliitin at Palakihin ang laki nito.",

        // The pet map, in words
        "Map around your area. Drag to move, pinch to zoom." to "Mapa sa paligid ng area mo. I-drag para gumalaw, i-pinch para mag-zoom.",
        "Your area: {0} pets" to "Ang area mo: {0} alaga",
        "An area near you: {0} pets" to "Isang area na malapit sa iyo: {0} alaga",
        "Show pets" to "Ipakita ang mga alaga",
        "Zoom in" to "Mag-zoom in",
        "Zoom out" to "Mag-zoom out",
        "Back to your area" to "Bumalik sa area mo",

        // Reminders, asked on the pet's page
        "🔔 Reminders for {0}?" to "🔔 Mga paalala para kay {0}?",
        "A gentle nudge when it's time for {0}'s care, only for the tasks you set. Change them any time." to
            "Isang mahinahong paalala kapag oras na para alagaan si {0}, para lang sa mga gawaing itinakda mo. Mababago mo ang mga ito anumang oras.",
        "Turn on reminders" to "I-on ang mga paalala",

        // Deleting a task
        "Delete {0} from {1}'s care?" to "Burahin ang {0} sa pag-aalaga kay {1}?",
        "This can't be undone." to "Hindi na ito maibabalik.",
        "Its history goes with it ({0} records, and any card photos). This can't be undone." to
            "Kasama nitong mabubura ang history ({0} tala, at anumang litrato ng card). Hindi na ito maibabalik.",

        // Saved data that couldn't be read
        "Your pets are safe" to "Ligtas ang mga alaga mo",
        "PawPixel couldn't read its latest save, so it opened the copy from when you last started the app. Care logged after that may be missing." to
            "Hindi mabasa ng PawPixel ang huling na-save nito, kaya binuksan nito ang kopya mula noong huli mong binuksan ang app. Baka wala ang mga pag-aalagang naitala pagkatapos noon.",
        "PawPixel couldn't read its saved pets. The file is kept on this phone; if you have a backup file, restore it from Settings." to
            "Hindi mabasa ng PawPixel ang mga naka-save na alaga. Nakatabi ang file sa phone na ito; kung may backup file ka, i-restore ito sa Settings.",
    )
}
