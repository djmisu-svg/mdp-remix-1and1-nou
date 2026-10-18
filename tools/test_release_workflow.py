"""Offline checks of the exact version-allocation script executed by release CI."""
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import urllib.error


REPOSITORY = "djmisu-svg/mdp-remix-1and1-nou"
API = "https://api.github.com/repos/" + REPOSITORY
PIN = "a" * 64  # Public, disposable certificate fingerprint fixture; not a signing key.
ROOT = Path(__file__).resolve().parents[1]


def metadata_source():
    workflow = (ROOT / ".github/workflows/release.yml").read_text()
    step = workflow.split("      - name: Allocate a monotonic version", 1)[1]
    script = step.split("          python3 - <<'PYCODE'\n", 1)[1].split("          PYCODE", 1)[0]
    return textwrap.dedent(script)


class ReleaseMetadataTest(unittest.TestCase):
    def allocate(self, latest=None, version_input="", overrides=None, repository=REPOSITORY):
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory, "mdp-release-private")
            private.mkdir()
            (private / "expected-sha256.txt").write_text(PIN)
            output = Path(directory, "output")
            responses = {
                API: {"full_name": REPOSITORY, "private": False},
                API + "/releases/latest": latest,
            }
            if latest:
                for asset in latest.get("assets", []):
                    responses[asset["browser_download_url"]] = {
                        "schemaVersion": 1, "packageName": "ro.mdp.remix",
                        "versionCode": 6, "certificateSha256": PIN,
                    }
            responses.update(overrides or {})

            def request_response(request, timeout):
                self.assertEqual(timeout, 30)
                value = responses.get(request.full_url)
                if isinstance(value, Exception):
                    raise value
                if value is None:
                    raise urllib.error.HTTPError(request.full_url, 404, "Not found", {}, None)
                return io.BytesIO(json.dumps(value).encode())

            environment = {"GITHUB_REPOSITORY": repository, "GH_TOKEN": "disposable-test-token",
                "RELEASE_VERSION_INPUT": version_input, "RUNNER_TEMP": directory,
                "GITHUB_OUTPUT": str(output), "GITHUB_ENV": str(Path(directory, "env"))}
            with patch.dict(os.environ, environment), patch("urllib.request.urlopen", side_effect=request_response), contextlib.redirect_stdout(io.StringIO()):
                exec(compile(metadata_source(), "release.yml:metadata", "exec"), {"__name__": "__main__"})
            return output.read_text()

    @staticmethod
    def previous_release():
        return {"tag_name": "v1.0.0", "draft": False, "prerelease": False, "assets": [{
            "name": "update-manifest.json", "size": 400,
            "browser_download_url": "https://github.com/" + REPOSITORY + "/releases/download/v1.0.0/update-manifest.json",
        }]}

    def test_first_release_uses_source_defaults(self):
        result = self.allocate()
        self.assertIn("version_name=1.0.1\n", result)
        self.assertIn("version_code=4\n", result)

    def test_update_code_increases_beyond_latest_public_version(self):
        result = self.allocate(self.previous_release(), version_input="1.0.2")
        self.assertIn("version_name=1.0.2\n", result)
        self.assertIn("version_code=7\n", result)

    def test_existing_tag_cannot_be_overwritten(self):
        with self.assertRaisesRegex(SystemExit, "already exists"):
            self.allocate(overrides={API + "/git/ref/tags/v1.0.1": {"ref": "refs/tags/v1.0.1"}})

    def test_api_permission_or_connection_failure_does_not_become_first_release(self):
        failure = urllib.error.HTTPError(API, 403, "Forbidden", {}, None)
        with self.assertRaisesRegex(SystemExit, "HTTP 403"):
            self.allocate(overrides={API + "/releases/latest": failure})

    def test_different_production_certificate_is_rejected(self):
        release = self.previous_release()
        url = release["assets"][0]["browser_download_url"]
        manifest = {"schemaVersion": 1, "packageName": "ro.mdp.remix", "versionCode": 6, "certificateSha256": "b" * 64}
        with self.assertRaisesRegex(SystemExit, "differs from the last public release"):
            self.allocate(release, overrides={url: manifest})

    def test_wrong_previous_package_is_rejected(self):
        release = self.previous_release()
        url = release["assets"][0]["browser_download_url"]
        with self.assertRaisesRegex(SystemExit, "invalid package/version"):
            self.allocate(release, overrides={url: {"schemaVersion": 1, "packageName": "wrong.app", "versionCode": 6}})

    def test_missing_previous_manifest_requires_verified_migration(self):
        with self.assertRaisesRegex(SystemExit, "no unique update-manifest"):
            self.allocate({"draft": False, "prerelease": False, "assets": []})

    def test_version_input_is_not_shell_or_path_content(self):
        with self.assertRaisesRegex(SystemExit, "major.minor.patch"):
            self.allocate(version_input="1.0.2/other")

    def test_fork_cannot_publish_as_official_application(self):
        with self.assertRaisesRegex(SystemExit, "official application repository"):
            self.allocate(repository="another/fork")


if __name__ == "__main__":
    unittest.main()
