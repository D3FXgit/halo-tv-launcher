# Changelog

All notable changes to Halo are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Halo uses
[Semantic Versioning](https://semver.org/).

## [1.1.0] – 2026-11

### Added
- Turkish user interface, setup wizard, assistant and voice.
- My pictures: your own photos as backgrounds, sent from your phone via a QR code.
- Your own app icons, sent from your phone (Customize → "Own icon from phone").
- Mono icons: a tile style with white logos on graphite for every app.
- "Download to TV" for any picture collection: up to 72 pictures stay on the TV and work offline
  (collections still stream by default).
- Favourites: a picture collection of your favourite pictures from every collection (hold OK on a
  picture → "Add to favourites", or ask the assistant).
- Remove single pictures from a pack, from My pictures or from Favourites (hold OK on a picture).

### Fixed
- Removed an empty Wikimedia Commons category from "Ocean & Coast".
- Pictures failing to load: downloads are now gentle with Wikimedia (one at a time, rate limits
  are waited out) and resume after an interruption.
- The phone pages (upload, API key) used the address of a VPN on the TV instead of its home-network
  address; a hint now explains when a VPN blocks local access.

## [1.0.0] – 2026

First public release.

### Added
- Home screen with uniform 16:9 app cards built from each app's own logo, with designed fallback
  cards; frosted-glass UI with focus glow and optional motion effects.
- Top row of favourites, recently used or most used apps; clock, date and weather; light, dark or
  automatic (sunrise/sunset) theme; navigation sounds.
- Backgrounds: animated Halo scene, and a picture library with collections from Wikimedia Commons
  (incl. picture of the day), NASA and Lorem Picsum (Unsplash); intervals from 1 minute to daily,
  or a pinned picture; credit line for every picture.
- Now-playing artwork as background and a full-screen Now playing view (needs media access).
- Weather from Open-Meteo (now, 12 hours, 7 days) with official DWD warnings for Germany via
  Bright Sky; location from the TV, the internet connection or a fixed city.
- "Ask Halo" voice assistant powered by Google Gemini with natural voices, using the user's own
  API key (optional).
- Profiles with Fluent Emoji avatars, each with its own favourites, order, hidden apps, card styles
  and background.
- Arrange Home (move, hide, uninstall) and Customize (name, colour, style, borrow another app's
  artwork).
- Search across apps and common TV settings.
- Optional accessibility service: YouTube-button remap and Home-button override.
- Halo Ambient screensaver (ambient mode and system screensaver).
- English and German user interface.
- First-run setup wizard.
- Developer helper Gradle tasks (tv.gradle.kts) and GitHub Actions build/release workflow.
