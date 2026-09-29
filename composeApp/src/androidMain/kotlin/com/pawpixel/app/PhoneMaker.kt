package com.pawpixel.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.pawpixel.i18n.tr

/**
 * Where each phone maker keeps "let this app run in the background". Most phones in the pilot
 * (Oppo, Realme, Vivo, Xiaomi) stop apps in the background unless the owner allows them, and
 * reminders stop with them. The screens are the makers' own and move between versions, so each is
 * tried in turn, then the app's own details page, which every phone has.
 */
enum class PhoneMaker(val label: String?, private val screens: List<ComponentName>) {
    XIAOMI("Xiaomi", listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )),
    OPPO("Oppo", COLOROS),
    REALME("Realme", COLOROS),
    ONEPLUS("OnePlus", COLOROS),
    VIVO("Vivo", listOf(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
    )),
    HUAWEI("Huawei", listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    )),
    SAMSUNG("Samsung", emptyList()),
    OTHER(null, emptyList());

    /** Short directions, in the owner's language (the phones' own menus are named as they appear in English). */
    fun steps(): String = when (this) {
        XIAOMI -> tr("Turn on Autostart for PawPixel, then in Settings → Apps → PawPixel → Battery saver, choose “No restrictions”.")
        OPPO, REALME, ONEPLUS -> tr("In Settings → Apps → PawPixel → Battery usage, turn on “Allow background activity” and “Allow auto launch”.")
        VIVO -> tr("In Settings → Battery → Background power consumption, allow PawPixel. Turn on Autostart for it too.")
        HUAWEI -> tr("In Settings → Battery → App launch, set PawPixel to “Manage manually” and allow all three.")
        SAMSUNG -> tr("In Settings → Apps → PawPixel → Battery, choose “Unrestricted”.")
        OTHER -> tr("In Settings → Apps → PawPixel → Battery, allow it to run in the background.")
    }

    fun openSettings(context: Context) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        for (intent in screens.map { Intent().setComponent(it) } + details) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // not on this phone or version: try the next one
            } catch (e: SecurityException) {
                // the maker's screen isn't open to other apps
            }
        }
    }

    companion object {
        fun of(manufacturer: String): PhoneMaker = when (manufacturer.lowercase()) {
            "xiaomi", "redmi", "poco" -> XIAOMI
            "oppo" -> OPPO
            "realme" -> REALME
            "oneplus" -> ONEPLUS
            "vivo", "iqoo" -> VIVO
            "huawei", "honor" -> HUAWEI
            "samsung" -> SAMSUNG
            else -> OTHER
        }
    }
}

/** ColorOS (Oppo, and Realme and OnePlus, which share it): the auto-launch list, by version. */
private val COLOROS = listOf(
    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
    ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
)
