package com.pawpixel.sprite

import com.pawpixel.core.Mood
import com.pawpixel.core.TaskKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class Behavior { IDLE, WALK, LOOK, HOP, SHAKE, BEG, SLEEP, EAT, PETTED, ZOOMIES }

/** Little icons drawn around the pet. */
enum class EffectKind { HEART, SPARKLE, QUESTION, EXCLAIM, ZZZ, RAIN, PILL, BOWL, DROPS }

/** An effect at (x, y) in pet canvas pixels (0,0 = canvas top-left, before [PetPose.x]). */
data class Effect(val kind: EffectKind, val x: Double, val y: Double)

/** What to draw right now. */
data class PetPose(
    val frame: Frame,
    /** Left edge of the pet canvas on the stage, in pet pixels. */
    val x: Double,
    /** Vertical offset in pet pixels; negative = in the air. */
    val lift: Int,
    val flip: Boolean,
    val behavior: Behavior,
    val effects: List<Effect>,
)

/** Things that happen to the pet. */
sealed interface PetEvent {
    data object Petted : PetEvent
    data class Cared(val kind: TaskKind) : PetEvent
}

/**
 * Decides what the pet is doing, frame by frame, like a small animal: it breathes, blinks at
 * irregular moments, looks around, wanders, hops when happy, begs when hungry, paces when it
 * needs a walk, droops when sad and curls up at night. Tapping it or finishing a care task
 * triggers a reaction (eating, zoomies after a walk, a shake after grooming, hearts when petted).
 *
 * Pure and deterministic for a given seed and sequence of calls, so it runs the same on every
 * platform and is unit-testable. The UI calls [pose] every frame.
 *
 * @param stageWidth stage width in pet pixels (the pet walks within it)
 * @param canvasWidth width of the pet's animation canvas ([AnimationSet.width])
 * @param body the pet's body bounds inside that canvas (left, top, right, bottom)
 */
