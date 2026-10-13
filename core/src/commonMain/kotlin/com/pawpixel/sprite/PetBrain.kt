package com.pawpixel.sprite

import com.pawpixel.core.Mood
import com.pawpixel.core.TaskKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class Behavior { IDLE, WALK, LOOK, HOP, SHAKE, BEG, SLEEP, EAT, PETTED, ZOOMIES, GLANCE }

/** Little icons drawn around the pet. */
enum class EffectKind { HEART, SPARKLE, QUESTION, EXCLAIM, ZZZ, RAIN, PILL, BOWL, DROPS }

/** An effect at (x, y) in pet canvas pixels (0,0 = canvas top-left, before [PetPose.x]). */
data class Effect(val kind: EffectKind, val x: Double, val y: Double)

/**
 * The room's floor as the pet knows it: how many tile rows it can walk on, where its bed and bowl
 * are (as the pet-canvas x and the row that put the pet on them), and which spots furniture takes.
 */
class Places(
    val rows: Int = 1,
    val bed: Pair<Double, Int>? = null,
    val bowl: Pair<Double, Int>? = null,
    /** The pet's favourite toy, where it goes to play after a walk or a game. */
    val play: Pair<Double, Int>? = null,
    /** True when a pet whose feet are centred on stage pixel [x] can stand on [row]. */
    val free: (x: Double, row: Int) -> Boolean = { _, _ -> true },
) {
    companion object { val NONE = Places() }
}

/** What to draw right now. */
data class PetPose(
    val frame: Frame,
    /** Left edge of the pet canvas on the stage, in pet pixels. */
    val x: Double,
    /** Vertical offset in pet pixels; negative = in the air. */
    val lift: Int,
    /** How far in front of its home row the pet's feet are, in pet pixels (negative = deeper into the room). */
    val dy: Int = 0,
    /** True while it eats from the room's own bowl (no bowl is drawn beside it then). */
    val atBowl: Boolean = false,
    /** Always false now: the pet is drawn as it is, never mirrored (its markings stay on the right side). */
    val flip: Boolean,
    val behavior: Behavior,
    val effects: List<Effect>,
) {
    /**
     * This pose on whole pet pixels, as it's drawn. Two poses that look the same on screen are
     * equal, so a view redraws only when the picture changes (a few times a second, not 60).
     */
    fun onPixelGrid(): PetPose = copy(
        x = kotlin.math.round(x),
        effects = effects.map { it.copy(x = kotlin.math.round(it.x), y = kotlin.math.round(it.y)) },
    )
}

