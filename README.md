# Halo

**A calm, glassy home screen for Google TV and Android TV.**

Halo replaces the stock Google TV home screen with a clean launcher that shows *your* apps, a
beautiful background and nothing else: no ads, no sponsored rows, no tracking. It is written in
Kotlin with Jetpack Compose and runs on Android 9 (API 28) and newer.

> Halo is an independent open-source project. It is not affiliated with, endorsed by or sponsored
> by Google, Apple or any app vendor. See [Disclaimer](#disclaimer).

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Install](#install)
- [First start: the setup wizard](#first-start-the-setup-wizard)
- [Optional adb power-ups](#optional-adb-power-ups)
- [Privacy](#privacy)
- [Building from source](#building-from-source)
- [Credits](#credits)
- [Licence](#licence)
- [Disclaimer](#disclaimer)

---

## Features

**Home screen**
- **Uniform app cards.** Every app gets a 16:9 card in the same style, built from the app's own
  logo (taken from its installed banner or adaptive icon) on its brand colour. Apps without
  usable artwork get a designed fallback card with a glyph or wordmark.
- **Glass UI.** Frosted-glass tiles, panels and controls with soft depth, a focus glow and gentle
  motion (motion effects can be turned off).
- **Top row of your choice:** favourites, recently used or most used apps.
- **Clock, date and weather** at a glance; ambient mode after a configurable idle time.
- **Light, dark or automatic theme** (automatic follows sunrise and sunset).
- **Navigation sounds** using the TV's own focus and click sounds (optional).

**Backgrounds**
- **Animated Halo scene** whose mountains and sky follow the time of day.
- **Picture library** with collections — Picture of the day (Wikimedia Commons), Landscapes,
  Forests & Nature, Aero, Space (NASA, Hubble, Webb), Ocean & Coast, Cities & Night and
  Photography (Unsplash via Lorem Picsum).
- **Intervals:** keep one picture, or change every 1, 5, 15, 30 or 60 minutes or daily; or pin a
  single picture. Every picture is shown with its credit (title, author, licence, source).
- **Now-playing artwork background:** while music or video plays, the artwork of what is playing
  can become the background, plus a full-screen **Now playing** view with play/pause and skip.

**Weather**
- Current conditions, the next 12 hours and 7 days from **Open-Meteo**.
- **Official severe-weather warnings of the Deutscher Wetterdienst (DWD)** for locations in
  Germany, via Bright Sky.
- Location from the TV (optional location permission), from the internet connection, or a fixed
  city you type in.

**"Ask Halo" voice assistant (optional)**
- Talk to Halo through the remote's microphone and run the whole launcher by voice: open apps,
  control playback and volume, change any Halo setting (theme, tile style, clock, sounds, weather
  location, screensaver, language, voice, remote buttons), restyle single app tiles (look, name,
  colour, symbol, borrowed artwork), arrange, hide or uninstall apps, create and edit profiles,
  change the background, run setup steps and open the right TV settings page (time zone, Wi-Fi …).
- **Halo over any app:** the remote's star button opens the assistant over whatever is on screen,
  with a glowing edge and an answer card at the bottom (optional, uses the Halo service).
- Powered by **Google Gemini** with natural-sounding Gemini voices (or the TV's own text-to-speech).
- **Bring your own key:** you enter your own free Gemini API key in the setup wizard. No key is
  built into Halo; without a key the assistant is simply off.

**Profiles and personalisation**
- **Profiles ("Who's watching?")** with avatars (Fluent Emoji) or coloured initials. Each profile
  has its own favourites, order, hidden apps, card styles and background.
- **Arrange Home:** move apps between favourites and the app grid, reorder, hide, and uninstall
  (via the system dialog).
- **App tile style for all apps at once:** uniform Halo cards, the apps' own TV banners (like the
  stock launcher) or app icons.
- **Customize cards:** rename an app, change its colour, choose card / logo / glass / banner /
  icon style, or **borrow another app's artwork** (for example a YouTube card for an alternative
  YouTube client).
- **Search** across apps and common TV settings.

**Remote and system integration**
- **YouTube-button remap:** make the remote's YouTube button open any app you choose (optional
  accessibility service; it only reacts to that button, to the star button and to the stock launcher
  opening).
- **Home-button override:** bring Halo back whenever the stock launcher would open — works without
  adb on devices that do not let you change the home app.
- **Halo Ambient** can also be used as the system screensaver.
- **English and German** user interface and assistant (follows the system language or can be set).
- **First-run setup wizard** for language, theme, profile, weather, background, favourites,
  assistant key (also from your phone via QR code), home screen and remote button.

---


## Requirements

- A **Google TV** or **Android TV** device with **Android 9 or newer** (developed and tested on the
  Google TV Streamer; Chromecast with Google TV, Android TV boxes and TVs should work).
- An internet connection for weather and background pictures (Halo works offline with the
  animated scene and cached pictures).
- Optional: a free **Google Gemini API key** for the voice assistant
  (create one at [Google AI Studio](https://aistudio.google.com/apikey)).
- Optional: a computer or phone with **adb** for the [power-ups](#optional-adb-power-ups).

---

## Install

1. Download the latest `Halo-<version>.apk` from the
   [**Releases**](../../releases) page of this repository.
2. Get the APK onto your TV — pick one:
   - **Downloader** (by AFTVnews, free in the Play Store on the TV): enter the APK's download URL
     and install it.
   - **Send Files to TV** (Play Store, on the TV and on your phone/PC): send the APK, then open
     it on the TV.
   - **adb** from a computer on the same network:
     ```sh
     adb connect <tv-ip>:<port>      # see Developer options → Wireless debugging on the TV
     adb install -r Halo-<version>.apk
     ```
3. When asked, allow the app you used to **install unknown apps** (Settings → Apps → Security &
   restrictions → Unknown sources on most devices).
4. Open **Halo** from the apps row. The setup wizard starts.

Updating: install the new APK over the old one. Your settings are kept as long as both APKs are
signed with the same key (all official releases are).

The step-by-step guide for end users, including how to undo everything, is in
[docs/SETUP.md](docs/SETUP.md).

---

## First start: the setup wizard

On first launch Halo walks you through a short wizard (optional steps can be skipped):

1. **Language** — system, English or German.
2. **Theme** — automatic, dark or light.
3. **Profile** — your name and avatar.
4. **Weather** — location from the TV, from the internet connection, or a city (optional).
5. **Background** — the animated scene or a picture collection (optional).
6. **Favourites** — the apps for the top row and the tile style: Halo cards, app banners or app
   icons (optional).
7. **Assistant** — your own Gemini API key, typed on the TV or pasted on your phone: the TV
   shows a QR code that opens a one-time page served by the TV itself in your home network
   (optional).
8. **Home** — make Halo open when you press Home: via the system dialog where the device offers
   it, with Halo's *Home override* (accessibility service), or with the adb commands below
   (optional).
9. **Remote** — the star button for the Halo assistant, and the app the remote's YouTube button opens
   (optional).

Everything can be changed later in Halo's settings, and each step can be run again from there.

---

## Optional adb power-ups

These commands are not required, but give the best experience on Google TV, which does not let
apps become the home screen on their own. Run them from a computer with
[platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb shell …`) or
from an on-device adb shell app (omit `adb shell`).

Halo's application id is **`app.halo.tv`**.

**Make Halo the default home screen**

```sh
adb shell cmd package set-home-activity app.halo.tv/app.halo.launcher.MainActivity
# Optional, stops the stock launcher from taking over again (reversible, see below):
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Undo:

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```

**Allow the now-playing card and artwork background** (media session access):

```sh
adb shell cmd notification allow_listener app.halo.tv/app.halo.launcher.media.MediaListenerService
```

Undo: `adb shell cmd notification disallow_listener app.halo.tv/app.halo.launcher.media.MediaListenerService`

**Pre-compile Halo for the smoothest animations** (run again after each update):

```sh
adb shell cmd package compile -f -m speed app.halo.tv
```

**Use Halo Ambient as the system screensaver:**

```sh
adb shell settings put secure screensaver_components app.halo.tv/app.halo.launcher.dream.HaloDreamService
```

**Grant the optional permissions without the on-screen prompts:**

```sh
adb shell pm grant app.halo.tv android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant app.halo.tv android.permission.RECORD_AUDIO
```

Developers can run all of these from Android Studio as Gradle tasks — see
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Privacy

Halo has **no analytics, no tracking, no ads and no account**. Everything you configure stays on
the TV. The only network requests Halo makes (directly or through the TV's own services) are:

| Purpose | Service | What is sent |
|---|---|---|
| Weather forecast | [Open-Meteo](https://open-meteo.com) (`api.open-meteo.com`) | Approximate coordinates of the weather location |
| City search (only if you type a city) | Open-Meteo Geocoding (`geocoding-api.open-meteo.com`) | The city name you typed |
| Weather warnings (DWD) | [Bright Sky](https://brightsky.dev) (`api.brightsky.dev`) | Approximate coordinates of the weather location |
| Location fallback (only without TV location and without a fixed city) | [ipwho.is](https://ipwho.is) | Your public IP address (implicit in any request); returns city and coordinates |
| Place name for the TV's location (only with TV location) | The TV's built-in geocoder (Android `Geocoder`, on Google TV provided by Google) | Approximate coordinates of the TV |
| Background pictures | Wikimedia (`commons.wikimedia.org`, `upload.wikimedia.org`, `api.wikimedia.org`, `en.wikipedia.org`), NASA (`images-api.nasa.gov`, `images-assets.nasa.gov`), Lorem Picsum (`picsum.photos`) | Standard image/API requests; no personal data |
| Voice assistant (only with your own key) | Google Gemini API (`generativelanguage.googleapis.com`) | Your spoken request as text plus the context needed to answer it: date and time, profile names, installed and favourite app names, the weather shown, and what is playing (title, artist, app); for natural voices also the reply text for speech synthesis |

- Your **Gemini API key** is stored only in Halo's private app storage on the TV and is sent only
  to Google's Gemini API. If you paste it from your phone during setup, the TV runs a small
  temporary web page in your home network only (bound to the TV's LAN address, protected by a
  one-time code from the QR code, stopped after the key arrives or after 15 minutes); the key is
  checked with Google and never sent anywhere else. Requests made with your key are governed by
  [Google's Gemini API terms](https://ai.google.dev/gemini-api/terms).
- **Speech recognition** uses the TV's own system speech service (on Google TV: Google's).
- **Location** (if granted) is used only to look up the weather; it never leaves the TV except as
  the coordinates in the weather requests above.
- **Media access** (if granted) is used only to show what is playing; no data leaves the TV.

See [PRIVACY.md](PRIVACY.md) for the full privacy statement.

---

## Building from source

Requirements: **JDK 17**, Android SDK (API 35), and optionally Android Studio (Ladybug or newer).

```sh
git clone <this repository>
cd <repository folder>
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

- Android Studio: **File → Open** the project folder, wait for the Gradle sync, then **Run** to
  install on a connected TV.
- Without your own signing key the release APK is signed with your local debug key. To sign with
  your own key (needed for updates across machines), see
  [docs/DEVELOPMENT.md → Release signing](docs/DEVELOPMENT.md#release-signing).
- Every push builds an APK on GitHub Actions; pushing a tag `v*` publishes a GitHub Release
  ([.github/workflows/build.yml](.github/workflows/build.yml)).

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Credits

- **Manrope** typeface by The Manrope Project Authors — SIL Open Font License 1.1.
- **Fluent Emoji** (profile avatars) by Microsoft — MIT License.
- **Weather data** by [Open-Meteo.com](https://open-meteo.com) (CC BY 4.0), based on models of
  national weather services incl. the DWD.
- **Weather warnings:** Quelle: Deutscher Wetterdienst, via [Bright Sky](https://brightsky.dev).
- **Pictures:** Wikimedia Commons contributors (individual licences shown with each picture),
  NASA (public domain unless noted), Unsplash photographers via Lorem Picsum.
- Built with AndroidX, Jetpack Compose, Kotlin, kotlinx.serialization, kotlinx.coroutines and ZXing.

Full licence texts and attribution: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## Licence

Halo is released under the [MIT License](LICENSE).
Copyright (c) 2026 Halo Launcher contributors.

Third-party components keep their own licences — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## Disclaimer

Halo is not affiliated with, endorsed by or sponsored by Google LLC, Apple Inc. or any other
company. Google, Google TV, Android, Android TV, Chromecast, YouTube and Gemini are trademarks of
Google LLC; Apple TV is a trademark of Apple Inc. All other product names, logos and brands are
the property of their respective owners and are used only to identify the apps installed on your
TV (nominative use). Halo does not bundle any third-party app logos; it displays the artwork that
each installed app provides itself.

The software is provided "as is", without warranty of any kind. Disabling system packages with adb
is done at your own risk; every command in this document can be undone as described.
