package app.halo.launcher.ui

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.halo.launcher.HaloViewModel
import app.halo.launcher.MainActivity
import app.halo.launcher.R
import app.halo.launcher.data.Icons
import app.halo.launcher.remap.ForceStopper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Recent apps", like Android's recents screen: big cards of the apps used since the TV started.
 * OK switches to an app, Down closes it (the card flies up and the app is force-stopped in the
 * background, behind a cover, by Halo's accessibility service), "Clear all" closes every app.
 * Back or Home leaves.
 */
@Composable
fun TaskManagerPanel(
    vm: HaloViewModel,
    onClose: () -> Unit,
    /** Where to come back to after closing apps (default: Halo's home screen). */
    returnTo: Intent? = null,
) {
    val context = LocalContext.current
    val c = LocalHalo.current.colors
    val scope = rememberCoroutineScope()
    val status by ForceStopper.status.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val all by produceState(vm.runningApps(), refresh, status) { value = vm.runningApps() }
    /** Closed here (shown as gone at once, while the stop runs in the background). */
    val closed = remember { mutableStateListOf<String>() }
    val apps = all.filter { it.tile.app.packageName !in closed }
    val usage = remember(refresh) { vm.hasUsageAccess() }
    val auto = ForceStopper.automatic
    val listState = rememberLazyListState()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val clearAll = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var inside by remember { mutableStateOf(false) }

    // Re-read now and then (coming back from the TV's settings, apps closed meanwhile).
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            refresh++
        }
    }
    // Keep the remote's focus in here (Home behind would take it otherwise).
    LaunchedEffect(inside, apps.size) {
        if (inside) return@LaunchedEffect
        repeat(20) {
            delay(120)
            if (inside) return@LaunchedEffect
            val target = apps.getOrNull(focusedIndex.coerceAtMost(apps.lastIndex))?.tile?.app?.packageName
            runCatching { (target?.let { requesters[it] } ?: clearAll).requestFocus() }
        }
    }
    BackHandler { onClose() }

    val back = remember {
        returnTo ?: Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_KEEP_SCREEN, true)
    }
    fun close(pkgs: List<String>) {
        if (pkgs.isEmpty()) return
        if (auto) {
            closed.addAll(pkgs)
            ForceStopper.stop(context, pkgs, back)
        } else {
            ForceStopper.openAppInfo(context, pkgs.first())
        }
    }
    fun open(r: HaloViewModel.RunningApp) {
        onClose()
        vm.recordLaunch(r.tile.app.key)
        runCatching { context.startActivity(r.tile.app.launchIntent()) }
            .onFailure {
                context.packageManager.getLeanbackLaunchIntentForPackage(r.tile.app.packageName)
                    ?.let { i -> runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xE6020309), Color(0xD9070B18))))
            .onFocusChanged { inside = it.hasFocus }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 80.px, bottom = 60.px)) {
            // ---- header ---------------------------------------------------------------
            Row(Modifier.fillMaxWidth().padding(horizontal = 120.px), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(stringResource(R.string.tasks_title), style = hstyle(48, FontWeight.Bold, Color.White, tracking = -0.01f))
                    Spacer(Modifier.height(8.px))
                    BasicText(
                        stringResource(if (auto) R.string.tasks_hint else R.string.tasks_subtitle_manual),
                        maxLines = 2,
                        style = hstyle(21, FontWeight.Medium, Color.White.copy(alpha = 0.6f)),
                    )
                }
                if (apps.isNotEmpty() && auto) {
                    PillButton(stringResource(R.string.tasks_close_all), { close(apps.map { it.tile.app.packageName }) }, icon = Icons.close, focusRequester = clearAll)
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- the cards ------------------------------------------------------------
            if (apps.isEmpty()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(stringResource(R.string.tasks_empty), style = hstyle(28, FontWeight.Medium, Color.White.copy(alpha = 0.7f)))
                        Spacer(Modifier.height(24.px))
                        PillButton(stringResource(R.string.done), onClose, primary = true, icon = Icons.check, focusRequester = clearAll)
                    }
                }
            } else {
                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 120.px, vertical = 60.px),
                    horizontalArrangement = Arrangement.spacedBy(48.px),
                ) {
                    itemsIndexed(apps, key = { _, r -> r.tile.app.packageName }) { i, r ->
                        val pkg = r.tile.app.packageName
                        val req = requesters.getOrPut(pkg) { FocusRequester() }
                        RecentCard(
                            r = r,
                            requester = req,
                            stopping = status[pkg] == ForceStopper.Status.Stopping || status[pkg] == ForceStopper.Status.Queued,
                            failed = status[pkg] == ForceStopper.Status.Failed,
                            onFocus = { focusedIndex = i },
                            onOpen = { open(r) },
                            onClose = {
                                close(listOf(pkg))
                                // Focus moves to the neighbour, like Android.
                                focusedIndex = i.coerceAtMost(apps.lastIndex - 1).coerceAtLeast(0)
                                scope.launch {
                                    delay(60)
                                    val next = apps.filter { it.tile.app.packageName != pkg }.getOrNull(focusedIndex)
                                    runCatching { (next?.let { requesters[it.tile.app.packageName] } ?: clearAll).requestFocus() }
                                }
                            },
                            onInfo = { ForceStopper.openAppInfo(context, pkg) },
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- usage access note ----------------------------------------------------
            if (!usage) {
                Row(
                    Modifier
                        .padding(horizontal = 120.px)
                        .clip(RoundedCornerShape(24.px))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 24.px, vertical = 14.px),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(stringResource(R.string.tasks_usage_hint), maxLines = 2, style = hstyle(18, FontWeight.Medium, Color.White.copy(alpha = 0.75f)))
                    Spacer(Modifier.width(16.px))
                    PillButton(stringResource(R.string.tasks_usage_allow), {
                        val ok = runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.isSuccess
                        if (!ok) ForceStopper.openAppInfo(context, context.packageName)
                    }, icon = Icons.external, height = 50)
                }
            }
        }
    }
}