class PetBrain(
    seed: Int,
    private val stageWidth: Double,
    private val canvasWidth: Int,
    body: IntArray,
) {
    private val headTop = body[1]
    private val feetY = body[3]
    private val bodyLeft = body[0]
    private val bodyRight = body[2]
    private val rnd = Random(seed)
    var x = (stageWidth - canvasWidth) / 2; private set
    private var facingLeft = false
    var behavior = Behavior.IDLE; private set
    private var started = 0L
    private var ends = 0L
    private var target = x
    private var speed = 14.0
    private var hops = 1
    private var zoomLegs = 0
    private var reaction = false
    private var currentMood: Mood? = null
    private var lastMs = -1L
    private var nextBlink = 0L
    private var blinkEnd = 0L
    private var secondBlink = 0L

    private val maxX get() = max(0.0, stageWidth - canvasWidth)
    private val bodyHeight get() = feetY - headTop

    fun react(event: PetEvent, nowMs: Long) {
        reaction = true
        when (event) {
            PetEvent.Petted -> start(Behavior.PETTED, nowMs, 1600)
            is PetEvent.Cared -> when (event.kind) {
                TaskKind.FEED, TaskKind.WATER -> start(Behavior.EAT, nowMs, 2800)
                TaskKind.WALK, TaskKind.PLAY -> { zoomLegs = 2; startZoom(nowMs) }
                TaskKind.GROOM -> start(Behavior.SHAKE, nowMs, 1100)
                TaskKind.MEDS, TaskKind.LITTER -> start(Behavior.PETTED, nowMs, 1600)
            }
        }
    }

    fun pose(nowMs: Long, mood: Mood): PetPose {
        if (lastMs < 0) {
            lastMs = nowMs; nextBlink = nowMs + 1500
            if (!reaction) pickNext(nowMs, mood) // a reaction sent before the first frame still plays
        }
        val dt = (nowMs - lastMs).coerceIn(0, 250) / 1000.0
        lastMs = nowMs
        if (mood != currentMood) {
            val first = currentMood == null
            currentMood = mood
            if (!first && !reaction) pickNext(nowMs, mood)
        }
        if (nowMs >= ends) {
            if (behavior == Behavior.ZOOMIES && zoomLegs > 0) startZoom(nowMs) else { reaction = false; pickNext(nowMs, mood) }
        }

        // Movement
        if (behavior == Behavior.WALK || behavior == Behavior.ZOOMIES) {
            val step = speed * dt
            if (abs(target - x) <= step) { x = target; ends = nowMs } else x += if (target > x) step else -step
            facingLeft = target < x
        }

        val t = nowMs - started
        val effects = ArrayList<Effect>()
        var lift = 0
        val frame: Frame = when (behavior) {
            Behavior.IDLE -> breathing(t, mood)
            Behavior.LOOK -> when (t) {
                in 0 until 550 -> if (facingLeft) Frame.LOOK_LEFT else Frame.LOOK_RIGHT
                in 550 until 850 -> Frame.BASE
                in 850 until 1450 -> if (facingLeft) Frame.LOOK_RIGHT else Frame.LOOK_LEFT
                else -> breathing(t, mood)
            }
            Behavior.WALK, Behavior.ZOOMIES -> {
                val stepMs = (2100 / speed).toLong().coerceIn(60, 220)
                if (behavior == Behavior.ZOOMIES) {
                    val p = (t % 420) / 420.0
                    lift = -(bodyHeight * 0.12 * sin(PI * p)).roundToInt()
                    if (t % 840 < 420) effects += Effect(EffectKind.EXCLAIM, headX() + 6.0, headTop - 8.0)
                }
                Frame.WALK[((t / stepMs) % 4).toInt()]
            }
            Behavior.HOP -> hopFrame(t, 0.18).also { (f, l) -> lift = l; if (mood == Mood.HAPPY && t % 520 in 100 until 450) effects += floating(EffectKind.HEART, t % 520, 350) }.first
            Behavior.BEG -> {
                effects += Effect(EffectKind.QUESTION, headX() + 5.0, headTop - 9.0 - bobble(t, 700, 1.0))
                hopFrame(t % 600 * 520 / 600, 0.07).also { lift = it.second }.first
            }
            Behavior.SHAKE -> {
                if (mood != Mood.SAD) effects += Effect(EffectKind.DROPS, headX() - 6.0, headTop - 3.0 - (t % 300) / 100.0)
                if (t > ends - started - 120) Frame.BASE else Frame.SHAKE[((t / 60) % 4).toInt()]
            }
            Behavior.SLEEP -> {
                val p = t % 2600
                effects += Effect(EffectKind.ZZZ, headX() + 4.0 + p / 900.0, headTop - 6.0 - p / 400.0)
                if (p < 1300) Frame.SLEEP else Frame.SLEEP_BREATHE
            }
            Behavior.EAT -> {
                effects += bowl()
                if ((t / 280) % 2 == 0L) Frame.EAT_DOWN else Frame.BASE
            }
            Behavior.PETTED -> {
                effects += floating(EffectKind.HEART, t, 1600)
                if (t in 300 until 1400) effects += floating(EffectKind.HEART, t - 300, 1300, dx = -7.0)
                when {
                    t < 120 -> Frame.SQUASH
                    t < 640 -> hopFrame(t - 120, 0.15).also { lift = it.second }.first
                    else -> Frame.BLINK // content, eyes closed
                }
            }
        }

        // Mood overlays that stay with the pet.
        when (mood) {
            Mood.SAD -> effects += Effect(EffectKind.RAIN, headX() - 6.0, headTop - 10.0)
            Mood.NEEDS_MEDS -> effects += Effect(EffectKind.PILL, headX() + 6.0, headTop - 7.0 - bobble(t, 1200, 1.0))
            Mood.HUNGRY -> if (behavior != Behavior.BEG && behavior != Behavior.EAT && (nowMs / 3000) % 3 == 0L) {
                effects += bowl()
            }
            else -> Unit
        }

        // Blinking, at irregular intervals, sometimes twice.
        var shown = frame
        val canBlink = frame == Frame.BASE || frame == Frame.BREATHE
        if (nowMs >= nextBlink) {
            blinkEnd = nowMs + 130
            secondBlink = if (rnd.nextDouble() < 0.2) nowMs + 300 else 0L
            nextBlink = nowMs + 2200 + rnd.nextLong(0, 4000)
        }
        if (secondBlink in 1..nowMs) { blinkEnd = nowMs + 110; secondBlink = 0 }
        if (canBlink && nowMs < blinkEnd) shown = Frame.BLINK

        val flip = facingLeft && behavior != Behavior.BEG
        return PetPose(shown, x, lift, flip, behavior, effects)
    }

    // ---------- Choosing what to do next ----------

    private fun pickNext(nowMs: Long, mood: Mood) {
        val weights: List<Pair<Behavior, Int>> = when (mood) {
            Mood.SLEEPY -> listOf(Behavior.SLEEP to 1)
            Mood.HAPPY -> listOf(Behavior.IDLE to 25, Behavior.WALK to 30, Behavior.HOP to 22, Behavior.LOOK to 15, Behavior.SHAKE to 8)
            Mood.CONTENT -> listOf(Behavior.IDLE to 40, Behavior.WALK to 30, Behavior.LOOK to 22, Behavior.HOP to 5, Behavior.SHAKE to 3)
            Mood.HUNGRY -> listOf(Behavior.BEG to 40, Behavior.WALK to 20, Behavior.LOOK to 20, Behavior.IDLE to 20)
            Mood.RESTLESS -> listOf(Behavior.WALK to 55, Behavior.LOOK to 15, Behavior.HOP to 15, Behavior.SHAKE to 15)
            Mood.NEEDS_MEDS -> listOf(Behavior.IDLE to 60, Behavior.LOOK to 25, Behavior.WALK to 15)
            Mood.SAD -> listOf(Behavior.IDLE to 70, Behavior.LOOK to 20, Behavior.WALK to 10)
        }
        var roll = rnd.nextInt(weights.sumOf { it.second })
        val next = weights.first { roll -= it.second; roll < 0 }.first
        when (next) {
            Behavior.IDLE -> start(next, nowMs, if (mood == Mood.SAD || mood == Mood.NEEDS_MEDS) rnd.nextLong(4000, 8000) else rnd.nextLong(2000, 5000))
            Behavior.WALK -> {
                speed = when (mood) { Mood.RESTLESS -> 24.0; Mood.HAPPY -> 18.0; Mood.SAD, Mood.NEEDS_MEDS -> 7.0; else -> 13.0 }
                target = pickTarget()
                start(next, nowMs, 20_000) // ends early on arrival
            }
            Behavior.LOOK -> start(next, nowMs, 1900)
            Behavior.HOP -> { hops = rnd.nextInt(1, 4); start(next, nowMs, 520L * hops) }
            Behavior.SHAKE -> start(next, nowMs, 800)
            Behavior.BEG -> start(next, nowMs, 2600)
            Behavior.SLEEP -> start(next, nowMs, 12_000)
            else -> start(Behavior.IDLE, nowMs, 3000)
        }
    }

    private fun pickTarget(): Double {
        if (maxX <= 1) return x
        var t: Double
        var tries = 0
        do { t = rnd.nextDouble() * maxX; tries++ } while (abs(t - x) < maxX * 0.2 && tries < 8)
        return t
    }

    private fun startZoom(nowMs: Long) {
        speed = 34.0
        target = if (x > maxX / 2) 0.0 else maxX
        zoomLegs--
        start(Behavior.ZOOMIES, nowMs, 20_000)
    }

    private fun start(b: Behavior, nowMs: Long, durationMs: Long) {
        behavior = b; started = nowMs; ends = nowMs + durationMs
    }

    // ---------- Frame helpers ----------

    private fun breathing(t: Long, mood: Mood): Frame {
        val period = when (mood) { Mood.SAD, Mood.NEEDS_MEDS -> 2600L; Mood.RESTLESS -> 1100L; Mood.HAPPY -> 1400L; else -> 1800L }
        return if (t % period < period / 2) Frame.BASE else Frame.BREATHE
    }

    /** Squash, jump (stretched, rising and falling), land, settle. Returns frame and lift. */
    private fun hopFrame(t: Long, heightFraction: Double): Pair<Frame, Int> {
        val p = t % 520
        return when {
            p < 70 -> Frame.SQUASH to 0
            p < 380 -> Frame.STRETCH to -(bodyHeight * heightFraction * sin(PI * (p - 70) / 310.0)).roundToInt()
            p < 450 -> Frame.SQUASH to 0
            else -> Frame.BASE to 0
        }
    }

    private fun headX() = (bodyLeft + bodyRight) / 2.0

    /** Food bowl on the floor in front of the pet (drawn in canvas coordinates, mirrored with the pet). */
    private fun bowl() = Effect(EffectKind.BOWL, bodyRight + 1.0, feetY - Icons.BOWL_FOOD.height.toDouble())

    /** An icon that floats up and drifts over [durationMs]. */
    private fun floating(kind: EffectKind, t: Long, durationMs: Long, dx: Double = 4.0): Effect {
        val p = (t.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return Effect(kind, headX() + dx + sin(p * PI * 2) * 2, headTop - 4.0 - p * 12)
    }

    private fun bobble(t: Long, period: Long, amp: Double) = amp * sin(2 * PI * (t % period) / period)
}
