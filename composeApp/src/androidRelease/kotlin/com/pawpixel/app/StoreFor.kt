package com.pawpixel.app

import android.content.Context

/** Release builds: always the real Google Play store. (The pretend store only exists in debug builds.) */
internal fun storeFor(@Suppress("UNUSED_PARAMETER") context: Context, play: Store): Store = play
