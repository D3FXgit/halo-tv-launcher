package app.halo.launcher.ui.setup

import androidx.compose.foundation.layout.width
import app.halo.launcher.ui.Segmented
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.R
import app.halo.launcher.data.HaloSettings
import app.halo.launcher.data.Icons
import app.halo.launcher.data.MAX_FAVOURITES
import app.halo.launcher.data.Tile
import app.halo.launcher.ui.ControlState
import app.halo.launcher.ui.GlassTile
import app.halo.launcher.ui.HaloIcon
import app.halo.launcher.ui.LocalHalo
import app.halo.launcher.ui.ScrollPadding
import app.halo.launcher.ui.aeroGloss
import app.halo.launcher.ui.control
import app.halo.launcher.ui.glass
import app.halo.launcher.ui.hstyle
import app.halo.launcher.ui.px

private const val CELL_W = 240
private const val CELL_H = 135

/** An app card in a setup grid, with an optional badge (favourite number or a check). */
@Composable
private fun AppCell(tile: Tile, badge: String?, onClick: () -> Unit) {
    Box {
        GlassTile(tile = tile, width = CELL_W, height = CELL_H, ring = true, focusedScale = 1.08f, onClick = { onClick() })
        if (badge != null) {
            val c = LocalHalo.current.colors
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.px)
                    .size(40.px)
                    .clip(CircleShape)
                    .background(c.aqua)
                    .border(2.px, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (badge == "✓") HaloIcon(Icons.check, 24.px, Color.White, strokeWidth = 2.6f)
                else BasicText(badge, style = hstyle(20, FontWeight.Bold, Color.White))
            }
        }
    }
}

/**
 * Keeps the grid order stable while favourites change (otherwise a picked app would jump to the
 * front and focus with it): the first order seen is kept, newly installed apps are appended.
 */
@Composable
private fun rememberStableOrder(tiles: List<Tile>): List<Tile> {
    // Plain list (not snapshot state): it is only appended to while composing.
    val order = remember { ArrayList<String>() }
    val byKey = tiles.associateBy { it.app.key }
    val known = order.toHashSet()
    tiles.forEach { if (it.app.key !in known) order.add(it.app.key) }
    return order.mapNotNull { byKey[it] }
}

// ==== 6. Favourites ================================================================

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FavouritesStep(vm: HaloViewModel) {
    val home by vm.home.collectAsStateWithLifecycle()
    val favKeys = home.favourites.map { it.app.key }
    val tiles = rememberStableOrder(home.favourites + home.apps)
    var full by remember { mutableStateOf(false) }
    Column {
        StepHeader(
            stringResource(R.string.setup_favourites_title),
            stringResource(R.string.setup_favourites_subtitle, MAX_FAVOURITES),
        )
        Spacer(Modifier.height(18.px))
        StatusLine(
            when {
                !home.ready -> stringResource(R.string.setup_favourites_loading)
                full && favKeys.size >= MAX_FAVOURITES -> stringResource(R.string.setup_favourites_full, MAX_FAVOURITES)
                else -> stringResource(R.string.setup_favourites_count, favKeys.size, MAX_FAVOURITES)
            },
            ok = if (!home.ready) null else favKeys.isNotEmpty() && !(full && favKeys.size >= MAX_FAVOURITES),
        )
        Spacer(Modifier.height(20.px))
        // Look of every tile (live: the grid below updates at once).
        val settings by vm.settings.collectAsStateWithLifecycle()
        val looks = listOf("halo", "banner", "icon")
        Row(verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(stringResource(R.string.settings_tile_look), Modifier.width(260.px))
            Segmented(
                options = listOf(
                    stringResource(R.string.settings_tile_halo),
                    stringResource(R.string.settings_tile_banner),
                    stringResource(R.string.settings_tile_icon),
                ),
                selected = looks.indexOf(settings.tileLook).coerceAtLeast(0),
                onSelect = { i -> vm.updateSettings { it.copy(tileLook = looks[i]) } },
                modifier = Modifier.width(900.px),
            )
        }
        Spacer(Modifier.height(4.px))
        ScrollPadding(60.px) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(vertical = 28.px, horizontal = 14.px),
                horizontalArrangement = Arrangement.spacedBy(32.px),
                verticalArrangement = Arrangement.spacedBy(36.px),
            ) {
                items(tiles, key = { it.app.key }) { t ->
                    val idx = favKeys.indexOf(t.app.key)
                    AppCell(t, if (idx >= 0) "${idx + 1}" else null) {
                        when {
                            idx >= 0 -> { vm.swapRow(t.app.key); full = false }
                            favKeys.size >= MAX_FAVOURITES -> full = true
                            else -> vm.addFavourite(t.app.key)
                        }
                    }
                }
            }
        }
    }
}

