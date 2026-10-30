package app.halo.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.R
import app.halo.launcher.data.AppEntry
import app.halo.launcher.data.Avatars
import app.halo.launcher.data.Icons
import app.halo.launcher.data.Profile
import app.halo.launcher.data.ThemeMode
import app.halo.launcher.data.Tile
import app.halo.launcher.data.TopRow
import app.halo.launcher.media.NowPlaying
import app.halo.launcher.weather.Weather
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Things only the Activity can do. */
interface HaloActions {
    fun launch(app: AppEntry, bounds: Rect)
    fun openSettings(action: String)
    fun openPackage(packageName: String)
    /** Opens the system "Uninstall this app?" dialog. */
    fun uninstall(packageName: String)
    /** Changes Halo's interface language ("system", "en" or "de"). */
    fun setLanguage(code: String)
    /** Brings Halo's own screen to the front (the assistant overlay calls this for Halo screens). */
    fun showHalo() = Unit
}

/** What the backdrop should react to (focused tile colour, position, lift). */
@Stable
class BackdropControl {
    var accent by mutableStateOf<Color?>(null)
    var parallax by mutableFloatStateOf(0f)
    var lift by mutableFloatStateOf(0f)
    /** Name of the focused tile, shown large above the favourites. */
    var label by mutableStateOf<String?>(null)
}

private enum class Zone { Top, Hero, Favourites, Apps }

/** Ticks once a minute, aligned to the wall clock (only HH:mm is shown). */
@Composable
fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L + 5)
        }
    }
    return now
}

