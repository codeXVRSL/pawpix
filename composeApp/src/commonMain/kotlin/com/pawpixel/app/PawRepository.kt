package com.pawpixel.app

import com.pawpixel.core.AppState
import com.pawpixel.i18n.I18n
import com.pawpixel.i18n.Lang
import com.pawpixel.i18n.tr
import com.pawpixel.core.Backup
import com.pawpixel.core.CareTask
import com.pawpixel.core.HealthPlan
import com.pawpixel.core.HouseholdSync
import com.pawpixel.core.Ids
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Mood
import com.pawpixel.core.Pet
import com.pawpixel.core.ReminderPlanner
import com.pawpixel.core.Settings
import com.pawpixel.core.Species
import com.pawpixel.core.SpriteSettings
import com.pawpixel.core.StateCodec
import com.pawpixel.core.StateOps
import com.pawpixel.core.WidgetSnapshot
import com.pawpixel.sprite.AnimatedExport
import com.pawpixel.sprite.Argb
import com.pawpixel.sprite.Chibi
import com.pawpixel.sprite.Ears
import com.pawpixel.sprite.FaceBox
import com.pawpixel.sprite.PetArt
import com.pawpixel.sprite.PetLook
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Png
import com.pawpixel.sprite.Poses
import com.pawpixel.sprite.RawImage
import com.pawpixel.sprite.RevealCard
import com.pawpixel.sprite.SpriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * The single source of truth. Every change is saved to `state.json`, then [publish] regenerates
 * what depends on it: the widget snapshot, scheduled reminders, and a widget reload.
 *
 * All data stays on the device. Photos are never uploaded; only a 256px crop is kept for the
 * reveal card and re-generating the sprite.
 */
class PawRepository(val platform: Platform) {
    val clock = LocalClock { platform.utcOffsetMs(it) }
    private val files get() = platform.files
    private val mutex = Mutex()

    /**
     * Set when the saved data couldn't be read at start (see [load]): what happened, in words for
     * the owner. The app shows it once; [dismissStartNotice] clears it.
     */
    private val _startNotice = MutableStateFlow<String?>(null)
    val startNotice: StateFlow<String?> = _startNotice.asStateFlow()
    fun dismissStartNotice() { _startNotice.value = null }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Pets' faces, read once. Replaced, never changed in place: screens and background work read it at once. */
    @kotlin.concurrent.Volatile
    private var headCache: Map<String, PixelImage> = emptyMap()

    /** Bumped after every widget.json write, so a live widget session re-reads it. */
    private val _widgetRevision = MutableStateFlow(0L)
    val widgetRevision: StateFlow<Long> = _widgetRevision.asStateFlow()

    fun now() = platform.nowMs()

    /** The opt-in pet map (sign-in session, shared pets, your ~1 km area). */
    val map: PetMapModel by lazy { PetMapModel(platform, files, onAccountDeleted = { family.forgetLocally() }) }

    /** Family sharing (same sign-in as the map). */
    val family: FamilyModel by lazy { FamilyModel(this, map) }

    /**
     * The saved state. A damaged save is never replaced by an empty app: the last good copy
     * ([STATE_BACKUP], refreshed at every start) takes over, and the damaged file is kept aside.
     */
    private fun load(): AppState {
        // A restore interrupted between its two renames: put the pets' files back.
        if (!files.exists("sprites") && files.exists(OLD_SPRITES)) files.rename(OLD_SPRITES, "sprites")
        val saved = runCatching { files.readText(STATE_FILE) }.getOrNull()
        val loaded = StateCodec.load(saved, runCatching { files.readText(STATE_BACKUP) }.getOrNull())
        when (loaded.source) {
            StateCodec.Source.SAVED -> files.writeText(STATE_BACKUP, saved!!)
            StateCodec.Source.NEW -> Unit
            StateCodec.Source.BACKUP, StateCodec.Source.UNREADABLE -> {
                // Keep what couldn't be read (support may recover it), and never write over the good copy.
                if (!saved.isNullOrEmpty()) { files.delete(STATE_DAMAGED); files.rename(STATE_FILE, STATE_DAMAGED) }
                platform.log("state.json unreadable (${saved?.length ?: -1} chars); using ${loaded.source}")
            }
        }
        applyLanguage(loaded.state)
        _startNotice.value = when (loaded.source) {
            StateCodec.Source.BACKUP -> tr("PawPixel couldn't read its latest save, so it opened the copy from when you last started the app. Care logged after that may be missing.")
            StateCodec.Source.UNREADABLE -> tr("PawPixel couldn't read its saved pets. The file is kept on this phone; if you have a backup file, restore it from Settings.")
            else -> null
        }
        return loaded.state
    }

