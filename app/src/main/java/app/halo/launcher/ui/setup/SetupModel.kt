package app.halo.launcher.ui.setup

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow

/** The steps of the one-time setup wizard, in order. [optional] steps show "Skip". */
enum class SetupStep(val optional: Boolean) {
    Language(false),
    Theme(false),
    Profile(false),
    Weather(true),
    Background(true),
    Favourites(true),
    Assistant(true),
    Home(true),
    Remote(true),
    Done(false),
}

/**
 * An explicit request to show the wizard (the first run needs none: it shows while
 * `settings.setupDone` is false). [single] = only [start], standalone (opened from Settings);
 * otherwise the whole wizard from [start] ("Run setup again").
 */
@Immutable
data class SetupRequest(val start: SetupStep, val single: Boolean)

/**
 * Wizard navigation state, process-wide so the Settings panel can open a step without new
 * plumbing, and so the current step survives an activity recreate (language change).
 * HaloViewModel exposes and drives it (openSetup / closeSetup / finishSetup).
 */
object SetupNav {
    /** Non-null while the wizard was opened explicitly (Settings rows, "Run setup again"). */
    val request = MutableStateFlow<SetupRequest?>(null)
    /** Index into [SetupStep.entries] of the step on screen. */
    val step = MutableStateFlow(0)
    /** Counts Home presses (for the "Test: press Home" check on the Home step). */
    val homePresses = MutableStateFlow(0)

    fun open(start: SetupStep, single: Boolean) {
        step.value = start.ordinal
        request.value = SetupRequest(start, single)
    }
}