/** Things that happen to the pet. */
sealed interface PetEvent {
    data object Petted : PetEvent
    data class Cared(val kind: TaskKind) : PetEvent
    /** Something caught its eye on that side of the room (a tap beside it): a glance, after a beat, unless it is busy or asleep. */
    data class Noticed(val left: Boolean) : PetEvent
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
    private val places: Places = Places.NONE,
    /** The phone asked for less motion: the pet breathes, blinks, glances and sleeps where it stands, and never walks, hops or zooms. */
    private val calm: Boolean = false,
) {
    /** The row the pet's feet start on: [Room.PET_ROW], or the last one on a shallower floor. */
    private val homeRow = Room.PET_ROW.coerceAtMost(places.rows - 1).coerceAtLeast(0)
    /** The tile row the pet stands on; it wanders the room's rows. */
    var row: Double = homeRow.toDouble(); private set
    private var targetRow = row
    /** What to do on arriving at a place (sleep on the bed, eat at the bowl). */
    private var then: Behavior? = null
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
    private val GLANCE_FROM = setOf(Behavior.IDLE, Behavior.LOOK, Behavior.HOP, Behavior.BEG, Behavior.SHAKE, Behavior.PETTED, Behavior.GLANCE)
    private var zoomLegs = 0
    /** After the zoomies, straight to the favourite toy for a few hops. */
    private var playAfter = false
    private var reaction = false
    private var currentMood: Mood? = null
    private var lastMs = -1L
    private var nextBlink = 0L
    private var blinkEnd = 0L
    private var secondBlink = 0L
    private var nextTwitch = 0L
    private var twitchEnd = 0L
    private var twitchSide = 1

    private val maxX get() = max(0.0, stageWidth - canvasWidth)
    private val bodyHeight get() = feetY - headTop

    fun react(event: PetEvent, nowMs: Long) {
        if (event is PetEvent.Noticed) {
            // Only a pet with nothing on its mind turns to look; a walk, a meal or a nap goes on.
            if (behavior in GLANCE_FROM) { facingLeft = event.left; start(Behavior.GLANCE, nowMs, 1500) }
            return
        }
        reaction = true
        then = null // a walk to the bed or the bowl is forgotten when something else happens
        when (event) {
            PetEvent.Petted -> start(Behavior.PETTED, nowMs, 1600)
            is PetEvent.Cared -> when (event.kind) {
                TaskKind.FEED, TaskKind.WATER -> if (calm) start(Behavior.EAT, nowMs, 2800) else goTo(places.bowl, nowMs, Behavior.EAT, 2800, speed = 20.0)
                TaskKind.WALK, TaskKind.PLAY -> if (calm) start(Behavior.LOOK, nowMs, 1900) else { zoomLegs = 2; playAfter = places.play != null; startZoom(nowMs) }
                TaskKind.GROOM -> start(Behavior.SHAKE, nowMs, 1100)
                TaskKind.MEDS, TaskKind.LITTER, TaskKind.VACCINE, TaskKind.DEWORM, TaskKind.FLEA_TICK, TaskKind.VET ->
                    start(Behavior.PETTED, nowMs, 1600)
            }
            is PetEvent.Noticed -> Unit // handled above
        }
    }

    fun pose(nowMs: Long, mood: Mood): PetPose {
        if (lastMs < 0) {
            lastMs = nowMs; nextBlink = nowMs + 1500; nextTwitch = nowMs + 4000
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
            val next = then
            when {
                behavior == Behavior.ZOOMIES && zoomLegs > 0 -> startZoom(nowMs)
                behavior == Behavior.ZOOMIES && playAfter -> { playAfter = false; hops = 3; goTo(places.play, nowMs, Behavior.HOP, 520L * 3, speed = 18.0) }
                next != null -> { then = null; start(next, nowMs, if (next == Behavior.SLEEP) 12_000 else 2800) }
                else -> { reaction = false; pickNext(nowMs, mood) }
            }
        }

        // Movement: toward the target across the floor and into it, arriving at both together.
        if (behavior == Behavior.WALK || behavior == Behavior.ZOOMIES) {
            val step = speed * dt
            val dx = target - x; val dyPx = (targetRow - row) * Room.TILE_D
            val dist = kotlin.math.sqrt(dx * dx + dyPx * dyPx)
            if (abs(dx) > 1.5) facingLeft = dx < 0 // before the step: on the arrival frame it still faces the way it came
            if (dist <= step) { x = target; row = targetRow; ends = nowMs }
            else { x += dx / dist * step; row += dyPx / dist * step / Room.TILE_D }
        }

        val t = nowMs - started
        val effects = ArrayList<Effect>()
        var lift = 0
        val frame: Frame = when (behavior) {
            Behavior.IDLE -> breathing(t, mood)
            // A beat before it reacts (an animal notices, then turns), a look held toward the spot, then back.
            Behavior.GLANCE -> when (t) {
                in 0 until 220 -> Frame.BASE
                in 220 until 1150 -> if (facingLeft) Frame.LOOK_LEFT else Frame.LOOK_RIGHT
                else -> Frame.BASE
            }
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
                // Uneven timing, as animators do: the feet stay on the ground longer than they pass.
                val cycle = stepMs * 4
                val p = t % cycle
                val i = when {
                    p < stepMs * 1.25 -> 0
                    p < stepMs * 2 -> 1
                    p < stepMs * 3.25 -> 2
                    else -> 3
                }
                (if (facingLeft) Frame.WALK_L else Frame.WALK_R)[i]
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
                if (!atBowl) effects += bowl()
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
        val idle = frame == Frame.BASE || frame == Frame.BREATHE || frame == Frame.TAIL_SWING
        if (nowMs >= nextBlink) {
            blinkEnd = nowMs + 130
            secondBlink = if (rnd.nextDouble() < 0.2) nowMs + 300 else 0L
            nextBlink = nowMs + 2200 + rnd.nextLong(0, 4000)
        }
        if (secondBlink in 1..nowMs) { blinkEnd = nowMs + 110; secondBlink = 0 }
        // An ear flick now and then, while standing still.
        if (nowMs >= nextTwitch) {
            twitchEnd = nowMs + 220
            twitchSide = if (rnd.nextBoolean()) 1 else -1
            nextTwitch = nowMs + 4000 + rnd.nextLong(0, 7000)
        }
        if (idle && nowMs < blinkEnd) shown = Frame.BLINK
        else if (idle && nowMs < twitchEnd) shown = if (twitchSide < 0) Frame.EAR_TWITCH_L else Frame.EAR_TWITCH_R

        return PetPose(shown, x, lift, dy = ((row - homeRow) * Room.TILE_D).roundToInt(), atBowl = behavior == Behavior.EAT && atBowl, flip = false, behavior = behavior, effects = effects)
    }

    /** Eating from the room's bowl: the pet walked to it (or stands at it). */
    private val atBowl: Boolean get() = places.bowl?.let { (bx, br) -> abs(x - bx) < 2 && abs(row - br) < 0.5 } ?: false

    /**
     * Walks to a place, then does [next] there; or does it on the spot when the room has no such
     * place or the pet is already there.
     */
    private fun goTo(place: Pair<Double, Int>?, nowMs: Long, next: Behavior, durationMs: Long, speed: Double) {
        if (place == null || (abs(x - place.first) < 2 && abs(row - place.second) < 0.5)) { start(next, nowMs, durationMs); return }
        this.speed = speed
        target = place.first.coerceIn(0.0, maxX); targetRow = place.second.toDouble()
        then = next
        start(Behavior.WALK, nowMs, 20_000)
    }

    // ---------- Choosing what to do next ----------

    private fun pickNext(nowMs: Long, mood: Mood) {
        then = null // a new mood mid-walk drops the errand; it is chosen again below if still wanted
        val weights: List<Pair<Behavior, Int>> = when {
            calm && mood == Mood.SLEEPY -> { start(Behavior.SLEEP, nowMs, 12_000); return } // asleep where it stands
            calm -> listOf(Behavior.IDLE to 70, Behavior.LOOK to 30)
            mood == Mood.SLEEPY -> listOf(Behavior.SLEEP to 1)
            mood == Mood.HAPPY -> listOf(Behavior.IDLE to 25, Behavior.WALK to 30, Behavior.HOP to 22, Behavior.LOOK to 15, Behavior.SHAKE to 8)
            mood == Mood.CONTENT -> listOf(Behavior.IDLE to 40, Behavior.WALK to 30, Behavior.LOOK to 22, Behavior.HOP to 5, Behavior.SHAKE to 3)
            mood == Mood.HUNGRY -> listOf(Behavior.BEG to 40, Behavior.WALK to 20, Behavior.LOOK to 20, Behavior.IDLE to 20)
            mood == Mood.RESTLESS -> listOf(Behavior.WALK to 55, Behavior.LOOK to 15, Behavior.HOP to 15, Behavior.SHAKE to 15)
            mood == Mood.NEEDS_MEDS -> listOf(Behavior.IDLE to 60, Behavior.LOOK to 25, Behavior.WALK to 15)
            mood == Mood.SAD -> listOf(Behavior.IDLE to 70, Behavior.LOOK to 20, Behavior.WALK to 10)
            else -> listOf(Behavior.IDLE to 1)
        }
        var roll = rnd.nextInt(weights.sumOf { it.second })
        val next = weights.first { roll -= it.second; roll < 0 }.first
        when (next) {
            Behavior.IDLE -> start(next, nowMs, if (mood == Mood.SAD || mood == Mood.NEEDS_MEDS) rnd.nextLong(4000, 8000) else rnd.nextLong(2000, 5000))
            Behavior.WALK -> {
                speed = when (mood) { Mood.RESTLESS -> 24.0; Mood.HAPPY -> 18.0; Mood.SAD, Mood.NEEDS_MEDS -> 7.0; else -> 13.0 }
                pickTarget()
                start(next, nowMs, 20_000) // ends early on arrival
            }
            Behavior.LOOK -> start(next, nowMs, 1900)
            Behavior.HOP -> {
                hops = rnd.nextInt(1, 4)
                // A happy pet often hops over to its toy to do it there.
                if (places.play != null && mood == Mood.HAPPY && rnd.nextInt(3) == 0) goTo(places.play, nowMs, Behavior.HOP, 520L * hops, speed = 18.0)
                else start(next, nowMs, 520L * hops)
            }
            Behavior.SHAKE -> start(next, nowMs, 800)
            Behavior.BEG -> start(next, nowMs, 2600)
            Behavior.SLEEP -> goTo(places.bed, nowMs, Behavior.SLEEP, 12_000, speed = 9.0)
            else -> start(Behavior.IDLE, nowMs, 3000)
        }
    }

    /** Somewhere else on the floor, a fair walk away, where nothing stands. */
    private fun pickTarget() {
        if (maxX <= 1) { target = x; targetRow = row; return }
        var t = x; var r = row.roundToInt()
        var tries = 0
        do {
            t = rnd.nextDouble() * maxX
            r = if (places.rows <= 1) 0 else rnd.nextInt(places.rows)
            tries++
        } while ((abs(t - x) < maxX * 0.2 || !places.free(t + canvasWidth / 2.0, r)) && tries < 12)
        if (!places.free(t + canvasWidth / 2.0, r)) { t = x; r = row.roundToInt() }
        target = t; targetRow = r.toDouble()
    }

    private fun startZoom(nowMs: Long) {
        speed = 34.0
        target = if (x > maxX / 2) 0.0 else maxX
        targetRow = row.roundToInt().toDouble()
        zoomLegs--
        start(Behavior.ZOOMIES, nowMs, 20_000)
    }

    private fun start(b: Behavior, nowMs: Long, durationMs: Long) {
        behavior = b; started = nowMs; ends = nowMs + durationMs
    }

    // ---------- Frame helpers ----------

    /**
     * Idle: a breath in (held), out, and the tail swinging the other way, on uneven beats so it
     * never reads as a metronome. A sad pet breathes slowly and doesn't wag.
     */
    private fun breathing(t: Long, mood: Mood): Frame {
        // A resting animal breathes 15 to 20 times a minute: about three seconds a breath, quicker when happy or restless, slower when low.
        val period = when (mood) { Mood.SAD, Mood.NEEDS_MEDS -> 3600L; Mood.RESTLESS -> 1400L; Mood.HAPPY -> 2400L; else -> 3200L }
        val p = (t % period).toDouble() / period
        return when {
            p < 0.38 -> Frame.BASE
            p < 0.68 -> Frame.BREATHE
            p < 0.80 -> Frame.BASE
            mood == Mood.SAD || mood == Mood.NEEDS_MEDS -> Frame.BASE
            else -> Frame.TAIL_SWING
        }
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
