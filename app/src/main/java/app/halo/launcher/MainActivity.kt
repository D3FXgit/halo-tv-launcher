package app.halo.launcher

import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.halo.launcher.data.AppEntry
import app.halo.launcher.ui.FocusDirection
import app.halo.launcher.ui.HaloActions
import app.halo.launcher.ui.HaloRoot
import app.halo.launcher.ui.Ui

class MainActivity : ComponentActivity(), HaloActions {
    companion object {
        /** Set by the assistant overlay (showHalo): don't treat the new intent as a Home press. */
        const val EXTRA_KEEP_SCREEN = "halo.keep_screen"
    }

    /** Shared with the assistant overlay (see [HaloApp]). */
    private val vm: HaloViewModel get() = (application as HaloApp).vm
    private var swallowUntilUp = false

    /** Weather follows the TV's location; ask once, fall back to the connection's location if declined. */
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.weather.refresh()
    }

    /** Interface language (Android 9–12; on 13+ the system applies the per-app language). */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(app.halo.launcher.i18n.Lang.wrap(newBase))
    }

    /** Locales the UI was built with; a different language means "recreate" (see onConfigurationChanged). */
    private var builtLocales: android.os.LocaleList? = null

    /**
     * The manifest handles locale changes itself (so nothing else recreates the launcher), but a new
     * interface language (Halo's per-app language on Android 13+, or the TV's language) must reach
     * every screen at once: recreate. The ViewModel, the wizard step and saved UI state survive.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Android 9–12 with a language chosen in Halo: it is applied in attachBaseContext and Lang.set
        // recreates the activity itself; the system configuration here never carries it.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU &&
            app.halo.launcher.i18n.Lang.current(this) != "system"
        ) return
        val built = builtLocales
        if (built != null && newConfig.locales != built) {
            builtLocales = newConfig.locales
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        builtLocales = resources.configuration.locales
        // The setup wizard asks for location on its Weather step. Afterwards, ask once per start
        // only if the user chose "This TV's location" but the permission is still missing.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                vm.settingsReady.first { it }
                val s = vm.settings.value
                if (s.setupDone && s.useDeviceLocation &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
                }
            }
        }
        val cfg = resources.configuration
        Ui.k = minOf(cfg.screenWidthDp / 1920f, cfg.screenHeightDp / 1080f).takeIf { it > 0f } ?: 0.5f
        Ui.fontScale = cfg.fontScale

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { HaloRoot(vm, this) }
        // Compose paints its own opaque background; skip the window fill (one full-screen draw less per frame).
        window.setBackgroundDrawable(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (openFromIntent(intent)) return
        // Brought to the front by the assistant overlay for a Halo screen: keep that screen.
        if (intent.getBooleanExtra(EXTRA_KEEP_SCREEN, false)) return
        // Home pressed while Halo is already in front.
        vm.onHomePressed()
    }

    /**
     * Developer hook for the screenshot tours (tv.gradle.kts → tvTour / tvWizardTour). Debug builds only
     * (BuildConfig.DEV_HOOKS; release builds ignore it):
     * adb shell am start -n app.halo.tv/app.halo.launcher.MainActivity --es halo.open <screen>
     * (<screen> may also be "setup" or "setup:<Step>", e.g. "setup:Assistant").
     */
    private fun openFromIntent(intent: Intent?): Boolean {
        if (!BuildConfig.DEV_HOOKS) return false
        val what = intent?.getStringExtra("halo.open") ?: return false
        vm.panel.value = Panel.None
        if (what == "setup" || what.startsWith("setup:")) {
            val step = app.halo.launcher.ui.setup.SetupStep.entries.firstOrNull { it.name.equals(what.substringAfter(':', ""), true) }
            vm.openSetup(step ?: app.halo.launcher.ui.setup.SetupStep.Language, single = step != null)
            return true
        }
        when (what) {
            "appearance" -> { vm.open(Screen.Home); vm.panel.value = Panel.Appearance }
            "quick" -> { vm.open(Screen.Home); vm.panel.value = Panel.Quick }
            "arrange" -> vm.open(Screen.Arrange(null))
            "customize" -> {
                val key = vm.home.value.favourites.firstOrNull()?.app?.key ?: vm.home.value.apps.firstOrNull()?.app?.key
                if (key != null) vm.open(Screen.Customize(key, Screen.Arrange(null)))
            }
            "profiles" -> vm.open(Screen.Profiles)
            "player" -> vm.open(Screen.Player)
            "weather" -> vm.open(Screen.Weather)
            "backgrounds" -> vm.open(Screen.Backgrounds)
            "search" -> vm.open(Screen.Search)
            else -> vm.open(Screen.Home)
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        vm.foreground = true
        vm.onUserInteraction()
        vm.ambient.value = false
        vm.nowPlaying.start()
        vm.appsRepo.refresh()
    }

    override fun onStop() {
        super.onStop()
        vm.foreground = false
        vm.nowPlaying.stop()
        vm.ambient.value = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) FocusDirection.record(event.keyCode)
        // Any key wakes Halo from ambient mode without doing anything else.
        if (event.action == KeyEvent.ACTION_DOWN && vm.wakeFromAmbient()) {
            swallowUntilUp = true
            return true
        }
        if (swallowUntilUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowUntilUp = false
            return true
        }
        vm.onUserInteraction()
        if (event.action == KeyEvent.ACTION_DOWN && vm.settings.value.sounds) playNavigationSound(event)
        return super.dispatchKeyEvent(event)
    }

    private val audio by lazy { getSystemService(android.media.AudioManager::class.java) }

    /**
     * The TV's own interface sounds (the same ones Google TV plays): a soft tick when moving focus,
     * a click on OK. They follow the system "sound effects" setting and volume.
     */
    private fun playNavigationSound(event: KeyEvent) {
        val fx = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> android.media.AudioManager.FX_FOCUS_NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> android.media.AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> android.media.AudioManager.FX_FOCUS_NAVIGATION_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> android.media.AudioManager.FX_FOCUS_NAVIGATION_DOWN
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (event.repeatCount == 0) android.media.AudioManager.FX_KEY_CLICK else return
            else -> return
        }
        runCatching { audio?.playSoundEffect(fx) }
    }

    // ---- HaloActions -----------------------------------------------------------

    override fun launch(app: AppEntry, bounds: Rect) {
        val opts = runCatching {
            ActivityOptions.makeScaleUpAnimation(
                window.decorView,
                bounds.left.toInt(), bounds.top.toInt(),
                bounds.width.toInt().coerceAtLeast(1), bounds.height.toInt().coerceAtLeast(1),
            ).toBundle()
        }.getOrNull()
        try {
            startActivity(app.launchIntent(), opts)
        } catch (_: Exception) {
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback != null) runCatching { startActivity(fallback, opts) }
            else Toast.makeText(this, getString(R.string.toast_cant_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    override fun openSettings(action: String) {
        val ok = runCatching { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun uninstall(packageName: String) {
        val ok = runCatching {
            startActivity(
                Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) {
            // Fallback: the app's page in system settings (Uninstall / Disable live there).
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    override fun setLanguage(code: String) {
        vm.updateSettings { it.copy(language = code) }
        app.halo.launcher.i18n.Lang.set(this, code)
    }

    override fun openPackage(packageName: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        if (i != null) runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
