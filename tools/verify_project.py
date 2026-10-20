#!/usr/bin/env python3
"""Read-only structural checks; this does not compile Kotlin or run Android.

Uses Python's standard library and the existing shell only. No SDK, Gradle,
downloads, writes, or network access are required. Run from any directory with:
    python3 tools/verify_project.py
"""

from __future__ import annotations

import hashlib
from pathlib import Path
import re
import shlex
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
WRAPPER_SHA256 = "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"
PLUGINS = {
    "com.android.application": "8.7.3",
    "org.jetbrains.kotlin.android": "2.0.21",
    "org.jetbrains.kotlin.plugin.compose": "2.0.21",
}
ERRORS: list[str] = []
CHECKS = 0


def check(condition: bool, description: str) -> None:
    global CHECKS
    CHECKS += 1
    if not condition:
        ERRORS.append(description)


def read(relative: str) -> str:
    try:
        return (ROOT / relative).read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        ERRORS.append(f"Cannot read {relative}: {error}")
        return ""


def matches(pattern: str, source: str) -> bool:
    return re.search(pattern, source, re.MULTILINE) is not None


def parse_xml(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except (OSError, ET.ParseError) as error:
        ERRORS.append(f"Invalid XML {path.relative_to(ROOT)}: {error}")
        return None


def check_structure() -> None:
    required = [
        "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
        "app/build.gradle.kts", "app/proguard-rules.pro", "app/src/main/AndroidManifest.xml",
        "app/src/main/res/values/strings.xml", "app/src/main/res/values/themes.xml",
        "app/src/premiumTest/res/values/strings.xml",
        "app/src/main/res/drawable/ic_launcher.xml", ".github/workflows/build.yml",
        "tools/verify_project.py", "tools/test_verify_project.py",
        "app/src/main/java/ro/mdp/remix/MainActivity.kt",
        "app/src/main/java/ro/mdp/remix/ui/RemixApp.kt",
        "app/src/main/java/ro/mdp/remix/ui/RemixTheme.kt",
        "app/src/main/java/ro/mdp/remix/data/LibraryModels.kt",
        "app/src/main/java/ro/mdp/remix/data/LibraryJson.kt",
        "app/src/main/java/ro/mdp/remix/data/MusicRepository.kt",
        "app/src/main/java/ro/mdp/remix/playback/PlaybackService.kt",
        "app/src/main/java/ro/mdp/remix/playback/PlayerViewModel.kt",
        "app/src/main/java/ro/mdp/remix/links/LinkViewModel.kt",
        "app/src/main/java/ro/mdp/remix/ui/LinksScreen.kt",
        "app/src/main/java/ro/mdp/remix/external/YouTubeLauncher.kt",
        "app/src/androidTest/java/ro/mdp/remix/PremiumUiTest.kt",
    ]
    for relative in required:
        check((ROOT / relative).is_file(), f"Missing required file: {relative}")
    tests = list((ROOT / "app/src/test").rglob("*Test.kt"))
    check(bool(tests), "No local Kotlin unit tests found")
    check(any("@Test" in path.read_text(encoding="utf-8") for path in tests),
          "No JUnit @Test declarations found")
    link_sources = "\n".join(path.read_text(encoding="utf-8")
                             for path in (ROOT / "app/src/main/java/ro/mdp/remix/links").rglob("*.kt"))
    for declaration in ("YouTubeUrl", "LinkRepository", "LinkViewModel", "SavedMusicLink", "LinkPlaylist"):
        check(matches(r"\b(?:class|object|interface)\s+" + declaration + r"\b", link_sources),
              f"Required YouTube links declaration missing: {declaration}")
    for path in (ROOT / "app/src").rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        check(not matches(r"\b(?:TODO\s*\(|NotImplementedError\s*\(|throw\s+UnsupportedOperationException\s*\(\s*[\"'](?:TODO|not implemented))", source),
              f"Unimplemented Kotlin code in {path.relative_to(ROOT)}")
        check(not matches(r"(?im)^\s*//\s*(?:TODO|FIXME)\b", source),
              f"Unresolved implementation comment in {path.relative_to(ROOT)}")


def check_resources_and_manifest() -> None:
    resources: dict[str, set[str]] = {}
    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    parsed: dict[Path, ET.Element] = {}
    for path in (ROOT / "app/src/main/res").rglob("*.xml"):
        node = parse_xml(path)
        if node is None:
            continue
        parsed[path] = node
        kind = path.parent.name.split("-")[0]
        if kind == "values":
            for child in node:
                if "name" in child.attrib:
                    resources.setdefault(child.tag, set()).add(child.attrib["name"])
        else:
            resources.setdefault(kind, set()).add(path.stem)
    manifest = parse_xml(manifest_path)
    if manifest is None:
        return
    parsed[manifest_path] = manifest
    for path, node in parsed.items():
        for element in node.iter():
            for value in element.attrib.values():
                reference = re.fullmatch(r"@(string|style|drawable|mipmap|color|xml)/([\w.]+)", value)
                if reference:
                    kind, name = reference.groups()
                    check(name in resources.get(kind, set()),
                          f"Unresolved @{kind}/{name} in {path.relative_to(ROOT)}")
    strings = parsed.get(ROOT / "app/src/main/res/values/strings.xml")
    check(strings is not None and any(
        child.attrib.get("name") == "app_name" and child.text == "MdP RemiX 1&1"
        for child in strings
    ), "App label must be MdP RemiX 1&1")
    premium_strings = parse_xml(ROOT / "app/src/premiumTest/res/values/strings.xml")
    check(premium_strings is not None and any(
        child.tag == "string" and child.attrib.get("name") == "app_name"
        and child.text == "MdP RemiX Premium Test"
        for child in premium_strings
    ), "Premium test label must be MdP RemiX Premium Test")
    permissions = {element.get(ANDROID + "name") for element in manifest.findall("uses-permission")}
    for permission in ("WAKE_LOCK", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "POST_NOTIFICATIONS"):
        check("android.permission." + permission in permissions, f"Missing permission: {permission}")
    app = manifest.find("application")
    check(app is not None, "Missing <application> in manifest")
    if app is None:
        return
    check(app.get(ANDROID + "label") == "@string/app_name",
          "Manifest must use the variant app_name label")
    activity = next((node for node in app.findall("activity")
                     if node.get(ANDROID + "name") in (".MainActivity", "ro.mdp.remix.MainActivity")), None)
    check(activity is not None, "MainActivity missing from manifest")
    if activity is not None:
        check(activity.get(ANDROID + "exported") == "true", "Launcher activity must be exported")
        check(any(
            any(action.get(ANDROID + "name") == "android.intent.action.MAIN" for action in item.findall("action"))
            and any(category.get(ANDROID + "name") == "android.intent.category.LAUNCHER" for category in item.findall("category"))
            for item in activity.findall("intent-filter")
        ), "MainActivity must have MAIN/LAUNCHER intent filter")
    service = next((node for node in app.findall("service")
                    if node.get(ANDROID + "name") in (".playback.PlaybackService", "ro.mdp.remix.playback.PlaybackService")), None)
    check(service is not None, "Media3 PlaybackService missing from manifest")
    if service is not None:
        check(service.get(ANDROID + "foregroundServiceType") == "mediaPlayback", "Incorrect playback service foreground type")
        check(service.get(ANDROID + "exported") in ("true", "false"), "Service must declare android:exported")
        check(any(node.get(ANDROID + "name") == "androidx.media3.session.MediaSessionService"
                  for node in service.findall("intent-filter/action")), "Media3 service intent filter missing")


def check_versions_and_wrapper() -> None:
    for filename in ("build.gradle", "build.gradle.kts"):
        source = read(filename)
        found = dict(re.findall(r"id\s*\(?\s*[\"']([^\"']+)[\"']\s*\)?\s+version\s+[\"']([^\"']+)[\"']", source))
        check(found == PLUGINS, f"Pinned plugin versions differ in {filename}: {found}")
    names = []
    for filename in ("settings.gradle", "settings.gradle.kts"):
        source = read(filename)
        name = re.search(r"rootProject\.name\s*=\s*[\"']([^\"']+)[\"']", source)
        names.append(name.group(1) if name else None)
        check(matches(r"include\s*\(?\s*[\"']:app[\"']", source), f":app not included in {filename}")
        for repository in ("google()", "mavenCentral()", "gradlePluginPortal()"):
            check(repository in source, f"Missing {repository} in {filename}")
        check("RepositoriesMode.FAIL_ON_PROJECT_REPOS" in source, f"Repository policy absent in {filename}")
    check(names[0] is not None and names[0] == names[1], "Groovy/Kotlin settings project names differ")
    source = read("app/build.gradle.kts")
    for pattern, description in (
        (r'namespace\s*=\s*"ro\.mdp\.remix"', "namespace"),
        (r'applicationId\s*=\s*"ro\.mdp\.remix"', "applicationId"),
        (r"compileSdk\s*=\s*35\b", "compileSdk 35"),
        (r"targetSdk\s*=\s*35\b", "targetSdk 35"),
        (r"minSdk\s*=\s*23\b", "minSdk 23"),
        (r'buildToolsVersion\s*=\s*"35\.0\.0"', "build tools 35.0.0"),
        (r"sourceCompatibility\s*=\s*JavaVersion\.VERSION_17", "Java source 17"),
        (r"targetCompatibility\s*=\s*JavaVersion\.VERSION_17", "Java target 17"),
        (r'jvmTarget\s*=\s*"17"', "Kotlin JVM 17"),
        (r"compose\s*=\s*true", "Compose enabled"),
        (r'testBuildType\s*=\s*"premiumTest"', "instrumentation targets premiumTest"),
        (r"abortOnError\s*=\s*true", "lint abortOnError"),
    ):
        check(matches(pattern, source), f"Module must declare {description}")
    production = re.search(r"\bdefaultConfig\s*\{([^{}]*)\}", source, re.DOTALL)
    check(production is not None and matches(
        r'applicationId\s*=\s*"ro\.mdp\.remix"', production.group(1),
    ), "Production applicationId must remain ro.mdp.remix in defaultConfig")
    premium = re.search(r'create\(\s*"premiumTest"\s*\)\s*\{([^{}]*)\}', source, re.DOTALL)
    check(premium is not None, "Dedicated premiumTest build type missing")
    premium_source = premium.group(1) if premium else ""
    for pattern, description in (
        (r'initWith\(\s*getByName\(\s*"debug"\s*\)\s*\)', "initialize from debug"),
        (r'applicationIdSuffix\s*=\s*"\.premiumtest"', "applicationIdSuffix .premiumtest"),
        (r'versionNameSuffix\s*=\s*"-premium-test"', "versionNameSuffix -premium-test"),
        (r'signingConfig\s*=\s*signingConfigs\.getByName\(\s*"debug"\s*\)', "debug signing"),
        (r'matchingFallbacks\s*\+=\s*listOf\(\s*"debug"\s*\)', "debug dependency fallback"),
    ):
        check(matches(pattern, premium_source), f"premiumTest must declare {description}")
    production_source = source[:premium.start()] + source[premium.end():] if premium else source
    check(not matches(r"\bapplicationIdSuffix\s*=", production_source),
          "Production variants must not receive an applicationIdSuffix")
    for dependency in (
        "androidx.compose:compose-bom:2024.12.01", "androidx.compose.material3:material3",
        "androidx.activity:activity-compose:1.9.3", "androidx.media3:media3-exoplayer:1.5.1",
        "androidx.media3:media3-session:1.5.1", "junit:junit:4.13.2",
        "androidx.compose.ui:ui-test-junit4", "androidx.test:runner:1.6.2",
    ):
        check(dependency in source, f"Required dependency missing: {dependency}")
    check(not matches(r'[\"\'][^\"\']*:(?:\+|latest\.[^\"\']+)[\"\']', source), "Dynamic dependency version found")
    props = read("gradle/wrapper/gradle-wrapper.properties")
    check("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip" in props,
          "Gradle distribution must be official Gradle 8.9")
    check("validateDistributionUrl=true" in props, "Wrapper distribution URL validation disabled")
    check("android.useAndroidX=true" in read("gradle.properties"), "AndroidX must be enabled")
    wrapper = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    if wrapper.is_file():
        check(hashlib.sha256(wrapper.read_bytes()).hexdigest() == WRAPPER_SHA256,
              "Wrapper SHA-256 does not match the official Gradle 8.9 wrapper")
        try:
            with zipfile.ZipFile(wrapper) as archive:
                check(archive.testzip() is None, "Wrapper ZIP CRC check failed")
                check("org/gradle/wrapper/GradleWrapperMain.class" in archive.namelist(),
                      "GradleWrapperMain missing from wrapper JAR")
        except (OSError, zipfile.BadZipFile) as error:
            ERRORS.append(f"Invalid Gradle wrapper JAR: {error}")
    shell_syntax(read("gradlew"), "gradlew")


def shell_syntax(source: str, label: str) -> None:
    try:
        result = subprocess.run(["bash", "-n"], input=source, text=True,
                                capture_output=True, check=False, timeout=10)
        check(result.returncode == 0, f"Shell syntax error in {label}: {result.stderr.strip()}")
    except (OSError, subprocess.TimeoutExpired) as error:
        ERRORS.append(f"Could not check shell syntax in {label}: {error}")


def check_workflow() -> None:
    source = read(".github/workflows/build.yml")
    check("\t" not in source, "Workflow must use spaces, not YAML tabs")
    check(matches(r"^\s*workflow_dispatch:\s*$", source), "Manual Actions trigger missing")
    check("actions/setup-java@v4" in source, "setup-java v4 missing")
    check(matches(r"java-version:\s*['\"]17['\"]", source), "Actions must use JDK 17")
    check("android-actions/setup-android@v3" in source, "Current Android setup action missing")
    package_match = re.search(r"^\s*packages:\s*(['\"])([^\n]+)\1\s*$", source, re.MULTILINE)
    packages = shlex.split(package_match.group(2)) if package_match else []
    check(set(packages) == {"platform-tools", "platforms;android-35", "build-tools;35.0.0"},
          f"SDK packages must be platform-tools, platforms;android-35, build-tools;35.0.0: {packages}")
    check("tools" not in packages, "Obsolete standalone Android SDK tools package is forbidden")
    check("gradle/actions/setup-gradle@v4" in source, "Gradle setup/wrapper validation missing")
    check("python3 tools/verify_project.py" in source, "CI must run this structural verifier")
    check("python3 -B -m unittest discover -s tools -p 'test_*.py' -v" in source,
          "CI must run the Python verifier regression tests")
    tasks = "testDebugUnitTest lintDebug assembleDebug"
    check(tasks in source and "bash ./gradlew --no-daemon --stacktrace" in source,
          "CI must run unit tests, lint and assembleDebug through the included wrapper")
    check("testPremiumTestUnitTest lintPremiumTest assemblePremiumTest" in source,
          "CI must run premiumTest unit tests, lint and APK assembly")
    check("assemblePremiumTestAndroidTest" in source,
          "CI must compile the Premium Test instrumentation APK")
    check("connectedPremiumTestAndroidTest" in source and
          "reactivecircus/android-emulator-runner@v2" in source,
          "CI must execute Premium Test instrumentation on an Android emulator")
    check("-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true" in source,
          "CI must preserve emulator test files for screenshot collection")
    for token in (
        'cp app/build/outputs/apk/debug/app-debug.apk dist/MdP-RemiX.apk',
        '/apksigner" verify --verbose dist/MdP-RemiX.apk',
        '/aapt" dump badging dist/MdP-RemiX.apk',
        "^package: name='ro.mdp.remix'",
        'cp app/build/outputs/apk/premiumTest/app-premiumTest.apk dist/MdP-RemiX-Premium-Test.apk',
        '/apksigner" verify --verbose dist/MdP-RemiX-Premium-Test.apk',
        '/aapt" dump badging dist/MdP-RemiX-Premium-Test.apk',
        "^package: name='ro.mdp.remix.premiumtest'",
        "application-label:'MdP RemiX Premium Test'",
        'sha256sum MdP-RemiX-Premium-Test.apk > SHA256SUMS.txt',
        'actions/upload-artifact@v4', 'name: MdP-RemiX-premiumtest',
        'dist/MdP-RemiX-Premium-Test.apk', 'dist/SHA256SUMS.txt', 'if-no-files-found: error',
    ):
        check(token in source, f"Required APK verification/artifact configuration missing: {token}")
    premium_upload = re.search(
        r"name: MdP-RemiX-premiumtest\s*\n(?P<settings>(?:[ ]{10,}[^\n]*\n?)+)", source,
    )
    check(premium_upload is not None and "dist/MdP-RemiX.apk" not in premium_upload.group("settings"),
          "Premium test artifact must not include the production APK")
    steps = re.findall(r"(?ms)^      - name:.*?(?=^      - name:|\Z)", source)
    build_index = next((index for index, step in enumerate(steps)
                        if matches(r"^\s+id:\s*build_apks\s*$", step)), -1)
    verify_index = next((index for index, step in enumerate(steps)
                         if matches(r"^\s+id:\s*verify_apks\s*$", step)), -1)
    upload_index = next((index for index, step in enumerate(steps)
                         if "name: MdP-RemiX-premiumtest\n" in step), -1)
    emulator_index = next((index for index, step in enumerate(steps)
                           if "reactivecircus/android-emulator-runner@v2" in step), -1)
    check(0 <= build_index < verify_index < upload_index < emulator_index,
          "Verified APK must upload before emulator tests so UI failures do not hide the build")
    upload_step = steps[upload_index] if upload_index >= 0 else ""
    gate = "${{ !cancelled() && steps.build_apks.outcome == 'success' && steps.verify_apks.outcome == 'success' }}"
    check("if: " + gate in upload_step,
          "APK upload must require successful build and package verification")
    emulator_step = steps[emulator_index] if emulator_index >= 0 else ""
    check("continue-on-error" not in emulator_step,
          "Emulator test failures must fail the workflow")
    check(not re.search(r"\b(?:base64|b64encode|hexdump|xxd)\b|MDP_PREVIEW_", source, re.IGNORECASE),
          "Workflow logs must not encode screenshots or binary data")
    lines = source.splitlines()
    for index, line in enumerate(lines):
        check(not re.search(r"\bcat\s+[^;\n]*(?:\.png|\.jpe?g)\b", line, re.IGNORECASE) and
              not re.search(r"\badb\s+(?:exec-out|shell)\s+screencap\s+-p\s*(?:$|[|;])", line),
              f"Workflow must keep screenshot bytes out of logs at line {index + 1}")
        match = re.match(r"^(\s*)(?:run|script):\s*(.*)$", line)
        if not match:
            continue
        indentation, value = match.groups()
        if value == "|":
            block = []
            for following in lines[index + 1:]:
                if following.strip() and len(following) - len(following.lstrip()) <= len(indentation):
                    break
                block.append(following[len(indentation) + 2:])
            shell_syntax("\n".join(block), f"workflow run block at line {index + 1}")
        elif value:
            shell_syntax(value, f"workflow run at line {index + 1}")


def main() -> int:
    check_structure()
    check_resources_and_manifest()
    check_versions_and_wrapper()
    check_workflow()
    if ERRORS:
        print(f"FAILED: {len(ERRORS)} structural/configuration issue(s) across {CHECKS} checks.")
        for error in ERRORS:
            print(f"  - {error}")
        return 1
    print(f"PASS: {CHECKS} read-only structural/configuration checks.")
    print("No Gradle build, Kotlin compilation, unit tests, Android lint, APK signing or device checks were executed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
