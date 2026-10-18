# Premium AKAI recorder interfaces

Sharing now prepares the unchanged installed APK plus a complete portable `.mdpremix` collection (local audio, background, playlists and saved settings). Settings also includes panel transparency, the public brand library and a GitHub updater that verifies package, certificate and SHA-256 before asking Android to install. See [sharing and updates](docs/SHARING_AND_UPDATES.md) for use, verification scope and signing prerequisites. No production release is claimed without its original key and successful verification.

PR #2 now includes the complete Studio, YouTube library, and Preferences redesign, with four persistently selectable AKAI recorder designs, synchronized clockwise reels, attached reel lettering, a complete MDP launcher icon and real PCM-driven stereo peak/VU meters. See [premium features and verification](docs/PREMIUM_UI.md). Download the isolated **MdP-RemiX-premiumtest** artifact from the latest successful PR build; the original application and its data stay separate.

Premium background controls support selected images, real animated GIFs and muted looping videos behind the recorder. See [background behavior and missing original asset](docs/BACKGROUND_SYSTEM.md) and the separate [iOS compatibility technical plan](docs/IOS_COMPATIBILITY_PLAN.md). The original owner-selected couple GIF is absent from the repository and must be supplied; no substitute is bundled.

Production v1.0 signing is prepared but not yet executed. Follow the [Android-tablet signing instructions](docs/ANDROID_RELEASE_SIGNING_TABLET.md) to reuse an existing production key or securely create and back up a first-release key, configure GitHub secrets, and run the manual signed-release workflow. No unsigned or debug-signed APK is published as a production release.

# MdP RemiX 1&1

Native Android music player and YouTube playlist-link manager, written in Kotlin with Jetpack Compose and AndroidX Media3. Matte black #09090B and blood red #A60920 interface with Romanian/English selection and a silver AKAI Classic recorder option; responsive layouts for phones and tablets. Android 6.0/API 23 or later.

## Features

- Save YouTube video and playlist links with your own titles. Search titles, URLs and collection names.
- Create, rename, delete, favorite and reorder local playlists of saved links; add, remove and reorder their entries.
- Favorite saved links and revisit recently opened links and playlists. Local data persists across app restarts.
- Open playback using the official YouTube Android app, with a standard HTTPS browser fallback. No scraping, stream extraction, downloading, hidden playback or unauthorized YouTube APIs.
- Import local audio through Android's document picker. Play/pause, previous/next, seek, volume, albums, favorites and local audio playlists with background and lock-screen playback.

Playlists managed here are local collections of saved links; renaming or arranging them does not edit the playlist on your YouTube account. No account or API key is required by this app. Viewing YouTube content requires connectivity through YouTube or a browser; saved links, their organization and local audio remain available offline.

## Download the APK

For branch `feature/premium-settings-share-updates`:

