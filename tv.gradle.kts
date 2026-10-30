// Halo developer helper tasks: run adb against a connected Android TV / Google TV from Android
// Studio's Gradle panel (Gradle → Halo → Tasks → halo tv) or from a terminal
// (./gradlew tvReport). Every change made here can be undone with tvRestoreStock / tvScreensaverOn.
// See docs/DEVELOPMENT.md for a description of each task.
//
// Output files (tv-*.txt, tv-screen.png, tv-tour/) are written to the project folder and are
// git-ignored, because they contain details of your own device.
import java.util.Properties

/** applicationId of Halo (see app/build.gradle.kts). The Kotlin package is app.halo.launcher. */
val APP_ID = "app.halo.tv"

/** Halo's home activity and media listener as adb component names (package / class). */
val HOME_COMPONENT = "$APP_ID/app.halo.launcher.MainActivity"
val LISTENER_COMPONENT = "$APP_ID/app.halo.launcher.media.MediaListenerService"

val sdkDir: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("sdk.dir", System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "")

fun adbPath(): String {
    val win = File(sdkDir, "platform-tools/adb.exe")
    val nix = File(sdkDir, "platform-tools/adb")
    return when {
        sdkDir.isNotEmpty() && win.exists() -> win.path
        sdkDir.isNotEmpty() && nix.exists() -> nix.path
        else -> "adb" // from PATH
    }
}

fun adb(vararg args: String): String {
    val p = ProcessBuilder(listOf(adbPath()) + args).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out
}

fun onlineTv(): String? = adb("devices").lines().drop(1)
    .map { it.split("\t") }
    .firstOrNull { it.size > 1 && it[1].trim() == "device" && !it[0].startsWith("emulator") }
    ?.get(0)

/**
 * adb serial of the connected TV. If none is online, tries to reconnect to an already-paired TV
 * found via mDNS (its wireless-debugging port changes after every Wi-Fi drop).
 */
fun tv(): String {
    onlineTv()?.let { return it }
    adb("mdns", "services").lines()
        .filter { "_adb-tls-connect" in it }
        .mapNotNull { Regex("""(\d+\.\d+\.\d+\.\d+:\d+)""").find(it)?.value }
        .forEach { println("adb connect $it: " + adb("connect", it).trim()) }
    Thread.sleep(1500)
    return onlineTv() ?: throw GradleException(
        "No TV connected over adb. On the TV: Settings > System > Developer options > Wireless debugging must be ON " +
            "(Android switches it off when Wi-Fi drops). Pair once (Android Studio: Device Manager > Pair devices using Wi-Fi, " +
            "or adb pair <ip>:<port>), then run tvConnect again."
    )
}

fun sh(serial: String, cmd: String): String = adb("-s", serial, "shell", cmd).trim()

fun report(name: String, text: String) {
    rootProject.file(name).writeText(text)
    println(text)
}

fun requireHalo(serial: String) {
    if (!sh(serial, "pm list packages $APP_ID").lines().any { it.trim() == "package:$APP_ID" }) {
        throw GradleException("Halo ($APP_ID) is not installed on the TV. Install it first (Run, or adb install), then retry.")
    }
}

// Stock Google TV home screen + its setup service that re-enables it.
val stockLauncher = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith")

/**
 * Preinstalled apps to switch off (reversible). Read from tv-debloat.txt (your own list, git-ignored);
 * copy tv-debloat.example.txt to start one. One package per line, "#" starts a comment.
 */
val bloat: List<String> = rootProject.file("tv-debloat.txt").takeIf { it.exists() }
    ?.readLines()?.map { it.substringBefore("#").trim() }?.filter { it.isNotEmpty() } ?: emptyList()

tasks.register("tvConnect") {
    group = "halo tv"
    description = "Reconnects to the paired TV (e.g. after a Wi-Fi drop) and writes tv-connect.txt"
    doLast {
        val out = StringBuilder()
        out.appendLine("mdns: " + adb("mdns", "services").trim())
        out.appendLine("connected: " + runCatching { tv() }.getOrElse { it.message })
        out.appendLine(adb("devices", "-l").trim())
        report("tv-connect.txt", out.toString())
    }
}

