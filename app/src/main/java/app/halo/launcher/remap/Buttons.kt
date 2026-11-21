package app.halo.launcher.remap

import android.os.SystemClock
import android.view.KeyEvent
import app.halo.launcher.data.ButtonBinding
import app.halo.launcher.data.HaloSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Remote buttons that open Halo's features (learned per TV, see ButtonLearn). */
object Buttons {
    const val SHORT = "short"
    const val HOLD = "hold"
    const val DOUBLE = "double"
    val PRESSES = listOf(SHORT, HOLD, DOUBLE)

    /** Actions a button can have (besides "app:<package>"). */
    val ACTIONS = listOf("assistant", "recents", "home")

    /** Holding at least this long counts as "hold". */
    const val HOLD_MS = 600L
    /** A second press within this time counts as "double". */
    const val DOUBLE_MS = 350L

    /** Buttons the TV needs for itself: never taken over. */
    val BLOCKED = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST,
    )

    /** What applies: the learned buttons, or the older "star button opens the assistant" choice. */
    fun effective(s: HaloSettings): List<ButtonBinding> = when {
        s.buttons.isNotEmpty() -> s.buttons
        s.assistantButton == "star" -> listOf(
            ButtonBinding(KeyEvent.KEYCODE_MACRO_1, SHORT, "assistant"),
            ButtonBinding(KeyEvent.KEYCODE_MACRO_1, HOLD, "recents"),
        )
        else -> emptyList()
    }

    fun encode(list: List<ButtonBinding>): String = list.joinToString("|") { "${it.key},${it.press},${it.action}" }

    fun decode(s: String?): List<ButtonBinding> = s.orEmpty().split('|').mapNotNull { part ->
        val f = part.split(',', limit = 3)
        val key = f.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        ButtonBinding(key, f.getOrNull(1) ?: SHORT, f.getOrNull(2) ?: return@mapNotNull null)
    }

    /** A readable name for a key: "★ Star", "Red", "Bookmark" … */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_MACRO_1 -> "★"
        KeyEvent.KEYCODE_PROG_RED -> "● Red"
        KeyEvent.KEYCODE_PROG_GREEN -> "● Green"
        KeyEvent.KEYCODE_PROG_YELLOW -> "● Yellow"
        KeyEvent.KEYCODE_PROG_BLUE -> "● Blue"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase()
            .split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}

/** "Press the button you want": the Halo service reports the next button pressed, for 10 seconds. */
object ButtonLearn {
    private const val WINDOW_MS = 10_000L

    @Volatile private var until = 0L

    private val _key = MutableStateFlow<Int?>(null)
    /** The button that was pressed (null = none yet). */
    val key: StateFlow<Int?> = _key

    private val _blocked = MutableStateFlow<Int?>(null)
    /** A button the TV needs (arrows, OK, Back …) was pressed: shown as a hint, not taken. */
    val blocked: StateFlow<Int?> = _blocked

    fun start() {
        _key.value = null
        _blocked.value = null
        until = SystemClock.elapsedRealtime() + WINDOW_MS
    }

    fun stop() {
        until = 0L
    }

    val active: Boolean get() = SystemClock.elapsedRealtime() < until

    /** Called by the service for every button while learning. True = consumed. */
    internal fun offer(event: KeyEvent): Boolean {
        if (!active) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return event.keyCode !in Buttons.BLOCKED
        if (event.keyCode in Buttons.BLOCKED) {
            _blocked.value = event.keyCode
            return false
        }
        _key.value = event.keyCode
        stop()
        return true
    }
}
