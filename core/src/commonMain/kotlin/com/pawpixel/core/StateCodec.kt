package com.pawpixel.core

/** Saves and loads [AppState] as JSON. Unknown fields are ignored and missing ones get defaults. */
object StateCodec {

    fun encode(state: AppState): String = Json.obj(
        "schema" to AppState.SCHEMA_VERSION,
        "settings" to Json.obj(
            "remindersEnabled" to state.settings.remindersEnabled,
            "awayUntil" to state.settings.awayUntilMs,
            "widgetTipDismissed" to state.settings.widgetTipDismissed,
            "pro" to state.settings.pro,
            "nightStart" to state.settings.nightStart,
            "nightEnd" to state.settings.nightEnd,
        ),
        "pets" to state.pets.map { p ->
            Json.obj(
                "id" to p.id, "name" to p.name, "species" to p.species.name, "createdAt" to p.createdAtMs,
                "spriteVersion" to p.spriteVersion,
                "eyes" to p.eyes.map { (x, y) -> listOf(x, y) },
                "ears" to (p.ears ?: ""),
                "birthDay" to p.birthDay,
                "shared" to p.shared,
                "look" to p.lookCode,
                "sprite" to Json.obj(
                    "size" to p.sprite.size, "colors" to p.sprite.colors,
                    "outline" to p.sprite.outline, "vibrance" to p.sprite.vibrance,
                ),
            )
        },
        "tasks" to state.tasks.map { t ->
            Json.obj(
                "id" to t.id, "petId" to t.petId, "kind" to t.kind.name, "title" to t.title,
                "slots" to t.slots, "everyDays" to t.everyDays, "anchorDay" to t.anchorDay,
                "adaptive" to t.adaptive, "exactAlarm" to t.exactAlarm, "remindersOn" to t.remindersOn,
                "createdAt" to t.createdAtMs,
                "series" to t.series,
            )
        },
        "completions" to state.completions.map { c ->
            Json.obj("taskId" to c.taskId, "at" to c.atMs, "minute" to c.localMinute, "day" to c.localDay, "id" to c.id, "by" to c.by)
        },
    ).stringify()

    fun decode(text: String): AppState {
        val root = Json.parse(text)
        val s = root["settings"]
        val defaults = Settings()
        val settings = Settings(
            remindersEnabled = s["remindersEnabled"].bool ?: defaults.remindersEnabled,
            awayUntilMs = s["awayUntil"].long ?: defaults.awayUntilMs,
            widgetTipDismissed = s["widgetTipDismissed"].bool ?: defaults.widgetTipDismissed,
            pro = s["pro"].bool ?: defaults.pro,
            nightStart = s["nightStart"].int ?: defaults.nightStart,
            nightEnd = s["nightEnd"].int ?: defaults.nightEnd,
        )
        val spriteDefaults = SpriteSettings()
        val pets = root["pets"].list.mapNotNull { p ->
            // Ids name folders on disk: only ever the app's own [a-z0-9] ids (a crafted backup can't say "..").
            val id = p["id"].str?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val sp = p["sprite"]
            Pet(
                id = id,
                name = p["name"].str ?: "Pet",
                species = enumOr(p["species"].str, Species.OTHER),
                createdAtMs = p["createdAt"].long ?: 0L,
                spriteVersion = p["spriteVersion"].int ?: 1,
                eyes = p["eyes"].list.mapNotNull { e ->
                    val x = e.list.getOrNull(0)?.double; val y = e.list.getOrNull(1)?.double
                    if (x != null && y != null && x in 0.0..1.0 && y in 0.0..1.0) x to y else null
                }.take(2),
                ears = p["ears"].str?.ifEmpty { null },
                birthDay = p["birthDay"].long,
                shared = p["shared"].bool ?: false,
                lookCode = p["look"].str?.takeIf { it.length <= 200 },
                sprite = SpriteSettings(
                    size = sp["size"].int ?: spriteDefaults.size,
                    colors = sp["colors"].int ?: spriteDefaults.colors,
                    outline = sp["outline"].bool ?: spriteDefaults.outline,
                    vibrance = sp["vibrance"].double ?: spriteDefaults.vibrance,
                ),
            )
        }
        val petIds = pets.map { it.id }.toSet()
        val tasks = root["tasks"].list.mapNotNull { t ->
            val id = t["id"].str?.takeIf { ID.matches(it) } ?: return@mapNotNull null
            val petId = t["petId"].str ?: return@mapNotNull null
            if (petId !in petIds) return@mapNotNull null
            val kind = enumOr(t["kind"].str, TaskKind.FEED)
            CareTask(
                id = id, petId = petId, kind = kind,
                title = t["title"].str ?: kind.label,
                slots = t["slots"].list.mapNotNull { it.int }.filter { it in 0 until MINUTES_PER_DAY }.sorted()
                    .ifEmpty { listOf(8 * 60) },
                everyDays = (t["everyDays"].int ?: 1).coerceIn(1, AppState.MAX_EVERY_DAYS),
                anchorDay = t["anchorDay"].long ?: 0L,
                adaptive = t["adaptive"].bool ?: true,
                exactAlarm = t["exactAlarm"].bool ?: false,
                remindersOn = t["remindersOn"].bool ?: true,
                createdAtMs = t["createdAt"].long ?: 0L,
                series = t["series"].list.mapNotNull { it.long }.sorted().take(HealthPlan.MAX_DOSES),
            )
        }
        val taskIds = tasks.map { it.id }.toSet()
        val completions = root["completions"].list.mapNotNull { c ->
            val taskId = c["taskId"].str ?: return@mapNotNull null
            if (taskId !in taskIds) return@mapNotNull null
            val at = c["at"].long ?: return@mapNotNull null
            Completion(
                taskId = taskId,
                atMs = at,
                localMinute = c["minute"].int ?: 0,
                localDay = c["day"].long ?: 0L,
                id = c["id"].str?.takeIf { ID.matches(it) } ?: Completion.derivedId(taskId, at),
                by = c["by"].str?.takeIf { it.length <= 64 },
            )
        }
        return AppState(pets, tasks, completions, settings)
    }

    private val ID = Regex("[a-z0-9]{1,40}")

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
