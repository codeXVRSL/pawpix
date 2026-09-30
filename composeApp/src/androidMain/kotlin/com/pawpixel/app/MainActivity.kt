package com.pawpixel.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pawpixel.app.ui.App
import com.pawpixel.app.ui.SystemBack
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as PawPixelApplication

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { app.repo.publish() }
    }

    private var locationAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationAnswer?.complete(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.platform.permissionRequester = {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.platform.activity = java.lang.ref.WeakReference(this)
        app.platform.locationPermission = {
            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
            locationAnswer = answer
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            answer.await()
        }
        setContent {
            // Enabled only while PawPixel has a screen to go back to: on the home screen the system
            // handles back itself, with the predictive back-to-home animation (targetSdk 36).
            App(app.repo, registerBack = { onBack ->
                val callback = object : OnBackPressedCallback(false) {
                    override fun handleOnBackPressed() = onBack()
                }
                onBackPressedDispatcher.addCallback(this, callback)
                SystemBack(setEnabled = { callback.isEnabled = it }, unregister = callback::remove)
            })
        }
        if (savedInstanceState == null) openLink(intent)
    }

    /** A widget tap opens its pet's page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        val url = intent?.data?.toString() ?: return
        if (url.startsWith("pawpixel://")) app.repo.openLink(url)
    }

    override fun onResume() {
        super.onResume()
        // Time has passed: refresh reminders and widgets.
        lifecycleScope.launch { app.repo.ingestWidgetTaps(); app.repo.publish(); app.repo.family.requestSync() }
    }

    override fun onDestroy() {
        app.platform.permissionRequester = null
        app.platform.locationPermission = null
        app.platform.activity = null
        super.onDestroy()
    }
}