    /** Everything drawn after this speaks the owner's language (Settings → Language, or the phone's). */
    private fun applyLanguage(state: AppState) {
        I18n.lang = Lang.resolve(state.settings.language, platform.systemLanguage())
    }

    /**
     * Applies a change, saves it and republishes. [stamp]: the owner's own change, so edited pets and
     * tasks get the time of the edit (the later edit wins between household phones); false for what
     * a household sync brought in.
     */
    suspend fun update(stamp: Boolean = true, change: (AppState) -> AppState): AppState = withContext(Dispatchers.Default) {
        // Off the main thread: saving, the widget snapshot and scheduling alarms take a while on a budget phone.
        mutex.withLock {
            val n = change(_state.value).let { if (stamp) HouseholdSync.stamp(_state.value, it, now()) else it }
            if (n != _state.value) {
                if (!files.writeText(STATE_FILE, StateCodec.encode(n))) platform.log("Couldn't save state.json (phone full?)")
                if (n.settings.language != _state.value.settings.language) applyLanguage(n)
                _state.value = n
            }
            // Inside the lock, so concurrent updates (UI, widget, notification) publish in order.
            publishLocked(n)
            n
        }
    }

    /** Rebuilds widget data and reminders from the current state. Safe to call any time (e.g. app start). */
    suspend fun publish() = withContext(Dispatchers.Default) { mutex.withLock { publishLocked(_state.value) } }

    private suspend fun publishLocked(state: AppState) {
        applyLanguage(state) // the phone's language may have changed while PawPixel was running
        val now = now()
        // Two days of widget timelines is real work: off the main thread (update() and publish() already are).
        val (snapshot, reminders) = withContext(Dispatchers.Default) {
            WidgetSnapshot.build(state, now, clock) to ReminderPlanner.plan(state, now, clock)
        }
        files.writeText(WidgetSnapshot.FILE_NAME, snapshot.stringify())
        _widgetRevision.value = _widgetRevision.value + 1
        platform.scheduleReminders(reminders)
        platform.refreshWidgets(WidgetSnapshot.nextChangeMs(snapshot, now))
    }

    /** A link into the app ("pawpixel://pet/<id>" from a widget) for the screens to follow, until [consumeLink]. */
    private val _link = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = _link.asStateFlow()
    fun openLink(url: String) { _link.value = url }
    fun consumeLink() { _link.value = null }

    /** Applies Done taps made on the iOS widget while the app was closed. */
    suspend fun ingestWidgetTaps() {
        val text = files.readText(WidgetSnapshot.PENDING_FILE_NAME) ?: return
        files.delete(WidgetSnapshot.PENDING_FILE_NAME)
        val taps = WidgetSnapshot.parsePending(text)
        if (taps.isEmpty()) return
        update { s -> taps.fold(s) { acc, (taskId, at) -> StateOps.completeTap(acc, taskId, at, clock) } }
    }

    // ---- Pets ----

    suspend fun addPet(name: String, species: Species, settings: SpriteSettings, result: SpriteResult, ears: Ears?, birthDay: Long? = null): Pet {
        val draft = Pet(Ids.newId(), name.trim().ifEmpty { "My pet" }, species, now(), settings, ears = ears?.name, birthDay = birthDay)
        val pet = withContext(Dispatchers.Default) {
            saveHead(draft.id, result.head, result.photoCrop)
            draft.copy(lookCode = art(draft)?.look?.encode()).also { writeWidgetPoses(it) }
        }
        update { StateOps.addPet(it, pet, now(), clock) }
        return pet
    }

    suspend fun updateSprite(pet: Pet, settings: SpriteSettings, result: SpriteResult, ears: Ears?) {
        val drawn = pet.copy(sprite = settings, eyes = emptyList(), ears = ears?.name, spriteVersion = pet.spriteVersion + 1)
        val updated = withContext(Dispatchers.Default) {
            saveHead(pet.id, result.head, result.photoCrop)
            // The look code travels to family phones, so keep it in step with the face.
            drawn.copy(lookCode = art(drawn)?.look?.encode() ?: pet.lookCode).also { writeWidgetPoses(it) }
        }
        update { StateOps.updatePet(it, updated) }
    }