/**
 * Halo home: top bar (search/Ask Halo, Play Store, background, arrange, theme,
 * system settings, profile), clock, a mini player that only appears while
 * something plays, weather, a configurable first row and all other apps.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    topRow: List<Tile>,
    topRowMode: TopRow,
    apps: List<Tile>,
    ready: Boolean,
    idle: () -> Boolean,
    profile: Profile,
    themeMode: ThemeMode,
    clock24h: Boolean,
    showWeather: Boolean,
    nowPlaying: NowPlaying?,
    mediaAccess: Boolean,
    weather: Weather?,
    backdrop: BackdropControl,
    homeSignal: Int,
    onLaunch: (Tile, Rect) -> Unit,
    onArrange: (String?) -> Unit,
    onQuickSettings: () -> Unit,
    onAppearance: () -> Unit,
    onProfiles: () -> Unit,
    onSearch: () -> Unit,
    onVoice: () -> Unit,
    onPlayStore: () -> Unit,
    onBackgrounds: () -> Unit,
    onWeather: () -> Unit,
    onCycleTopRow: () -> Unit,
    onToggleTheme: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenMedia: () -> Unit,
) {
    val c = LocalHalo.current.colors
    var zone by remember { mutableStateOf(Zone.Favourites) }
    val firstTop = remember { FocusRequester() }
    val firstApp = remember { FocusRequester() }
    val appsState = rememberLazyListState()

    // Initial focus, and "Home" pressed again → back to the first tile.
    LaunchedEffect(ready, homeSignal) {
        if (!ready) return@LaunchedEffect
        delay(50)
        appsState.scrollToItem(0)
        runCatching { (if (topRow.isNotEmpty()) firstTop else firstApp).requestFocus() }
    }

    val inApps = zone == Zone.Apps
    LaunchedEffect(inApps) { backdrop.lift = if (inApps) 1f else 0f }
    val shift by animateFloatAsState(if (inApps) -170f else 0f, Motion.gentle(), label = "shift")
    val heroAlpha by animateFloatAsState(if (inApps) 0.4f else 1f, tween(400), label = "hero")
    val favAlpha by animateFloatAsState(if (inApps) 0.6f else 1f, tween(400), label = "favA")
    val appsAlpha by animateFloatAsState(if (inApps) 1f else 0.6f, tween(400), label = "appsA")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = shift * Ui.k * density }
            .padding(top = 48.px),
    ) {
        // ---- Top bar ---------------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.px)
                .padding(horizontal = 96.px)
                .graphicsLayer { alpha = heroAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
                .onFocusChanged { if (it.hasFocus) zone = Zone.Top },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchPill(onSearch, onVoice)
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .glass(RoundedCornerShape(36.px))
                    .padding(4.px)
                    .focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(4.px),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopButton(Icons.store, stringResource(R.string.home_play_store), onPlayStore)
                TopButton(Icons.wallpaper, stringResource(R.string.home_background), onBackgrounds)
                TopButton(Icons.arrange, stringResource(R.string.home_arrange), { onArrange(null) })
                TopButton(
                    when (themeMode) {
                        ThemeMode.Auto -> Icons.auto
                        else -> if (c.isDark) Icons.sun else Icons.moon
                    },
                    stringResource(R.string.home_theme, stringResource(themeRes(themeMode))),
                    onToggleTheme,
                )
                TopButton(Icons.sliders, stringResource(R.string.home_launcher_settings), onAppearance)
                TopButton(Icons.settings, stringResource(R.string.home_tv_settings), onQuickSettings)
                ProfileChip(profile, onProfiles)
            }
        }

        Spacer(Modifier.weight(1f))

        // ---- Hero: clock + widgets -------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 96.px)
                .graphicsLayer { alpha = heroAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
                .onFocusChanged { if (it.hasFocus) zone = Zone.Hero }
                .focusGroup(),
            verticalAlignment = Alignment.Bottom,
        ) {
            // The widgets are exactly as tall as the clock block, so the hero reads as one line.
            var clockH by remember { mutableIntStateOf(0) }
            ClockBlock(clock24h, profileName(profile), Modifier.onSizeChanged { clockH = it.height })
            val cardH = if (clockH > 0) with(LocalDensity.current) { clockH.toDp() } else 250.px
            Spacer(Modifier.weight(1f))
            // The player only appears while something is playing (or paused).
            AnimatedVisibility(
                visible = nowPlaying != null,
                enter = fadeIn(tween(400)) + expandHorizontally(Motion.gentle(), expandFrom = Alignment.End),
                exit = fadeOut(tween(250)) + shrinkHorizontally(tween(350), shrinkTowards = Alignment.End),
            ) {
                // Keep showing the last track while the card animates away.
                val last = remember { arrayOf<NowPlaying?>(null) }
                if (nowPlaying != null) last[0] = nowPlaying
                Row {
                    NowPlayingCard(nowPlaying ?: last[0], mediaAccess, cardH, onPlayPause, onNext, onPrevious, onOpenMedia)
                    if (showWeather) Spacer(Modifier.width(24.px))
                }
            }
            if (showWeather) WeatherCard(weather, cardH, onWeather)
        }

        Spacer(Modifier.height(32.px))

        // ---- First row (favourites / recently used / most used) ---------------
        if (topRow.isNotEmpty()) {
            Row(
                Modifier
                    .padding(start = 84.px)
                    .graphicsLayer { alpha = favAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha },
            ) {
                RowModeChip(topRowMode, onCycleTopRow)
            }
            Spacer(Modifier.height(14.px))
            Row(
                modifier = Modifier
                    .padding(horizontal = 96.px)
                    .graphicsLayer { alpha = favAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
                    .focusRestorer()
                    .focusGroup()
                    .onFocusChanged { if (it.hasFocus) zone = Zone.Favourites },
                horizontalArrangement = Arrangement.spacedBy(32.px),
            ) {
                topRow.forEachIndexed { i, t ->
                    androidx.compose.runtime.key(t.app.key) {
                        CaptionTile(t, 320, 180) { mod ->
                            GlassTile(
                                tile = t,
                                width = 320,
                                height = 180,
                                modifier = mod,
                                ring = true,
                                focusRequester = if (i == 0) firstTop else null,
                                edges = Edges(left = i == 0, right = i == topRow.lastIndex, down = apps.isEmpty()),
                                idle = idle,
                                onFocus = {
                                    backdrop.label = t.label
                                    backdrop.parallax = if (topRow.size > 1) (i / (topRow.size - 1f)) * 2f - 1f else 0f
                                },
                                onClick = { b -> onLaunch(t, b) },
                                onLongClick = { onArrange(t.app.key) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.px))
        }

        // ---- Apps --------------------------------------------------------------
        Label(stringResource(R.string.apps), Modifier.padding(start = 96.px).graphicsLayer { alpha = appsAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha })
        Spacer(Modifier.height(14.px))
        LazyRow(
            state = appsState,
            modifier = Modifier
                .fillMaxWidth()
                .height(164.px)
                .bleed(40.px)
                .graphicsLayer { alpha = appsAlpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
                .focusRestorer()
                .onFocusChanged { if (it.hasFocus) zone = Zone.Apps },
            contentPadding = PaddingValues(start = 96.px, end = 96.px, top = 40.px, bottom = 40.px),
            horizontalArrangement = Arrangement.spacedBy(28.px),
        ) {
            itemsIndexed(apps, key = { _, t -> t.app.key }) { i, t ->
                CaptionTile(t, 220, 124) { mod ->
                    GlassTile(
                        tile = t,
                        width = 220,
                        height = 124,
                        modifier = mod,
                        ring = true,
                        focusRequester = if (i == 0) firstApp else null,
                        edges = Edges(left = i == 0, right = i == apps.lastIndex, down = true),
                        idle = idle,
                        onFocus = {
                            backdrop.label = t.label
                            backdrop.parallax = if (apps.size > 1) (i / (apps.size - 1f)) * 2f - 1f else 0f
                        },
                        onClick = { b -> onLaunch(t, b) },
                        onLongClick = { onArrange(t.app.key) },
                    )
                }
            }
        }
    }
}

/** Tile + its name, which fades in under the tile only while it's focused (like Apple TV). */
@Composable
private fun CaptionTile(t: Tile, w: Int, h: Int, tile: @Composable (Modifier) -> Unit) {
    val c = LocalHalo.current.colors
    var focused by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "cap")
    Column(Modifier.width(w.px), horizontalAlignment = Alignment.CenterHorizontally) {
        tile(Modifier.onFocusChanged { focused = it.hasFocus })
        val ts = if (h > 150) 22 else 18
        val pad = if (h > 150) 18 else 14
        // Fixed slot; the text is only composed while visible (no hidden text per tile).
        Box(Modifier.height((pad + ts + 8).px), contentAlignment = Alignment.BottomCenter) {
            if (focused || a > 0f) {
                BasicText(
                    t.label,
                    modifier = Modifier.graphicsLayer {
                        alpha = a
                        translationY = (1f - a) * -6f * Ui.k * density
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = hstyle(ts, FontWeight.SemiBold, c.text, tracking = -0.01f)
                        .copy(textAlign = TextAlign.Center, lineHeight = (ts + 6).tsp, shadow = textShadow),
                )
            }
        }
    }
}

