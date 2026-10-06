package com.pawpixel.i18n

/** Languages PawPixel speaks. Filipino first (the pilot is in Naga City); Bikol can follow the same way. */
enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    FIL("fil", "Filipino");

    companion object {
        /** [setting] is the owner's choice ("" = follow the phone); [system] is the phone's language code. */
        fun resolve(setting: String, system: String): Lang =
            entries.firstOrNull { it.code == setting }
                ?: if (system.lowercase().let { it.startsWith("fil") || it.startsWith("tl") }) FIL else EN
    }
}

/**
 * Translation by English text: every user-facing string is written in English at its call site and
 * looked up here, so a missing translation simply shows English. Variables are {0}, {1}...
 *
 *     tr("{0} is hungry", pet.name)  // "Gutom na si Mochi"
 *
 * [lang] is set by the app from the owner's setting (Settings → Language), before drawing anything.
 */
object I18n {
    @kotlin.concurrent.Volatile
    var lang: Lang = Lang.EN

    val filipino: Map<String, String> by lazy { FilCore.map + FilScreens.map + FilScreens2.map + FilScreens3.map + FilScreens4.map + FilScreens5.map + FilScreens6.map + FilScreens7.map + FilHealth.map + FilWidgets.map + FilAccess.map }

    fun lookup(en: String, l: Lang = lang): String = when (l) {
        Lang.EN -> en
        Lang.FIL -> filipino[en] ?: en
    }

    fun has(en: String): Boolean = filipino.containsKey(en)

    /** Fills {0}, {1}... in one pass, so a pet named "{1}" stays "{1}". */
    fun format(template: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return template
        return PLACEHOLDER.replace(template) { m ->
            val i = m.groupValues[1].toInt()
            if (i < args.size) args[i].toString() else m.value
        }
    }

    private val PLACEHOLDER = Regex("\\{(\\d)\\}")
}

/**
 * A name inside a sentence: first letter lower-cased ("Feed" -> "feed"), acronyms left alone
 * ("FVRCP vaccine" stays as is).
 */
fun inSentence(name: String): String =
    if (name.length > 1 && name[1].isUpperCase()) name else name.replaceFirstChar { it.lowercase() }

/** The owner's language version of [en], with {0}, {1}... filled in. */
fun tr(en: String, vararg args: Any?): String = I18n.format(I18n.lookup(en), args)

/**
 * For names that may be the app's own default (a task called "Feed", "Anti-rabies shot"): shown in
 * the owner's language when PawPixel knows it, otherwise exactly as the owner typed it.
 */
fun trName(name: String): String = I18n.lookup(name)
