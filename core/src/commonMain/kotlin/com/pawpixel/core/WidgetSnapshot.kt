package com.pawpixel.core

import com.pawpixel.i18n.tr
import com.pawpixel.i18n.trName

/**
 * Everything a home-screen widget needs, precomputed, written as `widget.json` next to the sprite
 * PNGs. The iOS widget (SwiftUI) and Android widget (Glance) only read this file, never the engine,
 * and [face] picks what to show at any moment. It covers the next [HORIZON_MS], so a widget stays
 * right (moods, the Done button, "Next: ...") for two days without the app running.
 *
 * Shape (version 2), all times epoch ms; each list is change points, the last one at or before
 * "now" applies:
 * ```
 * { "version":2, "generatedAt":ms, "utcOffsetMs":ms, "labels":{...},
 *   "pets":[ { "id","name","spriteVersion",
 *     "sprites": { "happy":"sprites/<id>/happy.png", ... },
 *     "timeline":[ {"at","mood","caption"} ],
 *     "actions":[ {"at"} | {"at","taskId","emoji","title","ifDone":{"timeline","actions","next"}} ],
 *     "next":[ {"at"} | {"at","taskId","dueAt","emoji","title"} ] } ] }
 * ```
 * An action is the one-tap Done: the most urgent everyday task (overdue, or due within
 * [DUE_SOON_MS]). `ifDone` is how things go if it's tapped when it appears, so iOS (whose widget
 * can't run Kotlin) shows the result of a tap at once.
 */
object WidgetSnapshot {
    const val VERSION = 2
    const val FILE_NAME = "widget.json"
    const val DUE_SOON_MS = HOUR_MS
    const val HORIZON_MS = 2 * DAY_MS
    const val STEP_MS = 15 * MINUTE_MS

    /** A widget's pet choice: whichever pet needs the owner most right now. */
    const val MOST_IN_NEED = "*"

    fun spritePath(petId: String, mood: Mood) = "sprites/$petId/${mood.key}.png"

    /** The [i]th distinct frame of the mood's idle animation (see [com.pawpixel.sprite.Poses.distinctFrames]). */
    fun framePath(petId: String, mood: Mood, i: Int) = "sprites/$petId/${mood.key}-f$i.png"

    /** The idle animation's frames, one path per step of [com.pawpixel.sprite.Poses.frameSequence]. */
    fun frames(petId: String, mood: Mood): List<String> =
        com.pawpixel.sprite.Poses.frameSequence(mood).map { framePath(petId, mood, it) }

    fun build(state: AppState, nowMs: Long, clock: LocalClock, horizonMs: Long = HORIZON_MS): Json {
        // Pets still being cared for come first; a remembered pet shows only if it's all there is.
        val pets = state.pets.filterNot { it.remembered }.ifEmpty { state.pets }.map { pet -> petJson(state, pet, nowMs, clock, horizonMs) }
        // The widgets' own words, in the owner's language (the iOS widget can't run Kotlin).
        val labels = Json.obj(
            "done" to tr("Done"),
            "yourPet" to tr("Your pet"),
            "empty" to tr("Open PawPixel to make your pixel pet"),
            "makePet" to tr("Make your pixel pet"),
            "next" to tr("Next: {0} {1} · {2}"),
            "mostInNeed" to tr("Whoever needs you most"),
        )
        return Json.obj(
            "version" to VERSION, "generatedAt" to nowMs, "utcOffsetMs" to clock.offsetMs(nowMs),
            "pets" to pets, "labels" to labels,
            // The owner's night, so the widgets' sky goes dark with the app's (see Sky).
            "night" to Json.obj("start" to state.settings.nightStart, "end" to state.settings.nightEnd),
            "frameMs" to com.pawpixel.sprite.Poses.FRAME_MS,
        )
    }

    private class Sample(val at: Long, val reading: MoodReading, val action: TaskStatus?, val next: TaskStatus?)

    private fun petJson(state: AppState, pet: Pet, nowMs: Long, clock: LocalClock, horizonMs: Long): Json {
        val about = Json.obj(
            "id" to pet.id,
            "name" to pet.name,
            "spriteVersion" to pet.spriteVersion,
            "sprites" to Mood.entries.associate { it.key to spritePath(pet.id, it) },
            // The idle animation per mood: frames shown in turn, every "frameMs" (Android plays it live).
            "frames" to Mood.entries.associate { it.key to frames(pet.id, it) },
            // Shown for a while after a Done tap on the widget (see [face]).
            "happy" to tr("{0} is happy!", pet.name),
        )
        return Json.Obj(about.fields + track(state, pet, nowMs, nowMs + horizonMs, clock, withIfDone = true).fields)
    }

