package com.pawpixel.core

/**
 * Core data model. Everything is plain data so it can be persisted with [StateCodec]
 * (a tiny JSON codec with no dependencies) and read by the native widgets.
 *
 * Time is always epoch milliseconds (UTC). Anything "local" (time of day, calendar day)
 * is derived with a timezone offset supplied by the platform — see [LocalClock].
 */

enum class Species(val label: String) { DOG("Dog"), CAT("Cat"), OTHER("Other") }

enum class TaskKind(
    val label: String,
    val emoji: String,
    val verb: String,
    /**
     * Health care (vaccines, deworming, tick & flea, vet visits): due a set number of days after it
     * was last done, rather than at fixed times each day. See [StateOps.complete].
     */
    val health: Boolean = false,
    /** Suggested name for a new task of this kind. */
    val defaultTitle: String = label,
) {
    FEED("Feed", "🍖", "Fed"),
    WATER("Fresh water", "💧", "Refilled water"),
    WALK("Walk", "🦮", "Walked"),
    PLAY("Play", "🎾", "Played"),
    MEDS("Medicine", "💊", "Gave medicine"),
    GROOM("Groom", "🪮", "Groomed"),
    LITTER("Clean litter", "🧹", "Cleaned litter"),
    VACCINE("Vaccine", "💉", "Vaccinated", health = true, defaultTitle = "Anti-rabies shot"),
    DEWORM("Deworming", "🪱", "Dewormed", health = true),
    FLEA_TICK("Tick & flea", "🛡️", "Gave tick & flea care", health = true, defaultTitle = "Tick & flea prevention"),
    VET("Vet check-up", "🩺", "Saw the vet", health = true),
}

data class Pet(
    val id: String,
    val name: String,
    val species: Species,
    val createdAtMs: Long,
    /** Settings the sprite was generated with, so it can be regenerated the same way. */
    val sprite: SpriteSettings = SpriteSettings(),
    /** Bumped every time the sprite is regenerated, so caches/widgets reload. */
    val spriteVersion: Int = 1,
    /** Eye positions tapped by the owner, as fractions (0..1) of the sprite's width/height, for blinking. */
    val eyes: List<Pair<Double, Double>> = emptyList(),
    /** Ear shape chosen by the owner ("POINTY" / "FLOPPY"); null = the species' usual ears. */
    val ears: String? = null,
    /** Local day index of the pet's birthday (a guess is fine); null = not given. Plans puppy/kitten care. */
    val birthDay: Long? = null,
    /** Cared for together with a family (see [HouseholdSync]); its care syncs with their phones. */
    val shared: Boolean = false,
    /**
     * The pixel look as a [com.pawpixel.sprite.PetLook] code. Pets that came from a family member's
     * phone have no face file here, so they're drawn from this.
     */
    val lookCode: String? = null,
    /**
     * Every day (local day index) anyone logged care for this pet, sorted. Kept separately from the
     * records (which are trimmed) so milestones like "100 days of care" count the whole history.
     */
    val careDays: List<Long> = emptyList(),
    /** The highest milestone already celebrated (see [Milestones]). */
    val milestoneSeen: Int = 0,
    /** The outfit it wears ([com.pawpixel.sprite.Accessory] name), earned with days of care. */
    val accessory: String? = null,
    /** When this phone last changed something the household shares (name, look, ...): the later edit wins. */
    val editedAtMs: Long = 0,
    /** The owner's Pet Studio choices ([com.pawpixel.sprite.PetStyle.encode]); null = as the photo says. */
    val style: String? = null,
    /**
     * The local day the pet passed away, when the owner chose to remember it: no more reminders or
     * needs, its room is quiet, and its album and history stay for as long as the owner wants them.
     */
    val rememberedDay: Long? = null,
) {
    /** Kept in memory: no care is due, and nothing about it is ever "overdue". */
    val remembered: Boolean get() = rememberedDay != null

    /**
     * Everything the pixel pet is drawn from. A screen that keeps a drawing of the pet keys it by
     * this, so a new outfit, ears or a look from the household shows at once (a name change doesn't redraw).
     */
    val lookKey: List<Any?> get() = listOf(id, spriteVersion, species, ears, accessory, lookCode, style)
}

