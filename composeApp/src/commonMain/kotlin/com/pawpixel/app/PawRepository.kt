package com.pawpixel.app

import com.pawpixel.core.AppState
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
import com.pawpixel.sprite.Animator
import com.pawpixel.sprite.Argb
import com.pawpixel.sprite.PixelImage
import com.pawpixel.sprite.Png
import com.pawpixel.sprite.Poses
import com.pawpixel.sprite.RawImage
import com.pawpixel.sprite.RevealCard
import com.pawpixel.sprite.SpriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
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
    private val spriteCache = HashMap<String, PixelImage>()

    /** Bumped after every widget.json write, so a live widget session re-reads it. */
    private val _widgetRevision = MutableStateFlow(0L)
    val widgetRevision: StateFlow<Long> = _widgetRevision.asStateFlow()

    fun now() = platform.nowMs()

    private fun load(): AppState =
        files.readText(STATE_FILE)?.let { runCatching { StateCodec.decode(it) }.getOrNull() } ?: AppState()

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

    suspend fun addPet(name: String, species: Species, settings: SpriteSettings, result: SpriteResult, eyes: List<Pair<Double, Double>>): Pet {
        val pet = Pet(Ids.newId(), name.trim().ifEmpty { "My pet" }, species, now(), settings, eyes = eyes)
        saveSprite(pet.id, result, eyes)
        update { StateOps.addPet(it, pet, now(), clock) }
        return pet
    }

    suspend fun updateSprite(pet: Pet, settings: SpriteSettings, result: SpriteResult, eyes: List<Pair<Double, Double>>) {
        saveSprite(pet.id, result, eyes)
        update { StateOps.updatePet(it, pet.copy(sprite = settings, eyes = eyes, spriteVersion = pet.spriteVersion + 1)) }
    }

    suspend fun renamePet(pet: Pet, name: String, species: Species) =
        update { StateOps.updatePet(it, pet.copy(name = name.trim().ifEmpty { pet.name }, species = species)) }

    suspend fun deletePet(petId: String) {
        update { StateOps.removePet(it, petId) }
        spriteCache.remove(petId)
        files.delete("sprites/$petId")
    }

    suspend fun complete(taskId: String) = update { StateOps.complete(it, taskId, now(), clock) }
    suspend fun undo(taskId: String) = update { StateOps.undoLast(it, taskId) }
    suspend fun setSettings(settings: Settings) = update { StateOps.setSettings(it, settings) }

    suspend fun deleteAllData() {
        mutex.withLock {
            files.delete("sprites")
            files.delete(STATE_FILE)
            files.delete(WidgetSnapshot.PENDING_FILE_NAME)
            spriteCache.clear()
            _state.value = AppState()
            publishLocked(_state.value)
        }
    }

    // ---- Sprite files ----

    private fun saveSprite(petId: String, result: SpriteResult, eyes: List<Pair<Double, Double>>) {
        files.writeBytes("sprites/$petId/sprite.bin", RawImage.encode(result.sprite))
        files.writeBytes("sprites/$petId/photo.bin", RawImage.encode(result.photoCrop))
        val closed = if (eyes.isEmpty()) null else Animator.closedEyes(result.sprite, Animator.eyePixels(result.sprite, eyes))
        // Pre-scaled PNGs so widgets (which may smooth when scaling) still look crisp.
        for ((mood, img) in Poses.renderAll(result.sprite, closed)) {
            files.writeBytes(WidgetSnapshot.spritePath(petId, mood), Png.encode(img.scaled(WIDGET_SCALE)))
        }
        spriteCache[petId] = result.sprite
    }

    fun sprite(petId: String): PixelImage? = spriteCache[petId]
        ?: files.readBytes("sprites/$petId/sprite.bin")?.let(RawImage::decode)?.also { spriteCache[petId] = it }

    fun photoCrop(petId: String): PixelImage? = files.readBytes("sprites/$petId/photo.bin")?.let(RawImage::decode)

    fun pose(petId: String, mood: Mood): PixelImage? = sprite(petId)?.let { Poses.render(it, mood) }

    /** The stored sprite and crop as a [SpriteResult], for editing eyes without a new photo. */
    fun storedResult(petId: String): SpriteResult? {
        val sprite = sprite(petId) ?: return null
        val photo = photoCrop(petId) ?: return null
        return SpriteResult(sprite, sprite.pixels.filter { Argb.alpha(it) > 0 }.distinct(), photo, backgroundRemoved = true)
    }

    fun shareReveal(pet: Pet) {
        val sprite = sprite(pet.id) ?: return
        val photo = photoCrop(pet.id) ?: return
        val card = RevealCard.render(photo, sprite, pet.name)
        platform.shareFile(Png.encode(card), "${fileStem(pet)}.png", "image/png")
    }

    /**
     * A 5-second looping GIF of the pet being itself. Pure (no repository state), so it's safe to run
     * on a background thread: get [sprite] on the main thread first.
     */
    fun animationGif(pet: Pet, sprite: PixelImage): ByteArray =
        AnimatedExport.clip(sprite, Animator.eyePixels(sprite, pet.eyes), pet.name)

    fun shareGif(pet: Pet, gif: ByteArray) = platform.shareFile(gif, "${fileStem(pet)}.gif", "image/gif")

    private fun fileStem(pet: Pet) = "pawpixel-" + pet.name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "pet" }

    companion object {
        const val STATE_FILE = "state.json"
        const val WIDGET_SCALE = 4
    }
}
