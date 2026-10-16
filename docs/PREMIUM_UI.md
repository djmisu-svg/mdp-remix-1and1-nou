# Premium interface

PR #2 implements the premium redesign across Studio, the YouTube library, and Preferences. The application theme uses matte black `#09090B`, blood red `#A60920`, and neutral white/achromatic details; the launcher and Android startup theme match. Green and yellow are reserved exclusively for the authentic stereo LED level meters, alongside their red peak segments.

## Implemented

- Responsive phone layout with a full tape-deck hero and a single scrolling local library. Large tablets place the deck beside the library and use a navigation rail.
- Custom Canvas reel-to-reel mechanism with shaded metal flanges, apertures, tape packs, tape path, guides, head block, and screws. A service-owned monotonic motion model advances both reels only while Media3 reports actual playback. Pause, buffering, audio-focus loss, stop, errors and completion freeze the current phase; play resumes it. Tabs, responsive layout changes and Activity recreation sample the same mechanism instead of restarting an animation. Background playback continues to advance the phase while offscreen frame rendering is suspended. The back-wound take-up path supports counter-rotating reels; pack proportions follow track progress. Mechanical motion remains enabled when Android UI animations are disabled.
- Illuminated power control with a sweeping red light, breathing glow, press feedback, and haptics. Switching off pauses the real player and disables deck controls. Power-on enables the deck; it does not start audio unexpectedly.
- Tactile previous/play/pause/next/stop controls; rewind/forward ten seconds on wider decks; seek, volume, current-track metadata, and favorites.
- Stereo VU needles and segmented peak LED bars driven by decoded local PCM, not random values, playback timers or microphone capture. Each channel has its own RMS attack/release envelope and sample-peak detector with hold and decay. Green segments show normal levels, yellow high levels and red only near-full-scale peaks, with a soft lens glow and a held peak marker. The transparent Media3 processor measures source samples before the output volume/mixer and clears on pause, buffering, seek, track transition, errors and shutdown. Envelopes advance by PCM frame duration, independent of decoder buffer boundaries; empty buffers are safe and audio bytes are unchanged. The dBFS scale spans -60 to 0. Compressed audio is decoded rather than sent through offload/passthrough so it remains measurable.
- Restyled YouTube cards, search, playlist management, favorites, and recently opened links. Playback continues to use official Android intents to YouTube/the browser.
- Restyled local tracks, albums, playlists, favorites, reordering, import, and management dialogs, retaining existing storage and playback behavior.
- Romanian/English interface controls, saved language selection, private Android sharing, and the existing GitHub releases/update link. Screen navigation preserves each screen's UI state.
- Isolated `ro.mdp.remix.premiumtest` test APK, with production ID `ro.mdp.remix` unchanged. No migration or modification of the installed production app's data.

## Automated verification

CI runs JVM unit tests for both build variants, including PCM envelope, stereo channel separation, silence, peak hold/release, empty-buffer and reel lifecycle regressions, Android lint, both APK builds, signature/package/label validation, and an Android 35 coexistence test with a separately signed production fixture.

Premium Test instrumentation exercises navigation, Romanian/English selection, power control, real YouTube playlist creation/rename/recreation, real asymmetric stereo WAV playback into the visible meters, silence and seek/pause resets, plus reel rotation, pause/resume, stop, completion and Activity recreation. A real-Android processor regression covers empty Media3 pipeline buffers and transparent PCM output. It saves phone screenshots; CI also captures a tablet layout and publishes screenshots and verification reports separately from the installable APK.

The verified APK uploads before emulator tests, so a later UI failure remains visible without hiding a successfully built artifact. Screenshots are artifact files only; no image data is printed in logs. See [emulator failure analysis](EMULATOR_VERIFICATION.md).

Run results and downloadable artifacts on GitHub Actions are the source of truth; this source document does not assert an unexecuted test passed.

## Device validation still required

Physical-device sound quality, Bluetooth/headset behavior, OEM battery/background restrictions, and subjective haptic feel require checks on actual phones/tablets. System permission/file-picker text and imported metadata use the device/file language. The VU display measures decoded source audio, not a calibrated acoustic measurement of the speaker output. YouTube audio remains in the external official player and is not measured by this deck.
