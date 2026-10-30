# Developing Halo

## Project layout

| Path | Contents |
|---|---|
| `app/src/main/java/app/halo/launcher/` | Kotlin sources (package `app.halo.launcher`) |
| `  data/` | Installed apps, app cards, brand list, settings (DataStore) |
| `  ui/` | Jetpack Compose screens (home, arrange, customize, backgrounds, weather, player, search …) |
| `  wallpaper/` | Picture library (Wikimedia Commons, NASA, Lorem Picsum) |
| `  weather/` | Open-Meteo forecast, Bright Sky / DWD warnings |
| `  assistant/` | "Ask Halo": speech input, Gemini tool calling, voices |
| `  media/` | Now playing (media sessions via the notification listener) |
| `  remap/`, `setup/` | Accessibility service (YouTube button, Home override), setup wizard helpers |
| `  i18n/` | Interface language switching (`Lang.kt`); the strings are in `res/values` (English) and `res/values-de` (German) |
| `app/src/main/res/` | Fonts, avatars, launcher icon and banner |
| `tv.gradle.kts` | adb helper tasks (below) |
| `licenses/` | Third-party licence texts |

- **applicationId:** `app.halo.tv` (what adb and Android use).
- **Kotlin package / namespace:** `app.halo.launcher`. Component names therefore look like
  `app.halo.tv/app.halo.launcher.MainActivity`.
- Both the debug and the release build type are minified (R8), because Compose is much slower in
  debuggable builds.

## Building

Requirements: JDK 17, Android SDK 35 (Android Studio installs it), internet for the first Gradle
sync.

```sh
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk (what Run installs)
```

In Android Studio: **File → Open** the project folder, wait for the Gradle sync, connect the TV
(below), select it and press **Run**.

## Connecting a TV

1. TV: Settings → System → About → press **Android TV OS build** seven times.
2. TV: Settings → System → Developer options → **Wireless debugging** on.
3. Android Studio: **Device Manager → Pair devices using Wi-Fi**, or on the command line
   `adb pair <ip>:<pairing-port>` followed by `adb connect <ip>:<port>`.

Android switches wireless debugging off when the TV loses Wi-Fi; switch it on again and run
`tvConnect`.

## TV helper tasks (`tv.gradle.kts`)

Run them from Android Studio's Gradle panel (**Gradle → Halo → Tasks → halo tv**) or from
a terminal, e.g. `./gradlew tvReport`. They use `adb` from the SDK in `local.properties`
(`sdk.dir`), from `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or from `PATH`. If no TV is connected,
they try to reconnect to an already paired TV via mDNS.

Each task writes its output to a `tv-*.txt` file in the project folder (git-ignored, because it
describes your own device).

| Task | What it does | Undo |
|---|---|---|
| `tvConnect` | Reconnects to the paired TV (after a Wi-Fi drop) → `tv-connect.txt` | – |
| `tvReport` | Model, Android version, current home app, enabled / disabled / third-party packages → `tv-report.txt` | – |
| `tvApps` | Installed third-party packages → `tv-apps.txt` | – |
| `tvMakeHaloDefault` | `cmd package set-home-activity app.halo.tv/app.halo.launcher.MainActivity`, disables the stock launcher (`com.google.android.apps.tv.launcherx`, `com.google.android.tungsten.setupwraith`), allows the media listener, grants microphone and coarse location → `tv-default.txt` | `tvRestoreStock` |
| `tvDebloat` | Disables (`pm disable-user --user 0`) every package listed in `tv-debloat.txt` → `tv-debloat-result.txt` | `tvRestoreStock` |
| `tvRestoreStock` | Re-enables the stock launcher and every package in `tv-debloat.txt` → `tv-restore.txt`. Halo stays the chosen home app (and keeps its permissions) until you pick another one or uninstall Halo. | – |
| `tvOptimize` | AOT-compiles Halo (`cmd package compile -f -m speed app.halo.tv`), restarts it → `tv-optimize.txt`. Run after every install. | – |
| `tvScreensaverOff` | Turns off the system screensaver / Google ambient mode (Halo's own ambient mode stays) → `tv-screensaver.txt` | `tvScreensaverOn` |
| `tvScreensaverOn` | Turns the system screensaver back on | – |
| `tvScreenshot` | Screenshot of the TV → `tv-screen.png` | – |
| `tvTour` | Opens every Halo screen (via the `halo.open` intent extra) and saves screenshots to `tv-tour/` | – |
| `tvWizardTour` | Walks through the setup wizard (OK on every step) and saves a screenshot of each step to `tv-wizard/`. Finishes the wizard. | – |
| `tvLogcat` | Halo's recent log (tags `HaloWall`, `HaloVoice`, `HaloKeys`, `Halo`, crashes) and accessibility state → `tv-log.txt` | – |

### Debloat list

`tvDebloat` reads **`tv-debloat.txt`** (git-ignored). Start from the commented example:

```sh
cp tv-debloat.example.txt tv-debloat.txt
```

Remove the `#` in front of packages you want switched off. Check `tvReport` first and never
disable speech, text-to-speech, Google Play services, the Play Store, Bluetooth/remote or settings
packages. Everything is reversible with `tvRestoreStock`.

