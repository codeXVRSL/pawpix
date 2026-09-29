package com.pawpixel.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    data class PetDetail(val petId: String) : Screen
    data class RemakeSprite(val petId: String) : Screen
    /** [health] picks which kinds a new task offers: daily care, or health care (vaccines, deworming...). */
    data class EditTask(val petId: String, val taskId: String?, val health: Boolean = false) : Screen
    data object Settings : Screen
    data object PetMap : Screen
    /** Sharing with your household. [sharePetId]: share this pet once in a household. [join]: you came to enter a code. */
    data class Family(val sharePetId: String? = null, val join: Boolean = false) : Screen
}

/** Shared state handed to every screen. */
class AppScope(
    val repo: PawRepository,
    val scope: CoroutineScope,
    val now: Long,
    val navigate: (Screen) -> Unit,
    val back: () -> Unit,
) {
    fun launch(block: suspend () -> Unit) { scope.launch { block() } }
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
        var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
        var now by remember { mutableLongStateOf(repo.now()) }

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
        // First open: straight to "make your pixel pet".
        LaunchedEffect(Unit) {
            if (repo.state.value.pets.isEmpty()) stack = listOf(Screen.Home, Screen.CreatePet)
        }
        // A widget tap: that pet's page ("pawpixel://pet/<id>"), or making one if there's none yet.
        val link by repo.link.collectAsState()
        LaunchedEffect(link) {
            val url = link ?: return@LaunchedEffect
            repo.consumeLink()
            val pet = repo.state.value.pet(url.substringAfter("pawpixel://pet/", ""))
            if (pet != null) stack = listOf(Screen.Home, Screen.PetDetail(pet.id))
            else if (repo.state.value.pets.isEmpty()) stack = listOf(Screen.Home, Screen.CreatePet)
        }

        val back: () -> Boolean = {
            if (stack.size > 1) { stack = stack.dropLast(1); true } else false
        }
        DisposableEffect(registerBack) {
            val unregister = registerBack?.invoke(back)
            onDispose { unregister?.invoke() }
        }

        val app = AppScope(
            repo = repo, scope = scope, now = now,
            navigate = { stack = stack + it },
            back = { back() },
        )

        // A new language redraws everything (remembered texts included).
        key(com.pawpixel.i18n.I18n.lang, state.settings.language) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val screen = stack.last()) {
                Screen.Home -> HomeScreen(app, state)
                Screen.CreatePet -> SpriteMakerScreen(app, state, existingPetId = null)
                is Screen.RemakeSprite -> SpriteMakerScreen(app, state, existingPetId = screen.petId)
                is Screen.PetDetail -> {
                    val pet = state.pet(screen.petId)
                    if (pet == null) LaunchedEffect(screen) { back() } else PetScreen(app, state, pet)
                }
                is Screen.EditTask -> {
                    val pet = state.pet(screen.petId)
                    if (pet == null) LaunchedEffect(screen) { back() } else TaskEditorScreen(app, state, pet, screen.taskId, screen.health)
                }
                Screen.Settings -> SettingsScreen(app, state)
                Screen.PetMap -> PetMapScreen(app, state)
                is Screen.Family -> FamilyScreen(app, state, screen.sharePetId, screen.join)
            }
        }
        }
    }
}
