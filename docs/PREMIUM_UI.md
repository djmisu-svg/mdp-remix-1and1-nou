# Premium interface

PR #2 implements the premium redesign across Studio, the YouTube library, and Preferences. The application theme uses matte black `#09090B`, blood red `#A60920`, and neutral white/achromatic details; the launcher and Android startup theme match.

## Implemented

- Responsive phone layout with a full tape-deck hero and a single scrolling local library. Large tablets place the deck beside the library and use a navigation rail.
- Custom Canvas reel-to-reel mechanism with shaded metal flanges, apertures, tape packs, tape path, guides, head block, and screws. Reels move with actual playback and retain their angle when paused; pack proportions follow track progress.
- Illuminated power control with a sweeping red light, breathing glow, press feedback, and haptics. Switching off pauses the real player and disables deck controls. Power-on enables the deck; it does not start audio unexpectedly.
- Tactile previous/play/pause/next/stop controls; rewind/forward ten seconds on wider decks; seek, volume, current-track metadata, and favorites.
- Stereo VU needles and peak LEDs driven by decoded local PCM, not random values or microphone capture. The transparent Media3 audio processor reads source RMS and sample peaks before the output volume/mixer, publishes at up to 25 Hz, and clears on pause, buffering, seek, errors, and shutdown. The dBFS scale spans -60 to 0. Compressed audio is decoded rather than sent through offload/passthrough so it remains measurable.
- Restyled YouTube cards, search, playlist management, favorites, and recently opened links. Playback continues to use official Android intents to YouTube/the browser.
- Restyled local tracks, albums, playlists, favorites, reordering, import, and management dialogs, retaining existing storage and playback behavior.
- Romanian/English interface controls, saved language selection, private Android sharing, and the existing GitHub releases/update link. Screen navigation preserves each screen's UI state.
- Isolated `ro.mdp.remix.premiumtest` test APK, with production ID `ro.mdp.remix` unchanged. No migration or modification of the installed production app's data.

## Automated verification

CI runs JVM unit tests for both build variants, including 12 PCM/meter tests, Android lint, both APK builds, signature/package/label validation, and an Android 35 coexistence test with a separately signed production fixture.

Premium Test instrumentation exercises navigation, Romanian/English selection, power control, real YouTube playlist creation/rename/recreation, and real stereo WAV playback through Media3 into the meters followed by a pause/reset check. It saves phone screenshots; CI also captures a tablet layout and publishes screenshots and verification reports separately from the installable APK.

Run results and downloadable artifacts on GitHub Actions are the source of truth; this source document does not assert an unexecuted test passed.

## Device validation still required

Physical-device sound quality, Bluetooth/headset behavior, OEM battery/background restrictions, and subjective haptic feel require checks on actual phones/tablets. System permission/file-picker text and imported metadata use the device/file language. The VU display measures decoded source audio, not a calibrated acoustic measurement of the speaker output. YouTube audio remains in the external official player and is not measured by this deck.