// ==== 9. Remote button =============================================================

/** Packages of YouTube itself (choosing them would change nothing). */
private val YOUTUBE_PACKAGES = setOf("com.google.android.youtube.tv", "com.google.android.youtube", "com.google.android.youtube.tvkids")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RemoteStep(vm: HaloViewModel, settings: HaloSettings) {
    val home by vm.home.collectAsStateWithLifecycle()
    val serviceOn = rememberServiceEnabled()
    // One card per app (not per launcher activity); YouTube alternatives first.
    val apps = remember(home.all) {
        home.all
            .filter { it.app.packageName !in YOUTUBE_PACKAGES }
            .distinctBy { it.app.packageName }
            .sortedBy { t ->
                val p = t.app.packageName.lowercase()
                val l = t.label.lowercase()
                if ("tube" in p || "tube" in l) 0 else 1
            }
    }
    val target = settings.youtubeButtonTarget
    Column {
        StepHeader(stringResource(R.string.setup_remote_title), stringResource(R.string.setup_remote_subtitle))
        Spacer(Modifier.height(18.px))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.px)) {
            ServiceStatus(serviceOn, needed = target != null || settings.assistantButton != "off")
            val context = androidx.compose.ui.platform.LocalContext.current
            GroupLabel(stringResource(R.string.settings_assistant_button), Modifier.padding(top = 14.px))
            val buttons = listOf("off", "star")
            app.halo.launcher.ui.Segmented(
                options = buttons.map { stringResource(app.halo.launcher.assistant.Assistant.assistantButtonRes(it)) },
                selected = buttons.indexOf(settings.assistantButton).coerceAtLeast(0),
                onSelect = { i ->
                    vm.updateSettings { it.copy(assistantButton = buttons[i]) }
                    if (buttons[i] != "off" && !serviceOn) app.halo.launcher.setup.DefaultLauncher.openAccessibilitySettings(context)
                },
                modifier = Modifier.width(720.px),
            )
        }
        Spacer(Modifier.height(4.px))
        ScrollPadding(60.px) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxWidth().focusRestorer(),
                contentPadding = PaddingValues(vertical = 28.px, horizontal = 14.px),
                horizontalArrangement = Arrangement.spacedBy(32.px),
                verticalArrangement = Arrangement.spacedBy(36.px),
            ) {
                item(key = "keep") {
                    KeepCell(selected = target == null) { vm.updateSettings { it.copy(youtubeButtonTarget = null) } }
                }
                items(apps, key = { it.app.key }) { t ->
                    AppCell(t, if (t.app.packageName == target) "✓" else null) {
                        val pkg = t.app.packageName
                        vm.updateSettings { it.copy(youtubeButtonTarget = pkg) }
                    }
                }
            }
        }
    }
}

/** "Keep YouTube" choice, the same size as an app card. */
@Composable
private fun KeepCell(selected: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(22.px)
    val fg = if (st.focused) c.ctaText else c.text
    Box(
        Modifier
            .size(CELL_W.px, CELL_H.px)
            .control(st, onClick, focusScale = 1.08f)
            .then(if (st.focused) Modifier.clip(shape).background(c.cta).aeroGloss() else Modifier.glass(shape, strong = true, shadow = false))
            .then(if (selected && !st.focused) Modifier.border(4.px, c.aqua, shape) else Modifier)
            .padding(18.px),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            HaloIcon(Icons.playFilled, 30.px, fg)
            Spacer(Modifier.height(8.px))
            BasicText(
                stringResource(R.string.setup_remote_keep),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(21, FontWeight.Bold, fg),
            )
        }
        if (selected) CheckBadge(Modifier.align(Alignment.TopEnd), size = 36)
    }
}

/** Live status of the Halo accessibility service + the button that opens its settings. */
@Composable
internal fun ServiceStatus(serviceOn: Boolean, needed: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.px),
    ) {
        StatusLine(
            stringResource(
                when {
                    serviceOn -> R.string.setup_service_on
                    needed -> R.string.setup_service_needed
                    else -> R.string.setup_service_off
                }
            ),
            ok = if (serviceOn) true else if (needed) false else null,
        )
        if (!serviceOn) {
            app.halo.launcher.ui.PillButton(
                stringResource(R.string.setup_service_open),
                { app.halo.launcher.setup.DefaultLauncher.openAccessibilitySettings(context) },
                icon = Icons.external,
                height = 56,
            )
        }
    }
}
