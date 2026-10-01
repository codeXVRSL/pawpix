package com.pawpixel

import com.pawpixel.core.Mood
import com.pawpixel.core.Sky
import com.pawpixel.core.WidgetSnapshot
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetLook
import com.pawpixel.sprite.Poses
import com.pawpixel.core.Species
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The widgets' idle animation and sky. */
class WidgetAnimationTest {
    private fun art(): PetArt = PetArt(PetLook(listOf(0xFF8B5A2B.toInt(), 0xFFD9A066.toInt(), 0xFF2B2430.toInt()), IntArray(PetLook.GRID * PetLook.GRID) { it % 3 }), Species.CAT, com.pawpixel.sprite.Ears.POINTY, null)

    @Test fun everyMoodHasAnAnimationWhoseFramesAllExist() {
        for (mood in Mood.entries) {
            val sequence = Poses.frameSequence(mood)
            val frames = Poses.distinctFrames(art(), mood)
            assertTrue(sequence.size >= 6, "$mood: ${sequence.size} steps")
            assertEquals(sequence.toSet(), frames.indices.toSet(), "$mood: every distinct frame is used, and every step has one")
            assertTrue(frames.size >= 2, "$mood: a still picture is not an animation")
            // Frames differ from one another (the pet really moves), and share one canvas size.
            assertEquals(1, frames.map { it.width to it.height }.toSet().size)
            for (i in frames.indices) for (j in i + 1 until frames.size) assertTrue(!frames[i].pixels.contentEquals(frames[j].pixels), "$mood: frames $i and $j are identical")
            assertEquals(sequence.map { WidgetSnapshot.framePath("p", mood, it) }, WidgetSnapshot.frames("p", mood))
        }
    }

    @Test fun theFirstFrameIsTheStillPose() {
        val a = art()
        assertTrue(Poses.distinctFrames(a, Mood.CONTENT)[0].pixels.contentEquals(Poses.render(a.still, Mood.CONTENT).pixels))
        assertTrue(Poses.distinctFrames(a, Mood.SLEEPY)[0].pixels.contentEquals(Poses.render(Chibi.sleeping(a), Mood.SLEEPY).pixels))
    }

    @Test fun theSkyFollowsTheDayAndTheOwnersNight() {
        assertEquals(Sky.Phase.NIGHT, Sky.phase(3 * 60, 22 * 60, 6 * 60))
        assertEquals(Sky.Phase.DAWN, Sky.phase(6 * 60 + 30, 22 * 60, 6 * 60))
        assertEquals(Sky.Phase.DAY, Sky.phase(12 * 60, 22 * 60, 6 * 60))
        assertEquals(Sky.Phase.DUSK, Sky.phase(17 * 60, 22 * 60, 6 * 60))
        assertEquals(Sky.Phase.NIGHT, Sky.phase(23 * 60, 22 * 60, 6 * 60))
        // A late sleeper: still night at 7, and the sky changes when their night ends.
        assertEquals(Sky.Phase.NIGHT, Sky.phase(7 * 60, 23 * 60, 7 * 60 + 30))
        assertEquals(30, Sky.minutesToNextChange(7 * 60, 23 * 60, 7 * 60 + 30))
        assertEquals(4 * 60 + 30, Sky.minutesToNextChange(12 * 60, 22 * 60, 6 * 60))
    }
}
