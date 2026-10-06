package com.pawpixel

import com.pawpixel.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The album: photos kept with a pet, and a pet remembered after it passed away. */
class AlbumTest {
    private val clock = LocalClock.MANILA
    private val today = 20500L
    private val mochi = Pet("mochi", "Mochi", Species.DOG, 0)
    private val feed = CareTask("feed", "mochi", TaskKind.FEED, "Feed", listOf(7 * 60, 18 * 60), anchorDay = 0, adaptive = false)
    private val base = AppState(pets = listOf(mochi), tasks = listOf(feed))

    @Test fun photosAreKeptNewestFirstAndSurviveSaving() {
        var s = StateOps.addAlbumPhoto(base, AlbumPhoto("p1", "mochi", 1000, "  First walk  "))
        s = StateOps.addAlbumPhoto(s, AlbumPhoto("p2", "mochi", 2000, "x".repeat(500)))
        assertEquals(listOf("p2", "p1"), s.albumFor("mochi").map { it.id })
        assertEquals("First walk", s.album.first { it.id == "p1" }.caption)
        assertEquals(AppState.MAX_CAPTION, s.album.first { it.id == "p2" }.caption.length)
        val back = StateCodec.decode(StateCodec.encode(s))
        assertEquals(s.album.toSet(), back.album.toSet())
        s = StateOps.setAlbumCaption(s, "p1", "Beach day")
        assertEquals("Beach day", s.album.first { it.id == "p1" }.caption)
        s = StateOps.removeAlbumPhoto(s, "p2")
        assertEquals(listOf("p1"), s.albumFor("mochi").map { it.id })
        // Another pet's album never mixes in.
        assertEquals(emptyList(), s.albumFor("kiko"))
    }

    @Test fun theAlbumHasALimitAndTheOldestGoesFirst() {
        var s = base
        for (i in 0 until AppState.MAX_ALBUM_PHOTOS_PER_PET + 5) s = StateOps.addAlbumPhoto(s, AlbumPhoto("p$i", "mochi", i.toLong()))
        assertEquals(AppState.MAX_ALBUM_PHOTOS_PER_PET, s.albumFor("mochi").size)
        assertEquals("p${AppState.MAX_ALBUM_PHOTOS_PER_PET + 4}", s.albumFor("mochi").first().id)
        assertTrue(s.album.none { it.id == "p0" })
    }

    @Test fun photosTravelInBackupsAndGoWithADeletedPet() {
        val s = StateOps.addAlbumPhoto(base, AlbumPhoto("p1", "mochi", 1000, "Hi"))
        val path = Backup.albumPhotoPath("mochi", "p1")
        assertEquals("sprites/mochi/album-p1.jpg", path)
        assertTrue(path in Backup.filesFor(s, "mochi"))
        val bytes = byteArrayOf(1, 2, 3)
        val back = Backup.decode(Backup.encode(s, mapOf(path to bytes), 5))
        assertEquals(listOf(1.toByte(), 2, 3), back.files[path]?.toList())
        assertEquals("Hi", back.state.album.single().caption)
        assertEquals(emptyList(), StateOps.removePet(s, "mochi").album)
        // Only a pet in the backup may own a photo (a crafted file can't point elsewhere).
        val stray = StateCodec.encode(s).replace("\"petId\":\"mochi\",\"at\"", "\"petId\":\"ghost\",\"at\"")
        assertEquals(emptyList(), StateCodec.decode(stray).album)
    }

    @Test fun aRememberedPetRestsWithNoRemindersOrNeeds() {
        val now = clock.at(today, 19 * 60) // dinner is an hour overdue
        val before = ReminderPlanner.plan(base, now, clock)
        assertTrue(before.isNotEmpty())
        val s = StateOps.rememberPet(base, "mochi", today, now)
        assertEquals(today, s.pet("mochi")!!.rememberedDay)
        assertTrue(s.pet("mochi")!!.remembered)
        assertEquals(emptyList(), ReminderPlanner.plan(s, now, clock), "no more reminders")
        val reading = MoodEngine.read(s, "mochi", now, clock)
        assertEquals(Mood.SLEEPY, reading.mood)
        assertEquals(100, reading.score)
        assertNull(reading.urgentTaskId)
        assertEquals("Mochi is resting. Forever in your heart.", reading.caption)
        // The widget shows it resting, with nothing to tap.
        val face = WidgetSnapshot.face(WidgetSnapshot.build(s, now, clock), now, WidgetSnapshot.MOST_IN_NEED)
        assertEquals("sleepy", face?.mood?.key)
        assertNull(face?.actionTaskId)
        // Its history stays: tasks and records aren't deleted.
        assertEquals(1, s.tasksFor("mochi").size)
        assertEquals(today, StateCodec.decode(StateCodec.encode(s)).pet("mochi")!!.rememberedDay)
        // And it can be undone.
        assertNull(StateOps.rememberPet(s, "mochi", null, now).pet("mochi")!!.rememberedDay)
    }

    @Test fun aLivingPetComesFirstOnTheWidget() {
        val kiko = Pet("kiko", "Kiko", Species.CAT, 0)
        val s = StateOps.rememberPet(base.copy(pets = listOf(mochi, kiko)), "mochi", today, 0)
        val json = WidgetSnapshot.build(s, clock.at(today, 12 * 60), clock)
        assertEquals(listOf("kiko"), json["pets"].list.map { it["id"].str })
    }
}
