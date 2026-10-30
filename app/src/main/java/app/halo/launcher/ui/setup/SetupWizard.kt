package app.halo.launcher.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.data.Icons
import app.halo.launcher.ui.Backdrop
import app.halo.launcher.ui.HaloActions
import app.halo.launcher.ui.Label
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.PhotoBackdrop
import app.halo.launcher.ui.PillButton
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px
import app.halo.launcher.ui.readabilityScrim
import kotlinx.coroutines.delay

/**
 * The one-time setup wizard (and its steps opened standalone from Settings).
 *
 * TV rules: one decision per screen, big focusable controls, BACK = previous step, a step
 * indicator, "Skip" on optional steps, and the primary button (Continue) focused on every step.
 * Every choice is applied immediately (theme, background, … change behind the wizard), so
 * Continue and Skip only move on.
 *
 * [request] null = first run (shown while settings.setupDone is false).
 */
@Composable
fun SetupWizard(vm: HaloViewModel, actions: HaloActions, request: SetupRequest?) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wallpaper by vm.wallpapers.current.collectAsStateWithLifecycle()
    val stepIndex by vm.setupStep.collectAsStateWithLifecycle()
    val single = request?.single == true
    val step = if (single) request!!.start else SetupStep.entries[stepIndex.coerceIn(0, SetupStep.entries.lastIndex)]
    val c = LocalHalo.current.colors
    val primary = remember { FocusRequester() }

    fun next() {
        when {
            single -> vm.closeSetup()
            step == SetupStep.Done -> vm.finishSetup()
            else -> vm.setSetupStep(step.ordinal + 1)
        }
    }
    fun previous() {
        when {
            single -> vm.closeSetup()
            step.ordinal > 0 -> vm.setSetupStep(step.ordinal - 1)
            request != null -> vm.closeSetup()
            // First run, first step: nothing behind the wizard to go back to.
        }
    }

    BackHandler(enabled = true) { previous() }

    // The primary button gets focus on every step (the user presses ▲ to reach the choices).
    LaunchedEffect(step) {
        delay(160)
        runCatching { primary.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(c.base)) {
        // Live preview of the chosen background (the Halo scene or the current photo).
        if (settings.profile.wallpaper != "scene" && wallpaper != null) {
            PhotoBackdrop(wallpaper!!, Modifier.fillMaxSize())
        } else {
            Backdrop(Modifier.fillMaxSize())
        }

        Column(
            Modifier
                .fillMaxSize()
                .readabilityScrim(strength = 1.1f)
                .padding(top = 56.px, bottom = 52.px),
        ) {
            // ---- Top: brand + step indicator --------------------------------------------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 120.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    if (single) stringResource(R.string.setup_brand_settings) else stringResource(R.string.setup_brand),
                    size = 18,
                    color = c.sub,
                )
                Spacer(Modifier.weight(1f))
                if (!single) StepIndicator(step)
            }

            // ---- Step content ------------------------------------------------------------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 120.px, end = 120.px, top = 36.px),
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                    label = "setupStep",
                ) { s ->
                    Box(Modifier.fillMaxSize().focusGroup()) {
                        when (s) {
                            SetupStep.Language -> LanguageStep(vm, settings)
                            SetupStep.Theme -> ThemeStep(vm, settings)
                            SetupStep.Profile -> ProfileStep(vm, settings)
                            SetupStep.Weather -> WeatherStep(vm, settings)
                            SetupStep.Background -> BackgroundStep(vm, settings)
                            SetupStep.Favourites -> FavouritesStep(vm)
                            SetupStep.Assistant -> AssistantStep(vm, settings)
                            SetupStep.Home -> HomeStep(vm, settings)
                            SetupStep.Remote -> RemoteStep(vm, settings)
                            SetupStep.Done -> DoneStep(vm, settings)
                        }
                    }
                }
            }

            // ---- Bottom: Back · Skip · Continue ------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 120.px)
                    .focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.px),
            ) {
                val canGoBack = single || step.ordinal > 0 || request != null
                if (canGoBack) {
                    PillButton(
                        if (single) stringResource(R.string.setup_close) else stringResource(R.string.setup_back),
                        { previous() },
                        icon = if (single) Icons.close else Icons.chevronLeft,
                        height = 64,
                    )
                } else {
                    // First run: a way out for people who want to set things up later.
                    PillButton(stringResource(R.string.setup_skip_all), { vm.finishSetup() }, height = 64)
                }
                Spacer(Modifier.weight(1f))
                if (step.optional && !single) {
                    PillButton(stringResource(R.string.setup_skip), { next() }, height = 64)
                }
                PillButton(
                    text = when {
                        single -> stringResource(R.string.setup_done)
                        step == SetupStep.Done -> stringResource(R.string.setup_start)
                        else -> stringResource(R.string.setup_continue)
                    },
                    onClick = { next() },
                    primary = true,
                    icon = if (single || step == SetupStep.Done) Icons.check else Icons.chevronRight,
                    height = 64,
                    focusRequester = primary,
                    modifier = Modifier.width(320.px),
                )
            }
        }
    }
}

/** Ten small pills (current one wide and aqua) + "Step 3 of 10". */
@Composable
private fun StepIndicator(step: SetupStep) {
    val c = LocalHalo.current.colors
    val total = SetupStep.entries.size
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.px)) {
        SetupStep.entries.forEach { s ->
            val current = s == step
            Box(
                Modifier
                    .size(if (current) 44.px else 14.px, 14.px)
                    .clip(RoundedCornerShape(7.px))
                    .background(
                        when {
                            current -> c.aqua
                            s.ordinal < step.ordinal -> c.text.copy(alpha = 0.7f)
                            else -> c.text.copy(alpha = 0.22f)
                        }
                    )
            )
        }
        Spacer(Modifier.width(10.px))
        BasicText(
            stringResource(R.string.setup_step_of, step.ordinal + 1, total),
            style = hstyle(19, FontWeight.SemiBold, c.sub),
        )
    }
}
