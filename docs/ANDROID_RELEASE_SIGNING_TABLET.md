# Android tablet: production signing setup

The approved application is preserved on `feature/premium-settings-share-updates`. Production uses `ro.mdp.remix`; the independently installed Premium Test app uses `ro.mdp.remix.premiumtest`. Source release metadata is prepared as **1.0.1, versionCode 4**. The workflow allocates a higher versionCode from the last official published update manifest when needed. A production release is not ready until signing and verification succeed.

No production keystore or configured release signing was found in the workspace. The connected tool cannot list your GitHub secrets, so their absence is not assumed. The setup script checks repository and environment secret **names only** in GitHub before generating a key and preserves any existing signing setup.

## First choose the correct signing identity

- If you have an older production APK that must receive an update, obtain its **original keystore, alias and passwords**. A new key cannot update it or inherit its private app data. Do not uninstall the old app to resolve a signing conflict.
- If there is no previous production signing key and this is the first public signing identity, the tablet can generate a new production keystore. The existing Premium Test app remains separate; its settings and playlists do not move automatically to production.
- An APK or certificate alone cannot recover a lost private signing key. Never use an online keystore generator or paste a signing password/private key into chat, an issue, a workflow input, or a repository file.

## 1. Install Termux on the tablet

Install [Termux from F-Droid](https://f-droid.org/packages/com.termux/). Open it and paste:

```sh
pkg update && pkg install openjdk-21 gh curl
termux-setup-storage
```

Allow the Android file-access prompt so the encrypted backup can be placed in Downloads. This permission is for Termux; the music application gains no new permission.

## 2. Download and start the setup script

Paste this block into Termux:

```sh
curl --fail --location --output "$HOME/mdp-signing-setup.sh" https://raw.githubusercontent.com/djmisu-svg/mdp-remix-1and1-nou/feature/premium-settings-share-updates/tools/setup_signing_tablet.sh
bash "$HOME/mdp-signing-setup.sh"
```

If an original production keystore exists, run the downloaded script with it instead:

```sh
bash "$HOME/mdp-signing-setup.sh" --existing "$HOME/storage/downloads/original-release-keystore.jks"
```

Use the real file's location. Do not generate a new key for an existing production installation that needs compatible updates.

The script will:

1. Ask you to sign in to GitHub through the browser/device-code flow. Sign in as the repository owner. It never asks for your GitHub password in the terminal.
2. Preserve existing signing secrets. If they already exist, stop key creation and use step 4 to validate that existing setup once.
3. For a first key, ask you to type `NEW` and enter a password twice. Use a password manager and at least 16 characters. Password entry is hidden. It creates an encrypted PKCS12 keystore with an RSA-4096 key and a 10,000-day certificate. Rerunning does not replace an existing local key.
4. For an original key, ask for its actual alias and passwords and verify that the private key opens. Compare the saved public certificate fingerprint with the known original production fingerprint before releasing an update.
5. Save the encrypted backup in **Downloads → MDP-release-signing-backup**. Copy that folder to private off-device storage, and save the password separately in your password manager. Then type `BACKEDUP`. Keep both for every future update. Uninstalling Termux removes its private copy, so the backup is essential.
6. Configure the protected `android-production` GitHub environment and upload the signing data directly as encrypted GitHub secrets. No key/password is printed or committed. No build starts automatically.

If the environment is newly created, deployments are limited to the existing development branch and `main`. Existing environment protection rules are retained. If GitHub reports insufficient permissions, use the repository-owner account; do not weaken a company's environment policies.

## 3. Confirm the secret names

In the tablet browser, open [the android-production environment](https://github.com/djmisu-svg/mdp-remix-1and1-nou/settings/environments). Select `android-production`. Under Environment secrets you should see these five names; GitHub hides their values:

| Secret | Source |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Encrypted keystore encoded by the script; Base64 alone is not encryption |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Original alias, or `mdp-remix-release` for a new key |
| `ANDROID_KEY_PASSWORD` | Private key password; the new PKCS12 key uses the same password |
| `ANDROID_SIGNING_CERT_SHA256` | Public signing-certificate fingerprint, pinned for verification |

You do not need to copy/paste secret values manually. The script uses GitHub CLI's encrypted secret upload and never uploads the keystore as an Actions artifact.

## 4. Run one signed release build

After secrets and backups are complete, paste this into Termux to build, verify and publish the approved public release in one run:

```sh
gh workflow run android.yml --repo djmisu-svg/mdp-remix-1and1-nou --ref feature/premium-settings-share-updates -f target=release -f version_name=1.0.1 -f publish_release=true
```

If you want only an Actions APK artifact without a GitHub Release, change `publish_release=true` to `publish_release=false` **before** running it. Artifacts in a public repository are accessible to GitHub users with repository access; they are not a private backup location. Run one option, not both. The registered `android.yml` entry works from the existing branch without merging PR #2. Do not merge the feature branch merely to make the new workflow appear in the Actions menu.

The release workflow checks credentials/certificate before SDK setup or building, rejects Android Debug certificates, and checks the official latest release. It automatically chooses `max(source versionCode, latest published versionCode + 1)` and rejects an existing version/tag before building. If there is a previous release, its update manifest must identify the same production certificate. It builds release unit tests/lint/APK once, then verifies the APK signature, pinned certificate, package, actual version, non-debuggable manifest and ZIP alignment. It uploads only the signed APK, checksum and `update-manifest.json` (package, version, APK size/hash and public certificate fingerprint). Private temporary keys and signing diagnostics are deleted afterward. No emulator installation test is performed by this workflow.

If signing is missing or invalid, it stops without an unsigned/debug-signed production artifact. Publishing requires GitHub Actions `contents: write`; if repository/organization policy refuses it, the verified signed APK can still be downloaded from the successful build job's artifact. Do not repeatedly rerun a failed job without correcting the reported prerequisite.

## 5. Download and retain the release identity

After a **successful** run, open [Actions](https://github.com/djmisu-svg/mdp-remix-1and1-nou/actions). The signed artifact is `MdP-RemiX-Premium-1.0.1-signed`, containing `MdP-RemiX-Premium-1.0.1.apk`, `SHA256SUMS.txt` and `update-manifest.json`. Future releases use their selected version in the artifact/APK name.

If publication was enabled and succeeded, the release is **MdP RemiX 1&1 Premium v1.0.1** and the public APK address will be:

<https://github.com/djmisu-svg/mdp-remix-1and1-nou/releases/download/v1.0.1/MdP-RemiX-Premium-1.0.1.apk>

That address is **not available until the signed release succeeds**. Download the APK and open it from Downloads; allow installation from your browser/file manager when Android asks. If Android reports a signing conflict, retain the installed app and obtain its original signing key. Do not uninstall it if its data matters.

For future updates, keep this same keystore, alias, passwords and certificate pin. For each next public release choose a new version name, for example replace `version_name=1.0.1` with `version_name=1.0.2` in the same command. The workflow increases versionCode automatically from the last published manifest and serializes release runs; it never replaces a public APK. If an older public release has no update manifest, or its signing fingerprint differs, the workflow stops for verified migration rather than guessing compatibility. Losing the signing identity prevents compatible direct-APK updates. GitHub secrets cannot be downloaded later as a backup.

## Installed applications and update verification

The installed production app must use package `ro.mdp.remix` and the same original certificate as the new release. The in-app updater compares the downloaded APK package, higher versionCode and certificate with the installed application before offering installation; it also checks the official release manifest SHA-256 and byte size. Android then asks you to approve the update. This does not require uninstalling or clearing data.

A local emulator upgrade regression with a disposable Debug/Premium Test signing identity is not proof that the owner's existing production key matches. Until the original production credentials are available and a real older production APK is tested against the new signed APK, production-key installation compatibility remains unverified. Retain the installed application, playlists, media files and original signing backup.
