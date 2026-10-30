package app.halo.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.halo.launcher.R
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.data.Icons
import app.halo.launcher.data.MAIN_PROFILE
import app.halo.launcher.data.PROFILE_COLORS
import app.halo.launcher.data.Profile
import kotlinx.coroutines.delay

/**
 * "Who's watching?" — pick, add, rename, recolour or remove Halo profiles.
 * Each profile has its own favourites, app order, hidden apps and custom icons.
 */
@Composable
fun ProfilesScreen(
    profiles: List<Profile>,
    activeId: String,
    canAdd: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> String?,
    onUpdate: (Profile) -> Unit,
    onRemove: (String) -> Unit,
    onAppearance: () -> Unit,
) {
    val c = LocalHalo.current.colors
    var editing by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    // One requester per profile, so focus can return to the profile that was just edited.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun req(id: String) = requesters.getOrPut(id) { FocusRequester() }
    var lastEdited by remember { mutableStateOf<String?>(null) }

    // On open, and whenever the edit card closes (Done / Remove / BACK), focus goes back to a
    // profile instead of being lost with the card.
    LaunchedEffect(editing == null) {
        if (editing != null) return@LaunchedEffect
        delay(80)
        val target = lastEdited?.takeIf { id -> profiles.any { it.id == id } } ?: activeId
        runCatching { req(target).requestFocus() }
    }

    // BACK closes the avatar picker, then the edit card, before it leaves the screen.
    BackHandler(enabled = editing != null) {
        if (picking) picking = false else editing = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .readabilityScrim(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(stringResource(R.string.profiles_title), maxLines = 1, style = hstyle(64, FontWeight.SemiBold, c.text, tracking = -0.03f))
        Spacer(Modifier.height(14.px))
        BasicText(
            stringResource(R.string.profiles_subtitle),
            Modifier.widthIn(max = 1500.px),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(22, FontWeight.Medium, c.sub).copy(textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(88.px))

        Row(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(56.px),
            verticalAlignment = Alignment.Top,
        ) {
            profiles.forEach { p ->
                key(p.id) {
                    ProfileItem(
                        profile = p,
                        note = if (p.id == activeId) stringResource(R.string.profiles_current) else null,
                        editing = editing == p.id,
                        focusRequester = req(p.id),
                        onClick = { onSelect(p.id) },
                        onLongClick = { editing = p.id; lastEdited = p.id },
                    )
                }
            }
            if (canAdd) {
                AddItem { onAdd()?.let { editing = it; lastEdited = it } }
            }
        }

        Spacer(Modifier.height(64.px))

        val ep = profiles.firstOrNull { it.id == editing }
        if (ep != null && picking) {
            AvatarPicker(
                current = ep.avatar,
                onPick = { id ->
                    onUpdate(ep.copy(avatar = id))
                    picking = false
                },
                onCancel = { picking = false },
            )
        } else if (ep != null) {
            EditCard(
                profile = ep,
                canRemove = ep.id != MAIN_PROFILE,
                onUpdate = onUpdate,
                onRemove = {
                    onRemove(ep.id)
                    editing = null
                },
                onPicture = { picking = true },
                onDone = { editing = null },
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PillButton(stringResource(R.string.halo_settings), onAppearance, icon = Icons.settings, height = 56)
                Spacer(Modifier.height(20.px))
                BasicText(
                    stringResource(R.string.profiles_hint),
                    Modifier.widthIn(max = 1500.px),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(19, FontWeight.Medium, c.sub),
                )
            }
        }
    }
}

@Composable
private fun ProfileItem(
    profile: Profile,
    note: String?,
    editing: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val motion = LocalHalo.current.motion
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val target = when {
        !motion -> 1f
        pressed -> 1.03f
        focused -> 1.1f
        else -> 1f
    }
    val scale by animateFloatAsState(target, if (focused) Motion.focusIn() else Motion.focusOut(), label = "avatar")
    val ring = focused || editing

    Column(
        modifier = Modifier
            .width(200.px)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .remoteClickable(onClick = onClick, onLongClick = onLongClick, onPressedChange = { pressed = it }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(196.px)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (ring) 1f else 0.82f
                }
                .then(if (ring) Modifier.border(5.px, c.cta, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(profile, 176)
        }
        Spacer(Modifier.height(22.px))
        BasicText(
            profileName(profile),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(26, FontWeight.SemiBold, c.text, tracking = -0.01f),
        )
        BasicText(note ?: " ", maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(17, FontWeight.Medium, c.sub))
    }
}

@Composable
private fun AddItem(onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val normal = secondaryFill()
    Column(
        modifier = Modifier
            .width(200.px)
            .control(st, onClick, focusScale = 1.1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(196.px)
                .then(if (st.focused) Modifier.border(5.px, c.cta, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(176.px)
                    .clip(CircleShape)
                    .background(if (st.focused) c.cta else normal)
                    .border(2.px, c.rimTop.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                HaloIcon(Icons.plus, 64.px, if (st.focused) c.ctaText else c.text, strokeWidth = 1.6f)
            }
        }
        Spacer(Modifier.height(22.px))
        // Up to two centred lines ("Profil hinzufügen" is wider than an avatar).
        BasicText(
            stringResource(R.string.profiles_add),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(26, FontWeight.SemiBold, c.text, tracking = -0.01f).copy(textAlign = TextAlign.Center),
        )
    }
}

@Composable
private fun EditCard(
    profile: Profile,
    canRemove: Boolean,
    onUpdate: (Profile) -> Unit,
    onRemove: () -> Unit,
    onPicture: () -> Unit,
    onDone: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val nameFocus = remember { FocusRequester() }
    // Removing needs a second OK (the button turns into a confirmation first).
    var armed by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(profile.id) {
        delay(80)
        runCatching { nameFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .glass(RoundedCornerShape(40.px), strong = true, fillAlpha = 0.9f)
            .padding(horizontal = 28.px, vertical = 22.px)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(18.px),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.px),
        ) {
            Avatar(profile, 64)
            HaloTextField(
                value = profileName(profile),
                modifier = Modifier.width(440.px),
                placeholder = stringResource(R.string.profiles_name),
                focusRequester = nameFocus,
                leading = Icons.user,
            ) { v ->
                val name = v.trim()
                if (name.isNotEmpty() && name != profile.name) onUpdate(profile.copy(name = name.take(20)))
            }
            PillButton(stringResource(R.string.profiles_picture), onPicture, icon = Icons.image)
            if (canRemove) {
                PillButton(
                    stringResource(if (armed) R.string.profiles_remove_confirm else R.string.profiles_remove),
                    { if (armed) onRemove() else armed = true },
                    icon = Icons.trash,
                )
            }
            PillButton(stringResource(R.string.done), onDone, primary = true, icon = Icons.check)
        }
        // Colour: all choices visible (used when the profile has no picture, and for accents).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.px),
        ) {
            BasicText(
                stringResource(R.string.profiles_colour),
                Modifier.widthIn(min = 80.px, max = 160.px).padding(start = 4.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(19, FontWeight.SemiBold, c.sub),
            )
            PROFILE_COLORS.forEachIndexed { i, _ ->
                ColourDot(i, selected = Math.floorMod(profile.color, PROFILE_COLORS.size) == i) {
                    onUpdate(profile.copy(color = i))
                }
            }
        }
    }
}

@Composable
private fun ColourDot(index: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val (a, b) = PROFILE_COLORS[index]
    val brush = remember(index) { androidx.compose.ui.graphics.Brush.linearGradient(listOf(a, b)) }
    Box(
        Modifier
            .control(st, onClick, focusScale = 1.18f)
            .size(48.px)
            .then(if (selected || st.focused) Modifier.border(3.px, if (st.focused) c.cta else c.aqua, CircleShape) else Modifier)
            .padding(if (selected || st.focused) 5.px else 0.px)
            .clip(CircleShape)
            .background(brush),
    )
}


/** Grid of the bundled Fluent 3D avatars (MIT). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AvatarPicker(current: String?, onPick: (String?) -> Unit, onCancel: () -> Unit) {
    val c = LocalHalo.current.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { first.requestFocus() }
    }
    Column(
        Modifier
            .width(1500.px)
            .glass(RoundedCornerShape(40.px), strong = true, fillAlpha = 0.9f)
            .padding(28.px)
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.px),
            verticalArrangement = Arrangement.spacedBy(14.px),
            maxItemsInEachRow = 14,
        ) {
            AvatarCell(null, current == null, first) { onPick(null) }
            app.halo.launcher.data.Avatars.all.forEach { a ->
                AvatarCell(a, a.id == current, null) { onPick(a.id) }
            }
        }
        Spacer(Modifier.height(20.px))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.px)) {
            BasicText(stringResource(R.string.profiles_avatar_credit), maxLines = 1, style = hstyle(16, FontWeight.Medium, c.sub))
            PillButton(stringResource(R.string.cancel), onCancel, height = 56)
        }
    }
}

@Composable
private fun AvatarCell(
    a: app.halo.launcher.data.Avatars.Avatar?,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    Box(
        Modifier
            .size(88.px)
            .control(st, onClick, focusRequester = focusRequester, focusScale = 1.18f)
            .clip(CircleShape)
            .background(if (st.focused) c.cta.copy(alpha = 0.35f) else c.pill)
            .then(if (st.focused || selected) Modifier.border(if (st.focused) 4.px else 2.px, c.cta, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (a != null) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(a.res),
                contentDescription = a.name,
                modifier = Modifier.size(68.px),
            )
        } else {
            BasicText("Aa", style = hstyle(26, FontWeight.Bold, c.text))
        }
    }
}
