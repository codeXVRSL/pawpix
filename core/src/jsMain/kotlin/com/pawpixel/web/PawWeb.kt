@file:OptIn(ExperimentalJsExport::class)

package com.pawpixel.web

import com.pawpixel.core.Mood
import com.pawpixel.core.SpriteSettings
import com.pawpixel.core.TaskKind
import com.pawpixel.sprite.AnimatedExport
import com.pawpixel.sprite.AnimationSet
import com.pawpixel.sprite.Animator
import com.pawpixel.sprite.Mask
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
 * Browser entry point for the Phase 0 likeness test page. It runs the very same sprite generator
 * and animation engine as the apps, so what people see on the web is what the app will make.
 * Everything happens in the browser: photos are never uploaded.
 *
 * Images cross the boundary as ARGB IntArrays (Int32Array in JS).
 */
@JsExport
class PawWebPet(
    argb: IntArray, width: Int, height: Int, size: Int, colors: Int,
    /** Optional pet cut-out from the in-browser model (0..1 per pixel, maskWidth x maskHeight). */
    mask: FloatArray?, maskWidth: Int, maskHeight: Int,
) {
    private val result: SpriteResult = SpritePipeline.generate(
        PixelImage(width, height, argb),
        SpriteSettings(size = size, colors = colors),
        mask?.let { Mask(maskWidth, maskHeight, it) },
    )
    private var eyes: List<Pair<Double, Double>> = emptyList()
    private var set: AnimationSet = Animator.build(result.sprite)
    private var moodSet: AnimationSet = set
    private var layout = StageLayout(set)
    private var brain: PetBrain = layout.brain(7)
    private var mood: Mood = Mood.HAPPY

    val backgroundRemoved: Boolean get() = result.backgroundRemoved
    val spriteWidth: Int get() = result.sprite.width
    val spriteHeight: Int get() = result.sprite.height
    val stageWidth: Int get() = layout.stageWidth
    val stageHeight: Int get() = layout.stageHeight
    val eyeCount: Int get() = eyes.size

    fun spritePixels(): IntArray = result.sprite.pixels.copyOf()

    /** Adds an eye tap (fractions of the sprite size). A third tap starts over. */
    fun tapEye(fx: Double, fy: Double) {
        eyes = if (eyes.size >= 2) listOf(fx to fy) else eyes + (fx to fy)
        rebuild()
        brain.react(PetEvent.Petted, lastT)
    }

    fun clearEyes() { eyes = emptyList(); rebuild() }

    /** "happy", "content", "hungry", "restless", "meds", "sleepy", "sad" */
    fun setMood(key: String) { mood = Mood.fromKey(key); moodSet = set.forMood(mood) }

    fun pet() = brain.react(PetEvent.Petted, lastT)

    /** "FEED", "WALK", "GROOM", ... */
    fun care(kind: String) {
        val k = TaskKind.entries.firstOrNull { it.name == kind } ?: return
        brain.react(PetEvent.Cared(k), lastT)
    }

    private var lastT = 0.0.toLong()

    /** The whole stage (floor, shadow, pet, effects) at time [tMs], as ARGB pixels of stageWidth x stageHeight. */
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

    fun gif(name: String): ByteArray = AnimatedExport.clip(result.sprite, Animator.eyePixels(result.sprite, eyes), name, mood = if (mood == Mood.SLEEPY) Mood.SLEEPY else Mood.HAPPY)

    fun revealPng(name: String): ByteArray = Png.encode(RevealCard.render(result.photoCrop, result.sprite, name))

    private fun rebuild() {
        set = Animator.build(result.sprite, Animator.eyePixels(result.sprite, eyes))
        moodSet = set.forMood(mood)
        layout = StageLayout(set)
        val x = brain.x
        brain = layout.brain(7)
        if (x > 0) brain.pose(lastT, mood)
    }
}
