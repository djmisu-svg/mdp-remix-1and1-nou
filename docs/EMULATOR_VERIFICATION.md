# Android emulator failure and fix

The diagnostic PR run `38895595681` built both APKs and passed lint/unit tests. Two of its three UI tests passed (navigation/language/power and playlist create/rename/recreation). The live stereo-audio test failed because playback stopped with an actual application exception; emulator shutdown was cleanup, not the cause.

The nested playback error was:

```
IllegalArgumentException: The source buffer is this buffer
  ByteBuffer.put
  MeterAudioProcessor.queueInput
  AudioProcessingPipeline.processData
```

Media3 calls an audio processor with its shared empty buffer during pipeline probes. Before its first allocation, `BaseAudioProcessor.replaceOutputBuffer(0)` returns that same empty object. Attempting `output.put(input)` therefore copies a buffer into itself, which throws even when it contains zero bytes. The processor now returns immediately for empty input. A real-Android regression covers the shared empty buffer, other empty direct buffers, subsequent stereo PCM consumption, unchanged bytes and draining. The real WAV playback/VU/pause-reset test remains required.

An earlier playlist test timeout was a missing `youtube_grid` test tag on the actual lazy grid. The tag was added, and the diagnostic run subsequently passed playlist creation, rename and activity recreation.

The side-by-side installation check uses a disposable production fixture on the CI emulator. That fixture is stopped before UI testing so its process/window cannot interfere. Tests fail explicitly on observed external system crash/ANR dialogs; they do not dismiss or hide them. The fixture stays installed and its private data marker is rechecked after testing.

## Artifacts and logs

The verified Premium Test APK and SHA-256 checksum upload **before** emulator tests, after successful build/lint/unit tests and package/label/signature verification. A later UI failure still fails the job and is reported separately, while the already-uploaded APK remains downloadable.

Screenshots remain PNG/JPEG artifacts. The workflow does not print screenshot bytes, Base64, hex dumps or preview markers. Text diagnostics include failing assertions, the nested player exception, and a bounded filtered logcat excerpt. Reports and screenshots upload even when emulator tests fail.

Production application ID remains `ro.mdp.remix`; the test package remains `ro.mdp.remix.premiumtest`. No step accesses a user's device, uninstalls the installed production app, or migrates its data. The PR remains unmerged.
