# iOS compatibility technical plan

This is a separate engineering plan. The Android application remains the working product; this phase does not create or publish an iOS app.

## Proposed architecture

Build a native SwiftUI application for iPhone and iPad, initially targeting iOS/iPadOS 16 or later. Keep one observable playback coordinator independent of screen lifetimes, a separate decorative-background coordinator, and persistent library/settings repositories. Use the existing Android JSON schemas and behavior as the specification. A future Kotlin Multiplatform module could share pure playlist rules and meter mathematics; extracting that module is optional and must not destabilize the Android app.

| Existing behavior | iOS implementation |
| --- | --- |
| Four AKAI recorder designs and MDP branding | SwiftUI Canvas/Paths and vector assets; a common recorder component takes the selected design |
| Clockwise synchronized reels | One accumulated monotonic playing-time clock, driven by actual audio engine transitions; display frames sample its phase |
| Local music and stereo meters | AVAudioEngine with AVAudioPlayerNode/AVAudioFile; a real decoded-PCM tap computes the existing RMS attack/release and live/held peaks per channel |
| Background music, lock screen and headsets | AVAudioSession playback category, remote command center, now-playing metadata and the audio background mode |
| YouTube links/playlists | Universal links/openURL to the official YouTube app with an HTTPS browser fallback; no stream extraction or downloading |
| Persistent playlists and preferences | Codable models in Application Support, atomic writes, stable IDs; lightweight preferences in UserDefaults |
| Romanian/English selection | Localized string resources with the saved in-app locale |
| Sharing and update information | ShareLink/UIActivityViewController and a platform-appropriate release/update destination |

The decorative video player must never become the music coordinator or change its audio-session category. Local music continues when the UI is backgrounded; decorative animation stops.

## Background media

Use UIDocumentPickerViewController/SwiftUI fileImporter for supported images, GIF and video. Retain security-scoped bookmarks where the provider supports them, and resolve stale bookmarks safely. For providers without durable access, offer an app-private imported copy with capacity checks. No broad photo-library or microphone permission is required for files selected through this picker.

Decode JPEG, PNG and WebP through ImageIO with target-size thumbnails, orientation correction and bounded memory. GIF playback needs an ImageIO frame decoder plus frame-duration scheduling (CADisplayLink or an equivalent display-driven view); SwiftUI's static Image alone does not animate a GIF. Keep only a small frame window in memory, decode off the main thread, and retain a still frame when animation is disabled.

Use AVQueuePlayer with AVPlayerLooper and AVPlayerLayer for videos. Set isMuted=true, volume=0, and construct video-only playback items so audio tracks cannot interfere with local music. Map Fit to resizeAspect and Fill to resizeAspectFill. Scene inactivity/backgrounding pauses decorative playback; disposal removes observers, loopers and player items. Dimming belongs above the media and below the opaque recorder/control panels.

Use only the owner's exact original couple GIF once supplied. Until it exists, retain the matte-black fallback and an honest missing-original status. Do not invent a replacement image.

## Persistence and file failure behavior

Store background identity/bookmark, media kind, fit/fill, dimming and animation preference independently of playlist and language settings. Preserve settings across ordinary relaunches. Deleted files, revoked bookmarks, unavailable cloud files, unsupported codecs and decode failures must fall back safely while keeping music controls usable. Avoid automatic deletion of music files or unrelated access bookmarks.

Android content URIs cannot be reused on iOS. Cross-device migration would require an explicit export/import format for playlist metadata and a user-assisted relinking/import of local media; this plan does not imply a synchronization service.

## Verification and delivery stages

1. Audit the current Android behaviors and schemas; establish Swift package targets and a native navigation/settings shell.
2. Implement persistent libraries, official YouTube handoff, localization, sharing and the four responsive recorder designs.
3. Implement real local audio, interruptions/focus handling, lock-screen controls and PCM meter ballistics. Unit-test the same known PCM fixtures and reel state transitions used on Android.
4. Implement background import, real GIF/video animation, loop/mute guarantees, fit/fill, dimming, persistence and lifecycle cleanup.
5. Add XCTest/XCUITest coverage using color-changing GIF/MP4 fixtures with an audio track, concurrent music playback, file removal, stale bookmarks, scene changes, memory pressure and repeated source switching. Verify rendered frames rather than random/timer meter values.
6. Test portrait/landscape iPhones and iPads, including a physical device for audio quality, Bluetooth/headsets, haptics, interruptions and long-running battery/thermal behavior. Run Instruments for allocations, leaks and energy use.
7. Configure a macOS GitHub Actions build/test workflow. Producing a device-installable IPA/TestFlight build requires the owner's Apple developer team, app identifier, signing/provisioning and App Store Connect access. Prepare a reviewable signed build before requesting distribution approval; publishing is a separate authorized step.

Acceptance requires stable music while backgrounds change, no decorative-video audio or audio-session takeover, real animated frames, predictable pause/release behavior, persistent selections, and preservation of the existing library behavior. Physical-device and App Store delivery results must be reported separately from simulator/unit results.
