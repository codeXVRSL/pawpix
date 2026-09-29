package com.pawpixel.app

import com.pawpixel.core.AppState
import com.pawpixel.core.Backup
import com.pawpixel.core.HealthPlan
import com.pawpixel.core.Ids
import com.pawpixel.core.LocalClock
import com.pawpixel.core.Mood
import com.pawpixel.core.MoodEngine
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
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val headCache = HashMap<String, PixelImage>()

    /** Bumped after every widget.json write, so a live widget session re-reads it. */
    private val _widgetRevision = MutableStateFlow(0L)
    val widgetRevision: StateFlow<Long> = _widgetRevision.asStateFlow()

    fun now() = platform.nowMs()

    /** The opt-in pet map (sign-in session, shared pets, your ~1 km area). */
    val map: PetMapModel by lazy { PetMapModel(platform, files, onAccountDeleted = { family.forgetLocally() }) }

    /** Family sharing (same sign-in as the map). */
    val family: FamilyModel by lazy { FamilyModel(this, map) }

    private fun load(): AppState {
        // A restore interrupted between its two renames: put the pets' files back.
        if (!files.exists("sprites") && files.exists(OLD_SPRITES)) files.rename(OLD_SPRITES, "sprites")
        return files.readText(STATE_FILE)?.let { runCatching { StateCodec.decode(it) }.getOrNull() } ?: AppState()
    }

    suspend fun update(change: (AppState) -> AppState): AppState = mutex.withLock {
        val n = change(_state.value)
        if (n != _state.value) {
            files.writeText(STATE_FILE, StateCodec.encode(n))
            _state.value = n
        }
        // Inside the lock, so concurrent updates (UI, widget, notification) publish in order.
        publishLocked(n)
        n
    }

    /** Rebuilds widget data and reminders from the current state. Safe to call any time (e.g. app start). */
    suspend fun publish() = mutex.withLock { publishLocked(_state.value) }

    private fun publishLocked(state: AppState) {
        val now = now()
        files.writeText(WidgetSnapshot.FILE_NAME, WidgetSnapshot.build(state, now, clock).stringify())
        _widgetRevision.value = _widgetRevision.value + 1
        platform.scheduleReminders(ReminderPlanner.plan(state, now, clock))
        val nextChange = state.pets.mapNotNull { MoodEngine.nextChangeMs(state, it.id, now, clock) }.minOrNull()
        platform.refreshWidgets(nextChange)
    }

    /** Applies Done taps made on the iOS widget while the app was closed. */
    suspend fun ingestWidgetTaps() {
        val text = files.readText(WidgetSnapshot.PENDING_FILE_NAME) ?: return
        files.delete(WidgetSnapshot.PENDING_FILE_NAME)
        val taps = WidgetSnapshot.parsePending(text)
        if (taps.isEmpty()) return
        update { s -> taps.fold(s) { acc, (taskId, at) -> StateOps.complete(acc, taskId, at, clock) } }
    }

    // ---- Pets ----

    suspend fun addPet(name: String, species: Species, settings: SpriteSettings, result: SpriteResult, ears: Ears?): Pet {
        val draft = Pet(Ids.newId(), name.trim().ifEmpty { "My pet" }, species, now(), settings, ears = ears?.name)
        saveHead(draft.id, result.head, result.photoCrop)
        val pet = draft.copy(lookCode = art(draft)?.look?.encode())
        writeWidgetPoses(pet)
        update { StateOps.addPet(it, pet, now(), clock) }
        return pet
    }

    suspend fun updateSprite(pet: Pet, settings: SpriteSettings, result: SpriteResult, ears: Ears?) {
        val drawn = pet.copy(sprite = settings, eyes = emptyList(), ears = ears?.name, spriteVersion = pet.spriteVersion + 1)
        saveHead(pet.id, result.head, result.photoCrop)
        // The look code travels to family phones, so keep it in step with the face.
        val updated = drawn.copy(lookCode = art(drawn)?.look?.encode() ?: pet.lookCode)
        writeWidgetPoses(updated)
        update { StateOps.updatePet(it, updated) }
    }

    /** Species decides the body shape (dog or cat), so changing it redraws the widget poses. */
    suspend fun renamePet(pet: Pet, name: String, species: Species) {
        val updated = pet.copy(
            name = name.trim().ifEmpty { pet.name }, species = species,
            spriteVersion = if (species != pet.species) pet.spriteVersion + 1 else pet.spriteVersion,
        )
        if (species != pet.species) writeWidgetPoses(updated)
        update { StateOps.updatePet(it, updated) }
    }

    suspend fun deletePet(petId: String) {
        update { StateOps.removePet(it, petId) }
        headCache.remove(petId)
        files.delete("sprites/$petId")
    }

    suspend fun complete(taskId: String) = update { StateOps.complete(it, taskId, now(), clock) }
    /** "Done" on a notification: health care only counts if it's actually due (see [StateOps.completeFromReminder]). */
    suspend fun completeFromReminder(taskId: String) = update { StateOps.completeFromReminder(it, taskId, now(), clock) }
    /** Health records: "given N days ago" (0 = today). */
    suspend fun givenDaysAgo(taskId: String, days: Int) =
        update { StateOps.logOnDay(it, taskId, clock.dayIndex(now()) - days, now(), clock) }

    /**
     * Adds the usual health care the pet doesn't have yet (see [HealthPlan]). With a [birthDay], a
     * puppy or kitten also gets its first-year vaccine and deworming series.
     */
    suspend fun addHealthCare(pet: Pet, birthDay: Long?) = update { s ->
        val withBirthday = pet.copy(birthDay = birthDay ?: pet.birthDay)
        HealthPlan.addTo(StateOps.updatePet(s, withBirthday), withBirthday, now(), clock)
    }

    suspend fun deleteTask(task: com.pawpixel.core.CareTask) {
        update { StateOps.removeTask(it, task.id) }
        files.delete(Backup.cardPath(task.petId, task.id))
    }

    // ---- Vaccination card photos ----

    /**
     * Keeps a photo of the vaccination card (or vet receipt) with a health item. Re-encoded on the
     * phone (max 1600 px, JPEG), which also removes location and other photo metadata.
     */
    suspend fun saveCard(task: com.pawpixel.core.CareTask, photo: ByteArray): Boolean {
        val img = platform.decodePhoto(photo, CARD_MAX_SIDE) ?: return false
        val bytes = platform.encodeJpeg(img) ?: withContext(Dispatchers.Default) { Png.encode(img) }
        files.writeBytes(Backup.cardPath(task.petId, task.id), bytes)
        _cardRevision.value = _cardRevision.value + 1
        return true
    }

    fun card(task: com.pawpixel.core.CareTask): ByteArray? = files.readBytes(Backup.cardPath(task.petId, task.id))
    fun hasCard(task: com.pawpixel.core.CareTask): Boolean = files.exists(Backup.cardPath(task.petId, task.id))

    fun deleteCard(task: com.pawpixel.core.CareTask) {
        files.delete(Backup.cardPath(task.petId, task.id))
        _cardRevision.value = _cardRevision.value + 1
    }

    /** Bumped when a card photo changes, so the screen re-reads it. */
    private val _cardRevision = MutableStateFlow(0L)
    val cardRevision: StateFlow<Long> = _cardRevision.asStateFlow()

    /** Someone else is caring for the pets for [days] days. 0 = "I'm back": away ends now, and what was missed stays forgiven. */
    suspend fun setAway(days: Int) = update {
        StateOps.setAway(it, if (days <= 0) now() else clock.at(clock.dayIndex(now()) + days, 12 * 60))
    }
    /** Undo takes back this phone's own last record, never a family member's. */
    suspend fun undo(taskId: String) = update { StateOps.undoLast(it, taskId, mine = setOf(null, family.myUserId)) }
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
            headCache.clear()
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
        if (bytes.size > Backup.MAX_BYTES) return "Your backup is too big to save as one file. Remove some card photos and try again."
        platform.shareFile(bytes, "pawpixel-backup-${LocalClock.isoDate(clock.dayIndex(now()))}.json", "application/json")
        return null
    }

    /** Reads a picked file as a backup, off the main thread. Throws [Backup.NotABackup] with a message for the owner. */
    suspend fun readBackup(bytes: ByteArray): Backup.Contents = withContext(Dispatchers.Default) {
        if (bytes.isEmpty() || bytes.size > Backup.MAX_BYTES) throw Backup.NotABackup("That file is too big to be a PawPixel backup.")
        val contents = Backup.decode(bytes.decodeToString())
        val missing = contents.state.pets.filter { "sprites/${it.id}/head.bin" !in contents.files }
        if (missing.isNotEmpty()) throw Backup.NotABackup("This backup is missing ${missing.first().name}'s pixel look.")
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
                    throw Backup.NotABackup("Couldn't restore: your phone may be out of space. Nothing was changed.")
                }
            }
            // Widget poses for each pet, drawn from its restored face.
            for (pet in contents.state.pets) {
                val head = contents.files["sprites/${pet.id}/head.bin"]?.let { runCatching { RawImage.decode(it) }.getOrNull() } ?: continue
                writeWidgetPoses(pet, PetArt(head, pet.species, Ears.of(pet.ears)), "$STAGING/")
            }
        }
        mutex.withLock {
            // Restored pets come back unshared: the family's copy may have moved on since the backup, and
            // re-sharing (Family sharing) merges them without deleting anyone's newer records.
            val restored = contents.state.copy(
                settings = contents.state.settings.copy(pro = _state.value.settings.pro),
                pets = contents.state.pets.map { it.copy(shared = false) },
            )
            files.delete(OLD_SPRITES)
            val hadSprites = files.exists("sprites")
            if (hadSprites && !files.rename("sprites", OLD_SPRITES)) fail()
            if (!files.rename("$STAGING/sprites", "sprites") || !files.writeText(STATE_FILE, StateCodec.encode(restored))) {
                // Put the old pets back.
                files.delete("sprites")
                if (hadSprites) files.rename(OLD_SPRITES, "sprites")
                fail()
            }
            files.delete(OLD_SPRITES)
            files.delete(STAGING)
            headCache.clear()
            // Keep this phone's own "Pro" (purchases belong to the store account, not the file).
            _state.value = restored
            publishLocked(restored)
        }
        family.forgetBase()
        _cardRevision.value = _cardRevision.value + 1
        return contents.state.pets.size
    }

    private fun fail(): Nothing = throw Backup.NotABackup("Couldn't restore: your phone may be out of space. Nothing was changed.")

    // ---- Sprite files ----
    // Each pet keeps a small pixelated copy of its face (head.bin), used only for its fur colours and
    // markings, and a small photo crop (photo.bin). The pixel pet is drawn from those plus species and
    // ears whenever needed (see Chibi), so nothing else is stored.

    private fun saveHead(petId: String, head: PixelImage, photoCrop: PixelImage) {
        files.writeBytes("sprites/$petId/head.bin", RawImage.encode(head))
        files.writeBytes("sprites/$petId/photo.bin", RawImage.encode(photoCrop))
        headCache[petId] = head
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
        ?: files.readBytes("sprites/$petId/head.bin")?.let(RawImage::decode)?.also { headCache[petId] = it }

    /**
     * The pet's face (its colours and markings), species and ears: everything needed to draw and
     * animate it. A pet from a family member's phone has no face file here and is drawn from its look code.
     */
    fun art(pet: Pet): PetArt? = head(pet.id)?.let { PetArt(it, pet.species, Ears.of(pet.ears)) }
        ?: pet.lookCode?.let { PetLook.decode(it) }?.let { PetArt(it, pet.species, Ears.of(pet.ears)) }

    /** Widget poses for pets whose look arrived or changed through family sharing. */
    fun redrawPoses(petIds: List<String>) {
        for (id in petIds) state.value.pet(id)?.let { pet -> headCache.remove(id); writeWidgetPoses(pet) }
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

    fun shareReveal(pet: Pet) {
        val art = art(pet) ?: return
        val photo = photoCrop(pet.id) ?: return
        val card = RevealCard.render(photo, art.still, pet.name)
        platform.shareFile(Png.encode(card), "${fileStem(pet)}.png", "image/png")
    }

    /**
     * A 5-second looping GIF of the pet being itself. Pure (no repository state), so it's safe to run
     * on a background thread: get [art] on the main thread first.
     */
    fun animationGif(pet: Pet, art: PetArt): ByteArray =
        AnimatedExport.clip(art, emptyList(), pet.name)

    fun shareGif(pet: Pet, gif: ByteArray) = platform.shareFile(gif, "${fileStem(pet)}.gif", "image/gif")

    private fun fileStem(pet: Pet) = "pawpixel-" + pet.name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "pet" }

    companion object {
        const val STATE_FILE = "state.json"
        const val WIDGET_SCALE = 4
        const val CARD_MAX_SIDE = 1600
        private const val STAGING = "restore"
        private const val OLD_SPRITES = "sprites.old"
    }
}
