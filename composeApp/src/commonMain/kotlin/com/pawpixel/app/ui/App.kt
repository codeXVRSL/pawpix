package com.pawpixel.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.pawpixel.i18n.tr
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pawpixel.app.PawRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object CreatePet : Screen
    data class RemakeSprite(val petId: String) : Screen
    /** The Pet Studio: how the pixel pet is drawn (eyes, ears, coat, colours...). */
    data class Studio(val petId: String) : Screen
    /**
     * A panel over the pet's room: "care", "health", "weight", "wardrobe", "share", "more" (the
     * pet's menu) or "pets" (switch pets). Panels slide up over the room and down again.
     */
    data class PetSection(val petId: String, val section: String) : Screen
    /** [health] picks which kinds a new task offers: daily care, or health care (vaccines, deworming...). */
    data class EditTask(val petId: String, val taskId: String?, val health: Boolean = false) : Screen
    data object Settings : Screen
    data object PetMap : Screen
    /** Pals: a small circle whose pixel pets visit each other. */
    data object Pals : Screen
    /** A walk timed with the app open (steps counted on the phone). */
    data class Walk(val petId: String) : Screen
    /** Sharing with your household. [sharePetId]: share this pet once in a household. [join]: you came to enter a code. */
    data class Family(val sharePetId: String? = null, val join: Boolean = false) : Screen
}

/**
 * A screen as a short string, so the back stack survives the phone rotating, dark mode or a bigger
 * font (Android re-creates the screen) and the app being reclaimed in the background.
 */
internal fun Screen.code(): String = when (this) {
    Screen.Home -> "home"
    Screen.CreatePet -> "create"
    is Screen.RemakeSprite -> "remake:$petId"
    is Screen.Studio -> "studio:$petId"
    is Screen.PetSection -> "section:$petId:$section"
    is Screen.EditTask -> "task:$petId:${taskId ?: "-"}:${if (health) 1 else 0}"
    Screen.Settings -> "settings"
    Screen.PetMap -> "map"
    Screen.Pals -> "pals"
    is Screen.Walk -> "walk:$petId"
    is Screen.Family -> "family:${sharePetId ?: "-"}:${if (join) 1 else 0}"
}

internal fun screenOf(code: String): Screen? {
    val p = code.split(':')
    fun id(i: Int) = p.getOrNull(i)?.takeIf { it != "-" && it.isNotEmpty() }
    return when (p[0]) {
        "home" -> Screen.Home
        "create" -> Screen.CreatePet
        "pet" -> null // older saved stacks: a pet's page is now the home screen showing it (dropped; Home is under it)
        "remake" -> id(1)?.let { Screen.RemakeSprite(it) }
        "studio" -> id(1)?.let { Screen.Studio(it) }
        "section" -> id(1)?.let { pet -> id(2)?.let { Screen.PetSection(pet, it) } }
        "task" -> id(1)?.let { Screen.EditTask(it, id(2), p.getOrNull(3) == "1") }
        "settings" -> Screen.Settings
        "map" -> Screen.PetMap
        "pals" -> Screen.Pals
        "walk" -> id(1)?.let { Screen.Walk(it) }
        "family" -> Screen.Family(id(1), p.getOrNull(2) == "1")
        else -> null
    }
}

private val StackSaver = Saver<List<Screen>, ArrayList<String>>(
    save = { stack -> ArrayList(stack.map { it.code() }) },
    restore = { codes -> codes.mapNotNull(::screenOf).ifEmpty { listOf(Screen.Home) } },
)

/** Shared state handed to every screen. */
class AppScope(
    val repo: PawRepository,
    val scope: CoroutineScope,
    val now: Long,
    val navigate: (Screen) -> Unit,
    val back: () -> Unit,
    /** The pet whose room the home screen shows (the first pet when unset). */
    val shownPetId: String? = null,
    val showPet: (String) -> Unit = {},
    /** Something went wrong in [launch]: tell the owner rather than closing the app. */
    private val onError: (Throwable) -> Unit = {},
) {
    fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.platform.log("Action failed: ${e.stackTraceToString()}")
                onError(e)
            }
        }
    }
}

/**
 * Root composable for both platforms.
 * @param registerBack lets the host (Android) route the system back button; returns an unregister call.
 */
