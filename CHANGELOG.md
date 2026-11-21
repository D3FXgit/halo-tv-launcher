# Changelog

All notable changes to Halo are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Halo uses
[Semantic Versioning](https://semver.org/).

## [1.2.0] – 2026-11

### Added
- Back up & restore (Settings → More, or "Restore a backup" on the first setup screen): save your
  profiles, home rows, widgets and settings as a file on your phone and bring them back after
  "Clear data" or on another TV. Local only – no cloud; API keys are never part of a backup.
- "Movies & Series" picture collection: fan art of trending, popular and upcoming films and series
  from TMDB, with title and year. Needs your own free TMDB API key, pasted from your phone.
- Updates over the air: Halo checks the project's GitHub releases once a day (or from Settings →
  "Check for updates") and asks before installing a new version. Only updates signed like your
  Halo are installed, and Android asks you to confirm.
- More home-screen widgets to pick in Settings → Widgets: next days' weather, calendar, sun & moon,
  world clock, holiday countdown and TV status (connection, VPN, free storage), plus a widget size
  setting (small to extra large) for the clock and all widgets.
- Running apps (Settings → More, or ask the assistant): the apps used since the TV started, with
  Force stop per app and Close all.
- Easter eggs. Ask Halo to sing, or try "open the pod bay doors", "do a barrel roll", "self-destruct",
  "beam me up", "let it snow", "party mode", "flip a coin", "roll a die", "good night" … and a few
  more. The remote has two secrets of its own.

### Changed
- Larger weather text: the weather card's next hours (bigger icons and temperatures), the
  next-days widget and the weather page are easier to read from the sofa.
- Smoother focus animations: tiles glide instead of jumping when focus arrives, and settle without
  bouncing.

### Fixed
- Home navigation with the D-pad: ▲/▼ always move to the next row (to the tile last used there)
  instead of a card far to the left or right, and ▲ from the favourites row reaches the row-mode
  switch and the top bar (Settings) again.
- Arranging favourites while the first row showed "Recently used" or "Most used" looked as if the
  new order was not saved: opening Arrange now switches the first row back to Favourites.

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