/** "Favourites ⌄" – OK switches what the first row shows. */
@Composable
private fun RowModeChip(mode: TopRow, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    Row(
        Modifier
            .control(st, onClick, focusScale = 1.05f)
            .height(40.px)
            .clip(RoundedCornerShape(20.px))
            .background(if (st.focused) c.cta else Color.Transparent)
            .then(if (st.focused) Modifier.aeroGloss() else Modifier)
            .padding(horizontal = 12.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.px),
    ) {
        val fg = if (st.focused) c.ctaText else c.sub
        BasicText(stringResource(topRowTitleRes(mode)), maxLines = 1, style = hstyle(18, FontWeight.SemiBold, fg, tracking = 0.04f))
        HaloIcon(Icons.chevronDown, 16.px, fg)
        if (st.focused) {
            BasicText(stringResource(R.string.home_ok_to_switch), maxLines = 1, style = hstyle(15, FontWeight.Medium, fg.copy(alpha = 0.7f)))
        }
    }
}

/** Search pill with a microphone: "Search or ask Halo". */
@Composable
private fun SearchPill(onClick: () -> Unit, onVoice: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    Row(
        modifier = Modifier.focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.px),
    ) {
        Row(
            modifier = Modifier
                .control(st, onClick, focusScale = 1.05f)
                .height(60.px)
                .glass(RoundedCornerShape(30.px), veil = if (st.focused) c.cta.copy(alpha = 0.9f) else null)
                .padding(start = 20.px, end = 26.px),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.px),
        ) {
            val fg = if (st.focused) c.ctaText else c.sub
            HaloIcon(Icons.search, 22.px, fg)
            BasicText(
                stringResource(R.string.home_search),
                Modifier.widthIn(max = 420.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(20, FontWeight.Medium, fg),
            )
        }
        RoundButton(
            Icons.mic, onVoice, size = 60, iconSize = 24,
            background = Brush.verticalGradient(listOf(Color(0xFF4FC8FF), Color(0xFF1E88E5))),
        )
    }
}

/** Icon button in the top bar; its name slides out while focused. */
@Composable
private fun TopButton(icon: app.halo.launcher.data.IconSpec, label: String, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    Row(
        modifier = Modifier
            .control(st, onClick, focusScale = 1.06f)
            .height(56.px)
            .clip(RoundedCornerShape(28.px))
            .background(if (st.focused) c.cta else Color.Transparent)
            .then(if (st.focused) Modifier.aeroGloss() else Modifier)
            .padding(horizontal = 17.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (st.focused) c.ctaText else c.text
        HaloIcon(icon, 22.px, fg)
        AnimatedVisibility(
            visible = st.focused,
            enter = fadeIn(tween(200)) + expandHorizontally(Motion.gentle()),
            exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180)),
        ) {
            BasicText(
                label,
                Modifier.padding(start = 10.px).widthIn(max = 300.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(18, FontWeight.SemiBold, fg),
            )
        }
    }
}