data class SpriteSettings(
    /** Width/height of the pet sprite in pixels before outline and effects. */
    val size: Int = 48,
    /** Number of colours in the palette. */
    val colors: Int = 12,
    val outline: Boolean = true,
    /** Colour boost applied before quantising; 1.0 = none. */
    val vibrance: Double = 1.15,
)

data class CareTask(
    val id: String,
    val petId: String,
    val kind: TaskKind,
    val title: String,
    /** Planned times of day in minutes after local midnight, sorted, 1..4 entries. */
    val slots: List<Int>,
    /** 1 = every day, 7 = weekly, etc. */
    val everyDays: Int = 1,
    /** Local day index (see [LocalClock.dayIndex]) the every-N-days cycle is anchored to. */
    val anchorDay: Long = 0,
    /** Let PawPixel shift reminder times toward when the owner actually does the task. */
    val adaptive: Boolean = true,
    /** Ask the OS for an exact alarm (Android needs a user permission for this). Useful for meds. */
    val exactAlarm: Boolean = false,
    val remindersOn: Boolean = true,
    /** Slots planned before this moment never count as missed (a pet added at 9am isn't "hungry" for 7am). */
    val createdAtMs: Long = 0,
    /**
     * Health only: planned days (local day indices) of a first-year series, e.g. a puppy's 5-in-1
     * at 6, 9, 12 and 16 weeks. Each dose given (each completion) moves to the next planned day;
     * after the last one the task repeats every [everyDays] from when it was last given.
     */
    val series: List<Long> = emptyList(),
    /** When this phone last changed the task (see [Pet.editedAtMs]). */
    val editedAtMs: Long = 0,
)

data class Completion(
    val taskId: String,
    val atMs: Long,
    /** Minute of local day when it happened (0..1439), stored so learning ignores later timezone changes. */
    val localMinute: Int,
    /** Local day index when it happened. */
    val localDay: Long,
    /** Stable id, so family phones can tell records apart. Old records get one derived from task and time. */
    val id: String = derivedId(taskId, atMs),
    /** Family sharing: the account that logged it (null = this phone, before sharing). */
    val by: String? = null,
) {
    companion object {
        fun derivedId(taskId: String, atMs: Long): String {
            var h = 1125899906842597L
            for (c in taskId) h = 31 * h + c.code
            h = 31 * h + atMs
            return "c" + (h and Long.MAX_VALUE).toString(36)
        }
    }
}

/** A weigh-in: [grams] on local day [day]. */
data class Weight(val petId: String, val day: Long, val grams: Int)

/**
 * One photo in a pet's album: a real photo the owner keeps with the pet, on this phone and in
 * backups (never uploaded). The file is at [Backup.albumPhotoPath]; [atMs] is when it was added
 * (the photo's own date isn't read: its metadata is dropped on purpose, see [Backup]).
 */
data class AlbumPhoto(val id: String, val petId: String, val atMs: Long, val caption: String = "")

data class Settings(
    val remindersEnabled: Boolean = true,
    /**
     * "Someone else is looking after my pet" (travel, pet-sitter, boarding) until this moment:
     * no reminders, and the pet doesn't get hungry or sad over care missed while you were away.
     */
    val awayUntilMs: Long = 0,
    /** The owner closed the "add the widget" tip. */
    val widgetTipDismissed: Boolean = false,
    /** "en", "fil", or "" to follow the phone's language. */
    val language: String = "",
    val pro: Boolean = false,
    /** Local minute to start "sleepy" night mode. */
    val nightStart: Int = 22 * 60,
    /** Local minute to end night mode. */
    val nightEnd: Int = 6 * 60,
    /**
     * The owner answered "turn on reminders?" on a pet's page (either way). PawPixel asks for the
     * notification permission there, once, where it's clear what the reminders are for.
     */
    val remindersAsked: Boolean = false,
)