tasks.register("tvReport") {
    group = "halo tv"
    description = "Lists the TV's model, home app and enabled/disabled/third-party packages into tv-report.txt"
    doLast {
        val s = tv()
        report(
            "tv-report.txt",
            buildString {
                appendLine("model: " + sh(s, "getprop ro.product.model") + " / Android " + sh(s, "getprop ro.build.version.release"))
                appendLine("--- home activity ---")
                appendLine(sh(s, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"))
                appendLine("--- enabled packages ---")
                appendLine(sh(s, "pm list packages -e"))
                appendLine("--- disabled packages ---")
                appendLine(sh(s, "pm list packages -d"))
                appendLine("--- third-party packages ---")
                appendLine(sh(s, "pm list packages -3"))
            },
        )
    }
}

tasks.register("tvMakeHaloDefault") {
    group = "halo tv"
    description = "Makes Halo the home screen (disables the stock Google TV launcher) and grants Halo's optional permissions"
    doLast {
        val s = tv()
        requireHalo(s)
        val log = StringBuilder()
        log.appendLine("set-home-activity: " + sh(s, "cmd package set-home-activity $HOME_COMPONENT"))
        stockLauncher.forEach { log.appendLine("$it: " + sh(s, "pm disable-user --user 0 $it")) }
        log.appendLine("media access: " + sh(s, "cmd notification allow_listener $LISTENER_COMPONENT"))
        log.appendLine("microphone: " + sh(s, "pm grant $APP_ID android.permission.RECORD_AUDIO"))
        log.appendLine("location: " + sh(s, "pm grant $APP_ID android.permission.ACCESS_COARSE_LOCATION"))
        log.appendLine("home now: " + sh(s, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"))
        sh(s, "input keyevent KEYCODE_HOME")
        report("tv-default.txt", log.toString())
    }
}

tasks.register("tvDebloat") {
    group = "halo tv"
    description = "Disables the preinstalled apps listed in tv-debloat.txt (reversible with tvRestoreStock)"
    doLast {
        if (bloat.isEmpty()) {
            throw GradleException("tv-debloat.txt is missing or empty. Copy tv-debloat.example.txt to tv-debloat.txt and edit it first.")
        }
        val s = tv()
        val installed = sh(s, "pm list packages").lines().map { it.removePrefix("package:").trim() }.toSet()
        val log = StringBuilder()
        bloat.forEach { p ->
            log.appendLine(if (p in installed) "$p: " + sh(s, "pm disable-user --user 0 $p") else "$p: not installed")
        }
        report("tv-debloat-result.txt", log.toString())
    }
}

tasks.register("tvRestoreStock") {
    group = "halo tv"
    description = "Undo: re-enables the stock launcher and every app from tv-debloat.txt"
    doLast {
        val s = tv()
        val log = StringBuilder()
        (stockLauncher + bloat).distinct().forEach { p -> log.appendLine("$p: " + sh(s, "pm enable $p")) }
        report("tv-restore.txt", log.toString())
    }
}

tasks.register("tvOptimize") {
    group = "halo tv"
    description = "Fully pre-compiles Halo on the TV (AOT, speed) – run after every install for smooth animations"
    doLast {
        val s = tv()
        requireHalo(s)
        val out = sh(s, "cmd package compile -f -m speed $APP_ID") + "\n" +
            sh(s, "am force-stop $APP_ID") + "\n" +
            sh(s, "input keyevent KEYCODE_HOME")
        report("tv-optimize.txt", out)
    }
}

tasks.register("tvScreenshot") {
    group = "halo tv"
    description = "Saves a screenshot of the TV to tv-screen.png in the project folder"
    doLast {
        val s = tv()
        val f = rootProject.file("tv-screen.png")
        val p = ProcessBuilder(adbPath(), "-s", s, "exec-out", "screencap", "-p")
            .redirectOutput(f).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.waitFor()
        println("saved ${f.length()} bytes to $f")
    }
}

/** Opens every Halo screen on the TV and screenshots it into tv-tour/ (for UX review and README screenshots). */
tasks.register("tvTour") {
    group = "halo tv"
    description = "Screenshots every Halo screen into tv-tour/ (needs Halo installed)"
    doLast {
        val s = tv()
        requireHalo(s)
        val dir = rootProject.file("tv-tour").apply { deleteRecursively(); mkdirs() }
        fun shot(name: String) {
            Thread.sleep(2200)
            ProcessBuilder(adbPath(), "-s", s, "exec-out", "screencap", "-p")
                .redirectOutput(File(dir, "$name.png")).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        }
        fun open(what: String) = sh(s, "am start -n $HOME_COMPONENT --es halo.open $what")
        fun keys(code: String, n: Int) = repeat(n) { sh(s, "input keyevent $code"); Thread.sleep(250) }
        sh(s, "input keyevent KEYCODE_HOME")
        Thread.sleep(1500)
        open("home"); shot("01_home")
        keys("KEYCODE_DPAD_DOWN", 1); shot("02_home_apps")
        open("appearance"); shot("03_settings_1")
        keys("KEYCODE_DPAD_DOWN", 6); shot("04_settings_2")
        keys("KEYCODE_DPAD_DOWN", 6); shot("05_settings_3")
        keys("KEYCODE_DPAD_DOWN", 8); shot("06_settings_4")
        open("quick"); shot("07_quick")
        open("arrange"); shot("08_arrange")
        keys("KEYCODE_DPAD_CENTER", 1); shot("09_arrange_held")
        keys("KEYCODE_BACK", 1)
        open("customize"); shot("10_customize")
        keys("KEYCODE_DPAD_DOWN", 3); shot("11_customize_2")
        open("profiles"); shot("12_profiles")
        open("backgrounds"); shot("13_backgrounds")
        keys("KEYCODE_DPAD_DOWN", 2); shot("14_backgrounds_grid")
        open("weather"); shot("15_weather")
        open("player"); shot("16_player")
        open("search"); shot("17_search")
        keys("KEYCODE_BACK", 1)
        open("home")
        println("saved ${dir.listFiles()?.size ?: 0} screenshots to $dir")
    }
}

tasks.register("tvLogcat") {
    group = "halo tv"
    description = "Saves Halo's recent log (wallpapers, voice, keys, crashes) to tv-log.txt"
    doLast {
        val s = tv()
        // "\$" keeps the dollar sign for the TV's shell instead of starting a Kotlin string template.
        val out = "accessibility_enabled=" + sh(s, "settings get secure accessibility_enabled") +
            "\nenabled_services=" + sh(s, "settings get secure enabled_accessibility_services") + "\n\n" +
            adb("-s", s, "logcat", "-d", "-t", "3000", "-s", "HaloVoice:*", "HaloKeys:*", "HaloWall:*", "AndroidRuntime:E", "Halo:*") +
            "\n\n--- app process (warnings+) ---\n" +
            sh(s, "pid=\$(pidof $APP_ID); [ -n \"\$pid\" ] && logcat -d -t 4000 --pid=\$pid *:W | tail -n 150")
        rootProject.file("tv-log.txt").writeText(out)
        println(out.takeLast(4000))
    }
}

tasks.register("tvScreensaverOff") {
    group = "halo tv"
    description = "Turns off the system screensaver / Google ambient mode (Halo's own ambient mode stays). Undo: tvScreensaverOn"
    doLast {
        val s = tv()
        val out = listOf(
            "settings put secure screensaver_enabled 0",
            "settings put secure screensaver_activate_on_sleep 0",
            "settings put secure screensaver_activate_on_dock 0",
        ).joinToString("\n") { "$it: " + sh(s, it) } + "\nnow: screensaver_enabled=" + sh(s, "settings get secure screensaver_enabled")
        report("tv-screensaver.txt", out)
    }
}

tasks.register("tvScreensaverOn") {
    group = "halo tv"
    description = "Undo tvScreensaverOff"
    doLast {
        val s = tv()
        sh(s, "settings put secure screensaver_enabled 1")
        sh(s, "settings put secure screensaver_activate_on_sleep 1")
        sh(s, "settings put secure screensaver_activate_on_dock 1")
        println("screensaver_enabled=" + sh(s, "settings get secure screensaver_enabled"))
    }
}

tasks.register("tvApps") {
    group = "halo tv"
    description = "Lists installed third-party apps into tv-apps.txt"
    doLast {
        val s = tv()
        report("tv-apps.txt", sh(s, "pm list packages -3"))
    }
}

/** Walks through the whole setup wizard on the TV (OK on every step) and screenshots each step into tv-wizard/. */
tasks.register("tvWizardTour") {
    group = "halo tv"
    description = "Screenshots every setup-wizard step into tv-wizard/ (debug build; finishes the wizard)"
    doLast {
        val s = tv()
        val dir = rootProject.file("tv-wizard").apply { deleteRecursively(); mkdirs() }
        fun shot(name: String) {
            ProcessBuilder(adbPath(), "-s", s, "exec-out", "screencap", "-p")
                .redirectOutput(File(dir, "$name.png")).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        }
        sh(s, "am start -n $HOME_COMPONENT --es halo.open setup")
        Thread.sleep(3000)
        for (i in 1..10) {
            Thread.sleep(2500)
            shot("%02d".format(i))
            sh(s, "input keyevent KEYCODE_DPAD_CENTER")
        }
        Thread.sleep(3000)
        shot("11_home")
        println("saved ${dir.listFiles()?.size ?: 0} screenshots to $dir")
    }
}
