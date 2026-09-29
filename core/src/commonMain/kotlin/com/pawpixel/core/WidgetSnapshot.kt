package com.pawpixel.core

/**
 * Everything a home-screen widget needs, precomputed, written as `widget.json` next to the sprite
 * PNGs. The iOS widget (SwiftUI) and Android widget (Glance) only read this file, never the engine.
 *
 * Shape (version 1):
 * ```
 * { "version":1, "generatedAt":ms,
 *   "pets":[ { "id","name","spriteVersion",
 *     "sprites": { "happy":"sprites/<id>/happy.png", ... },
 *     "timeline":[ {"at":ms,"mood":"happy","caption":"..."} ],
 *     "action": null | { "taskId","label","timelineIfDone":[...] },
 *     "next": null | { "title","emoji","at":ms } } ] }
 * ```
 * `action` is the one-tap Done button: the most urgent task right now (overdue, or due within the
 * next hour). `timelineIfDone` lets iOS show the result of a tap immediately, before the app runs.
 */
object WidgetSnapshot {
    const val VERSION = 1
    const val FILE_NAME = "widget.json"
    const val DUE_SOON_MS = HOUR_MS

    fun spritePath(petId: String, mood: Mood) = "sprites/$petId/${mood.key}.png"

    fun build(state: AppState, nowMs: Long, clock: LocalClock): Json {
        val pets = state.pets.map { pet ->
            val statuses = state.tasksFor(pet.id).map { t ->
                CareEngine.status(t, state.completions, nowMs, clock, AdaptiveTiming.effectiveSlots(t, state.completions, nowMs, clock))
            }
            // Health care (a vaccine, a vet visit) is logged in the app, not with a quick tap on the widget.
            val urgent = statuses
                .filter { !it.task.kind.health }
                .filter { it.isOverdue || (it.nextDueMs != null && it.nextDueMs - nowMs <= DUE_SOON_MS && !it.allDoneThisCycle) }
                .maxWithOrNull(compareBy<TaskStatus>({ MoodEngine.penalty(it, nowMs) }, { -(it.nextDueMs ?: Long.MAX_VALUE) }))
            val next = statuses.filter { !it.isOverdue && !it.task.kind.health }.mapNotNull { s -> s.nextDueMs?.let { s to it } }.minByOrNull { it.second }

            Json.obj(
                "id" to pet.id,
                "name" to pet.name,
                "spriteVersion" to pet.spriteVersion,
                "sprites" to Mood.entries.associate { it.key to spritePath(pet.id, it) },
                "timeline" to timelineJson(MoodEngine.timeline(state, pet.id, nowMs, clock)),
                "action" to urgent?.takeIf { !state.isAway(nowMs) }?.let { s ->
                    val after = StateOps.complete(state, s.task.id, nowMs, clock)
                    Json.obj(
                        "taskId" to s.task.id,
                        "label" to "${s.task.kind.emoji} ${com.pawpixel.i18n.tr(s.task.kind.verb)}",
                        "timelineIfDone" to timelineJson(MoodEngine.timeline(after, pet.id, nowMs, clock)),
                    )
                },
                "next" to next?.let { (s, at) -> Json.obj("title" to s.task.title, "emoji" to s.task.kind.emoji, "at" to at) },
            )
        }
        // The widgets' own words, in the owner's language (the iOS widget can't run Kotlin).
        val labels = Json.obj(
            "done" to com.pawpixel.i18n.tr("Done"),
            "yourPet" to com.pawpixel.i18n.tr("Your pet"),
            "empty" to com.pawpixel.i18n.tr("Open PawPixel to make your pixel pet"),
        )
        return Json.obj("version" to VERSION, "generatedAt" to nowMs, "pets" to pets, "labels" to labels)
    }

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

    private fun timelineJson(points: List<MoodPoint>): List<Json> =
        points.map { Json.obj("at" to it.atMs, "mood" to it.mood.key, "caption" to it.caption) }
}