@Composable
fun App(repo: PawRepository, registerBack: ((() -> Boolean) -> (() -> Unit))? = null) {
    PawTheme {
        val state by repo.state.collectAsState()
        val scope = rememberCoroutineScope()
        var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.Home)) }
        // Home is a pet's room: this pet's (a widget tap, the pet switcher, a pet just made).
        var shownPetId by rememberSaveable { mutableStateOf<String?>(null) }
        var now by remember { mutableLongStateOf(repo.now()) }
        var failed by remember { mutableStateOf(false) }
        val startNotice by repo.startNotice.collectAsState()

        // Anything just logged (Done, Undo) happened "now": refresh the clock with every change,
        // or a completion would look like it's in the future until the next tick.
        LaunchedEffect(state) { now = repo.now() }
        // Keep moods and "overdue since" labels current while the app is open.
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                now = repo.now()
            }
        }
        // Household: pick up the others' Done taps every minute while open, and send this phone's
        // changes a moment after they happen. (Does nothing outside a household.)
        LaunchedEffect(Unit) {
            while (true) {
                repo.family.requestSync()
                delay(60_000)
            }
        }
        LaunchedEffect(state) { repo.family.onLocalChange(state) }
        // First open: straight to "make your pixel pet" (not again when the screen is re-created).
        LaunchedEffect(Unit) {
            if (repo.state.value.pets.isEmpty() && stack == listOf(Screen.Home)) stack = listOf(Screen.Home, Screen.CreatePet)
        }
        // A widget tap: that pet's page ("pawpixel://pet/<id>"), or making one if there's none yet.
        val link by repo.link.collectAsState()
        LaunchedEffect(link) {
            val url = link ?: return@LaunchedEffect
            repo.consumeLink()
            val pet = repo.state.value.pet(url.substringAfter("pawpixel://pet/", ""))
            if (pet != null) { shownPetId = pet.id; stack = listOf(Screen.Home) }
            else if (repo.state.value.pets.isEmpty()) stack = listOf(Screen.Home, Screen.CreatePet)
        }

        val back: () -> Boolean = {
            repo.platform.log("back: stack=" + stack.joinToString { it.code() })
            if (stack.size > 1) { stack = stack.dropLast(1); true } else false
        }
        DisposableEffect(registerBack) {
            val unregister = registerBack?.invoke(back)
            onDispose { unregister?.invoke() }
        }

        val current = stack.last()
        val app = AppScope(
            repo = repo, scope = scope, now = now,
            // A double tap opens a screen once. (A pet's page is the home screen showing that pet: see showPet.)
            navigate = { if (stack.last() != it) stack = stack + it },
            // Only from the screen on top: a double tap on Back or Save doesn't also close the screen below.
            back = { if (stack.last() == current) back() },
            shownPetId = shownPetId,
            // A pet's room is the home screen showing that pet: whatever was open, back to Home.
            showPet = { shownPetId = it; stack = listOf(Screen.Home) },
            onError = { failed = true },
        )

        // A new language redraws everything (remembered texts included).
        key(com.pawpixel.i18n.I18n.lang, state.settings.language) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Screens slide in from the right and settle; going back slides them out again, the way
            // the phone's own apps move. Each screen on the stack gets its own state (two pets'
            // pages never share scroll or dialogs).
            AnimatedContent(
                targetState = stack,
                contentKey = { it.size to it.last() },
                transitionSpec = {
                    val forward = targetState.size >= initialState.size
                    // A panel over the room rises from the bottom and drops back down; the room
                    // stays put under it.
                    val panel = (if (forward) targetState else initialState).last() is Screen.PetSection
                    if (panel && forward) {
                        (slideInVertically(defaultSpatial()) { it } + fadeIn(tween(160))) togetherWith fadeOut(tween(360))
                    } else if (panel) {
                        fadeIn(tween(200)) togetherWith (slideOutVertically(tween(260)) { it } + fadeOut(tween(260)))
                    } else if (forward) {
                        (slideInHorizontally(defaultSpatial()) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(200)))
                    } else {
                        (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(defaultSpatial()) { it / 3 } + fadeOut(tween(200)))
                    }
                },
                label = "screens",
            ) { shown ->
            val screen = shown.last()
            key(shown.size, screen) {
            when (screen) {
                Screen.Home -> HomeScreen(app, state)
                Screen.CreatePet -> SpriteMakerScreen(app, state, existingPetId = null)
                is Screen.RemakeSprite -> SpriteMakerScreen(app, state, existingPetId = screen.petId)
                is Screen.Studio -> {
                    val pet = state.pet(screen.petId)
                    if (pet == null) LaunchedEffect(screen) { back() } else StudioScreen(app, state, pet)
                }
                is Screen.PetSection -> {
                    val pet = state.pet(screen.petId)
                    if (pet == null) LaunchedEffect(screen) { back() } else PetSectionScreen(app, state, pet, screen.section)
                }
                is Screen.EditTask -> {
                    val pet = state.pet(screen.petId)
                    if (pet == null) LaunchedEffect(screen) { back() } else TaskEditorScreen(app, state, pet, screen.taskId, screen.health)
                }
                Screen.Settings -> SettingsScreen(app, state)
                Screen.PetMap -> PetMapScreen(app, state)
                Screen.Pals -> PalsScreen(app, state)
                is Screen.Walk -> {
                    val pet = state.pet(screen.petId)
                    if (pet == null) LaunchedEffect(screen) { back() } else WalkScreen(app, state, pet)
                }
                is Screen.Family -> FamilyScreen(app, state, screen.sharePetId, screen.join)
            }
            }
            }
        }
        }
        startNotice?.let { text ->
            AlertDialog(
                onDismissRequest = repo::dismissStartNotice,
                title = { Text(tr("Your pets are safe")) },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = repo::dismissStartNotice) { Text(tr("OK")) } },
            )
        }
        if (failed) {
            AlertDialog(
                onDismissRequest = { failed = false },
                text = { Text(tr("Something went wrong. Please try again.")) },
                confirmButton = { TextButton(onClick = { failed = false }) { Text(tr("OK")) } },
            )
        }
    }
}
