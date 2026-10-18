#!/usr/bin/env bash
# Disposable CI emulator only: prove same-package/signer in-place upgrade preserves data.
# Uses a fixture version of Premium Test. It never installs over the production package.
set -euo pipefail
older_apk=${1:?Pass the older Premium Test fixture APK}
newer_apk=${2:?Pass the current Premium Test APK}
python3 - "$older_apk" "$newer_apk" <<'PY'
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import uuid
import wave
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

package = 'ro.mdp.remix.premiumtest'
sdk_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'

def run(*arguments, data=None, check=True):
    result = subprocess.run(arguments, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        raise RuntimeError('Command failed: ' + ' '.join(arguments[:4]) + ': ' + result.stderr.decode(errors='replace')[-1200:])
    return result

def text(*arguments):
    return run(*arguments).stdout.decode().strip()

def metadata(apk):
    output = text(str(sdk_tools / 'aapt'), 'dump', 'badging', apk)
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", output, re.M)
    if not match or match.group(1) != package or 'application-debuggable' not in output:
        raise RuntimeError('Only debuggable Premium Test APKs may be used by this emulator fixture.')
    signing = text(str(sdk_tools / 'apksigner'), 'verify', '--print-certs', apk)
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', signing, re.M)
    if len(certificates) != 1:
        raise RuntimeError('A single verified fixture signing certificate is required.')
    return int(match.group(2)), certificates[0].lower()

older, newer = sys.argv[1:]
old_code, old_certificate = metadata(older)
new_code, new_certificate = metadata(newer)
if new_code <= old_code or old_certificate != new_certificate:
    raise RuntimeError('Upgrade needs strictly increasing versionCode and the same actual signing certificate.')
if text('adb', 'shell', 'getprop', 'ro.kernel.qemu') != '1':
    raise RuntimeError('This fixture is restricted to the disposable Android emulator, never a physical/user device.')

# -d is permitted only for this debuggable fixture: side-by-side verification may
# already have installed the current Test APK. No data is cleared or uninstalled.
run('adb', 'install', '-r', '-d', older)
run('adb', 'shell', 'am', 'force-stop', package)
old_uid = text('adb', 'shell', 'pm', 'list', 'packages', '-U', package)
if not old_uid or 'uid:' not in old_uid:
    raise RuntimeError('Installed fixture UID was not available.')

media_uri = 'content://' + package + '.mdpfiles/media/'
track_id = 'upgrade-regression-track'
fixtures = {}
fixtures['files/music-library.json'] = json.dumps({'version': 1, 'tracks': [{
    'id': track_id, 'title': 'Upgrade regression audio', 'artist': 'MdP CI', 'album': 'Retained data',
    'durationMs': 500, 'uri': media_uri + 'upgrade-audio.wav'}],
    'favorites': [track_id], 'playlists': [{'id': 'upgrade-local', 'name': 'Retained local playlist', 'trackIds': [track_id]}]}).encode()
fixtures['files/youtube-link-library.json'] = json.dumps({'version': 1, 'links': [{
    'id': 'upgrade-youtube', 'title': 'Retained YouTube playlist',
    'url': 'https://www.youtube.com/playlist?list=PLupgradeRegression12345', 'kind': 'YOUTUBE_PLAYLIST',
    'favorite': True, 'addedAt': 1000, 'lastOpenedAt': 2000}],
    'playlists': [{'id': 'upgrade-online', 'name': 'Retained YouTube collection', 'linkIds': ['upgrade-youtube'],
                   'favorite': True, 'createdAt': 1000, 'lastOpenedAt': 2000}]}).encode()
fixtures['shared_prefs/mdp_settings.xml'] = b'<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="english" value="true" /><string name="recorder_design">studio_pro</string><int name="panel_transparency" value="37" /><boolean name="brand_sync_enabled" value="false" /></map>'
background = {'version': 1, 'uri': media_uri + 'upgrade-background.gif', 'kind': 'GIF', 'scale': 'FIT', 'dimming': 0.25, 'animationEnabled': True}
fixtures['shared_prefs/mdp_background_v1.xml'] = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="background_settings_json">' + escape(json.dumps(background)) + '</string></map>').encode()
fixtures['files/shared-media/upgrade-background.gif'] = Path('app/src/androidTest/assets/background_test/mdp-test-animation.gif').read_bytes()
audio = io.BytesIO()
with wave.open(audio, 'wb') as output:
    output.setparams((2, 2, 22050, 0, 'NONE', 'not compressed'))
    output.writeframes(b''.join(struct.pack('<hh', int(math.sin(i * 2 * math.pi * 440 / 22050) * 7000), int(math.sin(i * 2 * math.pi * 440 / 22050) * 3500)) for i in range(11025)))
fixtures['files/shared-media/upgrade-audio.wav'] = audio.getvalue()
fixtures['files/upgrade-install-marker.txt'] = uuid.uuid4().hex.encode()

# Restore only this test's seeded paths afterward so later device tests start
# with their original fixtures. Unrelated files (including coexistence markers)
# and the separately installed production app are never read or changed.
backups = {}
for path in fixtures:
    existing = run('adb', 'exec-out', 'run-as', package, 'cat', path, check=False)
    backups[path] = existing.stdout if existing.returncode == 0 else None

def write(path, contents):
    run('adb', 'shell', 'run-as', package, 'mkdir', '-p', str(Path(path).parent))
    run('adb', 'shell', 'run-as', package, 'tee', path, data=contents)

def verify():
    for path, expected in fixtures.items():
        actual = run('adb', 'exec-out', 'run-as', package, 'cat', path).stdout
        if path.endswith('.xml'):
            wanted = {node.attrib['name']: (node.tag, node.attrib.get('value'), node.text or '') for node in ET.fromstring(expected)}
            retained = {node.attrib['name']: (node.tag, node.attrib.get('value'), node.text or '') for node in ET.fromstring(actual)}
            if any(retained.get(key) != value for key, value in wanted.items()):
                raise RuntimeError('Upgrade changed saved preference: ' + path)
        elif path.endswith('.json'):
            if json.loads(actual) != json.loads(expected):
                raise RuntimeError('Upgrade changed saved playlist/library: ' + path)
        elif hashlib.sha256(actual).digest() != hashlib.sha256(expected).digest():
            raise RuntimeError('Upgrade changed saved media or private marker: ' + path)

def launch():
    output = text('adb', 'shell', 'am', 'start', '-W', '-n', package + '/ro.mdp.remix.MainActivity')
    if 'Status: ok' not in output:
        raise RuntimeError('Fixture Activity launch failed.')
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if run('adb', 'shell', 'pidof', package, check=False).stdout.strip():
            return
        time.sleep(0.25)
    raise RuntimeError('Fixture process did not remain available after launch.')

try:
    for path, contents in fixtures.items():
        write(path, contents)
    launch()
    # Allow repository loading before comparing on-disk fixtures. Animation and
    # playback rendering are asserted separately by Android instrumentation.
    time.sleep(1)
    text('adb', 'shell', 'pidof', package)
    run('adb', 'shell', 'am', 'force-stop', package)
    verify()
    run('adb', 'install', '-r', newer)
    if text('adb', 'shell', 'pm', 'list', 'packages', '-U', package) != old_uid:
        raise RuntimeError('Android changed the application UID during upgrade.')
    installed = text('adb', 'shell', 'dumpsys', 'package', package)
    if not re.search(r'\bversionCode=' + str(new_code) + r'\b', installed):
        raise RuntimeError('New versionCode was not actually installed.')
    verify()
    launch()
    time.sleep(1)
    text('adb', 'shell', 'pidof', package)
    verify()
    print('PASS: Premium Test in-place update ' + str(old_code) + ' -> ' + str(new_code) + ' kept package, certificate, UID, local/YouTube playlists, language, recorder skin, GIF selection, GIF/audio bytes and private marker.')
    print('Scope: disposable CI signer/emulator only; original production signing compatibility is not asserted.')
finally:
    run('adb', 'shell', 'am', 'force-stop', package, check=False)
    for path, contents in backups.items():
        if contents is None:
            run('adb', 'shell', 'run-as', package, 'rm', '-f', path, check=False)
        else:
            write(path, contents)
PY
