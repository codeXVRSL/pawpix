package com.pawpixel

import com.pawpixel.core.*
import com.pawpixel.sprite.Exif
import com.pawpixel.sprite.PixelImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pre-launch fixes: data that can't be lost, taps that land twice, sideways photos, hostile JSON. */
class QualityTest {
    private val clock = LocalClock.MANILA
    private val pet = Pet("mochi", "Mochi", Species.DOG, 0)
    private val feed = CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(7 * 60, 18 * 60), anchorDay = 0, adaptive = false)
    private val state = AppState(pets = listOf(pet), tasks = listOf(feed))

    // ---- Saved state: a damaged file never turns into an empty app ----

    @Test fun aGoodSaveLoadsAsItIs() {
        val loaded = StateCodec.load(StateCodec.encode(state), null)
        assertEquals(StateCodec.Source.SAVED, loaded.source)
        assertEquals(listOf("Mochi"), loaded.state.pets.map { it.name })
    }

    @Test fun aDamagedSaveFallsBackToTheLastGoodCopy() {
        val good = StateCodec.encode(state)
        val newer = StateCodec.encode(StateOps.complete(state, "feed", clock.at(20000, 7 * 60), clock))
        // Cut short by a crash or a full disk, zero bytes after a power cut, or junk.
        for (damaged in listOf(newer.take(newer.length / 2), "", "\u0000\u0000\u0000", "null", "[]", "{\"pets\":[]}")) {
            val loaded = StateCodec.load(damaged, good)
            assertEquals(StateCodec.Source.BACKUP, loaded.source, "for '$damaged'")
            assertEquals(listOf("Mochi"), loaded.state.pets.map { it.name })
        }
        // The save itself missing (a crash between writing the copy and the save): the copy.
        assertEquals(StateCodec.Source.BACKUP, StateCodec.load(null, good).source)
    }

    @Test fun theRemindersQuestionIsAskedOnce() {
        val asked = state.copy(settings = state.settings.copy(remindersAsked = true))
        assertEquals(true, StateCodec.decode(StateCodec.encode(asked)).settings.remindersAsked)
        // Saves from before the question existed: not asked yet.
        assertEquals(false, StateCodec.decode(StateCodec.encode(state).replace(",\"remindersAsked\":false", "")).settings.remindersAsked)
    }

    @Test fun nothingReadableIsReportedNotHidden() {
        assertEquals(StateCodec.Source.NEW, StateCodec.load(null, null).source)
        assertEquals(StateCodec.Source.NEW, StateCodec.load("", null).source)
        val broken = StateCodec.load("{\"settings\":{\"pro\":tr", "also broken")
        assertEquals(StateCodec.Source.UNREADABLE, broken.source)
        assertTrue(broken.state.pets.isEmpty())
    }

    // ---- JSON from the server or a file can't crash the app ----

    @Test fun deeplyNestedJsonIsRefusedNotAStackOverflow() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertFailsWith<IllegalArgumentException> { Json.parse(deep) }
        val objects = "{\"a\":".repeat(50_000) + "1" + "}".repeat(50_000)
        assertFailsWith<IllegalArgumentException> { Json.parse(objects) }
        // What PawPixel really nests still parses.
        val fine = "[".repeat(Json.MAX_DEPTH) + "1" + "]".repeat(Json.MAX_DEPTH)
        assertEquals(1, generateSequence(Json.parse(fine)) { it.list.firstOrNull() }.last().int)
        assertEquals(null, StateCodec.load(deep, null).state.pets.firstOrNull())
    }

    @Test fun aHotspotLoginPageOrACutOffReplyIsAPlainMessage() {
        val saved = arrayOfNulls<String>(1)
        val store = object : com.pawpixel.map.SessionStore {
            override fun load() = saved[0]
            override fun save(json: String?) { saved[0] = json }
        }
        var reply = com.pawpixel.map.HttpResponse(200, "<html><body>Free Wi-Fi: log in</body></html>")
        val client = com.pawpixel.map.MapClient(
            com.pawpixel.map.MapSettings("https://x.supabase.co", "anon", "", "", ""),
            com.pawpixel.map.Http { reply }, store,
        ) { 1_700_000_000_000L }
        fun failure() = runCatching { runSync { client.signInWithIdToken("google", "t", null) } }.exceptionOrNull()
        val login = failure()
        assertTrue(login is com.pawpixel.map.MapException && login.kind == com.pawpixel.map.MapException.Kind.OFFLINE, "$login")
        reply = com.pawpixel.map.HttpResponse(200, "{\"access_token\":\"tok1\",\"refresh_tok")
        val cut = failure()
        assertTrue(cut is com.pawpixel.map.MapException && cut.kind == com.pawpixel.map.MapException.Kind.SERVER, "$cut")
        assertTrue(!cut.message!!.contains("JSON"), cut.message)
    }

    // ---- Done tapped twice ----

    @Test fun aDoubleTapOnDoneLogsOnce() {
        val t = clock.at(20000, 7 * 60 + 5)
        var s = StateOps.completeTap(state, "feed", t, clock)
        s = StateOps.completeTap(s, "feed", t + 400, clock)
        s = StateOps.completeTap(s, "feed", t + 2_000, clock)
        assertEquals(1, s.completions.size, "one breakfast, not breakfast and dinner")
        // A real second feed later that day counts.
        s = StateOps.completeTap(s, "feed", t + 10 * MINUTE_MS, clock)
        assertEquals(2, s.completions.size)
        // Someone at home logged it a second ago: this tap is the same feed.
        val byPartner = state.copy(completions = listOf(Completion("feed", t, 7 * 60 + 5, 20000, id = "p1", by = "jamaica")))
        assertEquals(1, StateOps.completeTap(byPartner, "feed", t + 1_000, clock).completions.size)
        // Other tasks are separate taps.
        val walk = CareTask("walk", "mochi", TaskKind.WALK, "Walk", listOf(8 * 60), anchorDay = 0, adaptive = false)
        val both = StateOps.completeTap(StateOps.completeTap(state.copy(tasks = listOf(feed, walk)), "feed", t, clock), "walk", t + 100, clock)
        assertEquals(2, both.completions.size)
    }

    // ---- Sideways photos (Android 8 doesn't turn them) ----

    private fun jpegWithOrientation(orientation: Int, littleEndian: Boolean): ByteArray {
        fun u16(v: Int) = if (littleEndian) listOf(v and 0xff, v shr 8) else listOf(v shr 8, v and 0xff)
        fun u32(v: Int) = if (littleEndian) u16(v and 0xffff) + u16(v ushr 16) else u16(v ushr 16) + u16(v and 0xffff)
        val tiff = (if (littleEndian) listOf(0x49, 0x49) else listOf(0x4D, 0x4D)) + u16(42) + u32(8) +
            u16(2) + // two entries: something else first, then the orientation
            u16(0x010F) + u16(2) + u32(4) + u32(0) +
            u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) +
            u32(0)
        val app1 = "Exif".map { it.code } + listOf(0, 0) + tiff
        val bytes = listOf(0xFF, 0xD8) +
            listOf(0xFF, 0xE0, 0, 4, 0, 0) + // an APP0 before it, as cameras write
            listOf(0xFF, 0xE1) + listOf((app1.size + 2) shr 8, (app1.size + 2) and 0xff) + app1 +
            listOf(0xFF, 0xDA, 0, 2, 1, 2, 3)
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    @Test fun exifOrientationIsReadFromBothByteOrders() {
        for (o in 1..8) {
            assertEquals(o, Exif.orientation(jpegWithOrientation(o, littleEndian = true)))
            assertEquals(o, Exif.orientation(jpegWithOrientation(o, littleEndian = false)))
        }
        // No EXIF, not a JPEG, cut short, nonsense: upright, never a crash.
        val six = jpegWithOrientation(6, true)
        for (n in six.indices) Exif.orientation(six.copyOf(n))
        assertEquals(1, Exif.orientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 0, 2)))
        assertEquals(1, Exif.orientation(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals(1, Exif.orientation(ByteArray(0)))
        assertEquals(1, Exif.orientation(jpegWithOrientation(9, true)))
        val random = kotlin.random.Random(7)
        repeat(200) { Exif.orientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + random.nextBytes(64)) }
    }

    @Test fun uprightTurnsThePixels() {
        // 0 1 2
        // 3 4 5
        val img = PixelImage(3, 2, IntArray(6) { it })
        fun rows(p: PixelImage) = (0 until p.height).map { y -> (0 until p.width).map { x -> p[x, y] } }
        assertEquals(listOf(listOf(3, 0), listOf(4, 1), listOf(5, 2)), rows(Exif.upright(img, 6)))   // 90° clockwise
        assertEquals(listOf(listOf(2, 5), listOf(1, 4), listOf(0, 3)), rows(Exif.upright(img, 8)))   // 90° counter-clockwise
        assertEquals(listOf(listOf(5, 4, 3), listOf(2, 1, 0)), rows(Exif.upright(img, 3)))           // 180°
        assertEquals(listOf(listOf(2, 1, 0), listOf(5, 4, 3)), rows(Exif.upright(img, 2)))           // mirrored
        assertEquals(listOf(listOf(3, 4, 5), listOf(0, 1, 2)), rows(Exif.upright(img, 4)))           // upside down mirror
        assertEquals(listOf(listOf(0, 3), listOf(1, 4), listOf(2, 5)), rows(Exif.upright(img, 5)))   // transposed
        assertEquals(listOf(listOf(5, 2), listOf(4, 1), listOf(3, 0)), rows(Exif.upright(img, 7)))
        assertEquals(rows(img), rows(Exif.upright(Exif.upright(img, 6), 8)))
        assertTrue(Exif.upright(img, 1) === img)
    }

    // ---- Drawings of the pet stay current ----

    @Test fun theLookKeyChangesWithEverythingThePetIsDrawnFrom() {
        val key = pet.lookKey
        for (changed in listOf(pet.copy(accessory = "BANDANA"), pet.copy(ears = "POINTY"), pet.copy(lookCode = "1;abcdef;0"),
            pet.copy(species = Species.CAT), pet.copy(spriteVersion = 2))) {
            assertNotEquals(key, changed.lookKey, "$changed")
        }
        assertEquals(key, pet.copy(name = "Mochi Jr", careDays = listOf(1, 2), milestoneSeen = 7).lookKey)
    }

    @Test fun spritesAreDescribedWithNameAndMood() {
        assertEquals("Pixel Mochi, hungry", MoodEngine.describe("Mochi", Mood.HUNGRY))
        assertEquals("Pixel Mochi, sleepy", MoodEngine.describe("Mochi", Mood.SLEEPY))
        assertTrue(Mood.entries.map { MoodEngine.describe("Mochi", it) }.toSet().size == Mood.entries.size)
    }
}
