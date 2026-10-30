# Contributing to Halo

Thanks for your interest in improving Halo! Bug reports, ideas and pull requests are welcome.

## Reporting bugs and ideas

Open an issue and include:
- the device (e.g. Google TV Streamer, Chromecast with Google TV) and Android version,
- the Halo version (the APK file name, or `adb shell dumpsys package app.halo.tv | grep versionName`),
- what you did, what you expected and what happened,
- if possible, a log: `./gradlew tvLogcat` (see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)) or
  `adb logcat -d -s HaloWall:* HaloVoice:* Halo:* AndroidRuntime:E`.

Please remove anything personal (API keys, IP addresses, account names) from logs and screenshots
before posting them.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep changes focused; one feature or fix per pull request.
3. Follow the existing style (Kotlin official code style, Jetpack Compose, no new dependencies
   without a good reason).
4. Make sure `./gradlew assembleRelease` succeeds and test on a real TV or an Android TV emulator
   with the remote / D-pad.
5. User-visible text must be available in English and German.
6. Describe what you changed and why; add an entry to [CHANGELOG.md](CHANGELOG.md).

## Ground rules

- **No tracking.** Halo must not add analytics, ads or telemetry.
- **No bundled third-party artwork** (app logos, copyrighted images). Brand colours and app names
  for identification are fine; logos come from the installed apps themselves.
- **Only freely licensed picture sources** with visible attribution.
- **No secrets in the repository.** API keys are entered by each user at runtime.

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
