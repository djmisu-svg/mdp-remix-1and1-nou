# MdP RemiX 1&1 — complete Android project

This archive contains the complete source project. It contains no compiled APK. The previous project and ZIP in the workspace have been preserved separately.

## Publish without editing code

1. Extract **MdP-RemiX-project.zip** and open its **mdp-remix** folder.
2. Upload that folder's **contents** to the root of **djmisu-svg/mdp-remix-1and1-nou**, preserving subfolders and including the hidden **.github** folder. A Git client can copy all files over an existing checkout, commit and push from your own authenticated account. Replace matching files with the versions in this archive. The source requires no manual edits, API keys or secrets.
3. Keep **.github/workflows/build.yml** at the repository root path. Do not upload only the ZIP or nest the entire project one extra level down.
4. Open the repository's **Actions** tab. **Android Debug APK** runs automatically after push or pull request; manual **Run workflow** is also available when the workflow is on the default branch.
5. Wait for the complete run to turn green. In **Artifacts**, download **MdP-RemiX-debug**, extract it and install **MdP-RemiX.apk** on your Android device.

Root **build.gradle** and **settings.gradle** contain the complete active build configuration, replacing the old incomplete files in the repository. The root **.kts** copies are also retained. The app module uses **app/build.gradle.kts**. Old demo files at the repository root do not enter the app module's source set.

The workflow sets up JDK 17 and explicitly installs only platform-tools, Android 35 and Build Tools 35.0.0. It runs verifier regression tests, structural checks, the app's JVM tests, Android lint and the Debug build; then it verifies the APK signature and uploads the APK and SHA-256 checksum. It never requests the obsolete standalone Android SDK package named tools.

## What was verified here

Python verifier tests and offline structural/configuration checks were executed successfully. Kotlin/Android compilation, app JVM tests, lint, APK generation and installation on a Samsung device are **not confirmed**. No GitHub Actions run or uploaded APK artifact is claimed for this delivery.

The workspace lacks a usable Gradle distribution, Kotlin compiler and Android SDK. Earlier instructions to stop local SDK installation/build retries and GitHub integration writes remain in effect. GitHub writes were previously rejected with **403: Resource not accessible by integration**. The user confirmed the repository has not yet been updated, so uploading this completed project from an authorized GitHub session is still necessary before Actions can run.

The app itself needs no account. YouTube links open through the official app or browser, which may require connectivity or YouTube login for private content. Playlists created here organize local saved references; they do not modify your YouTube account.
