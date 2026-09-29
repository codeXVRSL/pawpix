package com.pawpixel.app

import android.Manifest
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
    }

    override fun onResume() {
        super.onResume()
        // Time has passed: refresh reminders and widgets.
        lifecycleScope.launch { app.repo.ingestWidgetTaps(); app.repo.publish(); app.repo.family.sync() }
    }

    override fun onDestroy() {
        app.platform.permissionRequester = null
        app.platform.locationPermission = null
        app.platform.activity = null
        super.onDestroy()
    }
}
