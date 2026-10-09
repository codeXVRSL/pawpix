package com.pawpixel.app

import android.app.Application
import android.content.Context

class PawPixelApplication : Application() {
    val platform by lazy { AndroidPlatform(this) }
    val repo by lazy { PawRepository(platform) }
    /** Work that must finish even if the screen that started it closes (widget settings, for one). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Read the saved pets on a background thread while Android sets up the first screen, so
        // opening the app doesn't wait for it on the main thread. (Whoever needs the repository
        // first just waits for this read to finish: `lazy` never reads twice.)
        Thread({ runCatching { repo } }, "pawpixel-load").apply { priority = Thread.NORM_PRIORITY }.start()
    }

    companion object {
        /** Repository for receivers and widgets, which get a Context but not the Application type. */
        fun repo(context: Context): PawRepository = (context.applicationContext as PawPixelApplication).repo

        fun scope(context: Context): kotlinx.coroutines.CoroutineScope = (context.applicationContext as PawPixelApplication).appScope
    }
}