/** Avatar + name of the active profile; opens "Who's watching?". */
@Composable
private fun ProfileChip(profile: Profile, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    Row(
        modifier = Modifier
            .control(st, onClick, focusScale = 1.06f)
            .height(56.px)
            .clip(RoundedCornerShape(28.px))
            .background(if (st.focused) c.cta else Color.Transparent)
            .then(if (st.focused) Modifier.aeroGloss() else Modifier)
            .padding(end = 22.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.px),
    ) {
        Avatar(profile, 56)
        BasicText(
            profileName(profile),
            Modifier.widthIn(max = 220.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(19, FontWeight.SemiBold, if (st.focused) c.ctaText else c.text),
        )
    }
}

/** Profile picture: a Fluent 3D avatar on the profile colour, or the initial. */
@Composable
fun Avatar(profile: Profile, size: Int, modifier: Modifier = Modifier) {
    val (a, b) = profile.colors
    val pic = Avatars.of(profile.avatar)
    Box(
        modifier = modifier
            .size(size.px)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(a, b)))
            .drawBehind { drawSheen(Color.White.copy(alpha = 0.35f)) },
        contentAlignment = Alignment.Center,
    ) {
        if (pic != null) {
            Image(
                painterResource(pic.res),
                contentDescription = pic.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size((size * 0.78f).px),
            )
        } else {
            BasicText(profile.initial, style = hstyle((size * 0.36f).toInt(), FontWeight.Bold, Color.White))
        }
    }
}

@Composable
private fun ClockBlock(clock24h: Boolean, name: String, modifier: Modifier = Modifier) {
    val c = LocalHalo.current.colors
    val now = rememberNow()
    // Dates in the interface language, not the TV's ("Mittwoch, 29. Oktober" / "Wednesday 29 October").
    val locale = appLocale()
    val datePattern = stringResource(R.string.date_long_pattern)
    val timeFmt = remember(clock24h, locale) { DateTimeFormatter.ofPattern(if (clock24h) "HH:mm" else "h:mm", locale) }
    val dateFmt = remember(locale, datePattern) { DateTimeFormatter.ofPattern(datePattern, locale) }
    val time = now.format(timeFmt)
    val date = now.format(dateFmt)
    val greeting = stringResource(
        when (now.hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        },
        name,
    )
    Column(modifier) {
        BasicText(
            greeting.uppercase(locale),
            modifier = Modifier.padding(start = 8.px, bottom = 6.px).widthIn(max = 1000.px),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = hstyle(20, FontWeight.Bold, c.sub, tracking = 0.12f).copy(shadow = textShadow),
        )
        RollingText(
            time,
            hstyle(184, FontWeight.Light, c.text, tracking = -0.05f).copy(
                lineHeight = (184 * 0.9f).tsp,
                fontFeatureSettings = "tnum",
                shadow = textShadow,
            ),
        )
        BasicText(
            date,
            modifier = Modifier.padding(start = 8.px, top = 10.px),
            style = hstyle(28, FontWeight.Medium, c.sub).copy(shadow = textShadow),
        )
    }
}

/** Soft shadow so big text stays readable on any photo. */
private val textShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 12f)

