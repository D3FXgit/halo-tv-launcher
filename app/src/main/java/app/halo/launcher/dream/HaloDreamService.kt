package app.halo.launcher.dream

import android.service.dreams.DreamService
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.Prefs
import app.halo.launcher.ui.AmbientScreen
import app.halo.launcher.ui.HaloThemeProvider
import app.halo.launcher.ui.Ui
import app.halo.launcher.ui.isNight

/**
 * Halo's ambient screen as a system screensaver. Google TV hides third-party
 * screensavers in Settings; select it with:
 * adb shell settings put secure screensaver_components app.halo.tv/app.halo.launcher.dream.HaloDreamService
 */
class HaloDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val cfg = resources.configuration
        Ui.k = minOf(cfg.screenWidthDp / 1920f, cfg.screenHeightDp / 1080f).takeIf { it > 0f } ?: 0.5f
        Ui.fontScale = cfg.fontScale

        val prefs = Prefs(this)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@HaloDreamService)
            setViewTreeSavedStateRegistryOwner(this@HaloDreamService)
            setContent {
                val settings by prefs.settings.collectAsStateWithLifecycle(HaloSettings())
                HaloThemeProvider(dark = isNight(settings.themeMode, null), motion = settings.motion) {
                    AmbientScreen(settings.clock24h, weather = null, nowPlaying = null)
                }
            }
        }
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDetachedFromWindow()
    }
}