/** One app card: OK opens, Down closes (flies up), Up shows App info. */
@Composable
private fun RecentCard(
    r: HaloViewModel.RunningApp,
    requester: FocusRequester,
    stopping: Boolean,
    failed: Boolean,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onInfo: () -> Unit,
) {
    val c = LocalHalo.current.colors
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fly = remember { Animatable(0f) }
    val scale by animateFloatAsState(if (focused) 1.08f else 0.94f, tween(220), label = "card")
    val mins = ((System.currentTimeMillis() - r.lastUsed) / 60_000L).toInt()
    Column(
        Modifier.graphicsLayer {
            translationY = -fly.value * 700f
            alpha = 1f - fly.value
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Name above the card, like Android's recents.
        BasicText(
            r.tile.label,
            Modifier.width(560.px).padding(bottom = 16.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(26, FontWeight.SemiBold, Color.White.copy(alpha = if (focused) 1f else 0.65f)),
        )
        Box(
            Modifier
                .size(560.px, 315.px)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .onKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    if (n.action != NativeKeyEvent.ACTION_DOWN) return@onKeyEvent false
                    when (n.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER -> { onOpen(); true }
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                            scope.launch {
                                fly.animateTo(1f, tween(260))
                                onClose()
                                // Still here (only App info could be opened): bring the card back.
                                delay(400)
                                fly.snapTo(0f)
                            }
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_UP -> false
                        else -> false
                    }
                }
                .focusable()
                .clip(RoundedCornerShape(28.px))
                .background(c.track)
                .then(if (focused) Modifier.border(4.px, Color.White, RoundedCornerShape(28.px)) else Modifier),
        ) {
            val img = r.tile.app.card ?: r.tile.app.banner ?: r.tile.app.icon
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (stopping) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }
        Spacer(Modifier.height(18.px))
        BasicText(
            when {
                stopping -> stringResource(R.string.tasks_stopping)
                failed -> stringResource(R.string.tasks_failed)
                mins < 1 -> stringResource(R.string.tasks_used_now)
                mins < 60 -> stringResource(R.string.tasks_used_min, mins)
                else -> stringResource(R.string.tasks_used_h, mins / 60)
            },
            style = hstyle(19, FontWeight.Medium, if (failed) Color(0xFFF2A93B) else Color.White.copy(alpha = 0.55f)),
        )
        // Close hint under the focused card.
        Spacer(Modifier.height(10.px))
        Row(
            Modifier.graphicsLayer { alpha = if (focused) 1f else 0f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaloIcon(Icons.close, 20.px, Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.width(8.px))
            BasicText(stringResource(R.string.tasks_down_to_close), style = hstyle(18, FontWeight.SemiBold, Color.White.copy(alpha = 0.8f)))
        }
    }
}