1. Open [GitHub Actions — Android Premium Test APK](https://github.com/djmisu-svg/mdp-remix-1and1-nou/actions/workflows/build.yml).
2. Choose the successful green run for the latest branch commit.
3. Under **Artifacts**, download **MdP-RemiX-premiumtest** while signed into GitHub.
4. Extract the ZIP and install **MdP-RemiX-Premium-Test.apk** on your Android device. Allow installation from the browser/file manager if Android asks.

The workflow runs on push, pull request and manual dispatch. It sets up JDK 17, installs an explicit list of current SDK packages, validates the Gradle wrapper, runs JVM tests and Android lint, builds Debug and debug-derived Premium Test APKs, verifies their signatures and distinct package IDs and uploads the APK with a SHA-256 checksum. Artifacts are retained for 30 days. Test/lint reports are uploaded separately. No secrets or manual code edits are needed.

## Use the app

Start in **Studio**. Use the recorder design selector to choose AKAI Classic, AKAI Black Edition, AKAI Red Edition or AKAI Studio Pro; the selection is saved for future launches. Switch to the **YouTube** library to organize music links. Add a video or playlist URL with a name, then organize it into local playlists. Playlist actions allow renaming, favorites, ordering and deletion. Entry actions allow title changes, favorites, collection membership and deletion. **Recente** shows successfully handed-off links and opened collections; it does not claim that a video finished playing in YouTube.

Use **Studio** for the offline audio player. **Importă / Import** opens Android's local-file picker. Importing retains document permissions without copying the audio or requesting broad storage access. Removing an entry never deletes the original file. Moving/deleting the original file can make playback unavailable; import it again from the new location.

The local player uses a MediaSessionService, handles audio focus and headphone removal, and exposes system media controls. Active audio continues when the app is dismissed from Recents. Force stopping the app stops playback. The playback queue is not restored after the process/device restarts. Media3 playback is used only for imported local audio; YouTube playback belongs to the external official app/browser.

## Toolchain and build

| Component | Pinned version |
| --- | --- |
| JDK | 17 |
| Gradle wrapper | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Compose compiler plugin | 2.0.21 |
| Compose BOM | 2024.12.01 |
| AndroidX Media3 | 1.5.1 |
| compile / target SDK | 35 |
| Android Build Tools | 35.0.0 |
| Minimum Android SDK | 23 |

Root `build.gradle` and `settings.gradle` are the effective scripts; equivalent root `.kts` copies are retained so previous workspace files are preserved. The app module uses `app/build.gradle.kts`. Production application ID remains `ro.mdp.remix`; release signing is supplied by the protected `android-production` environment. The test package keeps its `.premiumtest` suffix and separate debug signing.

Structural checks use only Python's standard library and do not download or install anything:

```sh
python3 tools/verify_project.py
```

In a configured JDK 17/Android SDK environment:

```sh
bash ./gradlew --no-daemon --stacktrace testDebugUnitTest lintDebug assembleDebug
```

The raw Gradle result is `app/build/outputs/apk/debug/app-debug.apk`; CI verifies and publishes it as `MdP-RemiX.apk`. On Windows use `gradlew.bat`. The SDK package list is exactly `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`; the obsolete standalone `tools` package is never requested.

## Verification status

The project is published on **feat/mdp-remix-youtube-player** in [Pull Request #1](https://github.com/djmisu-svg/mdp-remix-1and1-nou/pull/1). GitHub authorization is restored; no manual source upload is necessary. Follow the [branch Actions runs](https://github.com/djmisu-svg/mdp-remix-1and1-nou/actions?query=branch%3Afeat%2Fmdp-remix-youtube-player) for the actual build result and APK artifact.

The first real CI run passed Python checks, JDK/SDK setup and wrapper validation, then identified two Compose receiver-scope compilation errors. The layout now captures its dimensions before entering nested Compose scopes. Subsequent runs validate the fix, app JVM tests, Android lint and APK signing. No local SDK installation/build retry was used. Installation and real playback on a Samsung device remain unverified.

See [testing](docs/TESTING.md), [delivery checks](docs/VERIFICARE_LIVRARE.md) and [upload instructions](PENTRU_GITHUB_ACTIONS.md).

## Project layout

`links` contains strict YouTube URL validation, local collections, JSON persistence and its ViewModel. `external` launches official playback. `data` handles imported audio and atomic local-library storage. `playback` owns the Media3 service/controller. `ui` contains the two responsive Compose libraries. No analytics, advertisements or embedded YouTube playback SDK is included.
# Premium Test APK (PR #2)

The PR workflow publishes **MdP-RemiX-premiumtest**, containing **MdP-RemiX-Premium-Test.apk** and `SHA256SUMS.txt`. The launcher name is **MdP RemiX Premium Test**, with application ID **ro.mdp.remix.premiumtest**. It installs alongside the existing app without replacing it. The production ID remains **ro.mdp.remix**, and its data stays in its existing private storage. The test app starts with a separate library and settings; do not uninstall the existing application.

See [Premium Test build and verification](docs/PREMIUM_TEST.md) for the current artifact and verification process. PR #2 is not merged automatically.

