package app.halo.launcher.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.R
import app.halo.launcher.data.Icons
import app.halo.launcher.data.MAX_FAVOURITES
import app.halo.launcher.data.Tile
import kotlinx.coroutines.delay

/**
 * "Arrange Home": pick a tile up with OK, move it with ◀ ▶, ▲ ▼ moves it
 * between Favourites and Apps, OK again drops it. Tiles jiggle like tvOS.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ArrangeScreen(
    favourites: List<Tile>,
    apps: List<Tile>,
    hiddenCount: Int,
    startHeld: String?,
    onMove: (String, Int) -> Unit,
    onSwapRow: (String) -> Boolean,
    onHide: (String) -> Unit,
    onRestoreHidden: () -> Unit,
    onCustomize: (String) -> Unit,
    /** Package name → system uninstall dialog. */
    onUninstall: (String) -> Unit,
    onDone: () -> Unit,
) {
    val c = LocalHalo.current.colors
    var held by remember { mutableStateOf(startHeld) }
    var version by remember { mutableIntStateOf(0) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun req(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val appsState = rememberLazyListState()

    val heldTile = (favourites + apps).firstOrNull { it.app.key == held }
    val inFav = heldTile != null && favourites.any { it.app.key == held }

    // Back while holding = drop (takes precedence over the global Back handler).
    BackHandler(enabled = held != null) { held = null; version++ }

    // Keep focus on the held tile after it moves (possibly into another row).
    LaunchedEffect(version, favourites, apps) {
        val k = held ?: startHeld ?: favourites.firstOrNull()?.app?.key ?: apps.firstOrNull()?.app?.key ?: return@LaunchedEffect
        val ai = apps.indexOfFirst { it.app.key == k }
        if (ai >= 0) appsState.scrollToItem((ai - 2).coerceAtLeast(0))
        delay(40)
        runCatching { req(k).requestFocus() }
    }

    fun keyHandler(t: Tile): (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ e ->
        if (held != t.app.key || !e.isDown) return@handler false
        val list = if (inFav) favourites else apps
        val i = list.indexOfFirst { it.app.key == t.app.key }
        when (e.code) {
            NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                if (i > 0) { onMove(t.app.key, -1); version++ }
                true
            }
            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (i < list.lastIndex) { onMove(t.app.key, 1); version++ }
                true
            }
            NativeKeyEvent.KEYCODE_DPAD_UP -> {
                if (!inFav && favourites.size < MAX_FAVOURITES) { onSwapRow(t.app.key); version++; true } else false
            }
            NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                if (inFav) { onSwapRow(t.app.key); version++; true } else false
            }
            else -> false
        }
    }

    @Composable
    fun ArrangeTile(t: Tile, w: Int, h: Int, i: Int, count: Int) {
        GlassTile(
            tile = t,
            width = w,
            height = h,
            focusRequester = req(t.app.key),
            held = held == t.app.key,
            dim = held != null && held != t.app.key,
            jiggle = true,
            blur = w > 300,
            edges = Edges(left = i == 0, right = i == count - 1),
            onClick = { held = if (held == t.app.key) null else t.app.key },
            onLongClick = { onCustomize(t.app.key) },
            onKey = keyHandler(t),
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 64.px),
    ) {
        // One "Done" only (in the action bar), so there is never a second white button competing
        // with the focused one; BACK also leaves.
        Column(Modifier.fillMaxWidth().padding(horizontal = 96.px)) {
            BasicText(
                stringResource(R.string.arrange_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(56, FontWeight.Bold, c.text, tracking = -0.02f),
            )
            BasicText(
                stringResource(if (held == null) R.string.arrange_hint else R.string.arrange_hint_held),
                Modifier.padding(top = 10.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(24, FontWeight.Medium, c.sub),
            )
        }
        Spacer(Modifier.height(64.px))

        Row(Modifier.padding(start = 96.px), verticalAlignment = Alignment.CenterVertically) {
            Label(stringResource(R.string.favourites))
            BasicText(
                "  " + stringResource(R.string.arrange_count, favourites.size, MAX_FAVOURITES),
                maxLines = 1,
                style = hstyle(17, FontWeight.Medium, c.sub),
            )
        }
        // Room for the focused / held tile to grow without covering the label.
        Spacer(Modifier.height(30.px))
        Row(
            Modifier
                .height(180.px)
                .padding(horizontal = 96.px)
                .focusRestorer()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(32.px),
        ) {
            favourites.forEachIndexed { i, t ->
                key(t.app.key) { ArrangeTile(t, 320, 180, i, favourites.size) }
            }
            if (favourites.isEmpty()) {
                BasicText(stringResource(R.string.arrange_empty_favourites), maxLines = 1, style = hstyle(22, FontWeight.Medium, c.sub))
            }
        }
        Spacer(Modifier.height(48.px))
        Label(stringResource(R.string.apps), Modifier.padding(start = 96.px))
        Spacer(Modifier.height(16.px))
        LazyRow(
            state = appsState,
            modifier = Modifier
                .fillMaxWidth()
                .height(124.px)
                .bleed(48.px)
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 96.px, vertical = 48.px),
            horizontalArrangement = Arrangement.spacedBy(28.px),
        ) {
            itemsIndexed(apps, key = { _, t -> t.app.key }) { i, t -> ArrangeTile(t, 220, 124, i, apps.size) }
        }

        Spacer(Modifier.weight(1f))

        // ---- Action bar -------------------------------------------------------
        Box(Modifier.fillMaxWidth().padding(bottom = 56.px), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = heldTile?.app?.key,
                transitionSpec = {
                    (slideInVertically(Motion.gentle()) { it / 2 } + fadeIn()) togetherWith
                        (slideOutVertically(Motion.gentle()) { it / 2 } + fadeOut())
                },
                label = "bar",
            ) { key ->
                Row(
                    Modifier
                        .glass(RoundedCornerShape(42.px), strong = true, fillAlpha = 0.86f)
                        .padding(8.px)
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.px),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val t = (favourites + apps).firstOrNull { it.app.key == key }
                    if (t != null) {
                        BasicText(
                            stringResource(R.string.arrange_moving, t.label),
                            Modifier.padding(horizontal = 22.px).widthIn(max = 260.px),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = hstyle(20, FontWeight.SemiBold, c.text),
                        )
                        RoundButton(Icons.chevronLeft, { onMove(t.app.key, -1); version++ }, size = 64, background = androidx.compose.ui.graphics.SolidColor(c.pill))
                        RoundButton(Icons.chevronRight, { onMove(t.app.key, 1); version++ }, size = 64, background = androidx.compose.ui.graphics.SolidColor(c.pill))
                        val tInFav = favourites.any { it.app.key == key }
                        PillButton(
                            stringResource(
                                when {
                                    tInFav -> R.string.arrange_to_apps
                                    favourites.size >= MAX_FAVOURITES -> R.string.arrange_favourites_full
                                    else -> R.string.arrange_to_favourites
                                }
                            ),
                            { onSwapRow(t.app.key); version++ },
                            enabled = tInFav || favourites.size < MAX_FAVOURITES,
                        )
                        PillButton(stringResource(R.string.arrange_change_icon), { onCustomize(t.app.key) })
                        PillButton(stringResource(R.string.arrange_hide), { onHide(t.app.key); held = null })
                        PillButton(stringResource(R.string.arrange_uninstall), { held = null; onUninstall(t.app.packageName) }, icon = Icons.trash)
                        PillButton(stringResource(R.string.arrange_drop), { held = null; version++ }, primary = true)
                    } else {
                        BasicText(
                            stringResource(R.string.arrange_bar_hint),
                            Modifier.padding(horizontal = 22.px).widthIn(max = 1000.px),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = hstyle(20, FontWeight.SemiBold, c.sub),
                        )
                        if (hiddenCount > 0) {
                            PillButton(pluralResource(R.plurals.arrange_restore_hidden, hiddenCount, hiddenCount), onRestoreHidden)
                        }
                        PillButton(stringResource(R.string.done), onDone, primary = true)
                    }
                }
            }
        }
    }
}