    /** Species decides the body shape (dog or cat), so changing it redraws the widget poses. */
    suspend fun renamePet(pet: Pet, name: String, species: Species, birthDay: Long? = pet.birthDay) {
        val updated = pet.copy(
            name = name.trim().ifEmpty { pet.name }, species = species, birthDay = birthDay,
            spriteVersion = if (species != pet.species) pet.spriteVersion + 1 else pet.spriteVersion,
        )
        if (species != pet.species) withContext(Dispatchers.Default) { writeWidgetPoses(updated) }
        update { StateOps.updatePet(it, updated) }
    }

    suspend fun deletePet(petId: String) {
        update { StateOps.removePet(it, petId) }
        headCache = headCache - petId
        files.delete("sprites/$petId")
    }

    /** Done tapped in the app or on the widget (a double tap logs it once, see [StateOps.completeTap]). */
    suspend fun complete(taskId: String) = update { StateOps.completeTap(it, taskId, now(), clock) }
    /** "Done" on a notification: health care only counts if it's actually due (see [StateOps.completeFromReminder]). */
    suspend fun completeFromReminder(taskId: String) = update { StateOps.completeFromReminder(it, taskId, now(), clock) }

    /** "Done" on a (possibly bundled) notification: everything it's about that isn't done yet, in one change. */
    suspend fun completeFromReminder(refs: List<com.pawpixel.core.ReminderRef>) = update { StateOps.completeFromReminder(it, refs, now(), clock) }
    /** Health records: "given N days ago" (0 = today). */
    suspend fun givenDaysAgo(taskId: String, days: Int) =
        update { StateOps.logOnDay(it, taskId, clock.dayIndex(now()) - days, now(), clock) }

    /**
     * Records a health item as given [daysAgo] days ago, with an optional [photo] of the vaccination
     * card or receipt. The record is kept even if the photo can't be read: returns a message then.
     */
    suspend fun recordHealth(task: CareTask, daysAgo: Int, photo: ByteArray?): String? {
        val id = Ids.newId()
        update { StateOps.logOnDay(it, task.id, clock.dayIndex(now()) - daysAgo, now(), clock, newId = { id }) }
        if (photo == null || saveRecordPhoto(task.petId, id, photo)) return null
        return tr("Saved, but that photo couldn't be read. Add another one from History.")
    }

    /**
     * Adds the usual health care the pet doesn't have yet (see [HealthPlan]). With a birthday, a
     * puppy's or kitten's items follow its first-year schedule.
     */
    suspend fun addHealthCare(pet: Pet, birthDay: Long?) = update { s ->
        val withBirthday = (s.pet(pet.id) ?: pet).let { it.copy(birthDay = birthDay ?: it.birthDay) }
        HealthPlan.addTo(StateOps.updatePet(s, withBirthday), withBirthday, now(), clock)
    }

    suspend fun deleteTask(task: CareTask) {
        val records = _state.value.completionsFor(task.id)
        update { StateOps.removeTask(it, task.id) }
        files.delete(Backup.cardPath(task.petId, task.id))
        records.forEach { files.delete(Backup.recordPhotoPath(task.petId, it.id)) }
        _cardRevision.value = _cardRevision.value + 1
    }

    /** Deletes a health record logged by mistake, with its photo. */
    suspend fun deleteRecord(task: CareTask, completionId: String) {
        update { StateOps.removeCompletion(it, completionId) }
        deleteRecordPhoto(task.petId, completionId)
    }

    // ---- Health photos (vaccination cards, registration cards, vet receipts) ----

    /**
     * Keeps a photo with a health record. Re-encoded on the phone (long side at most [CARD_MAX_SIDE],
     * JPEG, or PNG where JPEG isn't available), which also drops location and other photo metadata.
     */
    suspend fun saveRecordPhoto(petId: String, completionId: String, photo: ByteArray): Boolean {
        val img = platform.decodePhoto(photo, CARD_MAX_SIDE) ?: return false
        val bytes = platform.encodeJpeg(img) ?: withContext(Dispatchers.Default) { Png.encode(img) }
        if (!files.writeBytes(Backup.recordPhotoPath(petId, completionId), bytes)) return false
        _cardRevision.value = _cardRevision.value + 1
        return true
    }

    fun recordPhoto(petId: String, completionId: String): ByteArray? = files.readBytes(Backup.recordPhotoPath(petId, completionId))
    fun hasRecordPhoto(petId: String, completionId: String): Boolean = files.exists(Backup.recordPhotoPath(petId, completionId))

