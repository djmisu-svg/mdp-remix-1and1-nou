# Premium background system

The existing AKAI recorder, MDP launcher resources, four recorder designs, music player, PCM meters, libraries and Romanian/English settings remain in place. Background media is a separate decorative layer below the app's controls. Recorder panels, navigation and cards retain opaque dark surfaces for readability; the surrounding Studio, YouTube and Settings areas reveal the background beneath the dimming overlay.

## Original artwork status

The feature branch and `main` were searched recursively, including existing assets, resource files, documentation and the legacy HTML. Neither contains the owner's original animated couple GIF or a reference to it. **The exact original GIF must be supplied.** No replacement couple image is invented or bundled.

The canonical future location is `app/src/main/assets/backgrounds/original_couple.gif`. The app resolves only that exact original asset when present. Until supplied, the original/default state is matte black #09090B and Settings reports the missing artwork. Restore Original safely returns to that state. The color-changing GIF/MP4 fixtures in `androidTest/assets` are test-only media, never a default background and never part of the installable application APK.

## User controls

Open Settings / Setări and find Background / Fundal. Change Background uses Android's system document picker for JPEG/JPG, PNG, WebP, GIF and video documents. No broad storage, photo-library or microphone permission is added. The app validates selected media and retains a persistable read grant before committing a new selection. Providers that cannot retain access produce a useful error and leave the prior selection in place.

Fit preserves the complete image/video with letterboxing as needed. Fill preserves aspect ratio while cropping the edges to cover the viewport. Dimming changes only the background, leaving foreground controls unaffected. Animation on/off freezes/resumes GIF or video playback. Restore Original returns background preferences to their defaults without changing recorder design, library, language or music playback.

Selections, fit/fill, dimming and animation are stored separately in private, versioned background preferences. File deletion, revoked access, corrupted media and unsupported device codecs produce a safe fallback/error while leaving the music controls usable. Background selection does not delete files or release document grants that the audio library might also need.

## Rendering and lifecycle

Static images are decoded off the UI thread at bounded resolution with orientation handling. GIFs use actual Android animated-image decoding on modern Android, with a bounded native Movie fallback on older supported Android releases. Animation uses decoded frames, not a simulated moving poster.

Videos use a separate Media3 player and TextureView, with repeat enabled, volume fixed to zero, audio tracks disabled and audio-focus handling disabled. It does not use the music player's session, meter processor or reel state. Android/Media3 codec support determines which video files can play; MP4/H.264 is covered by a real test fixture containing an audio track.

Decorative animation pauses when the Activity is not visible and when Animation is off. Source changes and disposal cancel decoding, remove drawable callbacks, release player/surface resources and discard stale asynchronous results. Repeated dimming or fit/fill changes do not recreate the media source. Music continues through its existing service lifecycle.

## Verification scope

JVM regressions cover settings classification, validation and persistence serialization. Android regressions exercise real document selection/grants, moving GIF/video frames, muted video looping, concurrent local PCM playback, settings restoration, missing files and phone/tablet layouts. Existing recorder, icon, language, library and PCM regressions remain required.

CI builds both APK variants, runs JVM tests and lint, verifies signatures/package identities, uploads the installable Premium Test APK before emulator tests, and publishes test reports/screenshots separately. Screenshots and media bytes are never printed to the Actions logs. Source documentation does not assert that an unexecuted test passed; the linked GitHub Actions run is the delivery evidence.

The package remains `ro.mdp.remix.premiumtest`, separate from production `ro.mdp.remix`, with production signing configuration unchanged. Actual Samsung/iPad/iPhone hardware behavior is outside Android emulator verification. The separate [iOS compatibility plan](IOS_COMPATIBILITY_PLAN.md) describes the proposed native iOS implementation; this phase does not produce an iOS binary.
