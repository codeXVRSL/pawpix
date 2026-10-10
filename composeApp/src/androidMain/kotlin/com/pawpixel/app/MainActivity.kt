package com.pawpixel.app

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.pawpixel.app.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as PawPixelApplication

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { app.repo.publish() }
    }

    private var activityAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val activityPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        activityAnswer?.complete(granted)
    }

    private var locationAnswer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationAnswer?.complete(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Phones stay upright, as on iPhone: on its side a phone has no room for the pet above the
        // care buttons. Tablets and unfolded foldables turn freely (the room just gets wider).
        // (Set both ways: the request survives a recreation, so a foldable opened folded turns freely once unfolded.)
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp < 600) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        app.platform.permissionRequester = {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.platform.activity = java.lang.ref.WeakReference(this)
        app.platform.activityPermission = {
            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
            activityAnswer = answer
            if (Build.VERSION.SDK_INT >= 29) activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) else answer.complete(true)
            answer.await()
        }
        app.platform.locationPermission = {
            val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
            locationAnswer = answer
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            answer.await()
        }
        setContent {
            App(app.repo, registerBack = { handler ->
                val callback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (!handler()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    }
                }
                onBackPressedDispatcher.addCallback(this, callback)
                val unregister: () -> Unit = { callback.remove() }
                unregister
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
        app.platform.activityPermission = null
        app.platform.activity = null
        super.onDestroy()
    }
}
