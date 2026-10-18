#!/usr/bin/env bash
# Owner-only tablet helper. Authentication stays in GitHub CLI; the app has no publishing token.
set +x
set -euo pipefail
umask 077
repo='djmisu-svg/mdp-remix-1and1-nou'
branch='feature/premium-settings-share-updates'
if [[ $# -ne 1 || ! -f "$1" ]]; then
    echo 'Usage: bash publish_brand_library_tablet.sh /path/to/exported/brand-library.json'
    exit 1
fi
for executable in gh python; do
    command -v "$executable" >/dev/null || { echo "Install the Termux package first: $executable"; exit 1; }
done
temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cp -- "$1" "$temporary/brand-library.json"
python - "$temporary/brand-library.json" <<'PY'
import json, pathlib, sys, urllib.parse, uuid
path = pathlib.Path(sys.argv[1])
if not 0 < path.stat().st_size <= 1024 * 1024:
    raise SystemExit('Fișierul trebuie să aibă cel mult 1 MiB.')
try:
    document = json.loads(path.read_text(encoding='utf-8'))
    assert set(document) == {'schemaVersion', 'revision', 'library'}
    assert type(document['schemaVersion']) is int and document['schemaVersion'] == 1
    uuid.UUID(document['revision'])
    library = document['library']
    assert set(library) == {'version', 'links', 'playlists'} and library['version'] == 1
    assert isinstance(library['links'], list) and isinstance(library['playlists'], list)
    for link in library['links']:
        assert set(link) <= {'id', 'title', 'url', 'kind', 'favorite', 'addedAt', 'lastOpenedAt'}
        assert all(isinstance(link[field], str) for field in ('id', 'title', 'url'))
        parsed = urllib.parse.urlparse(link['url'])
        assert parsed.scheme == 'https' and parsed.hostname in ('www.youtube.com', 'youtube.com', 'm.youtube.com', 'youtu.be')
        assert not parsed.username and not parsed.password and parsed.port in (None, 443)
        assert link.get('addedAt', 0) == 0 and link.get('lastOpenedAt', 0) == 0
    for playlist in library['playlists']:
        assert set(playlist) <= {'id', 'name', 'linkIds', 'favorite', 'createdAt', 'lastOpenedAt'}
        assert isinstance(playlist['id'], str) and isinstance(playlist['name'], str)
        assert isinstance(playlist['linkIds'], list) and all(isinstance(value, str) for value in playlist['linkIds'])
        assert playlist.get('createdAt', 0) == 0 and playlist.get('lastOpenedAt', 0) == 0
except (AssertionError, KeyError, TypeError, ValueError, AttributeError):
    raise SystemExit('Export invalid. Exportă din nou biblioteca prin aplicație; nu publica o arhivă personală.')
print(f"Export valid: {len(library['playlists'])} playlist-uri, {len(library['links'])} linkuri YouTube.")
PY
echo 'Această bibliotecă va fi PUBLICĂ pentru toți utilizatorii. Fișierele audio și fundalul nu sunt incluse.'
read -r -p 'Pentru publicare în repository-ul oficial, scrie PUBLICA: ' confirmation
[[ "$confirmation" == PUBLICA ]] || { echo 'Publicarea a fost oprită.'; exit 1; }
gh auth status --hostname github.com >/dev/null 2>&1 || gh auth login --hostname github.com --web --git-protocol https
gh repo view "$repo" --json nameWithOwner >/dev/null
if ! gh api "repos/$repo/releases/tags/brand-library" > "$temporary/release.json"; then
    gh release create brand-library --repo "$repo" --target "$branch" --prerelease --latest=false \
        --title 'MdP RemiX brand library' --notes 'Biblioteca publică de playlist-uri și linkuri YouTube a brandului. Nu este o versiune Android.'
    gh api "repos/$repo/releases/tags/brand-library" > "$temporary/release.json"
fi
python - "$temporary/release.json" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as source:
    release = json.load(source)
if release.get('tag_name') != 'brand-library' or not release.get('prerelease') or release.get('draft'):
    raise SystemExit('Publicare oprită: tag-ul brand-library trebuie să fie un prerelease public separat de versiunile Android.')
PY
gh release upload brand-library "$temporary/brand-library.json" --repo "$repo" --clobber
echo 'Biblioteca a fost publicată. În aplicație, apasă Sincronizează acum.'
echo "https://github.com/$repo/releases/tag/brand-library"
