@file:OptIn(ExperimentalJsExport::class)

package com.pawpixel.web

import com.pawpixel.core.Mood
import com.pawpixel.core.Species
import com.pawpixel.core.SpriteSettings
import com.pawpixel.core.TaskKind
import com.pawpixel.sprite.AnimatedExport
import com.pawpixel.sprite.AnimationSet
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.FaceBox
import com.pawpixel.sprite.Mask
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetBrain
import com.pawpixel.sprite.PetEvent
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Png
import com.pawpixel.sprite.RevealCard
import com.pawpixel.sprite.SpritePipeline
import com.pawpixel.sprite.SpriteResult
import com.pawpixel.sprite.StageLayout
import com.pawpixel.sprite.StageRenderer

/**
 * Browser entry point for the web Pet Maker. It runs the very same generator and animation engine
 * as the apps, so what people see on the web is what the app will make. Everything happens in the
 * browser: photos are never uploaded. Images cross the boundary as ARGB IntArrays (Int32Array in JS).
 *
 * @param species "DOG", "CAT" or "OTHER"
 * @param ears "POINTY", "FLOPPY", or "" for the species' usual ears
 * @param faceCx,faceCy,faceSide the face square as fractions of the photo (side: of its shorter
 *   edge); pass a negative faceSide to let PawPixel guess, then read the guess back from [faceCx] etc.
 */
@JsExport
class PawWebPet(
    argb: IntArray, width: Int, height: Int, size: Int, colors: Int,
    mask: FloatArray?, maskWidth: Int, maskHeight: Int,
    species: String, faceCx: Double, faceCy: Double, faceSide: Double, ears: String,
) {
    private val result: SpriteResult = SpritePipeline.generate(
        PixelImage(width, height, argb),
        SpriteSettings(size = size, colors = colors),
        mask?.let { Mask(maskWidth, maskHeight, it) },
        if (faceSide > 0) FaceBox(faceCx, faceCy, faceSide) else null,
    )
    private val art = PetArt(result.head, Species.entries.firstOrNull { it.name == species } ?: Species.DOG, Ears.of(ears))
    private var set: AnimationSet = Chibi.build(art, emptyList())
    private var moodSet: AnimationSet = set
    private var layout = StageLayout(set)
    private var brain: PetBrain = layout.brain(7)
    private var mood: Mood = Mood.HAPPY
    private var lastT = 0L

    val backgroundRemoved: Boolean get() = result.backgroundRemoved
    val faceCx: Double get() = result.face.cx
    val faceCy: Double get() = result.face.cy
    val faceSide: Double get() = result.face.side
    /** The ear shape actually used ("POINTY" / "FLOPPY"). */
    val ears: String get() = art.ears.name
    val stageWidth: Int get() = layout.stageWidth
    val stageHeight: Int get() = layout.stageHeight

    /** "happy", "content", "hungry", "restless", "meds", "sleepy", "sad" */
    fun setMood(key: String) { mood = Mood.fromKey(key); moodSet = set.forMood(mood) }

    fun pet() = brain.react(PetEvent.Petted, lastT)

    /** "FEED", "WALK", "GROOM", ... */
    fun care(kind: String) {
        val k = TaskKind.entries.firstOrNull { it.name == kind } ?: return
        brain.react(PetEvent.Cared(k), lastT)
    }

    /** The whole stage (floor, shadow, pet, effects) at [tMs], as ARGB pixels of stageWidth x stageHeight. */
    fun renderStage(tMs: Double): IntArray {
        lastT = tMs.toLong()
        return StageRenderer.render(layout, moodSet, brain.pose(lastT, mood)).pixels
    }

    /** True if a tap at stage pixel (x, y) lands on the pet. */
    fun hitTest(x: Double, y: Double): Boolean {
        val px = brain.x
        return x >= px + layout.body[0] - 4 && x <= px + layout.body[2] + 4 &&
            y >= layout.petTop + layout.body[1] - 8 && y <= layout.floorY + 2
    }

    fun gif(name: String): ByteArray = AnimatedExport.clip(art, emptyList(), name, mood = if (mood == Mood.SLEEPY) Mood.SLEEPY else Mood.HAPPY)

    fun revealPng(name: String): ByteArray = Png.encode(RevealCard.render(result.photoCrop, art.still, name))
}
