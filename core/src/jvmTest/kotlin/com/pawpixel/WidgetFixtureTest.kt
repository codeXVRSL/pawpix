package com.pawpixel

import com.pawpixel.core.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS widget reads widget.json with its own Swift port of [WidgetSnapshot.face]. Its tests
 * (iosApp/PawPixelUITests/WidgetRenderTests.swift) use this exact file, so both readers are checked
 * against the same snapshot. After changing the snapshot, regenerate it:
 * run this test with -Dpawpixel.regen=1.
 */
class WidgetFixtureTest {
    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        return d ?: error("repo root not found")
    }

    @Test fun iosFixtureMatchesTheSnapshot() {
        val file = File(repoRoot(), "iosApp/PawPixelUITests/widget-fixture.json")
        val built = fixture()
        if (System.getProperty("pawpixel.regen") != null) file.writeText(built.stringify())
        assertEquals(built, Json.parse(file.readText()), "widget-fixture.json is out of date: run with -Dpawpixel.regen=1")
    }

    companion object {
        /** Mochi (feed 7 AM and 5 PM) and Kiko (litter 6 AM), Manila, written at 6 AM on day 20600. */
        fun fixture(): Json {
            val clock = LocalClock.MANILA
            val day = 20600L
            val s = AppState(
                pets = listOf(Pet("mochi", "Mochi", Species.DOG, 0), Pet("kiko", "Kiko", Species.CAT, 0)),
                tasks = listOf(
                    CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(7 * 60, 17 * 60), anchorDay = 0, adaptive = false),
                    CareTask("litter", "kiko", TaskKind.LITTER, "Clean litter", listOf(6 * 60), anchorDay = 0, adaptive = false),
                ),
            )
            return WidgetSnapshot.build(s, clock.at(day, 6 * 60), clock)
        }
    }
}