data class AppState(
    val pets: List<Pet> = emptyList(),
    val tasks: List<CareTask> = emptyList(),
    val completions: List<Completion> = emptyList(),
    val settings: Settings = Settings(),
    val weights: List<Weight> = emptyList(),
    val album: List<AlbumPhoto> = emptyList(),
) {
    fun weightsFor(petId: String): List<Weight> = weights.filter { it.petId == petId }.sortedBy { it.day }
    /** A pet's album, newest first. */
    fun albumFor(petId: String): List<AlbumPhoto> = album.filter { it.petId == petId }.sortedByDescending { it.atMs }
    fun pet(id: String): Pet? = pets.firstOrNull { it.id == id }
    fun task(id: String): CareTask? = tasks.firstOrNull { it.id == id }
    fun isAway(nowMs: Long): Boolean = nowMs < settings.awayUntilMs
    fun tasksFor(petId: String): List<CareTask> = tasks.filter { it.petId == petId }
    fun completionsFor(taskId: String): List<Completion> = completions.filter { it.taskId == taskId }

    companion object {
        const val SCHEMA_VERSION = 1
        /** Completions kept per task; enough for ~5 weeks of 4x/day learning. */
        const val MAX_COMPLETIONS_PER_TASK = 140
        const val MAX_WEIGHTS_PER_PET = 200
        /** Album photos per pet (~300 KB each): a full album still fits a backup file. */
        const val MAX_ALBUM_PHOTOS_PER_PET = 100
        const val MAX_CAPTION = 140
        const val MAX_CARE_DAYS = 3000
        /** Longest repeat: yearly. */
        const val MAX_EVERY_DAYS = 365
        const val FREE_PET_LIMIT = 1
    }
}

/** Suggested defaults when a user adds a task, per kind and species. */
object TaskDefaults {
    fun slotsFor(kind: TaskKind, species: Species): List<Int> = when (kind) {
        TaskKind.FEED -> if (species == Species.CAT) listOf(7 * 60, 18 * 60) else listOf(7 * 60, 17 * 60 + 30)
        TaskKind.WATER -> listOf(8 * 60)
        TaskKind.WALK -> listOf(6 * 60 + 30, 17 * 60)
        TaskKind.PLAY -> listOf(19 * 60)
        TaskKind.MEDS -> listOf(8 * 60)
        TaskKind.GROOM -> listOf(10 * 60)
        TaskKind.LITTER -> listOf(9 * 60)
        // Health care is a day, not a time: the reminder comes in the morning of the day it's due.
        TaskKind.VACCINE, TaskKind.DEWORM, TaskKind.FLEA_TICK, TaskKind.VET -> listOf(9 * 60)
    }

    /**
     * Typical intervals for adult dogs and cats in the Philippines (anti-rabies yearly under RA 9482,
     * deworming every 3 months, monthly tick & flea prevention, a yearly check-up). Owners change
     * them to what their vet says; puppies and kittens need shorter gaps.
     */
    fun everyDaysFor(kind: TaskKind): Int = when (kind) {
        TaskKind.GROOM -> 7
        TaskKind.VACCINE, TaskKind.VET -> 365
        TaskKind.DEWORM -> 90
        TaskKind.FLEA_TICK -> 30
        else -> 1
    }

    /** The health care suggested for a species, for the "add health reminders" shortcut. */
    fun healthKindsFor(species: Species): List<TaskKind> = when (species) {
        Species.DOG, Species.CAT -> listOf(TaskKind.VACCINE, TaskKind.DEWORM, TaskKind.FLEA_TICK, TaskKind.VET)
        Species.OTHER -> listOf(TaskKind.VET)
    }

    fun kindsFor(species: Species): List<TaskKind> = when (species) {
        Species.DOG -> listOf(TaskKind.FEED, TaskKind.WALK, TaskKind.WATER)
        Species.CAT -> listOf(TaskKind.FEED, TaskKind.WATER, TaskKind.LITTER)
        Species.OTHER -> listOf(TaskKind.FEED, TaskKind.WATER)
    }
}

object Ids {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    fun newId(random: kotlin.random.Random = kotlin.random.Random.Default, length: Int = 12): String =
        buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