    /**
     * The pet's timeline, Done buttons and next care from [from] to [end]. With [withIfDone], each
     * Done button carries the same from the moment it appears as if it had been tapped then.
     */
    private fun track(state: AppState, pet: Pet, from: Long, end: Long, clock: LocalClock, withIfDone: Boolean): Json.Obj {
        // Hundreds of samples: each task's records and learned times (which only change by the day,
        // as every record here is at or before [from]) are worked out once.
        // A remembered pet has nothing due: the widget shows it resting, with no Done to tap.
        val tasks = if (pet.remembered) emptyList() else state.tasksFor(pet.id)
        val records = state.completions.groupBy { it.taskId }
        val learned = HashMap<Pair<String, Long>, List<Int>>()
        val samples = MoodEngine.samples(from, end - from, STEP_MS).map { t ->
            val statuses = tasks.map { task ->
                val mine = records[task.id].orEmpty()
                val slots = learned.getOrPut(task.id to clock.dayIndex(t)) { AdaptiveTiming.effectiveSlots(task, mine, t, clock) }
                CareEngine.status(task, mine, t, clock, slots, pet)
            }
            // Health care (a vaccine, a vet visit) is logged in the app, not with a quick tap on the widget.
            val daily = statuses.filter { !it.task.kind.health }
            val urgent = if (state.isAway(t)) null else daily
                .filter { it.isOverdue || (it.nextDueMs != null && it.nextDueMs - t <= DUE_SOON_MS && !it.allDoneThisCycle) }
                .maxWithOrNull(compareBy<TaskStatus>({ MoodEngine.penalty(it, t) }, { -(it.nextDueMs ?: Long.MAX_VALUE) }))
            val next = daily.filter { !it.isOverdue && it.nextDueMs != null }.minByOrNull { it.nextDueMs!! }
            Sample(t, MoodEngine.read(state, pet.id, t, clock, statuses), urgent, next)
        }.toList()

        val timeline = changes(samples, { it.reading.mood to it.reading.caption }) { s ->
            Json.obj("at" to s.at, "mood" to s.reading.mood.key, "caption" to s.reading.caption)
        }
        val actions = changes(samples, { it.action?.task?.id }) { s ->
            val task = s.action?.task ?: return@changes Json.obj("at" to s.at)
            val entry = Json.obj("at" to s.at, "taskId" to task.id, "emoji" to task.kind.emoji, "title" to trName(task.title))
            if (!withIfDone) return@changes entry
            val after = StateOps.complete(state, task.id, s.at, clock)
            Json.Obj(entry.fields + ("ifDone" to track(after, pet, s.at, end, clock, withIfDone = false)))
        }
        val next = changes(samples, { it.next?.let { n -> n.task.id to n.nextDueMs } }) { s ->
            val n = s.next ?: return@changes Json.obj("at" to s.at)
            Json.obj("at" to s.at, "taskId" to n.task.id, "dueAt" to n.nextDueMs, "emoji" to n.task.kind.emoji, "title" to trName(n.task.title))
        }
        return Json.obj("timeline" to timeline, "actions" to actions, "next" to next)
    }

    /** One entry per change of [key] along [samples]. */
    private fun <K> changes(samples: List<Sample>, key: (Sample) -> K, entry: (Sample) -> Json): List<Json> {
        val out = ArrayList<Json>()
        var last: Any? = Unit
        for (s in samples) {
            val k = key(s)
            if (k != last) { out += entry(s); last = k }
        }
        return out
    }

    private fun timelineJson(points: List<MoodPoint>): List<Json> =
        points.map { Json.obj("at" to it.atMs, "mood" to it.mood.key, "caption" to it.caption) }

    // ---- Reading (the Android widget uses this; iosApp/PetWidget/PetWidget.swift mirrors it) ----

    /**
     * What a widget shows at [nowMs], from a parsed `widget.json`. [petChoice]: a pet id, [MOST_IN_NEED],
     * or null for the first pet (also when the chosen pet is gone). [taps]: Done taps on the widget the
     * app hasn't seen yet (iOS). [utcOffsetNowMs]: the phone's offset now; if the owner travelled to
     * another time zone since the file was written, care times move with local time. Null = no pets.
     */
    fun face(
        snapshot: Json,
        nowMs: Long,
        petChoice: String? = null,
        taps: List<Pair<String, Long>> = emptyList(),
        utcOffsetNowMs: Long? = null,
    ): WidgetFace? {
        val pets = snapshot["pets"].list
        if (pets.isEmpty()) return null
        val shift = utcOffsetNowMs?.let { now -> (snapshot["utcOffsetMs"].long ?: now) - now } ?: 0L
        val now = nowMs - shift
        val generated = snapshot["generatedAt"].long ?: 0L
        val newTaps = taps.filter { it.second >= generated }.map { it.first to it.second - shift }
        val faces = pets.map { faceOf(it, now, newTaps, shift) }
        return when (petChoice) {
            MOST_IN_NEED -> faces.minBy { PRIORITY.indexOf(it.mood) }
            null -> faces.first()
            else -> faces.firstOrNull { it.petId == petChoice } ?: faces.first()
        }
    }

