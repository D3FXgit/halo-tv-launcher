package app.halo.launcher.ui.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.data.IconSpec
import app.halo.launcher.data.Icons
import app.halo.launcher.ui.ControlState
import app.halo.launcher.ui.HaloIcon
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.aeroGloss
import app.halo.launcher.ui.control
import app.halo.launcher.ui.glass
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px

/** The Activity behind a Compose context (Lang.set and permission requests need it). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Big title + one explanatory line at the top of every step. */
@Composable
fun StepHeader(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    val c = LocalHalo.current.colors
    Column(modifier) {
        BasicText(title, style = hstyle(60, FontWeight.Bold, c.text, tracking = -0.025f))
        if (subtitle != null) {
            BasicText(
                subtitle,
                Modifier.padding(top = 10.px).width(1300.px),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(25, FontWeight.Medium, c.sub),
            )
        }
    }
}

/** Small caps label above a group of controls. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalHalo.current.colors
    BasicText(
        text.uppercase(),
        modifier.padding(bottom = 14.px, start = 4.px),
        maxLines = 1,
        style = hstyle(17, FontWeight.Bold, c.sub, tracking = 0.12f),
    )
}

/** Body text in the wizard. */
@Composable
fun SetupText(text: String, modifier: Modifier = Modifier, size: Int = 21, maxLines: Int = 4, color: Color? = null) {
    val c = LocalHalo.current.colors
    BasicText(
        text,
        modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = hstyle(size, FontWeight.Medium, color ?: c.sub),
    )
}

/**
 * A large selectable card: icon, title, one line of explanation. Focused = white (the one focus
 * look of Halo); selected = aqua rim + check badge.
 */
@Composable
fun ChoiceCard(
    title: String,
    subtitle: String?,
    icon: IconSpec?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = 400,
    height: Int = 220,
    focusRequester: FocusRequester? = null,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(32.px)
    val focused = st.focused
    val fg = if (focused) c.ctaText else c.text
    val sub = if (focused) c.ctaText.copy(alpha = 0.7f) else c.sub
    Box(
        modifier
            .size(width.px, height.px)
            .control(st, onClick, focusRequester = focusRequester, focusScale = 1.05f)
            .then(
                if (focused) Modifier.clip(shape).background(c.cta).aeroGloss()
                else Modifier.glass(shape, strong = true, shadow = false)
            )
            .then(if (selected && !focused) Modifier.border(4.px, c.aqua, shape) else Modifier)
            .padding(28.px),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            if (icon != null) {
                HaloIcon(icon, 48.px, fg, strokeWidth = 1.7f)
                Spacer(Modifier.height(16.px))
            }
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(30, FontWeight.Bold, fg))
            if (subtitle != null) {
                BasicText(
                    subtitle,
                    Modifier.padding(top = 4.px),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(19, FontWeight.Medium, sub),
                )
            }
        }
        if (selected) CheckBadge(Modifier.align(Alignment.TopEnd))
    }
}

/** Aqua check in a circle (selected state). */
@Composable
fun CheckBadge(modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier
            .size(size.px)
            .clip(CircleShape)
            .background(LocalHalo.current.colors.aqua)
            .border(2.px, Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { HaloIcon(Icons.check, (size * 0.6f).toInt().px, Color.White, strokeWidth = 2.6f) }
}

/** Status line: coloured dot (green = ok, amber = needs action, grey = neutral) + text. */
@Composable
fun StatusLine(text: String, ok: Boolean?, modifier: Modifier = Modifier, size: Int = 22) {
    val c = LocalHalo.current.colors
    val dot = when (ok) {
        true -> Color(0xFF34C77B)
        false -> Color(0xFFF2A93B)
        null -> c.sub
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.px)) {
        Box(Modifier.size(14.px).clip(CircleShape).background(dot))
        BasicText(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = hstyle(size, FontWeight.SemiBold, c.text))
    }
}

/** A frosted panel that groups related controls. */
@Composable
fun SetupPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .glass(RoundedCornerShape(36.px), strong = true, fillAlpha = 0.86f)
            .padding(32.px),
    ) { content() }
}
