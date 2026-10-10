package com.pawpixel

import com.pawpixel.core.*
import com.pawpixel.i18n.I18n
import com.pawpixel.i18n.Lang
import com.pawpixel.i18n.tr
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every tr("...") in the app has a Filipino translation with the same {placeholders}. Reads the
 * source files, so a new English string without Filipino fails the build instead of shipping.
 */
class TranslationTest {
    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d ?: error("repo root not found")
    }

    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val call = Regex("\\btr\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")*)")

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\'", "'").replace("\\\\", "\\")

    private fun keysInSources(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val dirs = listOf("composeApp/src", "core/src/commonMain").map { File(repoRoot(), it) }
        for (dir in dirs) dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            for (m in call.findAll(f.readText())) {
                val key = literal.findAll(m.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
                out[key] = "${f.name}"
            }
        }
        return out
    }

    private fun placeholders(s: String) = Regex("\\{\\d+\\}").findAll(s).map { it.value }.toSortedSet()

    private val tables get() = listOf("Filipino" to I18n.filipino, "Spanish" to I18n.spanish, "Portuguese" to I18n.portuguese)

    @Test fun everyStringInTheAppHasEveryLanguage() {
        val keys = keysInSources()
        assertTrue(keys.size > 300, "found ${keys.size} tr() strings: is the scan working?")
        for ((name, table) in tables) {
            val missing = keys.filterKeys { it !in table }
            assertTrue(missing.isEmpty(), "No $name for:\n" + missing.entries.joinToString("\n") { (k, f) -> "  $f: \"$k\"" })
        }
        // Everything Filipino has (month names, widget lines, health items...) is covered too.
        assertTrue(I18n.filipino.keys.all { it in I18n.spanish && it in I18n.portuguese }, "a Filipino string has no Spanish or Portuguese")
    }

    @Test fun translationsKeepTheirPlaceholders() {
        for ((name, table) in tables) {
            val bad = table.filter { (en, t) -> placeholders(en) != placeholders(t) || t.isBlank() || '$' in t }
            assertTrue(bad.isEmpty(), "$name placeholders differ:\n" + bad.entries.joinToString("\n") { "  \"${it.key}\" -> \"${it.value}\"" })
        }
    }

    @Test fun theCareEngineSpeaksFilipino() {
        val clock = LocalClock.MANILA
        val pet = Pet("p1", "Mochi", Species.DOG, 0)
        val feed = CareTask("f", "p1", TaskKind.FEED, "Feed", listOf(7 * 60), anchorDay = 0, adaptive = false)
        val s = AppState(pets = listOf(pet), tasks = listOf(feed))
        try {
            I18n.lang = Lang.FIL
            assertEquals("Gutom na si Mochi", MoodEngine.read(s, "p1", clock.at(20000, 9 * 60 + 30), clock).caption)
            assertEquals("Set 29, 2026", LocalClock.shortDate(20725))
            val r = ReminderPlanner.plan(s, clock.at(20000, 5 * 60), clock).first()
            assertEquals("🍖 Pagpapakain · Mochi", r.title)
            assertEquals(Lang.FIL, Lang.resolve("", "tl"))
            assertEquals(Lang.EN, Lang.resolve("en", "fil"))
            assertEquals("Ayos lang si {0}".replace("{0}", "Mochi"), tr("{0} is doing fine", "Mochi"))
        } finally {
            I18n.lang = Lang.EN
        }
    }
}