    fun deleteRecordPhoto(petId: String, completionId: String) {
        files.delete(Backup.recordPhotoPath(petId, completionId))
        _cardRevision.value = _cardRevision.value + 1
    }

    /** A card photo from an older version, kept with the item rather than a record. */
    fun card(task: CareTask): ByteArray? = files.readBytes(Backup.cardPath(task.petId, task.id))
    fun hasCard(task: CareTask): Boolean = files.exists(Backup.cardPath(task.petId, task.id))

    fun deleteCard(task: CareTask) {
        files.delete(Backup.cardPath(task.petId, task.id))
        _cardRevision.value = _cardRevision.value + 1
    }

    /** Bumped when a health photo changes, so the screen re-reads it. */
    private val _cardRevision = MutableStateFlow(0L)
    val cardRevision: StateFlow<Long> = _cardRevision.asStateFlow()

    /** Someone else is caring for the pets for [days] days. 0 = "I'm back": away ends now, and what was missed stays forgiven. */
    suspend fun setAway(days: Int) = update {
        StateOps.setAway(it, if (days <= 0) now() else clock.at(clock.dayIndex(now()) + days, 12 * 60))
    }
    /** Undo takes back this phone's own last record, never a family member's. */
    suspend fun undo(taskId: String) {
        val mine = setOf(null, family.myUserId)
        val gone = _state.value.completions.lastOrNull { it.taskId == taskId && it.by in mine }
        val s = update { StateOps.undoLast(it, taskId, mine = mine) }
        val task = s.task(taskId)
        if (gone != null && task != null && task.kind.health && s.completions.none { it.id == gone.id }) deleteRecordPhoto(task.petId, gone.id)
    }

    /**
     * A Done from outside the app (widget, notification): logs it, then tells the household right
     * away (best effort, a few seconds), so nobody else is reminded to feed a pet that was just fed.
     */
    suspend fun completeInBackground(log: suspend PawRepository.() -> Unit) {
        log()
        family.syncWithin()
    }
    suspend fun setSettings(settings: Settings) = update { StateOps.setSettings(it, settings) }

    suspend fun deleteAllData() {
        // If you joined the pet map, delete that account too (best effort: offline still wipes the phone).
        if (map.client.isSignedIn) runCatching { map.deleteAccount() }
        map.forgetLocally()
        family.clearLocal()
        mutex.withLock {
            files.delete("sprites")
            files.delete(STATE_FILE)
            files.delete(WidgetSnapshot.PENDING_FILE_NAME)
            files.delete(STATE_BACKUP)
            files.delete(STATE_DAMAGED)
            headCache = emptyMap()
            _state.value = AppState()
            publishLocked(_state.value)
        }
    }

    // ---- Backup ----

    /**
     * Everything on this phone as one file, shared to wherever the owner keeps it (Drive, Files,
     * email). Built off the main thread. Returns a message for the owner if it can't be made.
     */
    suspend fun exportBackup(): String? {
        val state = _state.value
        val bytes = withContext(Dispatchers.Default) {
            val data = state.pets.flatMap { Backup.filesFor(state, it.id) }.mapNotNull { path -> files.readBytes(path)?.let { path to it } }.toMap()
            Backup.encode(state, data, now()).encodeToByteArray()
        }
        if (bytes.size > Backup.MAX_BYTES) return tr("Your backup is too big to save as one file. Remove some card photos and try again.")
        platform.shareFile(bytes, "pawpixel-backup-${LocalClock.isoDate(clock.dayIndex(now()))}.json", "application/json")
        return null
    }

    /** Reads a picked file as a backup, off the main thread. Throws [Backup.NotABackup] with a message for the owner. */
    suspend fun readBackup(bytes: ByteArray): Backup.Contents = withContext(Dispatchers.Default) {
        if (bytes.isEmpty() || bytes.size > Backup.MAX_BYTES) throw Backup.NotABackup("That file is too big to be a PawPixel backup.")
        val contents = Backup.decode(bytes.decodeToString())
        // A pet that came from a household member's phone has no face file: it's drawn from its look code.
        val missing = contents.state.pets.filter { "sprites/${it.id}/head.bin" !in contents.files && it.lookCode?.let(PetLook::decode) == null }
        if (missing.isNotEmpty()) throw Backup.NotABackup(tr("This backup is missing {0}'s pixel look.", missing.first().name))
        contents
    }

