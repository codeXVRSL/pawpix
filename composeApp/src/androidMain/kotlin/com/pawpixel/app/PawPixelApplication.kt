package com.pawpixel.app

import android.app.Application
import android.content.Context

class PawPixelApplication : Application() {
    val platform by lazy { AndroidPlatform(this) }
    val repo by lazy { PawRepository(platform) }

    companion object {
        /** Repository for receivers and widgets, which get a Context but not the Application type. */
        fun repo(context: Context): PawRepository = (context.applicationContext as PawPixelApplication).repo
    }
}
