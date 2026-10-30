package app.halo.launcher.data

import androidx.compose.ui.graphics.Color

/** Default look for well-known apps, matching the Halo design board. */
data class KnownApp(val glyph: Glyph, val tint: Color, val label: String? = null)

/** What goes in the middle of a Halo card (see [AppCards]). */
enum class CardLogo {
    /** The wordmark keyed out of the app's banner; else the icon's mark + the app name. */
    Auto,
    /** Logo alone: the banner wordmark, else the icon's mark without a name (for famous marks). */
    Mark,
    /** Always the icon's mark (or the icon in a glossy bubble) + the app name. For weak banners. */
    Lockup,
    /** Only the app name as a wordmark. */
    Text,
}

/**
 * Curated brand of an app for its Halo card.
 * [top] = light top-left end of the background gradient, [bottom] = deep bottom-right end,
 * [accent] = optional brand glow in the bottom-right corner, [name] = wordmark text,
 * [text] = wordmark colour (default white, dark on [light] cards), [light] = pearl-white card.
 */
data class Brand(
    val top: Color,
    val bottom: Color,
    val accent: Color? = null,
    val logo: CardLogo = CardLogo.Auto,
    val name: String? = null,
    val text: Color? = null,
    val light: Boolean = false,
)

object KnownApps {
    val Indigo = Color(0xFF5B6CF0)
    val Plum = Color(0xFF9B5DE5)
    val Rose = Color(0xFFE46A8B)
    val Amber = Color(0xFFE8A33D)
    val Teal = Color(0xFF2BB3A3)
    val Sky = Color(0xFF3BA7E0)
    val Sage = Color(0xFF6FA77A)
    val Slate = Color(0xFF5E6B82)
    val Graphite = Color(0xFF3A3F4B)

    /** Swatches in the "Glass tint" picker. `null` = clear glass. */
    val swatches: List<Pair<String, Color?>> = listOf(
        "Indigo" to Indigo, "Plum" to Plum, "Rose" to Rose, "Amber" to Amber, "Teal" to Teal,
        "Sky" to Sky, "Sage" to Sage, "Slate" to Slate, "Graphite" to Graphite, "Clear glass" to null,
    )

    /** Order also decides the default favourites on first start. */
    private val byPackage: List<Pair<String, KnownApp>> = listOf(
        "com.google.android.youtube.tv" to KnownApp(Glyph.play, Indigo, "YouTube"),
        "com.netflix.ninja" to KnownApp(Glyph.film, Rose, "Netflix"),
        "com.disney.disneyplus" to KnownApp(Glyph.spark, Plum, "Disney+"),
        "com.amazon.amazonvideo.livingroom" to KnownApp(Glyph.tv, Teal, "Prime Video"),
        "com.plexapp.android" to KnownApp(Glyph.stack, Amber, "Plex"),
        "de.swr.ard.avp.mobile.android.amazon" to KnownApp(Glyph.antenna, Sky, "ARD Mediathek"),
        "de.ard.ardmediathek" to KnownApp(Glyph.antenna, Sky, "ARD Mediathek"),
        "com.zdf.android.mediathek" to KnownApp(Glyph.news, Sage, "ZDFmediathek"),
        "com.spotify.tv.android" to KnownApp(Glyph.wave, Graphite, "Spotify"),
        "org.xbmc.kodi" to KnownApp(Glyph.box, Slate, "Kodi"),
        "org.videolan.vlc" to KnownApp(Glyph.clap, Plum, "VLC"),
        "com.google.android.tvfiles" to KnownApp(Glyph.folder, Slate, "Files"),
        "com.android.documentsui" to KnownApp(Glyph.folder, Slate, "Files"),
        "com.android.tv.settings" to KnownApp(Glyph.sliders, Graphite, "Settings"),
        "com.google.android.youtube.tvmusic" to KnownApp(Glyph.music, Rose, "YouTube Music"),
        "com.google.android.youtube.tvkids" to KnownApp(Glyph.heart, Amber, "YouTube Kids"),
        "com.apple.atve.androidtv.appletv" to KnownApp(Glyph.tv, Graphite, "Apple TV"),
        "com.wbd.stream" to KnownApp(Glyph.spark, Indigo, "Max"),
        "tv.twitch.android.app" to KnownApp(Glyph.pad, Plum, "Twitch"),
        "com.google.stadia.androidtv" to KnownApp(Glyph.pad, Rose),
        "com.valvesoftware.steamlink" to KnownApp(Glyph.pad, Sky, "Steam Link"),
        "com.google.android.apps.photos" to KnownApp(Glyph.photo, Sky, "Photos"),
        "com.android.chrome" to KnownApp(Glyph.globe, Sky),
        "com.google.android.tv.remote.service" to KnownApp(Glyph.sliders, Graphite),
        "de.joyn.android" to KnownApp(Glyph.play, Amber, "Joyn"),
        "de.rtl.plus" to KnownApp(Glyph.play, Rose, "RTL+"),
        "com.dazn" to KnownApp(Glyph.play, Graphite, "DAZN"),
        "de.telekom.magentatv" to KnownApp(Glyph.tv, Rose, "MagentaTV"),
        "com.waipu.tv" to KnownApp(Glyph.tv, Sky, "waipu.tv"),
        "tv.zattoo.android" to KnownApp(Glyph.tv, Amber, "Zattoo"),
        "com.zattoo.player" to KnownApp(Glyph.tv, Amber, "Zattoo"),
        "com.jellyfin.androidtv" to KnownApp(Glyph.stack, Plum, "Jellyfin"),
        "com.spocky.projengmenu" to KnownApp(Glyph.home, Slate),
        "me.efesser.flauncher" to KnownApp(Glyph.home, Slate),
    )
    private val map = byPackage.toMap()

