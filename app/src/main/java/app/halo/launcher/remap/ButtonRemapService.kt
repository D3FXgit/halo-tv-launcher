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

    private val starPresses = ArrayDeque<Long>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingShort: Runnable? = null
    private var pendingKey = -1

    private fun handleBound(event: KeyEvent, bound: List<app.halo.launcher.data.ButtonBinding>) {
        fun action(press: String) = bound.firstOrNull { it.press == press }?.action
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                starDownAt = event.eventTime
                starHeld = false
            } else if (!starHeld && event.eventTime - starDownAt >= Buttons.HOLD_MS) {
                action(Buttons.HOLD)?.let { a ->
                    starHeld = true
                    run(a)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (starHeld) return
                if (event.eventTime - starDownAt >= Buttons.HOLD_MS) {
                    action(Buttons.HOLD)?.let { run(it); return }
                }
                val short = action(Buttons.SHORT)
                // Easter egg: five quick presses of the assistant button roll the credits.
                if (short == "assistant") {
                    starPresses.addLast(event.eventTime)
                    while (starPresses.size > 5) starPresses.removeFirst()
                    if (starPresses.size == 5 && starPresses.last() - starPresses.first() < 3_000) {
                        starPresses.clear()
                        pendingShort?.let { handler.removeCallbacks(it) }
                        pendingShort = null
                        showCredits()
                        return
                    }
                }
                val double = action(Buttons.DOUBLE)
                if (double == null) {
                    short?.let { run(it) }
                    return
                }
                val waiting = pendingShort
                if (waiting != null && pendingKey == event.keyCode) {
                    handler.removeCallbacks(waiting)
                    pendingShort = null
                    run(double)
                } else {
                    val r = Runnable {
                        pendingShort = null
                        short?.let { run(it) }
                    }
                    pendingShort = r
                    pendingKey = event.keyCode
                    handler.postDelayed(r, Buttons.DOUBLE_MS)
                }
            }
        }
    }

    /** Does what a button was set up for. */
    private fun run(action: String) {
        when {
            action == "assistant" -> showAssistant()
            action == "recents" -> showRecents()
            action == "home" -> runCatching {
                startActivity(
                    Intent(this, app.halo.launcher.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            action.startsWith("app:") -> {
                val pkg = action.removePrefix("app:")
                val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
                if (i != null) runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
            }
        }
    }
    private var starDownAt = 0L
    private var starHeld = false
    private val STAR_HOLD_MS = 600L

    /** Recent apps over the app that is open. */
    private fun showRecents() {
        runCatching {
            startActivity(Intent(this, RecentsOverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "can't open recent apps", it) }
    }

    /** Brings Halo to the front with [extra] set. */
    private fun showHalo(extra: String) {
        runCatching {
            startActivity(
                Intent(this, app.halo.launcher.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(app.halo.launcher.MainActivity.EXTRA_KEEP_SCREEN, true)
                    .putExtra(extra, true)
            )
        }.onFailure { Log.w(TAG, "can't open Halo", it) }
    }

    private fun showCredits() {
        runCatching {
            startActivity(
                Intent(this, app.halo.launcher.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(app.halo.launcher.MainActivity.EXTRA_KEEP_SCREEN, true)
                    .putExtra(app.halo.launcher.MainActivity.EXTRA_EGG, "credits")
            )
        }.onFailure { Log.w(TAG, "can't open the credits", it) }
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
        // "Press the button you want" in Halo: report the button instead of acting on it.
        if (ButtonLearn.active) {
            val consumed = ButtonLearn.offer(event)
            if (consumed && event.action == KeyEvent.ACTION_DOWN) swallowUp = event.keyCode
            return consumed
        }
        // Buttons learned in Halo (or the star button): short press, hold, double press.
        val bound = Buttons.decode(prefs.getString(DefaultLauncher.KEY_BUTTONS, null)).filter { it.key == event.keyCode }
        if (bound.isNotEmpty()) {
            handleBound(event, bound)
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

    // ---- force stop for "Running apps" (only when the user asks) ----------------------------

    private val stopRun by lazy { ForceStopRun(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForceStopper.service = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (ForceStopper.service === this) ForceStopper.service = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (ForceStopper.service === this) ForceStopper.service = null
        super.onDestroy()
    }

    internal fun forceStop(packages: List<String>, returnTo: Intent?) = stopRun.start(packages, returnTo)

    override fun onInterrupt() = Unit
}
