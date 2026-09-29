package com.pawpixel.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.pawpixel.app.PawPixelApplication
import com.pawpixel.app.ui.PawTheme
import com.pawpixel.app.ui.SpriteView
import com.pawpixel.core.Mood
import com.pawpixel.core.Pet
import com.pawpixel.core.WidgetSnapshot
import com.pawpixel.i18n.tr
import kotlinx.coroutines.launch

/**
 * Which pet a widget shows. Opened from the widget's settings (Android 12+ "reconfigure"), or when
 * the widget is added on older Android. Placing a widget never needs it: without a choice the widget
 * shows the first pet, and with one pet (or none) there's nothing to pick, so it closes at once.
 */
class PetPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Backing out keeps the widget, showing what it showed before.
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val repo = PawPixelApplication.repo(this)
        val pets = repo.state.value.pets
        if (pets.size <= 1) { finish(); return }
        val manager = GlanceAppWidgetManager(this)
        val glanceId = runCatching { manager.getGlanceIdBy(widgetId) }.getOrNull() ?: run { finish(); return }

        fun choose(choice: String) {
            lifecycleScope.launch {
                updateAppWidgetState(this@PetPickerActivity, glanceId) { it[PetWidget.PET] = choice }
                PetWidget().update(this@PetPickerActivity, glanceId)
                finish()
            }
        }

        lifecycleScope.launch {
            val current = runCatching { currentChoice(glanceId) }.getOrNull() ?: pets.first().id
            setContent { PawTheme { Picker(pets, current, ::choose) } }
        }
    }

    @Composable
    private fun Picker(pets: List<Pet>, current: String, choose: (String) -> Unit) {
        val repo = PawPixelApplication.repo(this)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(tr("Which pet should this widget show?"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                val options = pets.map { it.id to it.name } + (WidgetSnapshot.MOST_IN_NEED to tr("Whoever needs you most"))
                for ((choice, label) in options) {
                    val pet = pets.firstOrNull { it.id == choice }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .clickable(role = Role.RadioButton, onClickLabel = label) { choose(choice) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = choice == current, onClick = null)
                        if (pet != null) SpriteView(repo.pose(pet, Mood.HAPPY), Modifier.size(56.dp), animate = false)
                        Column {
                            Text(label, style = MaterialTheme.typography.titleMedium)
                            if (pet == null) Text(tr("Shows the pet whose care is due"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    private suspend fun currentChoice(glanceId: androidx.glance.GlanceId): String? =
        androidx.glance.appwidget.state.getAppWidgetState(this, androidx.glance.state.PreferencesGlanceStateDefinition, glanceId)[PetWidget.PET]
}