    fun lookup(packageName: String): KnownApp? = map[packageName]

    /** Curated card brand for [packageName], or null (then the brand comes from the app's banner/icon). */
    fun brand(packageName: String): Brand? = brands[packageName]

    private fun dark(accent: Long, logo: CardLogo = CardLogo.Auto, name: String? = null) =
        Brand(Color(0xFF2A2B2E), Color(0xFF060607), Color(accent), logo, name)

    private fun grad(top: Long, bottom: Long, accent: Long? = null, logo: CardLogo = CardLogo.Auto, name: String? = null) =
        Brand(Color(top), Color(bottom), accent?.let { Color(it) }, logo, name)

    private val pearl = Brand(Color(0xFFFFFFFF), Color(0xFFDCE1E8), light = true, text = Color(0xFF1C1C1E))

    private val graphite = grad(0xFF4A5160, 0xFF16191F, 0xFF7F9CFF)

    /** Brand colours for Halo cards. Sideloaded apps with weak banners use [CardLogo.Lockup]. */
    private val brands: Map<String, Brand> = mapOf(
        // Streaming
        "com.netflix.ninja" to dark(0xFFE50914, CardLogo.Mark, "Netflix"),
        "com.netflix.mediaclient" to dark(0xFFE50914, CardLogo.Mark, "Netflix"),
        "com.disney.disneyplus" to grad(0xFF1D4BB8, 0xFF061030, 0xFF22C4F0, CardLogo.Mark, "Disney+"),
        "com.amazon.amazonvideo.livingroom" to grad(0xFF26364A, 0xFF0A1018, 0xFF00A8E1, CardLogo.Mark, "Prime Video"),
        "com.amazon.avod.thirdpartyclient" to grad(0xFF26364A, 0xFF0A1018, 0xFF00A8E1, CardLogo.Mark, "Prime Video"),
        "com.google.android.youtube.tv" to dark(0xFFFF0033, name = "YouTube"),
        "com.google.android.youtube.tvmusic" to dark(0xFFFF0033, name = "YouTube Music"),
        "com.google.android.youtube.tvkids" to grad(0xFFFF5A4F, 0xFFC0151C, name = "YouTube Kids"),
        "org.smarttube.stable" to dark(0xFFE53935, CardLogo.Lockup, "SmartTube"),
        "org.smarttube.beta" to dark(0xFFE53935, CardLogo.Lockup, "SmartTube"),
        "com.liskovsoft.smarttubetv" to dark(0xFFE53935, CardLogo.Lockup, "SmartTube"),
        "com.liskovsoft.smarttubetv.beta" to dark(0xFFE53935, CardLogo.Lockup, "SmartTube"),
        "com.spotify.tv.android" to dark(0xFF1DB954, name = "Spotify"),
        "com.crunchyroll.crunchyroid" to grad(0xFFFF8A3D, 0xFFD2440C, 0xFFFFB37A, name = "Crunchyroll"),
        "tv.twitch.android.app" to grad(0xFF9B5CFF, 0xFF3D1694, 0xFFC9A6FF, name = "Twitch"),
        "tv.twitch.android.viewer" to grad(0xFF9B5CFF, 0xFF3D1694, 0xFFC9A6FF, name = "Twitch"),
        "com.plexapp.android" to grad(0xFF30353D, 0xFF0D0F12, 0xFFE5A00D, name = "Plex"),
        "org.xbmc.kodi" to grad(0xFF2BB8EA, 0xFF0A3F66, 0xFF7FE1FF, name = "Kodi"),
        "org.videolan.vlc" to grad(0xFFFF9A1F, 0xFFC04A00, 0xFFFFC46B, name = "VLC"),
        "org.jellyfin.androidtv" to grad(0xFF8E4BD6, 0xFF13407F, 0xFF00A4DC, name = "Jellyfin"),
        "com.jellyfin.androidtv" to grad(0xFF8E4BD6, 0xFF13407F, 0xFF00A4DC, name = "Jellyfin"),
        "com.wbd.stream" to grad(0xFF1F4BFF, 0xFF020B3A, 0xFF6FA0FF, CardLogo.Mark, "Max"),
        "com.hbo.hbonow" to grad(0xFF1F4BFF, 0xFF020B3A, 0xFF6FA0FF, CardLogo.Mark, "Max"),
        "com.apple.atve.androidtv.appletv" to grad(0xFF3A3A3D, 0xFF0B0B0C, 0xFF8E8E93, CardLogo.Mark, "Apple TV"),
        "com.apple.android.music" to grad(0xFFFF4D63, 0xFFA3001B, name = "Apple Music"),
        "com.cbs.ott" to grad(0xFF2F7BFF, 0xFF001A66, 0xFF7FB2FF, name = "Paramount+"),
        "de.joyn.android" to dark(0xFFFF3D7F, name = "Joyn"),
        "de.prosiebensat1digital.seventv" to dark(0xFFFF3D7F, name = "Joyn"),
        "de.swr.ard.avp.mobile.android.amazon" to grad(0xFF2A6FD6, 0xFF04234F, 0xFF5FB4FF, name = "ARD Mediathek"),
        "de.ard.ardmediathek" to grad(0xFF2A6FD6, 0xFF04234F, 0xFF5FB4FF, name = "ARD Mediathek"),
        "de.swr.avp.ard.tv" to grad(0xFF2A6FD6, 0xFF04234F, 0xFF5FB4FF, name = "ARD Mediathek"),
        "com.zdf.android.mediathek" to grad(0xFFFF8A2A, 0xFFB83F00, name = "ZDFmediathek"),
        "de.rtl.plus" to dark(0xFFFF3B30, name = "RTL+"),
        "de.cbc.vp2gen.rtlplus" to dark(0xFFFF3B30, name = "RTL+"),
        "de.sky.online" to grad(0xFF3A2470, 0xFF0B0619, 0xFFFF2BAE, name = "WOW"),
        "com.dazn" to dark(0xFFF7FF1A, CardLogo.Mark, "DAZN"),
        "com.waipu.tv" to grad(0xFF3B2E8F, 0xFF120E36, 0xFFE4007C, name = "waipu.tv"),
        "de.exaring.waipu" to grad(0xFF3B2E8F, 0xFF120E36, 0xFFE4007C, name = "waipu.tv"),
        "de.telekom.magentatv" to grad(0xFFF0208A, 0xFF7A003F, name = "MagentaTV"),
        "de.telekom.magentatv.tv" to grad(0xFFF0208A, 0xFF7A003F, name = "MagentaTV"),
        "tv.zattoo.android" to grad(0xFFFF8A2E, 0xFFB5440A, name = "Zattoo"),
        "com.zattoo.player" to grad(0xFFFF8A2E, 0xFFB5440A, name = "Zattoo"),
        "com.valvesoftware.steamlink" to grad(0xFF2A3F5A, 0xFF0A1018, 0xFF66C0F4, name = "Steam Link"),
        // Sideloaded streaming apps (weak or missing banners: designed lockup cards)
        "com.streamflixreborn.streamflix" to grad(0xFF4A1018, 0xFF0E0305, 0xFFE5323B, CardLogo.Lockup, "StreamFlix"),
        "com.tanasi.streamflix" to grad(0xFF4A1018, 0xFF0E0305, 0xFFE5323B, CardLogo.Lockup, "StreamFlix"),
        "com.zstream.android" to grad(0xFF2F6FE0, 0xFF0C2160, 0xFF6BD3FF, CardLogo.Lockup, "ZStream"),
        "com.shubh.anililitv" to grad(0xFFFF6A98, 0xFF8E1A4F, 0xFFFFB3CF, CardLogo.Lockup, "AniLili"),
        "com.app.mlounge" to grad(0xFF7347EA, 0xFF1E0F52, 0xFFB79CFF, CardLogo.Lockup, "MLounge"),
        // Tools & stores
        "cm.aptoidetv.pt" to grad(0xFFFF7457, 0xFFC0301A, name = "Aptoide TV", logo = CardLogo.Lockup),
        "cm.aptoide.pt" to grad(0xFFFF7457, 0xFFC0301A, name = "Aptoide", logo = CardLogo.Lockup),
        "com.esaba.downloader" to grad(0xFFF59A2B, 0xFFB45300, name = "Downloader", logo = CardLogo.Lockup),
        "net.mullvad.mullvadvpn" to grad(0xFF2C5684, 0xFF0F223D, 0xFFFFD524, CardLogo.Lockup, "Mullvad VPN"),
        "com.cybercat.adbappcontrol.tv" to grad(0xFF3A4C68, 0xFF111924, 0xFF4FC3F7, CardLogo.Lockup, "ADB AppControl"),
        "com.hisense.odinbrowser" to grad(0xFF1BA9E8, 0xFF004C7A, 0xFF8BE0FF, CardLogo.Lockup, "Browser"),
        "com.android.vending" to pearl.copy(name = "Play Store", logo = CardLogo.Lockup),
        "com.google.android.play.games" to grad(0xFF1DA061, 0xFF00452B, 0xFF7CF0B4, CardLogo.Lockup, "Play Games"),
        "com.android.chrome" to pearl.copy(name = "Chrome", logo = CardLogo.Lockup),
        "com.google.android.apps.photos" to pearl.copy(name = "Photos", logo = CardLogo.Lockup),
        "com.google.android.tvfiles" to grad(0xFF4C74E6, 0xFF162A70, 0xFF8FB0FF, CardLogo.Lockup, "Files"),
        "com.android.documentsui" to grad(0xFF4C74E6, 0xFF162A70, 0xFF8FB0FF, CardLogo.Lockup, "Files"),
        "com.android.tv.settings" to graphite.copy(logo = CardLogo.Lockup, name = "Settings"),
        "com.android.settings" to graphite.copy(logo = CardLogo.Lockup, name = "Settings"),
        "com.google.android.apps.tv.launcherx" to graphite.copy(logo = CardLogo.Lockup, name = "Google TV"),
    )

