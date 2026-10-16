# Premium Test build

The `premiumTest` build type inherits the debug configuration and adds the `.premiumtest` application ID suffix. It keeps the Kotlin namespace, production application ID, main resources, and existing app functionality unchanged. Only this variant overrides the launcher label to **MdP RemiX Premium Test**.

| Build | Application ID | Launcher label |
| --- | --- | --- |
| Production debug/release | `ro.mdp.remix` | MdP RemiX 1&1 |
| Premium Test | `ro.mdp.remix.premiumtest` | MdP RemiX Premium Test |

Android gives these packages distinct app UIDs and private storage. The test app has its own playlists, settings, and document permissions. It does not migrate, clear, import, or modify the existing app's data. Keep the existing app installed.

## Build and verification

```sh
bash ./gradlew testDebugUnitTest lintDebug assembleDebug testPremiumTestUnitTest lintPremiumTest assemblePremiumTest
```

GitHub Actions checks both APK signatures and their exact package IDs and labels. It then uses a disposable Android 35 emulator to install a production fixture signed with a different temporary certificate, write a private data marker, and install the exact Premium Test APK without replacement flags. The check verifies both apps launch, have distinct UIDs, and retain isolated data; it confirms that the production package path and data marker survive. This emulator check does not access any user's device or app data.

The workflow publishes only the test APK in artifact **MdP-RemiX-premiumtest**, together with a SHA-256 checksum. The production APK is used for CI verification and is not included in that artifact. Download the artifact from the successful PR #2 Actions run, extract the ZIP, and select **MdP-RemiX-Premium-Test.apk**. No uninstall or production update is required.

Full music playback, physical-device behavior, and other device-specific checks remain described in [TESTING.md](TESTING.md). PR #2 stays open until explicitly merged.
