#!/usr/bin/env python3
"""Regression tests for the offline verifier, using disposable project copies.

These are Python tooling tests. They do not build or test the Android app.
"""

from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import verify_project as verifier


PROJECT_ROOT = verifier.ROOT


class ProjectVerifierTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="mdp-verifier-test-")
        self.addCleanup(temporary.cleanup)
        self.fixture = Path(temporary.name) / "project"
        shutil.copytree(
            PROJECT_ROOT,
            self.fixture,
            ignore=shutil.ignore_patterns(".git", ".gradle", "build", "dist", "__pycache__"),
        )
        root_patch = patch.object(verifier, "ROOT", self.fixture)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.addCleanup(verifier.ERRORS.clear)

    def run_check(self, checker):
        verifier.ERRORS.clear()
        verifier.CHECKS = 0
        checker()
        return verifier.ERRORS.copy()

    def replace(self, relative, before, after):
        path = self.fixture / relative
        contents = path.read_text(encoding="utf-8")
        self.assertIn(before, contents, "Fixture mutation must match actual configuration")
        path.write_text(contents.replace(before, after), encoding="utf-8")

    def assert_detected(self, checker, expected):
        errors = self.run_check(checker)
        self.assertTrue(any(expected in error for error in errors),
                        f"Expected {expected!r} in verification errors: {errors}")

    def test_current_project_passes_structural_checks(self):
        for checker in (
            verifier.check_structure,
            verifier.check_resources_and_manifest,
            verifier.check_versions_and_wrapper,
            verifier.check_workflow,
        ):
            with self.subTest(checker=checker.__name__):
                self.assertEqual([], self.run_check(checker))

    def test_rejects_obsolete_standalone_sdk_tools_package(self):
        self.assertEqual([], self.run_check(verifier.check_workflow))
        self.replace(
            ".github/workflows/build.yml",
            "packages: 'platform-tools platforms;android-35 build-tools;35.0.0'",
            "packages: 'tools platform-tools platforms;android-35 build-tools;35.0.0'",
        )
        self.assert_detected(verifier.check_workflow,
                             "Obsolete standalone Android SDK tools package is forbidden")

    def test_rejects_ci_jdk_mismatch(self):
        self.assertEqual([], self.run_check(verifier.check_workflow))
        self.replace(".github/workflows/build.yml", "java-version: '17'", "java-version: '21'")
        self.assert_detected(verifier.check_workflow, "Actions must use JDK 17")

    def test_rejects_corrupt_wrapper(self):
        self.assertEqual([], self.run_check(verifier.check_versions_and_wrapper))
        (self.fixture / "gradle/wrapper/gradle-wrapper.jar").write_bytes(b"corrupt wrapper")
        self.assert_detected(verifier.check_versions_and_wrapper, "Wrapper SHA-256 does not match")
        self.assert_detected(verifier.check_versions_and_wrapper, "Invalid Gradle wrapper JAR")

    def test_rejects_missing_wrapper(self):
        self.assertEqual([], self.run_check(verifier.check_structure))
        (self.fixture / "gradle/wrapper/gradle-wrapper.jar").unlink()
        self.assert_detected(verifier.check_structure,
                             "Missing required file: gradle/wrapper/gradle-wrapper.jar")

    def test_rejects_mismatched_root_plugin_versions(self):
        self.assertEqual([], self.run_check(verifier.check_versions_and_wrapper))
        self.replace("build.gradle", "version '8.7.3'", "version '8.6.1'")
        self.assert_detected(verifier.check_versions_and_wrapper,
                             "Pinned plugin versions differ in build.gradle:")

    def test_rejects_missing_media_playback_permission(self):
        self.assertEqual([], self.run_check(verifier.check_resources_and_manifest))
        manifest = self.fixture / "app/src/main/AndroidManifest.xml"
        tree = ET.parse(manifest)
        root = tree.getroot()
        permission = next(
            element for element in root.findall("uses-permission")
            if element.get(verifier.ANDROID + "name")
            == "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"
        )
        root.remove(permission)
        tree.write(manifest, encoding="utf-8", xml_declaration=True)
        self.assert_detected(verifier.check_resources_and_manifest,
                             "Missing permission: FOREGROUND_SERVICE_MEDIA_PLAYBACK")

    def test_rejects_changing_production_application_id(self):
        self.assertEqual([], self.run_check(verifier.check_versions_and_wrapper))
        self.replace("app/build.gradle.kts", 'applicationId = "ro.mdp.remix"',
                     'applicationId = "ro.mdp.remix.premiumtest"')
        self.assert_detected(verifier.check_versions_and_wrapper,
                             "Production applicationId must remain ro.mdp.remix")

    def test_rejects_missing_premium_test_application_suffix(self):
        self.assertEqual([], self.run_check(verifier.check_versions_and_wrapper))
        self.replace("app/build.gradle.kts", 'applicationIdSuffix = ".premiumtest"',
                     'applicationIdSuffix = ""')
        self.assert_detected(verifier.check_versions_and_wrapper,
                             "premiumTest must declare applicationIdSuffix .premiumtest")

    def test_rejects_misleading_premium_test_label(self):
        self.assertEqual([], self.run_check(verifier.check_resources_and_manifest))
        self.replace("app/src/premiumTest/res/values/strings.xml", "MdP RemiX Premium Test",
                     "MdP RemiX 1&amp;1")
        self.assert_detected(verifier.check_resources_and_manifest,
                             "Premium test label must be MdP RemiX Premium Test")

    def test_rejects_instrumentation_targeting_production_debug_app(self):
        self.assertEqual([], self.run_check(verifier.check_versions_and_wrapper))
        self.replace("app/build.gradle.kts", 'testBuildType = "premiumTest"',
                     'testBuildType = "debug"')
        self.assert_detected(verifier.check_versions_and_wrapper,
                             "instrumentation targets premiumTest")

    def test_rejects_missing_emulator_ui_test_execution(self):
        self.assertEqual([], self.run_check(verifier.check_workflow))
        self.replace(".github/workflows/build.yml",
                     "bash ./gradlew --no-daemon --stacktrace connectedPremiumTestAndroidTest",
                     "true")
        self.assert_detected(verifier.check_workflow,
                             "CI must execute Premium Test instrumentation")

    def test_rejects_screenshot_data_in_logs(self):
        workflow = self.fixture / ".github/workflows/build.yml"
        original = workflow.read_text(encoding="utf-8")
        for command in (
            "base64 dist/screenshots/phone/phone-studio.jpg",
            "cat dist/screenshots/tablet-studio.png",
            "adb exec-out screencap -p",
        ):
            with self.subTest(command=command):
                workflow.write_text(original + "\n      - name: Invalid image logging\n        run: " + command + "\n",
                                    encoding="utf-8")
                errors = self.run_check(verifier.check_workflow)
                self.assertTrue(any("logs" in error for error in errors), errors)

    def test_rejects_apk_upload_after_emulator_tests(self):
        workflow = self.fixture / ".github/workflows/build.yml"
        source = workflow.read_text(encoding="utf-8")
        start = source.index("      - name: Upload installable Premium Test APK\n")
        end = source.index("      - name: Enable emulator hardware acceleration\n", start)
        upload = source[start:end]
        without_upload = source[:start] + source[end:]
        marker = "      - name: Upload test and lint reports\n"
        self.assertIn(marker, without_upload)
        workflow.write_text(without_upload.replace(marker, upload + marker, 1), encoding="utf-8")
        self.assert_detected(verifier.check_workflow,
                             "Verified APK must upload before emulator tests")

    def test_rejects_upload_without_successful_build_and_verification(self):
        self.replace(".github/workflows/build.yml",
                     "if: ${{ !cancelled() && steps.build_apks.outcome == 'success' && steps.verify_apks.outcome == 'success' }}",
                     "if: always()")
        self.assert_detected(verifier.check_workflow,
                             "APK upload must require successful build and package verification")

    def test_rejects_ignoring_emulator_test_failures(self):
        self.replace(".github/workflows/build.yml",
                     "uses: reactivecircus/android-emulator-runner@v2",
                     "continue-on-error: true\n        uses: reactivecircus/android-emulator-runner@v2")
        self.assert_detected(verifier.check_workflow,
                             "Emulator test failures must fail the workflow")


if __name__ == "__main__":
    unittest.main()
