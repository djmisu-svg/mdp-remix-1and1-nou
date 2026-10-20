# MdP RemiX 1&1 Premium

The complete application lives on `feature/premium-settings-share-updates` (PR #2). That branch contains `main` and the earlier YouTube-player branch; all approved Premium features are preserved. PR #2 is not merged automatically.

Settings offers the installed APK through Android Sharesheet, the verified direct public release-download link, or the complete APK plus `.mdpremix` personal collection. The collection includes actual local audio, the selected GIF/image/video background, playlists, favorites and preferences. FileProvider grants temporary read access. See [sharing and updates](docs/SHARING_AND_UPDATES.md).

Production uses `ro.mdp.remix`. A single permanently backed-up Release key signs every future update, and versionCode increases. The new identity requires a fresh migration if the old Debug certificate differs: [save and verify the old data first](tools/BACKUP_LEGACY_TABLET.md), keep the old app installed until restoration is verified, and follow the [tablet signing setup](docs/ANDROID_RELEASE_SIGNING_TABLET.md). No signing key or password belongs in the repository or an artifact.

A signed verification build must pass the production fresh-install and same-key upgrade regression. Public publication promotes the exact tested artifact without rebuilding it, after the owner verifies the private backup, WhatsApp delivery and another Android device. See [final release status and procedure](docs/FINAL_RELEASE_STATUS.md). A production release is not claimed before these checks succeed.

The four AKAI skins, clockwise playback-driven reels, real stereo PCM peak/VU meters, RO/EN preferences, local albums/playlists, YouTube links and animated background controls remain implemented. See [premium details](docs/PREMIUM_UI.md) and [background behavior](docs/BACKGROUND_SYSTEM.md). The original owner-selected couple GIF is absent from the repository; selected custom backgrounds remain supported and no substitute is bundled.

Native Android music player and YouTube playlist-link manager, written in Kotlin with Jetpack Compose and AndroidX Media3. Matte black #09090B and blood red #A60920 interface with Romanian/English selection and a silver AKAI Classic recorder option; responsive layouts for phones and tablets. Android 6.0/API 23 or later.

## Features

- Save YouTube video and playlist links with your own titles. Search titles, URLs and collection names.
- Create, rename, delete, favorite and reorder local playlists of saved links; add, remove and reorder their entries.
- Favorite saved links and revisit recently opened links and playlists. Local data persists across app restarts.
- Open playback using the official YouTube Android app, with a standard HTTPS browser fallback. No scraping, stream extraction, downloading, hidden playback or unauthorized YouTube APIs.
- Import local audio through Android's document picker. Play/pause, previous/next, seek, volume, albums, favorites and local audio playlists with background and lock-screen playback.

Playlists managed here are local collections of saved links; renaming or arranging them does not edit the playlist on your YouTube account. No account or API key is required by this app. Viewing YouTube content requires connectivity through YouTube or a browser; saved links, their organization and local audio remain available offline.

## Release download

The stable direct APK address is:

`https://github.com/djmisu-svg/mdp-remix-1and1-nou/releases/latest/download/MdP-RemiX-Premium.apk`

It becomes available only after a verified signed Release is published. Until that happens, the app reports that no public release is available instead of sharing a repository page or Actions link. The versioned APK, SHA-256 checksums and update manifest are published together. Sharing an installed APK remains available offline.

Premium Test remains a separate development package (`ro.mdp.remix.premiumtest`). Its Actions artifact is for development verification and data-restoration checks; it is not the public production Release.

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

The prior Premium baseline passed both APK builds, JVM tests/lint, 16 Android 35 regressions, real GIF/MP4 background playback, stereo PCM meters and a disposable Test-package upgrade. That does not establish compatibility with the owner's old Debug signing certificate or prove WhatsApp delivery.

The release workflow is configured to exercise the actual permanent signer with a non-debuggable production fresh install and a higher-code same-key update. Those checks are not considered passed until a completed signed run reports them. Production signing, real owner-data restoration, WhatsApp transmission and another physical Android device must be reported separately. No local SDK or physical-device test is assumed to have run.

See [testing](docs/TESTING.md), [final release status](docs/FINAL_RELEASE_STATUS.md), and the reports attached to the actual completed workflow run.

## Project layout

`links` contains strict YouTube URL validation, local collections, JSON persistence and its ViewModel. `external` launches official playback. `data` handles imported audio and atomic local-library storage. `playback` owns the Media3 service/controller. `ui` contains the two responsive Compose libraries. No analytics, advertisements or embedded YouTube playback SDK is included.
# Premium Test APK (PR #2)

The PR workflow publishes **MdP-RemiX-premiumtest**, containing **MdP-RemiX-Premium-Test.apk** and `SHA256SUMS.txt`. The launcher name is **MdP RemiX Premium Test**, with application ID **ro.mdp.remix.premiumtest**. It installs alongside the existing app without replacing it. The production ID remains **ro.mdp.remix**, and its data stays in its existing private storage. The test app starts with a separate library and settings; do not uninstall the existing application.

See [Premium Test build and verification](docs/PREMIUM_TEST.md) for the current artifact and verification process. PR #2 is not merged automatically.

