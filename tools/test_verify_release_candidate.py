"""Security boundaries for promotion of an exact tested artifact; no signing keys or SDK needed."""
import copy
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from verify_release_candidate import REPOSITORY, inspect_source, require_monotonic_candidate, verify_apk, verify_archive


PIN = 'a' * 64
COMMIT = 'c' * 40
RUN_ID = 1234
VERSION = '1.0.1'
APK_NAME = 'MdP-RemiX-Premium-' + VERSION + '.apk'


class ExactArtifactPromotionTest(unittest.TestCase):
    def test_promotion_rechecks_latest_version_instead_of_relying_on_build_time(self):
        candidate = {'schemaVersion': 1, 'packageName': 'ro.mdp.remix', 'versionCode': 4, 'certificateSha256': PIN}
        require_monotonic_candidate(candidate, None, PIN)
        require_monotonic_candidate(candidate, dict(candidate, versionCode=3), PIN)
        for previous in [dict(candidate), dict(candidate, versionCode=5),
                         dict(candidate, versionCode=3, certificateSha256='b' * 64),
                         dict(candidate, versionCode=3, packageName='other.app')]:
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                require_monotonic_candidate(candidate, previous, PIN)

    def source(self):
        run = {'id': RUN_ID, 'status': 'completed', 'conclusion': 'success',
               'repository': {'full_name': REPOSITORY}, 'head_repository': {'full_name': REPOSITORY},
               'head_sha': COMMIT, 'event': 'workflow_dispatch', 'path': '.github/workflows/android.yml'}
        artifact = {'id': 12, 'name': 'MdP-RemiX-Premium-' + VERSION + '-signed', 'expired': False,
                    'size_in_bytes': 1000, 'digest': 'sha256:' + 'b' * 64,
                    'workflow_run': {'id': RUN_ID, 'head_sha': COMMIT}}
        job = {'name': 'release / Build and verify production-signed update APK', 'conclusion': 'success',
               'steps': [{'name': 'Verify a fresh production install and same-key update on Android 35', 'conclusion': 'success'},
                         {'name': 'Upload verified production APK only', 'conclusion': 'success'}]}
        return run, [artifact], [job]

    def test_accepts_only_the_exact_successful_release_run_and_unique_artifact(self):
        metadata = inspect_source(*self.source(), COMMIT, RUN_ID)
        self.assertEqual(metadata['source_commit'], COMMIT)
        self.assertEqual(metadata['artifact_id'], 12)

    def test_old_source_failure_fork_and_pr_build_cannot_be_promoted(self):
        for change in [{'head_sha': 'd' * 40}, {'conclusion': 'failure'}, {'event': 'pull_request'},
                       {'path': '.github/workflows/build.yml'}, {'head_repository': {'full_name': 'other/fork'}}]:
            with self.subTest(change=change):
                run, artifacts, jobs = self.source()
                run.update(change)
                with self.assertRaises(ValueError):
                    inspect_source(run, artifacts, jobs, COMMIT, RUN_ID)

    def test_missing_device_verification_or_duplicate_artifacts_blocks_promotion(self):
        run, artifacts, jobs = self.source()
        jobs[0]['steps'][0]['conclusion'] = 'skipped'
        with self.assertRaisesRegex(ValueError, 'installation/update'):
            inspect_source(run, artifacts, jobs, COMMIT, RUN_ID)
        run, artifacts, jobs = self.source()
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            inspect_source(run, artifacts + copy.deepcopy(artifacts), jobs, COMMIT, RUN_ID)

    def fixture(self, root, change=None, extras=()):
        apk = b'public APK-byte fixture, not a signing key'
        digest = hashlib.sha256(apk).hexdigest()
        manifest = {'schemaVersion': 1, 'packageName': 'ro.mdp.remix', 'versionName': VERSION,
                    'versionCode': 4, 'apkName': APK_NAME, 'certificateSha256': PIN,
                    'sha256': digest, 'sizeBytes': len(apk)}
        evidence = {'schemaVersion': 1, 'packageName': 'ro.mdp.remix', 'emulatorApiLevel': 35,
                    'physicalDevice': False, 'freshInstallPassed': True, 'upgradeRetainedData': True,
                    'fromVersionCode': 4, 'toVersionCode': 5, 'certificateSha256': PIN,
                    'releaseApkSha256': digest, 'fixtureApkSha256': 'f' * 64,
                    'uidBefore': 10123, 'uidAfter': 10123}
        entries = {APK_NAME: apk, 'MdP-RemiX-Premium.apk': apk,
                   'SHA256SUMS.txt': (digest + '  ' + APK_NAME + '\n' + digest + '  MdP-RemiX-Premium.apk\n').encode()}
        if change:
            change(manifest, evidence, entries)
        entries['update-manifest.json'] = json.dumps(manifest).encode()
        entries['production-install-verification.txt'] = json.dumps(evidence).encode()
        archive = root / 'candidate.zip'
        with ZipFile(archive, 'x') as out:
            for name, content in list(entries.items()) + list(extras):
                out.writestr(name, content)
        metadata = {'version_name': VERSION, 'archive_size': archive.stat().st_size,
                    'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}
        return archive, metadata, root / 'verified'

    def test_valid_archive_retains_both_identical_apks_and_real_installation_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, metadata, target = self.fixture(Path(directory))
            manifest = verify_archive(archive, metadata, target, PIN)
            self.assertEqual(manifest['versionCode'], 4)
            self.assertEqual((target / APK_NAME).read_bytes(), (target / 'MdP-RemiX-Premium.apk').read_bytes())

    def test_changed_downloaded_zip_is_rejected_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, metadata, target = self.fixture(Path(directory))
            metadata['archive_sha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'differs'):
                verify_archive(archive, metadata, target, PIN)
            self.assertFalse(target.exists())

    def test_extra_path_traversal_and_duplicate_entries_are_rejected(self):
        for extra in [('private.keystore', b'not a real key'), ('../outside', b'x'), (APK_NAME, b'duplicate')]:
            with self.subTest(name=extra[0]), tempfile.TemporaryDirectory() as directory:
                archive, metadata, target = self.fixture(Path(directory), extras=[extra])
                with self.assertRaisesRegex(ValueError, 'unexpected, duplicate or missing'):
                    verify_archive(archive, metadata, target, PIN)
                self.assertFalse(target.exists())

    def test_alias_integrity_must_match_the_exact_versioned_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, metadata, target = self.fixture(Path(directory),
                change=lambda manifest, evidence, entries: entries.update({'MdP-RemiX-Premium.apk': b'other bytes'}))
            with self.assertRaisesRegex(ValueError, 'integrity'):
                verify_archive(archive, metadata, target, PIN)

    def test_manifest_package_version_hash_and_certificate_are_verified(self):
        for change in [{'packageName': 'ro.mdp.remix.premiumtest'}, {'versionName': '9.9.9'},
                       {'sha256': '0' * 64}, {'certificateSha256': 'b' * 64}]:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                archive, metadata, target = self.fixture(Path(directory),
                    change=lambda manifest, evidence, entries: manifest.update(change))
                with self.assertRaises(ValueError):
                    verify_archive(archive, metadata, target, PIN)

    def test_missing_or_different_actual_installation_evidence_is_rejected(self):
        for change in [{'freshInstallPassed': False}, {'upgradeRetainedData': False}, {'uidAfter': 10124},
                       {'toVersionCode': 6}, {'releaseApkSha256': '0' * 64}]:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                archive, metadata, target = self.fixture(Path(directory),
                    change=lambda manifest, evidence, entries: evidence.update(change))
                with self.assertRaisesRegex(ValueError, 'evidence'):
                    verify_archive(archive, metadata, target, PIN)

    def test_sdk_rechecks_the_actual_certificate_instead_of_trusting_manifest(self):
        result = SimpleNamespace(returncode=0, stdout='Signer #1 certificate SHA-256 digest: ' + 'b' * 64 + '\n')
        with patch.dict('os.environ', {'ANDROID_HOME': '/sdk'}), patch('subprocess.run', return_value=result):
            with self.assertRaisesRegex(ValueError, 'certificate'):
                verify_apk(Path('candidate.apk'), {'versionCode': 4, 'versionName': VERSION}, PIN)

    def test_sdk_rejects_debuggable_package_and_checks_zip_alignment(self):
        manifest = {'versionCode': 4, 'versionName': VERSION}
        signing = 'Signer #1 certificate SHA-256 digest: ' + PIN + '\n'
        badging = "package: name='ro.mdp.remix' versionCode='4' versionName='1.0.1'\napplication-label:'MdP RemiX 1&1'\n"
        for debug in [True, False]:
            with self.subTest(debug=debug):
                responses = [SimpleNamespace(returncode=0, stdout=signing),
                             SimpleNamespace(returncode=0, stdout=badging + ('application-debuggable\n' if debug else '')),
                             SimpleNamespace(returncode=1, stdout='')]
                with patch.dict('os.environ', {'ANDROID_HOME': '/sdk'}), patch('subprocess.run', side_effect=responses):
                    with self.assertRaisesRegex(ValueError, 'non-debuggable|zipalign'):
                        verify_apk(Path('candidate.apk'), manifest, PIN)


if __name__ == '__main__':
    unittest.main()