    /**
     * Replaces everything on this phone with a backup, all or nothing: the new files are written to
     * a staging folder first, then swapped in with the new state. If anything fails, the phone keeps
     * what it had. The pet map sign-in is left as it is. Returns the number of pets restored.
     */
    suspend fun restoreBackup(contents: Backup.Contents): Int {
        withContext(Dispatchers.Default) {
            files.delete(STAGING)
            for ((path, data) in contents.files) {
                if (!files.writeBytes("$STAGING/$path", data)) {
                    files.delete(STAGING)
                    throw Backup.NotABackup(tr("Couldn't restore: your phone may be out of space. Nothing was changed."))
                }
            }
            // Widget poses for each pet, drawn from its restored face.
            for (pet in contents.state.pets) {
                val outfit = com.pawpixel.sprite.Accessory.of(pet.accessory)
                val art = contents.files["sprites/${pet.id}/head.bin"]?.let { runCatching { RawImage.decode(it) }.getOrNull() }
                    ?.let { PetArt(it, pet.species, Ears.of(pet.ears), outfit) }
                    ?: pet.lookCode?.let(PetLook::decode)?.let { PetArt(it, pet.species, Ears.of(pet.ears), outfit) }
                    ?: continue
                writeWidgetPoses(pet, art, "$STAGING/")
            }
        }
        withContext(Dispatchers.Default) { mutex.withLock {
            // Restored pets come back unshared: the family's copy may have moved on since the backup, and
            // re-sharing (Family sharing) merges them without deleting anyone's newer records.
            val restored = contents.state.copy(
                settings = contents.state.settings.copy(pro = _state.value.settings.pro),
                pets = contents.state.pets.map { it.copy(shared = false) },
            )
            val encoded = StateCodec.encode(restored)
            files.delete(OLD_SPRITES)
            val hadSprites = files.exists("sprites")
            if (hadSprites && !files.rename("sprites", OLD_SPRITES)) fail()
            if (!files.rename("$STAGING/sprites", "sprites") || !files.writeText(STATE_FILE, encoded)) {
                // Put the old pets back.
                files.delete("sprites")
                if (hadSprites) files.rename(OLD_SPRITES, "sprites")
                fail()
            }
            // The restored pets are now the last good copy (the one from this start is of the old pets).
            files.writeText(STATE_BACKUP, encoded)
            files.delete(OLD_SPRITES)
            files.delete(STAGING)
            headCache = emptyMap()
            // Keep this phone's own "Pro" (purchases belong to the store account, not the file).
            _state.value = restored
            publishLocked(restored)
        } }

        family.forgetBase()
        _cardRevision.value = _cardRevision.value + 1
        return contents.state.pets.size
    }

    private fun fail(): Nothing = throw Backup.NotABackup(tr("Couldn't restore: your phone may be out of space. Nothing was changed."))

    // ---- Sprite files ----
    // Each pet keeps a small pixelated copy of its face (head.bin), used only for its fur colours and
    // markings, and a small photo crop (photo.bin). The pixel pet is drawn from those plus species and
    // ears whenever needed (see Chibi), so nothing else is stored.

    private fun saveHead(petId: String, head: PixelImage, photoCrop: PixelImage) {
        files.writeBytes("sprites/$petId/head.bin", RawImage.encode(head))
        files.writeBytes("sprites/$petId/photo.bin", RawImage.encode(photoCrop))
        headCache = headCache + (petId to head)
    }

    /** Pre-rendered mood poses for the widgets, pre-scaled so widgets that smooth when scaling stay crisp. */
    private fun writeWidgetPoses(pet: Pet, art: PetArt? = art(pet), root: String = "") {
        art ?: return
        val sleeping = Chibi.sleeping(art)
        for ((mood, img) in Poses.renderAll(art.still, sleeping)) {
            files.writeBytes(root + WidgetSnapshot.spritePath(pet.id, mood), Png.encode(img.scaled(WIDGET_SCALE)))
        }
    }

    fun head(petId: String): PixelImage? = headCache[petId]
        ?: files.readBytes("sprites/$petId/head.bin")?.let(RawImage::decode)?.also { headCache = headCache + (petId to it) }

    /**
     * The pet's face (its colours and markings), species and ears: everything needed to draw and
     * animate it. A pet from a family member's phone has no face file here and is drawn from its look code.
     */
    fun art(pet: Pet): PetArt? {
        val outfit = com.pawpixel.sprite.Accessory.of(pet.accessory)
        return head(pet.id)?.let { PetArt(it, pet.species, Ears.of(pet.ears), outfit) }
            ?: pet.lookCode?.let { PetLook.decode(it) }?.let { PetArt(it, pet.species, Ears.of(pet.ears), outfit) }
    }

