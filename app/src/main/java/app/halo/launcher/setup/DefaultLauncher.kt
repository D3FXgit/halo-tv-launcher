package app.halo.launcher.setup

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import app.halo.launcher.remap.ButtonRemapService

/**
 * Everything around "make Halo the home screen":
 *  - the official way (RoleManager.ROLE_HOME, Android 10+), which Google TV usually does not offer,
 *  - the Halo accessibility service ([ButtonRemapService]), which brings Halo back whenever the
 *    stock launcher opens and remaps the remote's YouTube button,
 *  - the adb commands for people who prefer to disable the stock launcher.
 */
object DefaultLauncher {

    /** Stock TV launchers that Home override replaces with Halo. Keep in sync with res/xml/remap_service.xml. */
    val STOCK_LAUNCHERS: Set<String> = setOf(
        "com.google.android.apps.tv.launcherx",   // Google TV
        "com.google.android.tvlauncher",          // Android TV (2018+)
        "com.google.android.leanbacklauncher",    // Android TV (old)
        "com.android.tv.launcher",
        "com.amazon.tv.launcher",                 // Fire TV
    )

    /** SharedPreferences mirrored from the settings for the accessibility service (it can't read DataStore synchronously). */
    const val SERVICE_PREFS = "halo_service"
    const val KEY_HOME_OVERRIDE = "home_override"
    const val KEY_YOUTUBE_TARGET = "youtube_target"
    /** Which remote button opens the Halo assistant: "off" or "star". */
    const val KEY_ASSISTANT_BUTTON = "assistant_button"

    /** True when the system lets apps ask for the home role (usually false on Google TV). */
    fun isHomeRoleAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_HOME) == true
        }.getOrDefault(false)

    fun isHomeRoleHeld(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        }.getOrDefault(false)

    /** The system dialog that makes Halo the home app, or null where it does not exist. */
    fun homeRoleRequest(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isHomeRoleAvailable(context)) return null
        return runCatching { context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_HOME) }
            .getOrNull()
    }

    /** True when pressing Home opens Halo directly (Halo is the default home activity). */
    fun isDefaultHome(context: Context): Boolean {
        if (isHomeRoleHeld(context)) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = runCatching { context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull()
        return ri?.activityInfo?.packageName == context.packageName
    }

    /** Our accessibility service, e.g. "app.halo.tv/app.halo.launcher.remap.ButtonRemapService". */
    fun serviceComponent(context: Context): ComponentName = ComponentName(context, ButtonRemapService::class.java)

    /** Is the Halo accessibility service switched on? */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val ours = serviceComponent(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == ours }
    }

    /** Opens the system accessibility settings (falls back to the main settings). */
    fun openAccessibilitySettings(context: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val ok = runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(flags)) }.isSuccess
        if (!ok) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(flags)) }
    }

    /**
     * adb commands that switch the service on without the settings screens. Halo is added to the
     * list of enabled services, so other services (e.g. TalkBack) stay on.
     */
    fun adbServiceCommands(context: Context): List<String> {
        val c = serviceComponent(context).flattenToString()
        return listOf(
            "adb shell 'cur=\$(settings get secure enabled_accessibility_services); " +
                "case \"\$cur\" in null|\"\") new=$c;; *$c*) new=\$cur;; *) new=\$cur:$c;; esac; " +
                "settings put secure enabled_accessibility_services \$new'",
            "adb shell settings put secure accessibility_enabled 1",
        )
    }

    /** adb commands that disable Google TV's own launcher (undo: "pm enable" with the same package). */
    val adbDisableStockCommands: List<String> = listOf(
        "adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx",
        "adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith",
    )

    val adbUndoCommands: List<String> = listOf(
        "adb shell pm enable com.google.android.apps.tv.launcherx",
        "adb shell pm enable com.google.android.tungsten.setupwraith",
    )

    /** Human-readable name of an installed package, or null when it isn't installed. */
    fun appLabel(context: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    }
}