### Opening a screen directly

```sh
adb shell am start -n app.halo.tv/app.halo.launcher.MainActivity --es halo.open <screen>
```

`<screen>`: `home`, `appearance`, `quick`, `arrange`, `customize`, `profiles`, `backgrounds`,
`weather`, `player`, `search`, `setup` (the whole setup wizard) or `setup:<Step>` (one step, e.g.
`setup:Assistant`). Works in debug builds only (`BuildConfig.DEV_HOOKS`).

## Release signing

Android only installs an update over an existing installation if both APKs are signed with the
**same key**. Without configuration, release builds are signed with the debug key of the machine
that builds them (fine for personal use, but a different key on every machine and on CI).

### 1. Create a keystore (once)

```sh
keytool -genkeypair -v -keystore halo-release.jks -alias halo \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` file and its passwords safe and private — if you lose them you cannot publish
updates that install over existing installations. `*.jks` and `*.keystore` are git-ignored.

### 2a. Local builds: `keystore.properties`

Create `keystore.properties` in the project root (git-ignored):

```properties
storeFile=/absolute/or/relative/path/to/halo-release.jks
storePassword=…
keyAlias=halo
keyPassword=…
```

A relative `storeFile` is resolved against the project root.

### 2b. Environment variables (CI, or instead of the file)

| Variable | Meaning |
|---|---|
| `HALO_KEYSTORE_FILE` | Path to the `.jks` file |
| `HALO_KEYSTORE_PASSWORD` | Keystore password |
| `HALO_KEY_ALIAS` | Key alias |
| `HALO_KEY_PASSWORD` | Key password (defaults to the keystore password) |

Values in `keystore.properties` take precedence over environment variables. If the keystore file,
store password or alias is missing, the build falls back to debug signing.

### 2c. GitHub Actions

Add these **repository secrets** (Settings → Secrets and variables → Actions):

- `HALO_KEYSTORE_BASE64` — the keystore as base64 (`base64 -w0 halo-release.jks` on Linux,
  `base64 -i halo-release.jks` on macOS, `[Convert]::ToBase64String([IO.File]::ReadAllBytes("halo-release.jks"))` in PowerShell)
- `HALO_KEYSTORE_PASSWORD`, `HALO_KEY_ALIAS`, `HALO_KEY_PASSWORD`

The workflow [`.github/workflows/build.yml`](../.github/workflows/build.yml) builds the release APK
on every push and pull request and uploads it as an artifact. Pushing a tag such as `v1.0.0`
additionally creates a GitHub Release with `Halo-1.0.0.apk` attached.

## Releasing a new version

1. Update `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. Add the changes to `CHANGELOG.md`.
3. Commit, then tag and push: `git tag v1.1.0 && git push origin v1.1.0`.

## Wallpaper sources

`wallpaper/Wallpapers.kt` defines the picture collections. Only add sources that are freely
licensed (public domain, CC0, CC BY, CC BY-SA, Unsplash licence) and keyless, and make sure every
`Picture` carries a credit line (title, author, licence, source). When removing a source, bump
`MANIFEST_VERSION` so cached picture lists are rebuilt. Wikimedia requests must keep the
identifying User-Agent and the per-host limits (Wikimedia API etiquette).