    fun rank(packageName: String): Int =
        byPackage.indexOfFirst { it.first == packageName }.let { if (it < 0) Int.MAX_VALUE else it }

    /** Glyph guess for unknown apps, from words in the label. */
    fun guessGlyph(label: String): Glyph {
        val l = label.lowercase()
        return when {
            listOf("music", "radio", "audio", "podcast", "sound").any { it in l } -> Glyph.music
            listOf("tv", "live").any { it in l } -> Glyph.tv
            listOf("game", "play store games", "steam", "xbox").any { it in l } -> Glyph.pad
            listOf("photo", "gallery", "image").any { it in l } -> Glyph.photo
            listOf("file", "explorer").any { it in l } -> Glyph.folder
            listOf("browser", "web", "chrome").any { it in l } -> Glyph.globe
            listOf("setting", "tool", "adb", "manager").any { it in l } -> Glyph.sliders
            listOf("news", "mediathek").any { it in l } -> Glyph.news
            listOf("home", "launcher").any { it in l } -> Glyph.home
            listOf("video", "movie", "film", "cinema", "player").any { it in l } -> Glyph.film
            else -> Glyph.spark
        }
    }

    fun toHex(c: Color?): String = if (c == null) "clear" else String.format(
        "#%02X%02X%02X", (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()
    )

    fun parseTint(s: String?): Pair<Boolean, Color?> {
        if (s == null) return false to null
        if (s == "clear") return true to null
        return runCatching { true to Color(android.graphics.Color.parseColor(s)) }.getOrDefault(false to null)
    }
}