    /** Puts on (or takes off) an outfit the pet has earned, and redraws the widget poses. */
    suspend fun wear(pet: Pet, outfit: com.pawpixel.sprite.Accessory?) {
        val s = update { com.pawpixel.core.Milestones.wear(it, pet.id, outfit) }
        s.pet(pet.id)?.let { withContext(Dispatchers.Default) { writeWidgetPoses(it) }; platform.refreshWidgets(null) }
    }

    /** Widget poses for pets whose look arrived or changed through family sharing. */
    fun redrawPoses(petIds: List<String>) {
        for (id in petIds) state.value.pet(id)?.let { pet -> headCache = headCache - id; writeWidgetPoses(pet) }
        if (petIds.isNotEmpty()) platform.refreshWidgets(null)
    }

    fun photoCrop(petId: String): PixelImage? = files.readBytes("sprites/$petId/photo.bin")?.let(RawImage::decode)

    fun pose(pet: Pet, mood: Mood): PixelImage? = art(pet)?.let { a ->
        if (mood == Mood.SLEEPY) Poses.render(Chibi.sleeping(a), mood) else Poses.render(a.still, mood)
    }

    /** The stored face and crop as a [SpriteResult], for marking eyes or switching species without a new photo. */
    fun storedResult(pet: Pet): SpriteResult? {
        val head = head(pet.id) ?: return null
        val photo = photoCrop(pet.id) ?: return null
        return SpriteResult(head, head.pixels.filter { Argb.alpha(it) > 0 }.distinct(), photo, backgroundRemoved = true, face = FaceBox(0.5, 0.5, 1.0))
    }

    /** The before/after card: drawn off the main thread, then the share sheet. */
    suspend fun shareReveal(pet: Pet) {
        val png = withContext(Dispatchers.Default) {
            val art = art(pet) ?: return@withContext null
            val photo = photoCrop(pet.id) ?: return@withContext null
            Png.encode(RevealCard.render(photo, art.still, pet.name))
        } ?: return
        platform.shareFile(png, "${fileStem(pet)}.png", "image/png")
    }

    /**
     * A 5-second looping GIF of the pet being itself. Pure (no repository state), so it's safe to run
     * on a background thread: get [art] on the main thread first.
     */
    fun animationGif(pet: Pet, art: PetArt): ByteArray =
        AnimatedExport.clip(art, emptyList(), pet.name)

    /** Shares a milestone card ("100 days of care"). */
    /** A milestone card: drawn off the main thread, then the share sheet. */
    suspend fun shareMilestone(pet: Pet, days: Int) {
        val png = withContext(Dispatchers.Default) {
            val art = art(pet) ?: return@withContext null
            Png.encode(com.pawpixel.sprite.MilestoneCard.render(art.still, pet.name, com.pawpixel.core.Milestones.title(days)))
        } ?: return
        platform.shareFile(png, "${fileStem(pet)}-$days-days.png", "image/png")
    }

    suspend fun celebrate(petId: String, days: Int) = update { com.pawpixel.core.Milestones.celebrate(it, petId, days) }
    suspend fun logWeight(petId: String, day: Long, grams: Int) = update { StateOps.logWeight(it, petId, day, grams) }
    suspend fun editWeight(petId: String, day: Long, newDay: Long, grams: Int) = update { StateOps.editWeight(it, petId, day, newDay, grams) }
    suspend fun removeWeight(petId: String, day: Long) = update { StateOps.removeWeight(it, petId, day) }

    fun shareGif(pet: Pet, gif: ByteArray) = platform.shareFile(gif, "${fileStem(pet)}.gif", "image/gif")

    private fun fileStem(pet: Pet) = "pawpixel-" + pet.name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "pet" }

    companion object {
        const val STATE_FILE = "state.json"
        /** The last good state.json, from when the app last started (see [load]). */
        const val STATE_BACKUP = "state-backup.json"
        /** A state.json that couldn't be read, kept aside (never sent anywhere) in case it can be recovered. */
        private const val STATE_DAMAGED = "state-damaged.json"

        const val WIDGET_SCALE = 4
        /** Health photos: big enough to read a vaccination card, small enough for backups (~250 KB). */
        const val CARD_MAX_SIDE = 1280
        private const val STAGING = "restore"
        private const val OLD_SPRITES = "sprites.old"
    }
}
