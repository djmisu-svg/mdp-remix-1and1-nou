#!/usr/bin/env python3
"""Promote only the exact, verified release artifact; never build or access a private key."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request
import urllib.parse
from zipfile import BadZipFile, ZipFile

REPOSITORY = 'djmisu-svg/mdp-remix-1and1-nou'
MAX_ARCHIVE = 128 * 1024 * 1024
MAX_EXTRACTED = 256 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise ValueError(message)


def api(path, missing=False):
    token = os.environ.get('GH_TOKEN', '')
    require(bool(token), 'GitHub Actions authentication is unavailable.')
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + path,
        headers={'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + token,
                 'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'MdP-Exact-Artifact-Promotion'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            encoded = response.read(2 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as failure:
        if missing and failure.code == 404:
            return None
        raise
    require(len(encoded) <= 2 * 1024 * 1024, 'GitHub metadata exceeded the bounded response size.')
    return json.loads(encoded)


def require_monotonic_candidate(candidate, previous, certificate_pin):
    require(candidate.get('schemaVersion') == 1 and candidate.get('packageName') == 'ro.mdp.remix' and
            type(candidate.get('versionCode')) is int and candidate['versionCode'] >= 1 and
            candidate.get('certificateSha256') == certificate_pin,
            'The candidate package/version/certificate must remain pinned before publication.')
    if previous is None:
        return
    require(previous.get('schemaVersion') == 1 and previous.get('packageName') == 'ro.mdp.remix' and
            type(previous.get('versionCode')) is int and previous['versionCode'] >= 1 and
            previous.get('certificateSha256') == certificate_pin,
            'The latest official update manifest has a different or invalid package/signing identity.')
    require(candidate['versionCode'] > previous['versionCode'],
            'A newer or equal versionCode is already public. Do not promote an older tested APK.')


def latest_manifest():
    latest = api('/releases/latest', missing=True)
    if latest is None:
        return None
    require(not latest.get('draft') and not latest.get('prerelease') and isinstance(latest.get('tag_name'), str),
            'The latest stable release metadata is invalid.')
    assets = [asset for asset in latest.get('assets', []) if asset.get('name') == 'update-manifest.json']
    require(len(assets) == 1, 'The latest official release requires one update manifest before promotion.')
    asset = assets[0]
    expected = 'https://github.com/' + REPOSITORY + '/releases/download/' + urllib.parse.quote(latest['tag_name'], safe='') + '/update-manifest.json'
    require(asset.get('browser_download_url') == expected and type(asset.get('size')) is int and 0 < asset['size'] <= 32768,
            'The latest update manifest is not an official bounded asset.')
    # No GitHub token is sent to public asset/CDN redirects.
    request = urllib.request.Request(expected, headers={'User-Agent': 'MdP-Exact-Artifact-Promotion'})
    with urllib.request.urlopen(request, timeout=30) as response:
        final = urllib.parse.urlsplit(response.geturl())
        require(final.scheme == 'https' and final.hostname in {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'} and
                final.username is None and final.password is None,
                'The latest update manifest redirected outside supported GitHub HTTPS hosts.')
        encoded = response.read(32769)
    require(len(encoded) == asset['size'] and len(encoded) <= 32768, 'The latest update manifest size is inconsistent.')
    digest = asset.get('digest')
    if digest is not None:
        require(isinstance(digest, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', digest) is not None and
                hashlib.sha256(encoded).hexdigest() == digest[7:], 'The latest update manifest failed its GitHub SHA-256 check.')
    return json.loads(encoded)


def inspect_source(run, artifacts, jobs, expected_sha, run_id):
    require(run.get('id') == run_id and run.get('status') == 'completed' and run.get('conclusion') == 'success',
            'The source must be a completed, successful verification run.')
    require(run.get('repository', {}).get('full_name') == REPOSITORY and
            run.get('head_repository', {}).get('full_name') == REPOSITORY,
            'The source run must belong to the official repository, not a fork.')
    require(run.get('head_sha') == expected_sha and re.fullmatch(r'[0-9a-f]{40}', expected_sha),
            'The tested source commit differs from the currently selected approved source.')
    require(run.get('event') == 'workflow_dispatch' and
            run.get('path', '').split('@', 1)[0] in {'.github/workflows/android.yml', '.github/workflows/release.yml'},
            'Only an explicitly dispatched signed verification run may be promoted.')
    verified = [job for job in jobs if job.get('conclusion') == 'success' and
                job.get('name', '').endswith('Build and verify production-signed update APK')]
    require(len(verified) == 1, 'A unique successful production verification job is required.')
    steps = {step.get('name'): step.get('conclusion') for step in verified[0].get('steps', [])}
    require(steps.get('Verify a fresh production install and same-key update on Android 35') == 'success' and
            steps.get('Upload verified production APK only') == 'success',
            'The actual production installation/update checks and verified upload must have passed.')
    candidates = []
    for artifact in artifacts:
        match = re.fullmatch(r'MdP-RemiX-Premium-([0-9]+\.[0-9]+\.[0-9]+)-signed', artifact.get('name', ''))
        if match:
            candidates.append((artifact, match.group(1)))
    require(len(candidates) == 1, 'The source run must have exactly one signed release artifact.')
    artifact, version = candidates[0]
    digest = artifact.get('digest', '')
    require(not artifact.get('expired') and type(artifact.get('id')) is int and
            type(artifact.get('size_in_bytes')) is int and 0 < artifact['size_in_bytes'] <= MAX_ARCHIVE,
            'The signed artifact is missing, expired or too large.')
    require(re.fullmatch(r'sha256:[0-9a-f]{64}', digest) is not None,
            'The exact artifact ZIP requires a GitHub SHA-256 digest.')
    require(artifact.get('workflow_run', {}).get('id') == run_id and
            artifact.get('workflow_run', {}).get('head_sha') == expected_sha,
            'Artifact provenance does not match the verified source run.')
    return {'artifact_id': artifact['id'], 'artifact_name': artifact['name'],
            'archive_sha256': digest[7:], 'archive_size': artifact['size_in_bytes'],
            'source_run_id': run_id, 'source_commit': expected_sha, 'version_name': version}


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(64 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_archive(archive_path, metadata, target, certificate_pin):
    require(archive_path.stat().st_size == metadata['archive_size'] and
            file_hash(archive_path) == metadata['archive_sha256'], 'The downloaded artifact ZIP differs from the tested artifact.')
    require(re.fullmatch(r'[0-9a-f]{64}', certificate_pin) is not None, 'The public certificate pin is invalid.')
    version = metadata['version_name']
    versioned_name = 'MdP-RemiX-Premium-' + version + '.apk'
    stable_name = 'MdP-RemiX-Premium.apk'
    allowed = {versioned_name, stable_name, 'SHA256SUMS.txt', 'update-manifest.json', 'production-install-verification.txt'}
    require(not target.exists(), 'The candidate extraction directory already exists; nothing was overwritten.')
    with ZipFile(archive_path) as archive:
        names = archive.namelist()
        require(len(names) == len(allowed) and set(names) == allowed, 'The candidate archive has unexpected, duplicate or missing files.')
        require(sum(entry.file_size for entry in archive.infolist()) <= MAX_EXTRACTED and
                all(not entry.is_dir() and entry.file_size > 0 and ((entry.external_attr >> 16) & 0o170000) != 0o120000
                    for entry in archive.infolist()), 'The candidate archive is oversized or contains non-file entries.')
        require(archive.getinfo('update-manifest.json').file_size <= 32768 and
                archive.getinfo('production-install-verification.txt').file_size <= 32768 and
                archive.getinfo('SHA256SUMS.txt').file_size <= 4096, 'Candidate metadata exceeds its size limit.')
        target.mkdir(mode=0o700)
        # Explicit filenames only, never extract arbitrary archive paths.
        for name in sorted(allowed):
            with archive.open(name) as source, (target / name).open('xb') as output:
                while block := source.read(64 * 1024):
                    output.write(block)
    manifest = json.loads((target / 'update-manifest.json').read_text())
    require(manifest.get('schemaVersion') == 1 and manifest.get('packageName') == 'ro.mdp.remix' and
            manifest.get('versionName') == version and type(manifest.get('versionCode')) is int and
            1 <= manifest['versionCode'] < 2100000000 and manifest.get('apkName') == versioned_name,
            'The update manifest has invalid package/version metadata.')
    require(manifest.get('certificateSha256') == certificate_pin, 'The artifact signing identity differs from the protected permanent certificate pin.')
    apk = target / versioned_name
    digest = file_hash(apk)
    require(manifest.get('sha256') == digest and type(manifest.get('sizeBytes')) is int and
            manifest['sizeBytes'] == apk.stat().st_size and file_hash(target / stable_name) == digest and
            (target / stable_name).stat().st_size == apk.stat().st_size, 'The APK, stable alias or manifest failed integrity verification.')
    expected_checksums = {versioned_name: digest, stable_name: digest}
    checksums = {}
    for line in (target / 'SHA256SUMS.txt').read_text().splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9.\-]+)', line)
        require(match is not None and match[2] not in checksums, 'The candidate checksum file is invalid.')
        checksums[match[2]] = match[1]
    require(checksums == expected_checksums, 'The candidate checksum file does not match both exact APK files.')
    evidence = json.loads((target / 'production-install-verification.txt').read_text())
    code = manifest['versionCode']
    require(evidence.get('schemaVersion') == 1 and evidence.get('packageName') == 'ro.mdp.remix' and
            evidence.get('emulatorApiLevel') == 35 and evidence.get('physicalDevice') is False and
            evidence.get('freshInstallPassed') is True and evidence.get('upgradeRetainedData') is True and
            evidence.get('fromVersionCode') == code and evidence.get('toVersionCode') == code + 1 and
            evidence.get('certificateSha256') == certificate_pin and evidence.get('releaseApkSha256') == digest and
            type(evidence.get('uidBefore')) is int and evidence['uidBefore'] >= 10000 and
            evidence.get('uidAfter') == evidence['uidBefore'] and
            re.fullmatch(r'[0-9a-f]{64}', evidence.get('fixtureApkSha256', '')) is not None,
            'The artifact lacks matching evidence of a fresh install and same-key update retaining data.')
    return manifest


def verify_apk(apk, manifest, certificate_pin):
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    def tool(name, *arguments):
        result = subprocess.run([str(sdk / name), *map(str, arguments)], capture_output=True, text=True, timeout=90)
        require(result.returncode == 0, 'The signed APK failed ' + name + ' verification.')
        return result.stdout
    signing = tool('apksigner', 'verify', '--print-certs', apk)
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)', signing, re.M)
    require(len(signers) == 1 and signers[0].lower() == certificate_pin and
            re.search(r'certificate DN:.*Android\s+Debug', signing, re.I) is None,
            'The candidate must have the exact permanent release certificate, without a Debug signer.')
    badging = tool('aapt', 'dump', 'badging', apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(package is not None and package.groups() == ('ro.mdp.remix', str(manifest['versionCode']), manifest['versionName']) and
            'application-debuggable' not in badging and "application-label:'MdP RemiX 1&1'" in badging,
            'The candidate applicationId, version, label or non-debuggable status is incorrect.')
    tool('zipalign', '-c', '-P', '16', '-v', '4', apk)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    inspect = sub.add_parser('inspect')
    inspect.add_argument('run_id', type=int)
    inspect.add_argument('source_sha')
    inspect.add_argument('metadata', type=Path)
    verify = sub.add_parser('verify')
    verify.add_argument('archive', type=Path)
    verify.add_argument('metadata', type=Path)
    verify.add_argument('target', type=Path)
    latest = sub.add_parser('check-latest')
    latest.add_argument('manifest', type=Path)
    args = parser.parse_args()
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Exact-artifact promotion is restricted to the official repository.')
    if args.command == 'inspect':
        require(args.run_id > 0 and re.fullmatch(r'[0-9a-f]{40}', args.source_sha) is not None, 'The source run/commit is invalid.')
        run = api('/actions/runs/' + str(args.run_id))
        artifact_data = api('/actions/runs/' + str(args.run_id) + '/artifacts?per_page=100')
        job_data = api('/actions/runs/' + str(args.run_id) + '/jobs?per_page=100')
        require(artifact_data.get('total_count', 0) <= 100 and job_data.get('total_count', 0) <= 100,
                'Unexpected source-run pagination; cannot establish unique artifact provenance.')
        metadata = inspect_source(run, artifact_data.get('artifacts', []), job_data.get('jobs', []), args.source_sha, args.run_id)
        args.metadata.write_text(json.dumps(metadata, indent=2) + '\n')
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('artifact_id=' + str(metadata['artifact_id']) + '\nartifact_name=' + metadata['artifact_name'] + '\n')
        print('Verified source run and exact signed artifact provenance; no build was started.')
    elif args.command == 'verify':
        metadata = json.loads(args.metadata.read_text())
        pin = re.sub(r'[:\s]', '', os.environ.get('ANDROID_SIGNING_CERT_SHA256', '')).lower()
        manifest = verify_archive(args.archive, metadata, args.target, pin)
        verify_apk(args.target / manifest['apkName'], manifest, pin)
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('version_name=' + manifest['versionName'] + '\nversion_code=' + str(manifest['versionCode']) +
                         '\nsource_commit=' + metadata['source_commit'] + '\n')
        print('Exact tested APK, stable alias, actual permanent signature and retained-data evidence verified for publication.')
    else:
        pin = re.sub(r'[:\s]', '', os.environ.get('ANDROID_SIGNING_CERT_SHA256', '')).lower()
        require(re.fullmatch(r'[0-9a-f]{64}', pin) is not None, 'The permanent public certificate pin is invalid.')
        require_monotonic_candidate(json.loads(args.manifest.read_text()), latest_manifest(), pin)
        print('Latest official versionCode and permanent signing continuity rechecked immediately before publication.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, BadZipFile, subprocess.SubprocessError) as failure:
        # No signing key/password is read. Keep API/SDK diagnostics bounded and do not print tokens.
        raise SystemExit('Release promotion stopped: ' + str(failure)[:1000])
