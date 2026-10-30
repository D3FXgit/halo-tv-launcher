package app.halo.launcher.data

/**
 * Line icons on a 24×24 grid, taken 1:1 from the Halo design file.
 * Every shape is stored as SVG path data so a single renderer can draw them.
 */
class IconSpec(val paths: List<String>, val filled: Boolean = false)

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float): String {
    val iw = w - 2 * rx
    val ih = h - 2 * rx
    return "M${x + rx},$y h$iw a$rx,$rx 0 0 1 $rx,$rx v$ih a$rx,$rx 0 0 1 ${-rx},$rx " +
        "h${-iw} a$rx,$rx 0 0 1 ${-rx},${-rx} v${-ih} a$rx,$rx 0 0 1 $rx,${-rx} z"
}

private fun icon(vararg p: String) = IconSpec(p.toList())
private fun filled(vararg p: String) = IconSpec(p.toList(), filled = true)

/** The 18 tile glyphs a user can pick in "Customize icon". */
enum class Glyph(val spec: IconSpec) {
    play(icon(circle(12f, 12f, 9f), "M10 8.5v7l6-3.5z")),
    film(icon(rect(3f, 4f, 18f, 16f, 2f), "M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4")),
    spark(icon("M11 3l1.9 5.6 5.6 1.9-5.6 1.9L11 18l-1.9-5.6-5.6-1.9 5.6-1.9z", "M19 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z")),
    tv(icon(rect(3f, 6f, 18f, 12f, 2.5f), "M8 21h8M9 2.5l3 3 3-3")),
    stack(icon("M12 3l9 5-9 5-9-5z", "M3 12.5l9 5 9-5", "M3 16.5l9 5 9-5")),
    antenna(icon(circle(12f, 10f, 2f), "M8 6a5.5 5.5 0 0 0 0 8M16 6a5.5 5.5 0 0 1 0 8M5 3.5a9.5 9.5 0 0 0 0 13M19 3.5a9.5 9.5 0 0 1 0 13M12 12v9")),
    news(icon(rect(3f, 4f, 18f, 16f, 2.5f), "M7 9h6M7 12.5h10M7 16h8")),
    wave(icon("M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2")),
    box(icon("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5M12 12v9")),
    clap(icon(rect(3f, 9f, 18f, 11f, 2f), "M3 9l2.5-5h15L18 9M8.5 4L6 9M13.5 4L11 9M18.5 4L16 9")),
    folder(icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")),
    sliders(icon("M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f))),
    pad(icon("M7 8.5h10a4 4 0 0 1 4 4V15a2.5 2.5 0 0 1-4.5 1.5L15 14.5H9l-1.5 2A2.5 2.5 0 0 1 3 15v-2.5a4 4 0 0 1 4-4z", "M8 11v3M6.5 12.5h3")),
    photo(icon(rect(3f, 4f, 18f, 16f, 3f), circle(9f, 10f, 2f), "M21 16l-5-5-9 9")),
    globe(icon(circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18")),
    music(icon("M9 18V6l11-2v12", circle(6.5f, 18f, 2.5f), circle(17.5f, 16f, 2.5f))),
    home(icon("M3 11l9-8 9 8", "M5 9.5V20h14V9.5", "M10 20v-5h4v5")),
    heart(icon("M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7a4.3 4.3 0 0 1 7.5 2.8C19.5 15.4 12 20 12 20z"));

    companion object {
        fun of(name: String?): Glyph? = entries.firstOrNull { it.name == name }
    }
}

/** Interface icons. */
object Icons {
    val search = icon(circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val sun = icon(circle(12f, 12f, 4f), "M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")
    val moon = icon("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val auto = icon(circle(12f, 12f, 8f), "M12 4a8 8 0 0 1 0 16z")
    val arrange = icon(rect(3f, 3f, 7.5f, 7.5f, 2f), rect(13.5f, 3f, 7.5f, 7.5f, 2f), rect(3f, 13.5f, 7.5f, 7.5f, 2f), "M17.25 14v6.5M14 17.25h6.5")
    val sliders = Glyph.sliders.spec
    val prev = filled("M6 5h2v14H6zM20 5v14L9 12z")
    val next = filled("M16 5h2v14h-2zM4 5v14l11-7z")
    val pause = filled(rect(6f, 5f, 4f, 14f, 1f), rect(14f, 5f, 4f, 14f, 1f))
    val playFilled = filled("M7 4.5v15l12.5-7.5z")
    val partly = icon(circle(9f, 8f, 3f), "M9 2.5V3.5M3.5 8h1M5.2 4.2l.7.7M12.8 4.2l-.7.7", "M8 20h9a4 4 0 0 0 .4-8 5 5 0 0 0-9.6 1.2A3.4 3.4 0 0 0 8 20z")
    val cloud = icon("M7 19h10a4.5 4.5 0 0 0 .5-9 6 6 0 0 0-11.6 1.6A3.8 3.8 0 0 0 7 19z")
    val rain = icon("M7 15h10a4 4 0 0 0 .4-8 5.5 5.5 0 0 0-10.6 1.4A3.4 3.4 0 0 0 7 15z", "M8 18l-1 2.5M12 18l-1 2.5M16 18l-1 2.5")
    val snow = icon("M7 15h10a4 4 0 0 0 .4-8 5.5 5.5 0 0 0-10.6 1.4A3.4 3.4 0 0 0 7 15z", "M8 18.5h.01M12 18.5h.01M16 18.5h.01M10 21h.01M14 21h.01")
    val fog = icon("M4 8h16M3 12h18M5 16h14M8 20h8")
    val storm = icon("M7 15h10a4 4 0 0 0 .4-8 5.5 5.5 0 0 0-10.6 1.4A3.4 3.4 0 0 0 7 15z", "M12.5 15l-2 3.5h3l-2 3.5")
    val clear = sun
    val wifi = icon("M2.5 9a14 14 0 0 1 19 0M5.5 12.5a9.5 9.5 0 0 1 13 0M8.5 16a5 5 0 0 1 7 0", "M12 19.5h.01")
    val ethernet = icon(rect(4f, 4f, 16f, 12f, 2f), "M8 16v4h8v-4M9 8v3M12 8v3M15 8v3")
    val bluetooth = icon("M7 7l10 10-5 4V3l5 4L7 17")
    val monitor = icon(rect(3f, 4f, 18f, 12f, 2f), "M8 20h8M12 16v4")
    val image = Glyph.photo.spec
    val palette = icon("M12 3a9 9 0 1 0 0 18c1.2 0 1.8-.8 1.8-1.7 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-.9.8-1.7 1.7-1.7H16a5 5 0 0 0 5-5c0-4-4-7.2-9-7.2z", circle(7.5f, 11f, 1f), circle(10f, 7f, 1f), circle(15f, 7.5f, 1f))
    val motion = Glyph.spark.spec
    val screensaver = icon(rect(3f, 4f, 18f, 16f, 3f), "M6 17l4-5 3 3.5 2-2.5 3 4")
    val chevronRight = icon("M9 6l6 6-6 6")
    val chevronLeft = icon("M15 6l-6 6 6 6")
    val close = icon("M6 6l12 12M18 6L6 18")
    val check = icon("M5 12.5l4.5 4.5L19 7")
    val hide = icon("M3 3l18 18", "M10.6 6.1A10 10 0 0 1 12 6c5 0 9 6 9 6a17 17 0 0 1-2.9 3.4M6.5 7.6C4.3 9.2 3 12 3 12s4 6 9 6a9 9 0 0 0 3.9-.9")
    val plus = icon("M12 5v14M5 12h14")
    val settings = icon(circle(12f, 12f, 3f), "M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1")
    val apps = icon(rect(3f, 3f, 7.5f, 7.5f, 2f), rect(13.5f, 3f, 7.5f, 7.5f, 2f), rect(3f, 13.5f, 7.5f, 7.5f, 2f), rect(13.5f, 13.5f, 7.5f, 7.5f, 2f))
    val clock = icon(circle(12f, 12f, 9f), "M12 7v5l3 2")
    val info = icon(circle(12f, 12f, 9f), "M12 11v6M12 7.5h.01")
    val speaker = icon("M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4.5 4.5 0 0 1 0 6M18 6.5a8 8 0 0 1 0 11")
    val storage = icon(rect(4f, 4f, 16f, 7f, 2f), rect(4f, 13f, 16f, 7f, 2f), "M8 7.5h.01M8 16.5h.01")
    val accessibility = icon(circle(12f, 4.5f, 1.5f), "M5 8.5l7 1.5 7-1.5M12 10v4.5l-3 6M12 14.5l3 6")
    val code = icon("M8 7l-5 5 5 5M16 7l5 5-5 5")
    val language = Glyph.globe.spec
    val pin = icon("M12 21s-6.5-6.2-6.5-11.5a6.5 6.5 0 0 1 13 0C18.5 14.8 12 21 12 21z", circle(12f, 9.5f, 2.3f))
    val user = icon(circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0")
    val trash = icon("M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")
    val back10 = icon("M10 4.5L5.5 8 10 11.5", "M5.5 8H14a5.5 5.5 0 0 1 0 11h-3")
    val fwd10 = icon("M14 4.5L18.5 8 14 11.5", "M18.5 8H10a5.5 5.5 0 0 0 0 11h3")
    val external = icon("M14 4h6v6", "M20 4l-9 9", "M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val rocket = icon("M12 2.5c3.5 2.5 5 6 4.5 10.5l-2 3h-5l-2-3C7 8.5 8.5 5 12 2.5z", circle(12f, 9.5f, 1.8f), "M9.5 16l-2.5 4 3-1M14.5 16l2.5 4-3-1")
    val camera = icon("M4 8h3l1.5-2.5h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", circle(12f, 13f, 3.5f))
    val mic = icon(rect(9f, 3f, 6f, 11f, 3f), "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")
    val store = icon("M5 4.5l13.5 7.5L5 19.5z", "M5 4.5l9 9M5 19.5l9-9")
    val assistant = icon("M12 3l1.8 5.4L19 10l-5.2 1.6L12 17l-1.8-5.4L5 10l5.2-1.6z", "M18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z")
    val wallpaper = icon(rect(3f, 4f, 18f, 16f, 3f), "M3 16l5-5 4 4 3-3 6 6", circle(16f, 8.5f, 1.5f))
    val bell = icon("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0")
    val warning = icon("M12 3.5l9.5 16.5h-19z", "M12 10v4.5M12 17.5h.01")
    val drop = icon("M12 3.5c3 4 5.5 7 5.5 10a5.5 5.5 0 0 1-11 0c0-3 2.5-6 5.5-10z")
    val wind = icon("M3 9h11a2.5 2.5 0 1 0-2.5-2.5M3 13h15a2.5 2.5 0 1 1-2.5 2.5M3 17h7")
    val thermo = icon("M10 14V5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z")
    val chevronDown = icon("M6 9l6 6 6-6")
}
