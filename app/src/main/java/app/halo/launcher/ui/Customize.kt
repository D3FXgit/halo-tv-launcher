package app.halo.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.halo.launcher.R
import app.halo.launcher.data.Glyph
import app.halo.launcher.data.Icons
import app.halo.launcher.data.KnownApps
import app.halo.launcher.data.Tile
import app.halo.launcher.data.TileLayout
import app.halo.launcher.data.TileSource
import app.halo.launcher.data.TileStyle
import kotlinx.coroutines.delay

/** "Customize icon": replace any app's banner with your own glass icon. */
@Composable
fun CustomizeScreen(
    tiles: List<Tile>,
    startKey: String,
    styleOf: (String) -> TileStyle?,
    resolve: (Tile, TileStyle?) -> Tile,
    /** Every installed app (unresolved), to borrow artwork from. */
    allApps: List<app.halo.launcher.data.AppEntry> = emptyList(),
    onSave: (String, TileStyle?) -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalHalo.current.colors
    if (tiles.isEmpty()) return
    var index by remember { mutableStateOf(tiles.indexOfFirst { it.app.key == startKey }.coerceAtLeast(0)) }
    val base = tiles[index.coerceIn(0, tiles.lastIndex)]
    val key = base.app.key
    // Working copy, reset whenever another app is selected.
    var draft by remember(key) { mutableStateOf(styleOf(key) ?: TileStyle()) }
    val preview = resolve(base, draft)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize()) {
        // ---- Left: big preview --------------------------------------------------
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 96.px, top = 64.px),
        ) {
            // Hint only (not focusable): BACK returns to wherever the editor was opened from.
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(Icons.chevronLeft, 20.px, c.sub)
                BasicText(stringResource(R.string.customize_back_hint), Modifier.padding(start = 8.px), maxLines = 1, style = hstyle(20, FontWeight.SemiBold, c.sub))
            }
            Spacer(Modifier.height(18.px))
            BasicText(
                stringResource(R.string.customize_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(56, FontWeight.Bold, c.text, tracking = -0.02f),
            )
            BasicText(
                stringResource(R.string.customize_subtitle),
                Modifier.padding(top = 10.px),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = hstyle(24, FontWeight.Medium, c.sub),
            )
            Spacer(Modifier.height(36.px))
            Box(Modifier.padding(start = 132.px)) {
                GlassTile(
                    tile = preview,
                    width = 776,
                    height = 436,
                    showcase = true,
                    focusedScale = 1f,
                    modifier = Modifier.focusPropertiesNone(),
                )
            }
            Spacer(Modifier.height(56.px))
            Label(stringResource(R.string.customize_on_home))
            Spacer(Modifier.height(20.px))
            Row(horizontalArrangement = Arrangement.spacedBy(20.px), verticalAlignment = Alignment.CenterVertically) {
                val n = tiles.size
                listOf(-1, 0, 1).forEach { d ->
                    val t = if (d == 0) preview else tiles[(index + d + n) % n]
                    Box(Modifier.alpha(if (d == 0) 1f else 0.45f)) {
                        GlassTile(
                            tile = t,
                            width = if (d == 0) 344 else 320,
                            height = if (d == 0) 194 else 180,
                            showcase = d == 0,
                            focusedScale = 1f,
                            blur = false,
                            modifier = Modifier.focusPropertiesNone(),
                        )
                    }
                }
            }
        }

        // ---- Right: editor panel ------------------------------------------------
        Column(
            Modifier
                .padding(top = 40.px, bottom = 40.px, end = 40.px)
                .width(620.px)
                .fillMaxHeight()
                .glass(RoundedCornerShape(48.px), strong = true, fillAlpha = 0.92f)
                .padding(horizontal = 40.px, vertical = 40.px),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(stringResource(R.string.customize_editing), size = 17, color = c.sub)
                    BasicText(base.label, Modifier.padding(top = 4.px), maxLines = 1, overflow = TextOverflow.Ellipsis, style = hstyle(36, FontWeight.Bold, c.text))
                }
                RoundButton(Icons.chevronLeft, { index = (index - 1 + tiles.size) % tiles.size }, background = SolidColor(c.pill))
                Spacer(Modifier.width(10.px))
                RoundButton(Icons.chevronRight, { index = (index + 1) % tiles.size }, background = SolidColor(c.pill))
            }
            Spacer(Modifier.height(26.px))

            // Scrollable editor body; the Reset / Save bar stays pinned at the bottom.
            Box(Modifier.weight(1f).fillMaxWidth()) { ScrollPadding(110.px) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 6.px, vertical = 6.px),
            ) {
            Caption(stringResource(R.string.customize_source))
            val sources = listOf(TileSource.Card, TileSource.Logo, TileSource.Glass, TileSource.Banner, TileSource.Icon)
            Segmented(
                options = listOf(
                    stringResource(R.string.customize_source_card),
                    stringResource(R.string.customize_source_logo),
                    stringResource(R.string.customize_source_glass),
                    stringResource(R.string.customize_source_banner),
                    stringResource(R.string.customize_source_icon),
                ),
                selected = sources.indexOf(preview.source),
                onSelect = { i -> draft = draft.copy(source = sources[i].name) },
                enabled = { i ->
                    when (sources[i]) {
                        TileSource.Card -> preview.app.card != null
                        TileSource.Banner -> preview.app.banner != null
                        TileSource.Logo -> preview.app.logo != null
                        else -> true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                focusRequester = firstFocus,
            )
            BasicText(
                stringResource(
                    when (preview.source) {
                        TileSource.Card -> R.string.customize_source_card_hint
                        TileSource.Logo -> R.string.customize_source_logo_hint
                        TileSource.Glass -> R.string.customize_source_glass_hint
                        TileSource.Banner -> R.string.customize_source_banner_hint
                        TileSource.Icon -> R.string.customize_source_icon_hint
                    }
                ),
                Modifier.padding(top = 10.px, start = 4.px),
                style = hstyle(16, FontWeight.Medium, c.sub),
            )
            Spacer(Modifier.height(24.px))

            if (allApps.size > 1) {
                Caption(stringResource(R.string.customize_artwork))
                ArtworkPicker(
                    apps = allApps.filter { it.key != key },
                    own = allApps.firstOrNull { it.key == key },
                    selected = draft.artFrom,
                    onSelect = { k -> draft = draft.copy(artFrom = k) },
                )
                BasicText(
                    stringResource(R.string.customize_artwork_hint),
                    Modifier.padding(top = 10.px),
                    style = hstyle(16, FontWeight.Medium, c.sub),
                )
                Spacer(Modifier.height(24.px))
            }

            val isCard = preview.source == TileSource.Card
            if (preview.source == TileSource.Logo || isCard) {
                // Apple TV style box / Halo card: background colour (+ logo size for the box).
                Caption(stringResource(if (isCard) R.string.customize_card_colour else R.string.customize_box_colour))
                val boxColours: List<Color?> = listOf(
                    null, Color(0xFF1C1C1E), Color(0xFFF2F2F7), Color(0xFFE50914), Color(0xFF113CCF),
                ) + KnownApps.swatches.mapNotNull { it.second } + (
                    if (isCard) listOf(Color(0xFFFF7A1A), Color(0xFF1DB954), Color(0xFFFF4F9A), Color(0xFF0B1E4A)) else emptyList()
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.px)) {
                    boxColours.chunked(7).forEachIndexed { row, rowColours ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.px)) {
                            rowColours.forEachIndexed { col, color ->
                                val auto = color == null
                                Swatch(
                                    color ?: ((if (isCard) base.app.brand else null) ?: base.app.logoBg ?: Color(0xFF2C2C2E)),
                                    selected = if (auto) draft.bg == null else KnownApps.toHex(color) == draft.bg,
                                    enabled = true,
                                ) { draft = draft.copy(bg = if (auto) null else KnownApps.toHex(color)) }
                            }
                        }
                    }
                }
                BasicText(stringResource(R.string.customize_first_swatch), Modifier.padding(top = 10.px), style = hstyle(16, FontWeight.Medium, c.sub))
                Spacer(Modifier.height(24.px))
                if (!isCard) {
                Caption(stringResource(R.string.customize_logo_size))
                val scales = listOf(0.45f, 0.62f, 0.8f)
                Segmented(
                    options = listOf(
                        stringResource(R.string.customize_small),
                        stringResource(R.string.customize_medium),
                        stringResource(R.string.customize_large),
                    ),
                    selected = scales.indexOfFirst { kotlin.math.abs(it - preview.logoScale) < 0.05f }.coerceAtLeast(0),
                    onSelect = { i -> draft = draft.copy(logoScale = scales[i]) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.px))
                }
            } else if (preview.source == TileSource.Glass) {

            // Glyph, tint and layout only exist for the Glass look, so they are only shown for it
            // (instead of greyed-out controls that look broken).
            val glyphsEnabled = true
            val glassOnly = true
            Caption(stringResource(R.string.customize_glyph))
            Column(verticalArrangement = Arrangement.spacedBy(12.px)) {
                Glyph.entries.chunked(6).forEachIndexed { row, glyphs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.px)) {
                        glyphs.forEachIndexed { col, g ->
                            GlyphCell(
                                glyph = g,
                                selected = g == preview.glyph,
                                tint = preview.tint,
                                enabled = glyphsEnabled,
                                focusRequester = null,
                            ) { draft = draft.copy(glyph = g.name, source = TileSource.Glass.name) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.px))

            Caption(stringResource(R.string.customize_glass_tint))
            Row(horizontalArrangement = Arrangement.spacedBy(6.px)) {
                KnownApps.swatches.forEach { (_, color) ->
                    Swatch(color, selected = color == preview.tint, enabled = glassOnly) {
                        draft = draft.copy(tint = KnownApps.toHex(color))
                    }
                }
            }
            Spacer(Modifier.height(24.px))

            Caption(stringResource(R.string.customize_layout))
            val layouts = listOf(TileLayout.Both, TileLayout.Glyph, TileLayout.Name)
            Segmented(
                options = listOf(
                    stringResource(R.string.customize_layout_both),
                    stringResource(R.string.customize_layout_glyph),
                    stringResource(R.string.customize_layout_name),
                ),
                selected = layouts.indexOf(preview.layout),
                onSelect = { i -> draft = draft.copy(layout = layouts[i].name) },
                enabled = { glassOnly },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.px))
            }

            Caption(stringResource(if (isCard) R.string.customize_name_on_card else R.string.customize_name_on_icon))
            HaloTextField(
                value = draft.label ?: preview.label,
                placeholder = base.app.label,
                commitOnChange = true,
            ) { v -> draft = draft.copy(label = v) }
            Spacer(Modifier.height(16.px))
            }
            } }

            Spacer(Modifier.height(20.px))
            Row(horizontalArrangement = Arrangement.spacedBy(14.px)) {
                PillButton(stringResource(R.string.customize_reset), { draft = TileStyle(); onSave(key, null) }, height = 64)
                Box(Modifier.weight(1f)) {
                    PillButton(stringResource(R.string.customize_save), { onSave(key, draft); onBack() }, primary = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Row of the other apps' artwork; OK borrows it, the first chip restores the app's own. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ArtworkPicker(
    apps: List<app.halo.launcher.data.AppEntry>,
    own: app.halo.launcher.data.AppEntry?,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(14.px),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.px, horizontal = 4.px),
    ) {
        item(key = "own") { ArtChip(own, stringResource(R.string.customize_artwork_own), selected == null) { onSelect(null) } }
        items(apps.size, key = { apps[it].key }) { i ->
            val a = apps[i]
            ArtChip(a, a.label, selected == a.key) { onSelect(a.key) }
        }
    }
}

@Composable
private fun ArtChip(app: app.halo.launcher.data.AppEntry?, label: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(16.px)
    Column(Modifier.width(160.px), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .control(st, onClick, focusScale = 1.08f)
                .size(160.px, 90.px)
                .clip(shape)
                .background(c.pill)
                .then(
                    if (st.focused || on) Modifier.border(if (st.focused) 4.px else 3.px, if (st.focused) c.cta else c.text.copy(alpha = 0.7f), shape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            val img = app?.card ?: app?.banner
            if (img != null) {
                androidx.compose.foundation.Image(img, label, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (app?.icon != null) {
                androidx.compose.foundation.Image(app.icon, label, modifier = Modifier.size(56.px))
            }
        }
        BasicText(
            label,
            Modifier.padding(top = 6.px),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = hstyle(15, if (on) FontWeight.Bold else FontWeight.Medium, if (on) c.text else c.sub),
        )
    }
}

@Composable
private fun Caption(text: String) {
    Label(text, Modifier.padding(bottom = 12.px), size = 18)
}

@Composable
private fun GlyphCell(
    glyph: Glyph,
    selected: Boolean,
    tint: Color?,
    enabled: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val shape = RoundedCornerShape(18.px)
    val bg: Brush = when {
        selected && tint != null -> Brush.linearGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.55f)))
        selected -> SolidColor(Color.White.copy(alpha = 0.3f))
        else -> SolidColor(c.pill)
    }
    Box(
        Modifier
            .control(st, onClick, enabled, focusRequester, 1.12f)
            .size(76.px, 64.px)
            .clip(shape)
            .background(bg)
            .border(
                if (st.focused) 3.px else 1.px,
                if (st.focused) c.cta else Color.White.copy(alpha = if (selected) 0.5f else 0.12f),
                shape,
            )
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        HaloIcon(glyph.spec, 28.px, if (selected) Color.White else c.text, strokeWidth = 1.8f)
    }
}

@Composable
private fun Swatch(color: Color?, selected: Boolean, enabled: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    val c = LocalHalo.current.colors
    val st = remember { ControlState() }
    val brush: Brush = color?.let { SolidColor(it) }
        ?: Brush.linearGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f)))
    // Every swatch gets a hairline rim, so dark / grey tints stay visible on the dark panel.
    Box(
        Modifier
            .control(st, onClick, enabled, focusRequester, focusScale = 1.18f)
            .size(46.px)
            .then(
                if (selected || st.focused) Modifier.border(3.px, if (st.focused) c.cta else c.aqua, CircleShape) else Modifier
            )
            .padding(if (selected || st.focused) 5.px else 0.px)
            .clip(CircleShape)
            .background(brush)
            .border(1.5.px, Color.White.copy(alpha = 0.35f), CircleShape)
            .alpha(if (enabled) 1f else 0.4f),
    )
}

private fun Modifier.focusPropertiesNone(): Modifier = this.focusProperties { canFocus = false }