    /** Worst first: "most in need" shows the pet that needs the owner most. */
    private val PRIORITY = listOf(Mood.SAD, Mood.NEEDS_MEDS, Mood.HUNGRY, Mood.RESTLESS, Mood.CONTENT, Mood.HAPPY, Mood.SLEEPY)

    private fun faceOf(pet: Json, now: Long, taps: List<Pair<String, Long>>, shift: Long): WidgetFace {
        // Follow Done taps on the widget: a tapped button switches to its "if done" track. One level is
        // precomputed; a further tap (before the app has run) just hides its button.
        var track = pet
        val unused = taps.filter { it.second <= now }.toMutableList()
        val hidden = ArrayList<Json>()
        var lastTap: Long? = null
        while (true) {
            val (button, tap) = tappedButton(track["actions"].list, unused) ?: break
            unused -= tap
            hidden += button
            lastTap = maxOf(lastTap ?: tap.second, tap.second)
            track = button["ifDone"].takeIf { it is Json.Obj } ?: break
        }
        val point = current(track["timeline"].list, now)
        val action = current(track["actions"].list, now)?.takeIf { it["taskId"].str != null && it !in hidden }
        val next = current(track["next"].list, now)?.takeIf { (it["dueAt"].long ?: 0L) > now }
        var mood = Mood.fromKey(point?.get("mood")?.str ?: "content")
        var caption = point?.get("caption")?.str ?: ""
        // "If done" assumed the tap came when the button appeared; the pet is happy for a while after the real one.
        if (lastTap != null && mood == Mood.CONTENT && now - lastTap <= MoodEngine.RECENT_CARE_MS) {
            mood = Mood.HAPPY
            caption = pet["happy"].str ?: caption
        }
        return WidgetFace(
            petId = pet["id"].str ?: "",
            name = pet["name"].str ?: "",
            mood = mood,
            caption = caption,
            sprite = pet["sprites"][mood.key].str,
            frames = pet["frames"][mood.key].list.mapNotNull { it.str },
            actionTaskId = action?.get("taskId")?.str,
            actionEmoji = action?.get("emoji")?.str,
            actionTitle = action?.get("title")?.str,
            nextEmoji = next?.get("emoji")?.str,
            nextTitle = next?.get("title")?.str,
            nextAtMs = next?.get("dueAt")?.long?.plus(shift),
        )
    }

    /** The earliest Done button in [buttons] that one of [taps] was made on (while it showed). */
    private fun tappedButton(buttons: List<Json>, taps: List<Pair<String, Long>>): Pair<Json, Pair<String, Long>>? {
        for ((i, b) in buttons.withIndex()) {
            val id = b["taskId"].str ?: continue
            val from = b["at"].long ?: 0L
            val until = buttons.getOrNull(i + 1)?.get("at")?.long ?: Long.MAX_VALUE
            taps.firstOrNull { (t, at) -> t == id && at in from until until }?.let { return b to it }
        }
        return null
    }

    private fun current(points: List<Json>, now: Long): Json? =
        points.lastOrNull { (it["at"].long ?: 0L) <= now } ?: points.firstOrNull()

    /**
     * The first moment after [nowMs] when anything a widget shows changes (mood, Done button, next
     * care), so Android can redraw then. Null if nothing changes within the snapshot.
     */
    fun nextChangeMs(snapshot: Json, nowMs: Long): Long? =
        snapshot["pets"].list.flatMap { p ->
            listOf("timeline", "actions", "next").flatMap { k -> p[k].list.mapNotNull { it["at"].long } }
        }.filter { it > nowMs }.minOrNull()

    /**
     * iOS widget taps can't run Kotlin, so the widget's App Intent appends `{"taskId","at"}` records
     * to `pending_done.json` in the shared App Group. The app ingests them on launch/foreground.
     */
    const val PENDING_FILE_NAME = "pending_done.json"

    fun parsePending(text: String): List<Pair<String, Long>> = runCatching {
        Json.parse(text).list.mapNotNull { j ->
            val id = j["taskId"].str ?: return@mapNotNull null
            val at = j["at"].long ?: return@mapNotNull null
            id to at
        }
    }.getOrDefault(emptyList())
}

/** What one widget shows at a moment (see [WidgetSnapshot.face]). */
data class WidgetFace(
    val petId: String,
    val name: String,
    val mood: Mood,
    val caption: String,
    /** The mood's pose, relative to the shared folder. */
    val sprite: String?,
    /** The mood's idle animation: the frames in order (paths repeat), empty on an older snapshot. */
    val frames: List<String> = emptyList(),
    /** The one-tap Done ("🍖 Done"), when something is due now, and what it's for ("🍖", "Feed"). */
    val actionTaskId: String?,
    val actionEmoji: String?,
    val actionTitle: String?,
    /** The next planned everyday care, when there is one. */
    val nextEmoji: String?,
    val nextTitle: String?,
    val nextAtMs: Long?,
)
