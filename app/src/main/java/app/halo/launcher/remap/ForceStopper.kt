package app.halo.launcher.remap

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Force-stopping apps for Halo's "Running apps" screen. Android doesn't let a launcher stop other
 * apps, so — only when the user asks for it — Halo opens the app's system "App info" page and presses
 * "Force stop" and "OK" there for the user, through its accessibility service. Without the service,
 * the App info page is just opened and the user presses the button.
 */
object ForceStopper {
    enum class Status { Queued, Stopping, Stopped, Failed }

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    /** Per package: what happened to the last stop request. */
    val status: StateFlow<Map<String, Status>> = _status

    @Volatile internal var service: ButtonRemapService? = null

    /** True when stops are done automatically (Halo's accessibility service is running). */
    val automatic: Boolean get() = service != null

    /** Stops [packages] one after the other. Returns false when only the App info page could be opened. */
    fun stop(context: android.content.Context, packages: List<String>, returnTo: Intent?): Boolean {
        val s = service
        if (s == null) {
            packages.firstOrNull()?.let { openAppInfo(context, it) }
            return false
        }
        _status.value = _status.value + packages.associateWith { Status.Queued }
        s.forceStop(packages, returnTo)
        return true
    }

    internal fun set(pkg: String, st: Status) {
        _status.value = _status.value + (pkg to st)
    }

    fun clear(pkg: String) {
        _status.value = _status.value - pkg
    }

    fun openAppInfo(context: android.content.Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** The click-through on the App info page, run by [ButtonRemapService]. */
internal class ForceStopRun(private val svc: AccessibilityService) {
    private companion object {
        const val TAG = "HaloStop"
        val SETTINGS = setOf("com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings")
        const val STEP_TIMEOUT_MS = 7_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var current: String? = null
    private var stage = 0
    private var deadline = 0L
    private var returnTo: Intent? = null

    private val forceStopLabels: List<String> by lazy {
        (settingsStrings("force_stop", "device_apps_app_management_force_stop", "force_stop_dlg_title") +
            listOf("Force stop", "Stopp erzwingen", "Beenden erzwingen", "Zorla durdur", "Durdurmaya zorla")).distinct()
    }
    private val okLabels: List<String> by lazy {
        (settingsStrings("dlg_ok", "okay", "settings_ok", "device_apps_app_management_force_stop_ok") +
            listOfNotNull(runCatching { svc.getString(android.R.string.ok) }.getOrNull()) + listOf("OK", "Ok", "Tamam")).distinct()
    }

    private fun settingsStrings(vararg names: String): List<String> = SETTINGS.flatMap { pkg ->
        runCatching {
            val r = svc.packageManager.getResourcesForApplication(pkg)
            names.mapNotNull { n -> r.getIdentifier(n, "string", pkg).takeIf { it != 0 }?.let { r.getString(it) } }
        }.getOrDefault(emptyList())
    }

    fun start(packages: List<String>, back: Intent?) {
        returnTo = back
        queue.addAll(packages.filter { it != svc.packageName && it !in queue && it != current })
        if (current == null) {
            showCover()
            next()
        }
    }

    // ---- a cover over the Settings page while it is being clicked through --------------------

    private var cover: android.view.View? = null

    private fun showCover() {
        if (cover != null) return
        runCatching {
            val wm = svc.getSystemService(android.view.WindowManager::class.java)
            val label = android.widget.TextView(svc).apply {
                text = app.halo.launcher.i18n.Lang.string(svc, app.halo.launcher.R.string.tasks_closing)
                textSize = 26f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(android.graphics.Color.argb(0xF2, 0x05, 0x07, 0x0E))
            }
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            )
            wm.addView(label, lp)
            cover = label
        }.onFailure { Log.w(TAG, "no cover: ${it.message}") }
    }

    private fun hideCover() {
        val v = cover ?: return
        cover = null
        runCatching { svc.getSystemService(android.view.WindowManager::class.java).removeView(v) }
    }

    private fun next() {
        val pkg = queue.removeFirstOrNull()
        current = pkg
        if (pkg == null) {
            // All done: back to Halo, then lift the cover.
            returnTo?.let { i -> runCatching { svc.startActivity(i) } }
            handler.postDelayed(::hideCover, 450)
            return
        }
        stage = 0
        deadline = SystemClock.elapsedRealtime() + STEP_TIMEOUT_MS
        ForceStopper.set(pkg, ForceStopper.Status.Stopping)
        runCatching {
            svc.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        }.onFailure {
            finish(pkg, false)
            return
        }
        handler.postDelayed(::step, 250)
    }

    private fun finish(pkg: String, ok: Boolean) {
        Log.i(TAG, "$pkg: ${if (ok) "stopped" else "not stopped"}")
        ForceStopper.set(pkg, if (ok) ForceStopper.Status.Stopped else ForceStopper.Status.Failed)
        // Leave the App info page.
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        handler.postDelayed(::next, 200)
    }

    private fun step() {
        val pkg = current ?: return
        if (SystemClock.elapsedRealtime() > deadline) {
            finish(pkg, false)
            return
        }
        val root = runCatching { svc.rootInActiveWindow }.getOrNull()
        if (root != null && root.packageName?.toString() in SETTINGS) {
            when (stage) {
                0 -> {
                    val node = find(root, forceStopLabels)
                    if (node != null) {
                        val target = clickable(node)
                        if (target == null || !target.isEnabled) {
                            // Greyed out: the app isn't running.
                            finish(pkg, true)
                            return
                        }
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        stage = 1
                        deadline = SystemClock.elapsedRealtime() + STEP_TIMEOUT_MS
                    }
                }
                1 -> {
                    val ok = find(root, okLabels, exact = true)
                    if (ok != null) {
                        clickable(ok)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        stage = 2
                        deadline = SystemClock.elapsedRealtime() + 600
                    }
                }
                else -> {
                    finish(pkg, true)
                    return
                }
            }
        }
        handler.postDelayed(::step, 150)
    }

    /** First node whose text (or description) matches one of [labels]. */
    private fun find(root: AccessibilityNodeInfo, labels: List<String>, exact: Boolean = false): AccessibilityNodeInfo? {
        for (l in labels) {
            val hits = runCatching { root.findAccessibilityNodeInfosByText(l) }.getOrNull().orEmpty()
            hits.firstOrNull { n ->
                val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (exact) t.equals(l, ignoreCase = true) else t.equals(l, ignoreCase = true) || t.startsWith(l, ignoreCase = true)
            }?.let { return it }
        }
        return null
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) {
            val c = cur ?: return null
            if (c.isClickable) return c
            cur = c.parent
        }
        return null
    }
}