@Composable
private fun NowPlayingCard(
    np: NowPlaying?,
    access: Boolean,
    height: androidx.compose.ui.unit.Dp,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val has = np != null
    // Progress is read only while drawing the bar → no recomposition per tick.
    val tick = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np?.playing) {
        while (np?.playing == true) {
            tick.longValue = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val shape = RoundedCornerShape(36.px)
    Row(
        modifier = Modifier
            .width(580.px)
            .height(height)
            .glass(shape)
            .padding(24.px)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(24.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .shadow(16.px, RoundedCornerShape(24.px))
                .clip(RoundedCornerShape(24.px))
                .background(Brush.linearGradient(listOf(Color(0xFF3FB8F0), Color(0xFF7ED7A7)))),
        ) {
            Crossfade(targetState = np?.art, animationSpec = tween(600), label = "art") { art ->
                if (art != null) {
                    Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        HaloIcon(Icons.playFilled, 56.px, Color.White.copy(alpha = 0.85f))
                    }
                }
            }
            Box(Modifier.fillMaxSize().aeroGloss(0.5f))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            val locale = appLocale()
            Label(
                (if (has) stringResource(R.string.now_playing_app, np!!.appLabel) else stringResource(R.string.now_playing)).uppercase(locale),
                size = 15,
                color = c.sub,
            )
            Spacer(Modifier.height(6.px))
            BasicText(
                when {
                    has -> np!!.title.ifBlank { np.appLabel }
                    access -> stringResource(R.string.nothing_playing)
                    else -> stringResource(R.string.media_access_off)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(26, FontWeight.SemiBold, c.text, tracking = -0.01f),
            )
            BasicText(
                if (has) np!!.artist else "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(19, FontWeight.Medium, c.sub),
            )
            Spacer(Modifier.weight(1f))
            val track = c.track
            val fill = c.text
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.px)
                    .drawBehind {
                        val p = np?.progressAt(tick.longValue) ?: 0f
                        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                        drawRoundRect(track, cornerRadius = r)
                        if (p > 0f) drawRoundRect(fill, size = size.copy(width = size.width * p), cornerRadius = r)
                    }
            )
            Spacer(Modifier.height(10.px))
            Row(horizontalArrangement = Arrangement.spacedBy(6.px)) {
                RoundButton(Icons.prev, onPrevious, size = 48, enabled = has)
                RoundButton(if (np?.playing == true) Icons.pause else Icons.playFilled, onPlayPause, size = 48, iconSize = 24, enabled = has)
                RoundButton(Icons.next, onNext, size = 48, enabled = has)
                RoundButton(Icons.external, onOpen, size = 48) // opens the full player
            }
        }
    }
}

@Composable
private fun WeatherCard(w: Weather?, height: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(36.px)
    Column(
        modifier = Modifier
            .control(st, onClick, focusScale = 1.05f)
            .width(300.px)
            .height(height)
            .glass(shape, strong = st.focused)
            .padding(26.px),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (w?.fromDevice == true) {
                HaloIcon(Icons.pin, 18.px, c.sub)
                Spacer(Modifier.width(6.px))
            }
            BasicText(
                w?.city ?: stringResource(R.string.weather_title),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(18, FontWeight.SemiBold, c.sub),
            )
            HaloIcon(w?.icon ?: Icons.partly, 40.px, c.text, strokeWidth = 1.6f)
        }
        Column {
            BasicText(
                if (w != null) "${w.temperature}°" else "—",
                style = hstyle(64, FontWeight.Light, c.text, tracking = -0.04f).copy(lineHeight = 64.tsp),
            )
            BasicText(
                when {
                    w == null -> stringResource(R.string.weather_finding)
                    w.todayMax == null || w.todayMin == null -> stringResource(w.conditionsRes)
                    else -> stringResource(
                        R.string.weather_summary,
                        stringResource(w.conditionsRes),
                        w.todayMax ?: 0,
                        w.todayMin ?: 0,
                    )
                },
                modifier = Modifier.padding(top = 6.px),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(17, FontWeight.Medium, c.sub),
            )
            if (w != null && w.alerts.isNotEmpty()) {
                Row(Modifier.padding(top = 6.px), verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(Icons.warning, 16.px, alertColor(w.alerts.first().severity))
                    Spacer(Modifier.width(6.px))
                    BasicText(
                        pluralResource(R.plurals.weather_warnings, w.alerts.size, w.alerts.size),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = hstyle(15, FontWeight.Bold, alertColor(w.alerts.first().severity)),
                    )
                }
            } else {
                // Next hours, compact.
                val hours = w?.hourly.orEmpty().drop(1).take(3)
                if (hours.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.px), horizontalArrangement = Arrangement.SpaceBetween) {
                        hours.forEach { h ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(h.time.format(DateTimeFormatter.ofPattern("HH")), style = hstyle(14, FontWeight.SemiBold, c.sub))
                                Spacer(Modifier.width(3.px))
                                HaloIcon(Weather.iconFor(h.code, h.isDay), 18.px, c.text, strokeWidth = 1.8f)
                                Spacer(Modifier.width(2.px))
                                BasicText("${h.temp}°", style = hstyle(14, FontWeight.SemiBold, c.text))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** DWD warning colours: yellow, orange, red, violet. */
fun alertColor(severity: String): Color = when (severity) {
    "extreme" -> Color(0xFFB24BD8)
    "severe" -> Color(0xFFE5484D)
    "moderate" -> Color(0xFFF2994A)
    else -> Color(0xFFF2C94C)
}
