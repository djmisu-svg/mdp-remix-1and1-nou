"""Run the real production-upgrade verifier with mocked SDK/device processes.

These regressions exercise APK metadata variations without creating a signing
key, invoking Android tools or touching a device.
"""
import contextlib
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
PIN = 'a' * 64  # Public certificate fingerprint fixture, not a signing secret.
PACKAGE = 'ro.mdp.remix'


def verifier_source():
    script = (ROOT / 'tools/verify_production_upgrade.sh').read_text()
    return script.split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]


class ProductionUpgradeVerifierTest(unittest.TestCase):
    @contextlib.contextmanager
    def scenario(self, *, test_version='', package_overrides=None,
                 code_overrides=None, certificate_overrides=None,
                 qemu='1', uid_after=10123, already_installed=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / 'production.apk'
            instrumentation = root / 'android-test.apk'
            future = root / 'next.apk'
            for apk in (release, instrumentation, future):
                apk.write_bytes(('public fixture bytes: ' + apk.name).encode())
            pin = root / 'certificate-pin.txt'
            pin.write_text(PIN)
            state = SimpleNamespace(root=root, release=release,
                instrumentation=instrumentation, future=future, pin=pin,
                report=root / 'report.json', calls=[], installed=None)
            packages = {release.name: PACKAGE, future.name: PACKAGE,
                        instrumentation.name: PACKAGE + '.test'}
            packages.update(package_overrides or {})
            codes = {release.name: '4', future.name: '5', instrumentation.name: test_version}
            codes.update(code_overrides or {})
            certificates = dict(certificate_overrides or {})

            def response(arguments, **kwargs):
                command = list(map(str, arguments))
                state.calls.append(command)
                executable = Path(command[0]).name
                output = ''
                if executable == 'apksigner':
                    fingerprint = certificates.get(Path(command[-1]).name, PIN)
                    output = 'Signer #1 certificate SHA-256 digest: ' + fingerprint + '\n'
                elif executable == 'aapt':
                    apk_name = Path(command[-1]).name
                    output = "package: name='" + packages[apk_name] + "'"
                    if codes[apk_name] is not None:
                        output += " versionCode='" + codes[apk_name] + "'"
                    output += " versionName='1.0.1'\n"
                elif command[:4] == ['adb', 'shell', 'getprop', 'ro.kernel.qemu']:
                    output = qemu + '\n'
                elif command[:4] == ['adb', 'shell', 'getprop', 'ro.build.version.sdk']:
                    output = '35\n'
                elif command[:4] == ['adb', 'shell', 'pm', 'path']:
                    if already_installed and command[-1] == PACKAGE:
                        output = 'package:/data/app/existing/base.apk\n'
                elif command[:2] == ['adb', 'install']:
                    state.installed = Path(command[-1]).name
                    output = 'Success\n'
                elif command[:5] == ['adb', 'shell', 'pm', 'list', 'packages']:
                    uid = uid_after if state.installed == future.name else 10123
                    output = f'package:{PACKAGE} uid:{uid}\npackage:{PACKAGE}.test uid:10124\n'
                elif command[:4] == ['adb', 'shell', 'am', 'instrument']:
                    output = 'OK (1 test)\nINSTRUMENTATION_CODE: -1\n'
                else:
                    raise AssertionError('Unexpected SDK/device command: ' + repr(command))
                return SimpleNamespace(returncode=0, stdout=output.encode(), stderr=b'')

            state.response = response
            yield state

    def execute(self, state):
        argv = ['verify_production_upgrade.sh', str(state.release), str(state.instrumentation),
                str(state.future), str(state.pin), str(state.report)]
        with patch.dict(os.environ, {'ANDROID_HOME': '/mock-sdk'}), \
                patch('sys.argv', argv), \
                patch('subprocess.run', side_effect=state.response), \
                contextlib.redirect_stdout(io.StringIO()):
            exec(compile(verifier_source(), 'verify_production_upgrade.sh:inline', 'exec'), {'__name__': '__main__'})
        return json.loads(state.report.read_text())

    def assert_no_install_or_report(self, state):
        self.assertFalse(any(command[:2] == ['adb', 'install'] for command in state.calls))
        self.assertFalse(state.report.exists())

    def test_empty_android_test_version_does_not_block_real_package_and_certificate_checks(self):
        with self.scenario(test_version='') as state:
            report = self.execute(state)
            for executable in ('apksigner', 'aapt'):
                checked = [Path(command[-1]).name for command in state.calls if Path(command[0]).name == executable]
                self.assertCountEqual(['production.apk', 'next.apk', 'android-test.apk'], checked)
            self.assertEqual((4, 5), (report['fromVersionCode'], report['toVersionCode']))
            self.assertEqual(PIN, report['certificateSha256'])
            self.assertTrue(report['freshInstallPassed'])
            self.assertTrue(report['upgradeRetainedData'])
            self.assertEqual(report['uidBefore'], report['uidAfter'])

    def test_missing_android_test_version_is_accepted_without_affecting_runner_versions(self):
        with self.scenario(test_version=None) as state:
            self.execute(state)
            runs = [command for command in state.calls if command[:4] == ['adb', 'shell', 'am', 'instrument']]
            self.assertEqual(2, len(runs))
            self.assertIn('ro.mdp.remix.ProductionUpgradeTest#seedOldInstallation', runs[0])
            self.assertIn('ro.mdp.remix.ProductionUpgradeTest#verifyUpdatedInstallation', runs[1])
            for command, expected in zip(runs, ('4', '5')):
                self.assertEqual(expected, command[command.index('expectedVersionCode') + 1])
                self.assertEqual(PIN, command[command.index('expectedCertificateSha256') + 1])

    def test_wrong_android_test_package_is_rejected_even_when_its_version_is_empty(self):
        with self.scenario(package_overrides={'android-test.apk': 'another.instrumentation'}) as state:
            with self.assertRaisesRegex(RuntimeError, 'applicationId'):
                self.execute(state)
            self.assert_no_install_or_report(state)

    def test_production_and_future_packages_remain_mandatory(self):
        for apk in ('production.apk', 'next.apk'):
            with self.subTest(apk=apk), self.scenario(package_overrides={apk: PACKAGE + '.premiumtest'}) as state:
                with self.assertRaisesRegex(RuntimeError, 'applicationId'):
                    self.execute(state)
                self.assert_no_install_or_report(state)

    def test_only_instrumentation_may_omit_or_empty_its_version_code(self):
        for apk in ('production.apk', 'next.apk'):
            for value in (None, '', '0', '-1', 'not-a-number', '2100000001'):
                with self.subTest(apk=apk, value=value), self.scenario(code_overrides={apk: value}) as state:
                    with self.assertRaisesRegex(RuntimeError, 'versionCode'):
                        self.execute(state)
                    self.assert_no_install_or_report(state)

    def test_every_apk_must_match_the_actual_permanent_certificate(self):
        for apk in ('production.apk', 'android-test.apk', 'next.apk'):
            with self.subTest(apk=apk), self.scenario(certificate_overrides={apk: 'b' * 64}) as state:
                with self.assertRaisesRegex(RuntimeError, 'certificate'):
                    self.execute(state)
                self.assert_no_install_or_report(state)

    def test_physical_device_is_rejected_before_apk_or_install_commands(self):
        with self.scenario(qemu='0') as state:
            with self.assertRaisesRegex(SystemExit, 'disposable emulator'):
                self.execute(state)
            self.assert_no_install_or_report(state)
            self.assertFalse(any(Path(command[0]).name == 'apksigner' for command in state.calls))

    def test_existing_installation_is_not_replaced_or_cleared(self):
        with self.scenario(already_installed=True) as state:
            with self.assertRaisesRegex(SystemExit, 'existing installation'):
                self.execute(state)
            self.assert_no_install_or_report(state)

    def test_future_fixture_must_increase_version_code_by_exactly_one(self):
        for future_code in ('4', '6'):
            with self.subTest(code=future_code), self.scenario(code_overrides={'next.apk': future_code}) as state:
                with self.assertRaisesRegex(SystemExit, 'exactly one'):
                    self.execute(state)
                self.assert_no_install_or_report(state)

    def test_changed_uid_blocks_retained_data_report(self):
        with self.scenario(uid_after=10125) as state:
            with self.assertRaisesRegex(RuntimeError, 'UID changed'):
                self.execute(state)
            self.assertFalse(state.report.exists())
            runs = [command for command in state.calls if command[:4] == ['adb', 'shell', 'am', 'instrument']]
            self.assertEqual(1, len(runs), 'A changed UID must fail before the retention verification is claimed.')


if __name__ == '__main__':
    unittest.main()
