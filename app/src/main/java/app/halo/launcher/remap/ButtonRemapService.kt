package app.halo.launcher.remap

import app.halo.launcher.assistant.AssistantOverlayActivity
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import app.halo.launcher.BuildConfig
import app.halo.launcher.MainActivity
import app.halo.launcher.setup.DefaultLauncher

/**
 * The Halo accessibility service. Switched on once by the user (setup wizard → "Make Halo your home
 * screen" / "Remote button", or Settings → Accessibility → Halo). It does two things, each only when
 * the user turned it on in Halo:
 *
 *  1. Home override: when a stock launcher window opens (Home was pressed and the system still
 *     prefers Google TV's launcher), Halo is brought back to the front.
 *  2. Remote button: the remote's YouTube button opens the app chosen in Halo instead.
 *  3. Assistant button: the remote's star button opens the Halo assistant over any app
 *     (AssistantOverlayActivity).
 *
 * Both options are mirrored by HaloViewModel into SharedPreferences [DefaultLauncher.SERVICE_PREFS],
 * because a service can't read DataStore synchronously.
 *
 * Nothing the user types is logged. Debug builds log only the YouTube button (tag "HaloKeys").
 *
 * Safety: if Halo is brought back three times within ten seconds (for example because it keeps
 * closing), the home override pauses for five minutes so the stock home screen stays reachable.
 */
class ButtonRemapService : AccessibilityService() {

    companion object {
        private const val TAG = "HaloKeys"
        /** Key codes the YouTube button is known to send on Google remotes. */
        val YOUTUBE_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_3,     // Chromecast / Google TV Streamer remote YouTube key on some firmwares
            KeyEvent.KEYCODE_F7,
        )
        /** Don't relaunch Halo more often than this (the launcher sends several window events). */
        private const val HOME_DEBOUNCE_MS = 1500L
        /** Loop guard: this many relaunches within [LOOP_WINDOW_MS] pause the override for [LOOP_PAUSE_MS]. */
        private const val LOOP_LIMIT = 3
        private const val LOOP_WINDOW_MS = 10_000L
        private const val LOOP_PAUSE_MS = 5 * 60_000L
        /**
         * The remote's star (shortcut) button on Google TV remotes. The microphone button and holding
         * Home never reach accessibility services there (the system keeps them for Google Assistant).
         */
        const val STAR_KEY = KeyEvent.KEYCODE_MACRO_1
    }

    private fun showAssistant() {
        runCatching {
            startActivity(
                Intent(this, AssistantOverlayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure { Log.w(TAG, "can't open the assistant", it) }
    }

    /** Swallow the matching key-up too, so the system never sees half a press. */
    private var swallowUp = -1
    private var lastHomeLaunch = 0L
    private val recentLaunches = ArrayDeque<Long>()
    private var pausedUntil = 0L

    private val prefs: SharedPreferences by lazy { getSharedPreferences(DefaultLauncher.SERVICE_PREFS, MODE_PRIVATE) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Debug builds only: which buttons reach the service (tvLogcat). Release builds log nothing.
        if (BuildConfig.DEV_HOOKS && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)}")
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowUp) {
            swallowUp = -1
            return true
        }
        if (event.keyCode == STAR_KEY && prefs.getString(DefaultLauncher.KEY_ASSISTANT_BUTTON, "off") == "star") {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) showAssistant()
                swallowUp = event.keyCode
            }
            return true
        }
        if (event.keyCode !in YOUTUBE_KEYS || event.action != KeyEvent.ACTION_DOWN) return false
        // No app chosen in Halo → leave the button alone.
        val target = prefs.getString(DefaultLauncher.KEY_YOUTUBE_TARGET, null)?.takeIf { it.isNotBlank() } ?: return false
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(target) ?: pm.getLaunchIntentForPackage(target) ?: return false
        if (event.repeatCount == 0) {
            runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
                .onFailure { Log.w(TAG, "can't open $target", it) }
        }
        swallowUp = event.keyCode
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in DefaultLauncher.STOCK_LAUNCHERS) return
        if (!prefs.getBoolean(DefaultLauncher.KEY_HOME_OVERRIDE, false)) return
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return
        if (now - lastHomeLaunch < HOME_DEBOUNCE_MS) return
        lastHomeLaunch = now
        while (recentLaunches.isNotEmpty() && now - recentLaunches.first() > LOOP_WINDOW_MS) recentLaunches.removeFirst()
        if (recentLaunches.size >= LOOP_LIMIT) {
            recentLaunches.clear()
            pausedUntil = now + LOOP_PAUSE_MS
            Log.w(TAG, "home override paused for 5 min (Halo was brought back too often)")
            return
        }
        recentLaunches.addLast(now)
        val home = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        runCatching { startActivity(home) }.onFailure { Log.w(TAG, "can't bring Halo back", it) }
    }

    override fun onInterrupt() = Unit
}
