# Halo – installation and setup guide

This guide is for people who want to **use** Halo on their TV. You do not need a computer for the
basic installation. (Building Halo yourself is described in [DEVELOPMENT.md](DEVELOPMENT.md).)

---

## 1. Download

Download the latest `Halo-<version>.apk` from the **Releases** page of the Halo repository on
GitHub (the "Assets" section of the newest release).

## 2. Install on the TV

Choose one of these ways:

**A – Downloader app (TV only)**
1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Open it, enter the download link of the APK from the Releases page, and download.
3. When the TV asks, allow Downloader to **install unknown apps**, then select **Install**.

**B – Send Files to TV (TV + phone or computer)**
1. Install **Send Files to TV** on the TV (Play Store) and on your phone or computer.
2. Download the APK on the phone/computer and send it to the TV.
3. On the TV, open the received file, allow the app to install unknown apps, and install.

**C – adb (computer)**
1. On the TV: Settings → System → About → press **Android TV OS build** seven times to unlock
   Developer options. Then Settings → System → Developer options → turn on **Wireless debugging**
   (or **USB debugging**).
2. On the computer (with [platform-tools](https://developer.android.com/tools/releases/platform-tools)):
   ```sh
   adb pair <tv-ip>:<pairing-port>      # first time only; enter the code shown on the TV
   adb connect <tv-ip>:<port>
   adb install -r Halo-<version>.apk
   ```

The menu names above are those of Google TV; other devices may use slightly different names.

## 3. First start

Open **Halo** from your apps. The **setup wizard** guides you through language, theme, your
profile, weather, background, favourites, the assistant, the Home button and the remote's YouTube
button. Optional steps have a **Skip** button.

- **Weather location:** the weather can follow where the TV is (location permission), use the
  approximate location of your internet connection, or a city you type in.
- **Assistant:** paste your own Gemini API key, or skip. You can create a free key at
  [Google AI Studio](https://aistudio.google.com/apikey). To avoid typing it with the remote, scan
  the QR code with your phone: it opens a one-time page served by the TV in your home network,
  where you paste the key. The key is checked with Google and stored only on your TV.
- **Microphone:** needed for "Ask Halo"; the TV asks the first time you use it.
- **Media access:** shows what is playing and uses its artwork as background (see section 5).

You can change everything later in Halo's settings, and run any setup step again from there.

## 4. Make Halo your home screen

Google TV does not let apps replace the home screen on their own. Halo offers three ways:

| Way | Needs | Effect |
|---|---|---|
| **System dialog** | A device that offers "Default home app" | Halo becomes the real home app |
| **Home override** (in the wizard or Halo settings) | Enabling the "Halo button remap" accessibility service | Whenever the stock home screen opens, Halo comes to the front |
| **adb** (most reliable on Google TV) | A computer with adb, once | Halo becomes the default home activity |

adb commands (from a computer; inside an on-TV adb shell omit `adb shell`):

```sh
adb shell cmd package set-home-activity app.halo.tv/app.halo.launcher.MainActivity
# Optional: switch off the stock launcher so it cannot take over again (reversible)
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

Keep another launcher installed until you are happy with Halo.

## 5. Optional extras

```sh
# Now-playing card and artwork background
adb shell cmd notification allow_listener app.halo.tv/app.halo.launcher.media.MediaListenerService

# Smoother animations (run again after each Halo update)
adb shell cmd package compile -f -m speed app.halo.tv

# Halo Ambient as the system screensaver
adb shell settings put secure screensaver_components app.halo.tv/app.halo.launcher.dream.HaloDreamService
```

**YouTube button and assistant button:** in Halo's settings you can choose which app the
remote's YouTube button opens (for example an alternative YouTube client), and let the star button
open the Halo assistant over any app. Both use the optional Halo accessibility service, which only reacts to those buttons and
to the stock launcher opening.

## 6. Using Halo

| Remote | Action |
|---|---|
| OK on a card | Open the app |
| Hold OK on a card | Arrange Home with that card picked up |
| Arrange: ◀ ▶ / ▲ ▼ | Move the picked-up card; ▲ ▼ moves between Favourites and Apps |
| Arrange: hold OK | Customize the card (name, colour, style, artwork) |
| Top-right buttons | Theme · Arrange · Settings · Profile ("Who's watching?") |
| Who's watching? | OK switches profile · hold OK to rename, change avatar/colour or remove |
| Back | Close panels and screens |
| Home | Back to the start of Halo's home screen |
| Star button (if chosen in Halo) | Ask Halo over any app · Back closes it |

Each profile keeps its own favourites, order, hidden apps, card styles and background.

## 7. Undo everything

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd notification disallow_listener app.halo.tv/app.halo.launcher.media.MediaListenerService
adb shell settings delete secure screensaver_components
adb uninstall app.halo.tv
```

Without adb: turn off Home override / the accessibility service in the TV's settings
(Settings → System → Accessibility) and uninstall Halo under Settings → Apps.

## 8. Troubleshooting

- **Pressing Home opens the stock home screen:** use one of the ways in section 4. After a system
  update Google TV may re-enable its launcher; run the adb commands again.
- **No weather:** check the internet connection; set a city in Halo's settings if location is
  unavailable.
- **No pictures in a collection:** the picture sources may be temporarily unreachable; Halo retries
  automatically and shows cached pictures meanwhile.
- **"Ask Halo" does not answer:** check the Gemini key in Halo's settings and that the microphone
  permission is granted. Free keys have daily limits.
