#!/usr/bin/env bash
# Disposable emulator only. Uses the supplied permanent signer; never creates a key.
set -euo pipefail
python3 - "$@" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

if len(sys.argv) != 6:
    raise SystemExit('Usage: verify_production_upgrade.sh release.apk instrumentation.apk next.apk certificate-pin.txt report.txt')
release, instrumentation, future, pin_file, report_file = map(Path, sys.argv[1:])
package = 'ro.mdp.remix'
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
pin = re.sub(r'[:\s]', '', pin_file.read_text()).lower()
if not re.fullmatch(r'[0-9a-f]{64}', pin):
    raise SystemExit('The production certificate pin is invalid.')

def run(*args, check=True):
    result = subprocess.run(list(map(str, args)), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=150)
    if check and result.returncode:
        # Arguments never contain signing credentials. Keep device output bounded text.
        output = (result.stdout + result.stderr).decode(errors='replace')[-5000:]
        raise RuntimeError('Verification command failed: ' + ' '.join(map(str, args[:4])) + '\n' + output)
    return result

def text(*args, check=True):
    return run(*args, check=check).stdout.decode(errors='replace').strip()

def identity(apk, expected_package, release_app):
    if not apk.is_file():
        raise RuntimeError('A required signed APK is missing.')
    signing = text(sdk / 'apksigner', 'verify', '--print-certs', apk)
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)', signing, re.M)
    if len(certificates) != 1 or certificates[0].lower() != pin:
        raise RuntimeError('Every production/test/upgrade APK must use the actual pinned permanent certificate.')
    badging = text(sdk / 'aapt', 'dump', 'badging', apk)
    found = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    if not found or found.group(1) != expected_package:
        raise RuntimeError('An APK has an unexpected applicationId.')
    if release_app and 'application-debuggable' in badging:
        raise RuntimeError('The actual production APK must be non-debuggable.')
    return int(found.group(2))

if text('adb', 'shell', 'getprop', 'ro.kernel.qemu') != '1':
    raise SystemExit('Production installation verification is restricted to a disposable emulator.')
if text('adb', 'shell', 'getprop', 'ro.build.version.sdk') != '35':
    raise SystemExit('The production fixture requires the configured Android 35 emulator.')
for existing in (package, package + '.test'):
    if any(line.startswith('package:') for line in text('adb', 'shell', 'pm', 'path', existing, check=False).splitlines()):
        raise SystemExit('The emulator already contains the production fixture. Refusing to replace or clear an existing installation.')

old_code = identity(release, package, True)
new_code = identity(future, package, True)
identity(instrumentation, package + '.test', False)
if new_code != old_code + 1:
    raise SystemExit('The same-key verification APK must increase versionCode by exactly one.')

def installed_uid():
    listing = text('adb', 'shell', 'pm', 'list', 'packages', '-U', package)
    found = re.search(r'^package:' + re.escape(package) + r'\s+uid:(\d+)\s*$', listing, re.M)
    if not found:
        raise RuntimeError('The actual installed production UID could not be read.')
    return int(found.group(1))

def instrument(method, code):
    output = text('adb', 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'ro.mdp.remix.ProductionUpgradeTest#' + method,
        '-e', 'expectedVersionCode', str(code), '-e', 'expectedCertificateSha256', pin,
        package + '.test/androidx.test.runner.AndroidJUnitRunner')
    if not re.search(r'\bOK \(1 test\)', output) or re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]\b', output):
        raise RuntimeError('Production instrumentation failed:\n' + output[-12000:])

run('adb', 'install', '--no-incremental', release)
run('adb', 'install', '--no-incremental', instrumentation)
uid_before = installed_uid()
instrument('seedOldInstallation', old_code)
print('PASS: the actual non-debuggable production APK installed fresh, launched and saved the audio/GIF/library fixture.')
run('adb', 'install', '--no-incremental', '-r', future)
uid_after = installed_uid()
if uid_after != uid_before:
    raise RuntimeError('The production UID changed across the in-place update.')
instrument('verifyUpdatedInstallation', new_code)
print('PASS: the higher-code APK updated the production package with the same permanent certificate and retained the fixture data.')

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(64 * 1024), b''):
            value.update(block)
    return value.hexdigest()

report = {'schemaVersion': 1, 'packageName': package, 'emulatorApiLevel': 35,
    'physicalDevice': False, 'freshInstallPassed': True, 'upgradeRetainedData': True,
    'fromVersionCode': old_code, 'toVersionCode': new_code, 'certificateSha256': pin,
    'releaseApkSha256': digest(release), 'fixtureApkSha256': digest(future),
    'uidBefore': uid_before, 'uidAfter': uid_after,
    'retained': ['local/YouTube playlists and favorites/order/history', 'language and recorder skin',
                 'panel transparency and background settings', 'actual WAV/GIF bytes', 'private marker']}
report_file.parent.mkdir(parents=True, exist_ok=True)
report_file.write_text(json.dumps(report, indent=2) + '\n')
print('Scope: disposable emulator using the actual release signer. No physical device, WhatsApp or original Debug-key migration is asserted.')
PY
