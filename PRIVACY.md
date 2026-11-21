# Privacy statement

Halo is a home-screen app (launcher) for Google TV and Android TV. It is designed to work without
collecting any data about you.

## What Halo does not do

- No analytics, telemetry, crash reporting, advertising or tracking of any kind.
- No user account and no Halo server. The Halo project does not receive any data from your TV.

## Data stored on the TV

Your settings (favourites, profiles, card styles, background choice, weather city, language) and,
if you enter them, your **Gemini API key** and **TMDB API key** are stored only in Halo's private
app storage on the TV. Downloaded background pictures and weather data are cached there too.
Uninstalling Halo (or clearing its storage) deletes all of it.

**Backups** (Settings → "Back up & restore") are made only when you ask: the TV hands a JSON file
with your settings, profiles and home rows to your phone over the temporary home-network page
described below, and you keep it wherever you like. API keys are never included in a backup.
Restoring a backup sends such a file back to the TV the same way. Nothing is uploaded to a cloud.

## Network requests

Halo only contacts these services, and only for the purpose given:

- **Open-Meteo** (`api.open-meteo.com`, `geocoding-api.open-meteo.com`) — weather forecast for
  the approximate coordinates of the weather location; city search when you type a city.
- **Bright Sky** (`api.brightsky.dev`) — official DWD weather warnings for those coordinates.
- **ipwho.is** — only when the TV's location is not available and no fixed city is set: looks up
  the approximate location of your internet connection from its public IP address.
- **The TV's geocoder** (Android `Geocoder`; on Google TV a Google service) — only when the TV's
  location is used: turns its approximate coordinates into the place name shown with the weather.
- **Wikimedia** (`commons.wikimedia.org`, `upload.wikimedia.org`, `api.wikimedia.org`,
  `en.wikipedia.org`), **NASA** (`images-api.nasa.gov`, `images-assets.nasa.gov`) and **Lorem
  Picsum** (`picsum.photos`) — lists of background pictures and the pictures themselves, only when
  a picture collection is selected.
- **TMDB** (`api.themoviedb.org`, `image.tmdb.org`) — only if you entered your own TMDB key and
  selected the "Movies & Series" collection: lists of popular and upcoming films and series in
  Halo's language, and their backdrop pictures. These requests are made with your key and are
  subject to [TMDB's terms of use](https://www.themoviedb.org/api-terms-of-use) and privacy policy.
  This product uses the TMDB API but is not endorsed or certified by TMDB.
- **Google Gemini API** (`generativelanguage.googleapis.com`) — only if you entered your own API
  key and use "Ask Halo": your request as text plus the context needed to answer (date and time,
  profile names, installed and favourite app names, the weather shown, what is playing), and the
  reply text for the natural voice. These requests are made with your key and are subject to
  [Google's Gemini API terms](https://ai.google.dev/gemini-api/terms) and privacy policy.
- **GitHub** (`api.github.com`, `github.com` and its download servers) — at most once a day (if
  "Check for updates automatically" is on) or when you check by hand: reads the latest release of
  Halo, and downloads it only after you choose "Update now".

During setup you can paste the Gemini key from your phone instead of typing it. For that the TV
runs a small temporary web page that is reachable only in your home network (bound to the TV's
LAN address, protected by a one-time code from the QR code, stopped after the key arrives or after
15 minutes). The key is verified with Google's Gemini API and then stored on the TV.

Your own wallpapers and app icons are sent the same way: while the "send from your phone" QR code
is on screen (at most 30 minutes), the TV runs a temporary page in your home network only,
protected by a one-time code. Pictures are decoded and re-encoded on the TV and stored only in
Halo's private app storage; they never leave the TV. Uninstalling Halo deletes them.

Like any internet request, these requests reveal your IP address to the respective service. Each
service's own privacy policy applies.

## Permissions

- **Location (optional)** — used only to determine the weather location.
- **Microphone (optional)** — used only while you talk to "Ask Halo"; speech recognition is done by
  the TV's system speech service.
- **Notification / media access (optional, granted by adb or in settings)** — used only to show
  what is playing and its artwork; nothing leaves the TV.
- **Accessibility service (optional)** — used only to react to the remote's YouTube button, to
  the star button (opens Halo's assistant, if chosen in Halo), to the stock launcher opening (Home override) and, only
  when you choose "Force stop" or "Close all" in Halo's Running apps, to press "Force stop" and "OK" on that app's page
  in the TV's Settings. It reads no other screen content and does not log what you type.
- **Usage access (optional, granted in the TV's settings)** — used only to list the apps used since the TV started in
  Running apps; nothing leaves the TV.
- **Query all packages** — a launcher has to list all installed apps.
- **Install apps (asked when you first update)** — used only to install Halo's own updates; Android
  asks you to confirm every installation, and only updates signed with Halo's own key are offered.

## Contact

Questions or concerns: please open an issue in this repository.
