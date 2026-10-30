package app.halo.launcher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.data.IconSpec

/** Tracks focus + press for a remote-operated control. */
class ControlState {
    var focused by mutableStateOf(false)
    var pressed by mutableStateOf(false)
}

@Composable
fun Modifier.control(
    state: ControlState,
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    focusScale: Float = 1.08f,
): Modifier {
    val motion = LocalHalo.current.motion
    val target = when {
        state.pressed -> focusScale * 0.94f
        state.focused -> focusScale
        else -> 1f
    }
    val scale by animateFloatAsState(
        if (motion) target else 1f,
        if (state.focused) Motion.focusIn() else Motion.focusOut(),
        label = "ctl",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .focusProperties { canFocus = enabled }
        .onFocusChanged { state.focused = it.isFocused }
        .remoteClickable(onClick = onClick, onPressedChange = { state.pressed = it })
}

/** Aero gloss over a filled control (draw after clip + background). Built once per size. */
fun Modifier.aeroGloss(strength: Float = 1f): Modifier = drawWithCache {
    val g = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.55f * strength),
        0.48f to Color.White.copy(alpha = 0.10f * strength),
        0.49f to Color.Transparent,
        1f to Color.Transparent,
    )
    onDrawBehind { drawRect(g) }
}

/** Round icon button (top bar, transport controls). Inverts when focused. */
@Composable
fun RoundButton(
    icon: IconSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 56,
    iconSize: Int = 22,
    strokeWidth: Float = 2f,
    background: Brush? = null,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    content: (@Composable (Color) -> Unit)? = null,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val bg: Brush = when {
        st.focused -> Brush.linearGradient(listOf(c.cta, c.cta))
        background != null -> background
        else -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val fg = if (st.focused) c.ctaText else if (background != null) Color.White else c.text
    Box(
        modifier = modifier
            .size(size.px)
            .control(st, onClick, enabled, focusRequester, 1.12f)
            .clip(CircleShape)
            .background(bg)
            .then(if (st.focused || background != null) Modifier.aeroGloss() else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content(fg) else HaloIcon(icon, iconSize.px, fg.copy(alpha = if (enabled) 1f else 0.4f), strokeWidth = strokeWidth)
    }
}

/*
 * Visual language shared by every control (so "focused" and "selected" can never be confused):
 *  - FOCUSED  = solid white (c.cta) + gloss + scale. Only one thing on screen looks like this.
 *  - SELECTED / PRIMARY = glossy aqua tint ("Aero" blue), text in the normal text colour.
 *  - NORMAL   = translucent dark/light glass with a hairline rim (clearly tappable, not disabled).
 *  - DISABLED = no fill, 40 % text.
 */

/** Aqua "selected / primary" fill. */
@Composable
internal fun aquaBrush(): Brush {
    val aqua = LocalHalo.current.colors.aqua
    return remember(aqua) { Brush.verticalGradient(listOf(aqua.copy(alpha = 0.62f), aqua.copy(alpha = 0.36f))) }
}

/** Fill of a normal (secondary) control: readable over photos in both themes. */
@Composable
internal fun secondaryFill(): Color {
    val c = LocalHalo.current.colors
    return remember(c) { c.pill.compositeOver(c.base.copy(alpha = if (c.isDark) 0.38f else 0.22f)) }
}

/** Pill button: "Done", "Drop", bottom bar actions. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: IconSpec? = null,
    enabled: Boolean = true,
    height: Int = 64,
    focusRequester: FocusRequester? = null,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape((height / 2).px)
    val focused = st.focused && enabled
    val aqua = aquaBrush()
    val normal = secondaryFill()
    val bg: Brush = when {
        focused -> SolidColor(c.cta)
        primary && enabled -> aqua
        else -> SolidColor(normal)
    }
    val fg = when {
        focused -> c.ctaText
        enabled -> c.text
        else -> c.text.copy(alpha = 0.4f)
    }
    Row(
        modifier = modifier
            .height(height.px)
            .control(st, onClick, enabled, focusRequester, 1.06f)
            .clip(shape)
            .background(bg)
            .then(if (focused || (primary && enabled)) Modifier.aeroGloss(if (focused) 1f else 0.7f) else Modifier)
            .then(if (!focused) Modifier.border(1.px, c.rimLine, shape) else Modifier)
            .padding(horizontal = 26.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.px, Alignment.CenterHorizontally),
    ) {
        if (icon != null) HaloIcon(icon, 22.px, fg)
        BasicText(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(20, if (focused || primary) FontWeight.Bold else FontWeight.SemiBold, fg),
        )
    }
}

/**
 * iOS/tvOS-style switch (drawing only, not focusable): put it at the end of a focusable row.
 * [onLight] = the row behind it is the white focus fill.
 */
@Composable
fun ToggleSwitch(checked: Boolean, onLight: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHalo.current.colors
    val motion = LocalHalo.current.motion
    val bias by animateFloatAsState(
        if (checked) 1f else -1f,
        if (motion) Motion.focusIn<Float>() else tween<Float>(0),
        label = "switch",
    )
    val track = when {
        checked -> c.aqua
        onLight -> Color(0x40000000)
        c.isDark -> Color(0x38FFFFFF)
        else -> Color(0x2612141A)
    }
    Box(
        modifier
            .size(64.px, 36.px)
            .clip(RoundedCornerShape(18.px))
            .background(track)
            .padding(4.px),
        contentAlignment = BiasAlignment(bias, 0f),
    ) {
        Box(
            Modifier
                .size(28.px)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** Pill with a label and a switch: one OK toggles it, the state is always visible. */
@Composable
fun TogglePill(
    text: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: IconSpec? = null,
    height: Int = 64,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape((height / 2).px)
    val fg = if (st.focused) c.ctaText else c.text
    val normal = secondaryFill()
    Row(
        modifier = modifier
            .height(height.px)
            .control(st, { onToggle(!checked) }, focusScale = 1.06f)
            .clip(shape)
            .background(if (st.focused) c.cta else normal)
            .then(if (st.focused) Modifier.aeroGloss() else Modifier.border(1.px, c.rimLine, shape))
            .padding(start = 24.px, end = 14.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.px),
    ) {
        if (icon != null) HaloIcon(icon, 22.px, fg)
        BasicText(
            text,
            Modifier.widthIn(max = 460.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(20, FontWeight.SemiBold, fg),
        )
        ToggleSwitch(checked, onLight = st.focused)
    }
}

/**
 * Segmented choice control: every option visible, one D-pad press away. The selected option is
 * aqua; the focused one is white. Entering the control (from above/below) lands on the selected
 * option; [focusRequester], if given, is attached to the selected option.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (Int) -> Boolean = { true },
    focusRequester: FocusRequester? = null,
) {
    val c = LocalHalo.current.colors
    val own = remember(options.size) { List(options.size) { FocusRequester() } }
    val sel = selected.takeIf { it in options.indices && enabled(it) }
    val fallback = if (sel != null) (focusRequester ?: own[sel]) else FocusRequester.Default
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(30.px))
            .background(secondaryFill())
            .border(1.px, c.rimLine, RoundedCornerShape(30.px))
            .padding(4.px)
            .focusRestorer(fallback)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.px),
    ) {
        options.forEachIndexed { i, label ->
            val req = if (i == sel && focusRequester != null) focusRequester else own[i]
            SegmentButton(label, i == selected, enabled(i), req) { onSelect(i) }
        }
    }
}

@Composable
private fun RowScope.SegmentButton(
    label: String,
    on: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val focused = st.focused && enabled
    val shape = RoundedCornerShape(26.px)
    val aqua = aquaBrush()
    val bg: Brush = when {
        focused -> SolidColor(c.cta)
        on -> aqua
        else -> SolidColor(Color.Transparent)
    }
    val fg = when {
        focused -> c.ctaText
        !enabled -> c.text.copy(alpha = 0.35f)
        else -> c.text
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(52.px)
            .control(st, onClick, enabled, focusRequester, focusScale = 1.05f)
            .clip(shape)
            .background(bg)
            .then(if (focused || on) Modifier.aeroGloss(if (focused) 1f else 0.6f) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(19, if (on || focused) FontWeight.Bold else FontWeight.SemiBold, fg),
        )
    }
}

/** Clock whose digits roll over (like a flip/odometer) when the minute changes. */
@Composable
fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val motion = LocalHalo.current.motion
    Row(modifier) {
        text.forEachIndexed { i, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    if (!motion) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else (slideInVertically(Motion.gentle()) { it / 2 } + fadeIn(tween(500))) togetherWith
                        (slideOutVertically(Motion.gentle()) { -it / 2 } + fadeOut(tween(300)))
                },
                label = "digit$i",
            ) { BasicText(it.toString(), style = style) }
        }
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, size: Int = 18, color: Color? = null) {
    val c = LocalHalo.current.colors
    BasicText(
        text,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = hstyle(size, FontWeight.SemiBold, color ?: c.sub, tracking = 0.04f),
    )
}

/** Shape helper used by cards. */
fun rounded(px: Int): Shape = RoundedCornerShape(px.px)
