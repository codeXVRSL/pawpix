package com.pawpixel.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
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
 * A widget's settings: which pet it shows, the sky behind the pet (the hour's, always day, always
 * night, or plain paper) and whether the name is written. Opened from the widget's settings
 * (Android 12+ "reconfigure"), or when the widget is added on older Android. Placing a widget never
 * needs it: without a choice the widget shows the first pet under the sky of the hour.
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
        val manager = GlanceAppWidgetManager(this)
        val glanceId = runCatching { manager.getGlanceIdBy(widgetId) }.getOrNull() ?: run { finish(); return }

        fun save(choice: String?, theme: PetWidget.Theme, showName: Boolean) {
            lifecycleScope.launch {
                updateAppWidgetState(this@PetPickerActivity, glanceId) {
                    if (choice != null) it[PetWidget.PET] = choice
                    it[PetWidget.THEME] = theme.key
                    it[PetWidget.SHOW_NAME] = showName
                }
                PetWidget().update(this@PetPickerActivity, glanceId)
                finish()
            }
        }

        lifecycleScope.launch {
            val prefs = runCatching { getAppWidgetState(this@PetPickerActivity, PreferencesGlanceStateDefinition, glanceId) }.getOrNull()
            setContent { PawTheme { Settings(pets, prefs, ::save) } }
        }
    }

    @Composable
    private fun Settings(pets: List<Pet>, prefs: Preferences?, save: (String?, PetWidget.Theme, Boolean) -> Unit) {
        val repo = PawPixelApplication.repo(this)
        var choice by remember { mutableStateOf(prefs?.get(PetWidget.PET) ?: pets.firstOrNull()?.id) }
        var theme by remember { mutableStateOf(PetWidget.Theme.of(prefs?.get(PetWidget.THEME))) }
        var showName by remember { mutableStateOf(prefs?.get(PetWidget.SHOW_NAME) ?: true) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tr("Widget settings"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (pets.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Which pet should this widget show?"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val options = pets.map { it.id to it.name } + (WidgetSnapshot.MOST_IN_NEED to tr("Whoever needs you most"))
                    for ((id, label) in options) {
                        val pet = pets.firstOrNull { it.id == id }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(role = Role.RadioButton, onClickLabel = label) { choice = id },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = id == choice, onClick = null)
                            if (pet != null) SpriteView(repo.pose(pet, Mood.HAPPY), Modifier.size(48.dp), animate = false)
                            Column {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                if (pet == null) Text(tr("Shows the pet whose care is due"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(tr("Sky"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(tr("The sky behind your pet."), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = listOf(
                        PetWidget.Theme.AUTO to tr("Follows the time of day"), PetWidget.Theme.DAY to tr("Always day"),
                        PetWidget.Theme.NIGHT to tr("Always night"), PetWidget.Theme.PAPER to tr("Plain"),
                    )
                    for ((t, label) in labels) FilterChip(selected = theme == t, onClick = { theme = t }, label = { Text(label) })
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Switch) { showName = !showName },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Show the name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = showName, onCheckedChange = { showName = it })
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { save(choice, theme, showName) }, Modifier.fillMaxWidth().height(52.dp)) { Text(tr("Done")) }
            }
        }
    }
}
