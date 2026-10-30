# Third-party notices

Halo itself is licensed under the [MIT License](LICENSE) — Copyright (c) 2026 Halo Launcher
contributors. It includes or uses the third-party components and services listed below, which
remain under their own licences and terms.

Full licence texts are in the [`licenses/`](licenses/) folder.

---

## 1. Libraries (compiled into the APK)

All of the following are licensed under the **Apache License 2.0**
([licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)). Versions are defined in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

| Component | Copyright | Licence |
|---|---|---|
| AndroidX Core KTX, Activity Compose, Lifecycle (runtime, viewmodel, service), DataStore Preferences, Palette, ProfileInstaller | The Android Open Source Project | Apache-2.0 |
| Jetpack Compose (UI, Graphics, Foundation, Animation, Tooling) | The Android Open Source Project | Apache-2.0 |
| Kotlin standard library | JetBrains s.r.o. and Kotlin Programming Language contributors | Apache-2.0 |
| kotlinx.coroutines | JetBrains s.r.o. and contributors | Apache-2.0 |
| kotlinx.serialization | JetBrains s.r.o. and contributors | Apache-2.0 |
| ZXing core (QR codes in the setup wizard) | ZXing authors | Apache-2.0 |

Build tooling (not shipped in the APK): Gradle and the Gradle Wrapper (Apache-2.0), Android Gradle
Plugin (Apache-2.0), Kotlin Gradle plugins (Apache-2.0).

---

## 2. Bundled assets

### Manrope typeface
- Files: `app/src/main/res/font/manrope_300.ttf` … `manrope_700.ttf`
- Copyright 2019 The Manrope Project Authors (https://github.com/googlefonts/manrope)
- Licence: **SIL Open Font License 1.1** — [licenses/OFL-1.1-Manrope.txt](licenses/OFL-1.1-Manrope.txt)
- The font is bundled unmodified inside the app; it is not sold on its own.

### Fluent Emoji (profile avatars)
- Files: `app/src/main/res/drawable-nodpi/avatar_*.webp` (converted to WebP)
- Copyright (c) Microsoft Corporation (https://github.com/microsoft/fluentui-emoji)
- Licence: **MIT License** — [licenses/MIT-Fluent-Emoji.txt](licenses/MIT-Fluent-Emoji.txt)

### Halo's own artwork
The launcher icon (`ic_launcher_foreground.xml`), TV banner (`banner.xml`), the animated Halo
scene and all glyphs are original vector artwork of this project, licensed under the MIT License
with the rest of Halo.

### App names and brand colours
`KnownApps.kt` contains names and brand colours of popular TV apps so that their cards look right.
These are used only to identify the apps installed on the user's TV (nominative use). **No
third-party logos are bundled**; the logos shown on app cards are read at runtime from the artwork
each installed app provides itself. All trademarks belong to their respective owners.

---

## 3. Online data sources

Halo downloads data from the following services at runtime. Their terms apply to that data.

### Open-Meteo — weather forecast and geocoding
- https://open-meteo.com — Terms: https://open-meteo.com/en/terms
- Data licence: **Creative Commons Attribution 4.0 (CC BY 4.0)**; attribution required. Halo
  shows "Weather data: Open-Meteo.com (CC BY 4.0, DWD ICON)" on its weather screen.
- The free API is intended for non-commercial use. Commercial redistributions of Halo need an
  Open-Meteo API subscription.

### Bright Sky / Deutscher Wetterdienst — weather warnings (Germany)
- Bright Sky (https://brightsky.dev) is a free, open-source JSON API for open data of the
  Deutscher Wetterdienst (DWD).
- Source of the warnings: **Quelle: Deutscher Wetterdienst** (DWD Open Data,
  https://www.dwd.de/opendata), provided under the German "Verordnung zur Festlegung der
  Nutzungsbestimmungen für die Bereitstellung von Geodaten des Bundes" (GeoNutzV). Halo shows the
  source on its weather screen.

### ipwho.is — approximate location fallback
- https://ipwho.is — used only when the TV's location is unavailable and no city is set.
- The keyless endpoint is offered free of charge for non-commercial use, subject to its terms and
  rate limits.

### Wikimedia Commons — background pictures and picture of the day
- https://commons.wikimedia.org — files from selected Commons categories, and Commons' picture of
  the day via Wikipedia's featured-content feed (`api.wikimedia.org`, `en.wikipedia.org`).
- Every file has its **own licence** (mostly CC BY, CC BY-SA, CC0 or public domain). Halo shows
  the file title, author and licence with each picture (taken from the file's metadata); the full
  terms are on each file's page on Commons.
- Halo follows the Wikimedia User-Agent policy and API etiquette (identifying User-Agent, limited
  parallel requests, back-off on errors).
- Wikimedia, Wikipedia and Wikimedia Commons are trademarks of the Wikimedia Foundation; Halo is
  not endorsed by it.

### NASA Image and Video Library — space pictures
- https://images.nasa.gov — API `images-api.nasa.gov`.
- NASA material is generally **not subject to copyright** in the United States, unless noted.
  Some images are from partners (ESA, STScI, JPL-Caltech …) and carry their own credit, which Halo
  shows with the picture. Usage follows the NASA Images and Media Usage Guidelines
  (https://www.nasa.gov/nasa-brand-center/images-and-media/). Use of NASA imagery does not imply
  endorsement by NASA.

### Lorem Picsum / Unsplash — "Photography" collection and offline fallback
- https://picsum.photos — serves photographs from Unsplash. Also used for every collection when
  Wikimedia Commons and NASA can't be reached.
- Photos are under the **Unsplash License** (https://unsplash.com/license): free to use, no
  permission or attribution needed; Halo names the photographer where Picsum provides it.

### Google Gemini API — voice assistant (optional)
- https://ai.google.dev — used only with the user's **own** API key, entered at runtime; no key is
  included in Halo.
- Use is subject to the Gemini API Additional Terms of Service
  (https://ai.google.dev/gemini-api/terms) and Google's privacy policy, which the user accepts when
  creating the key.

---

## 4. Trademarks

Google, Google TV, Android, Android TV, Chromecast, YouTube and Gemini are trademarks of Google
LLC. Apple TV is a trademark of Apple Inc. Microsoft and Fluent are trademarks of Microsoft
Corporation. All other names are trademarks of their respective owners. Halo is not affiliated
with or endorsed by any of them.
