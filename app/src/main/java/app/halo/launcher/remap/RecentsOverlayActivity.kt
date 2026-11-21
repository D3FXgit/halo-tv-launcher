package app.halo.launcher.remap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloApp
import app.halo.launcher.HaloViewModel
import app.halo.launcher.i18n.Lang
import app.halo.launcher.ui.HaloThemeProvider
import app.halo.launcher.ui.TaskManagerPanel
import app.halo.launcher.ui.Ui

/**
 * "Recent apps" over whatever app is open (holding the remote's star button), like the Halo
 * assistant: a translucent window on top; it disappears when another app or Home comes to the front.
 */
class RecentsOverlayActivity : ComponentActivity() {
    private val vm: HaloViewModel get() = (application as HaloApp).vm

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = resources.configuration
        Ui.k = minOf(cfg.screenWidthDp / 1920f, cfg.screenHeightDp / 1080f).takeIf { it > 0f } ?: 0.5f
        Ui.fontScale = cfg.fontScale
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val back = Intent(this, RecentsOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            HaloThemeProvider(dark = true, motion = settings.motion) {
                TaskManagerPanel(vm, onClose = { finish() }, returnTo = back)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Another app or Home came to the front: done. (Not while Halo is closing apps behind its cover.)
        val busy = ForceStopper.status.value.values.any { it == ForceStopper.Status.Queued || it == ForceStopper.Status.Stopping }
        if (!busy && !isChangingConfigurations && !isFinishing) finish()
    }
}
