# Final Premium release: verification and delivery

The final application is the existing Premium source on `feature/premium-settings-share-updates`, not the older `main` application. The release package remains **ro.mdp.remix**. Premium Test stays separate. No workflow uninstalls the owner's app or clears its data.

## Permanent signing identity

Configure the permanent keystore only once using the tablet setup described in [ANDROID_RELEASE_SIGNING_TABLET.md](ANDROID_RELEASE_SIGNING_TABLET.md). Keep the encrypted key backup and password in separate private locations. The protected `android-production` environment supplies the existing signing secrets. Neither workflow uploads signing keys/passwords as artifacts, saves Gradle signing caches, nor generates a replacement key.

A new permanent release certificate cannot update a legacy Debug-signed installation with a different certificate. Complete and verify the [legacy data backup](../tools/BACKUP_LEGACY_TABLET.md), restore into the separate test app and inspect local audio, GIF/background, playlists and preferences before considering any manual migration. An APK alone does not include that private data.

## Build one signed verification candidate

From Termux, after signing is configured:

```sh
gh workflow run android.yml --repo djmisu-svg/mdp-remix-1and1-nou --ref feature/premium-settings-share-updates -f target=release -f publish_release=false
```

The signed workflow performs unit tests, lint, package/signature/certificate/alignment checks and a fresh installation of the actual non-debuggable production APK on a disposable Android 35 emulator. It saves a seeded library containing local/YouTube playlists, favorites/order/history, settings, actual WAV/GIF media and a private marker. It builds a private higher-versionCode fixture using the **same permanent key**, installs that fixture with `adb install -r`, and verifies the same UID/certificate and retained data after launch. The fixture and instrumentation APK are never distributed.

The distributable remains the original candidate APK, copied before the private update fixture build. Only after these checks pass is artifact `MdP-RemiX-Premium-<version>-signed` uploaded, containing the versioned APK, identical `MdP-RemiX-Premium.apk` alias, SHA-256 checksums, update manifest and production installation evidence. Artifact access is for candidate testing, not a claim of a published public release.

No successful run is asserted merely because this workflow exists. Missing signing secrets, compiler errors, failed installation/instrumentation or mismatched certificates block its verified artifact and publication.

## Required owner device checks

Download that exact candidate artifact to the tablet. Keep the original app until data backup/restoration are verified. In the separate Premium Test app, verify the collection import before any manual replacement. For the signed production candidate, verify:

1. The APK installs on the intended clean/migrated device; audio, AKAI designs/reels/PCM meters, playlists, language and animated background operate as expected.
2. **Share application → APK** opens the Android Sharesheet and sends the APK through WhatsApp. Open the received attachment on another Android device and install it, then launch it. WhatsApp or recipient policies may reject APK attachments; record the real result instead of assuming success from an intent test.
3. The encrypted permanent key/password backups and personal-data backup are stored privately and can be recovered.

These are physical-device checks. An emulator cannot establish that WhatsApp delivery or another physical device's installer succeeded.

## Publish the exact APK that was tested

Publication is a separate promotion step. It does **not** rebuild or re-sign the APK. Supply the successful signed verification run ID from the candidate's Actions URL and confirm the actual private-backup and physical-sharing checks:

```sh
gh workflow run android.yml --repo djmisu-svg/mdp-remix-1and1-nou --ref feature/premium-settings-share-updates -f target=publish-release -f source_run_id=RUN_ID -f private_backup_confirmed=true -f physical_sharing_verified=true
```

Replace only `RUN_ID` with the numeric successful verification run. Do not set the confirmation fields until the corresponding checks were actually performed. If the branch receives another code change, build/test a new candidate first; promotion requires its source commit to equal the selected current source.

Promotion verifies official run provenance, success of the signed installation job, the exact artifact ZIP SHA-256, every asset/checksum, package/version, permanent certificate, non-debuggable status, ZIP alignment and matching installation-retention evidence. It rejects expired/changed artifacts, unexpected files and existing release tags. It publishes only the exact tested bytes.

After successful publication the stable direct APK address is:

<https://github.com/djmisu-svg/mdp-remix-1and1-nou/releases/latest/download/MdP-RemiX-Premium.apk>

This address is unavailable until a release containing that exact asset exists. It is the sharing link for the app; repository/Actions pages are not substituted. Each release also retains an immutable versioned direct APK URL. In-app updates use the official versioned APK and update manifest, comparing integer versionCode and the actual installed signer, with Android's user-confirmed installation.

For future releases reuse the permanent key, increment versionName and let the workflow allocate a strictly higher versionCode. Retest and promote the exact next artifact. Losing the private signing identity prevents compatible direct-APK updates.
