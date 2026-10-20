#!/usr/bin/env python3
"""Non-destructive, tablet-only recovery of the original debuggable ro.mdp.remix v3.

Requires Python and authorized Android wireless ADB. Optional Termux:API's
termux-storage-get opens Android's picker when a document cannot be read directly.
No install, uninstall, clear-data, force-stop, root, or old-app writes are used.
All backup bytes stay in a new mode-0700 directory; no user data is printed.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import time
from urllib.parse import unquote, urlsplit
import uuid
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = "ro.mdp.remix"
MAX_MEDIA = 2 * 1024**3
MAX_PRIVATE = 256 * 1024**2
MAX_MANIFEST = 4 * 1024**2
EXPECTED_CODE = 3
DESIGNS = {"classic", "black", "red", "studio_pro"}


class BackupError(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.write("\n")
        out.flush()
        os.fsync(out.fileno())


def checked_private_tar(path: Path) -> dict[str, str]:
    """Never extract archive paths, links or special files onto the filesystem."""
    result = {}
    total = 0
    with tarfile.open(path, "r:") as archive:
        for item in archive:
            name = PurePosixPath(item.name)
            if name.is_absolute() or ".." in name.parts or not name.parts or name.parts[0] not in {"files", "shared_prefs"}:
                raise BackupError("Calea din copia privată nu este sigură.")
            if item.isdir():
                continue
            if not item.isfile() or item.name in result:
                raise BackupError("Copia privată conține o intrare neașteptată.")
            total += item.size
            if total > MAX_PRIVATE or len(result) >= 10_000:
                raise BackupError("Datele private depășesc limita copiei de siguranță.")
            source = archive.extractfile(item)
            assert source is not None
            digest = hashlib.sha256()
            for block in iter(lambda: source.read(64 * 1024), b""):
                digest.update(block)
            result[item.name] = digest.hexdigest()
    return result


def private_value(archive: tarfile.TarFile, name: str) -> bytes | None:
    # Android AtomicFile/SharedPreferences recover a surviving .bak before main.
    members = {entry.name: entry for entry in archive.getmembers()}
    selected = members.get(name + ".bak") or members.get(name)
    if selected is None:
        return None
    if not selected.isfile() or selected.size > MAX_MANIFEST:
        raise BackupError("Un fișier de setări este prea mare sau nu este un fișier normal.")
    source = archive.extractfile(selected)
    assert source is not None
    return source.read(MAX_MANIFEST + 1)


def preferences(raw: bytes | None) -> dict:
    if raw is None:
        return {}
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise BackupError("Format XML de setări nesigur.")
    root = ET.fromstring(raw)
    if root.tag != "map":
        raise BackupError("Formatul setărilor nu este recunoscut.")
    result = {}
    for entry in root:
        name = entry.attrib.get("name")
        if name in result:
            raise BackupError("Setări cu nume duplicate.")
        if entry.tag == "string":
            result[name] = entry.text or ""
        elif entry.tag == "boolean":
            value = entry.attrib.get("value")
            if value not in {"true", "false"}:
                raise BackupError("Valoare booleană invalidă.")
            result[name] = value == "true"
        elif entry.tag in {"int", "long"}:
            result[name] = int(entry.attrib["value"])
    return result


def load_legacy(path: Path) -> dict:
    with tarfile.open(path, "r:") as archive:
        music_raw = private_value(archive, "files/music-library.json")
        links_raw = private_value(archive, "files/youtube-link-library.json")
        settings = preferences(private_value(archive, "shared_prefs/mdp_settings.xml"))
        background_prefs = preferences(private_value(archive, "shared_prefs/mdp_background_v1.xml"))
    music = json.loads(music_raw) if music_raw is not None else {"version": 1, "tracks": [], "favorites": [], "playlists": []}
    youtube = json.loads(links_raw) if links_raw is not None else {"version": 1, "links": [], "playlists": []}
    background = json.loads(background_prefs.get("background_settings_json", "null")) or {
        "version": 1, "uri": None, "kind": "ORIGINAL", "scale": "FILL", "dimming": 0.55, "animationEnabled": True,
    }
    data = {
        "music": music, "youtube": youtube, "english": settings.get("english", False),
        "recorderDesignId": settings.get("recorder_design", "black"), "background": background,
        "panelTransparency": settings.get("panel_transparency", 0),
        "brandSyncEnabled": settings.get("brand_sync_enabled", True),
    }
    validate_data(data)
    return data


def validate_data(data: dict) -> None:
    music, youtube, bg = data["music"], data["youtube"], data["background"]
    if any(type(item.get("version")) is not int or item.get("version") != 1 for item in [music, youtube, bg]):
        raise BackupError("Schema datelor nu este v3/schema 1; originalele au fost păstrate.")
    if data["recorderDesignId"] not in DESIGNS or type(data["english"]) is not bool or type(data["brandSyncEnabled"]) is not bool:
        raise BackupError("Setări necunoscute; copia nu va fi modificată automat.")
    if type(data["panelTransparency"]) is not int or not 0 <= data["panelTransparency"] <= 100:
        raise BackupError("Transparența panoului nu este validă.")
    for container, rows, member_key in [(music, "tracks", "trackIds"), (youtube, "links", "linkIds")]:
        ids = [row["id"] for row in container[rows]]
        if len(set(ids)) != len(ids) or any(not isinstance(item, str) or not item for item in ids):
            raise BackupError("Biblioteca are identificatori invalizi sau duplicați.")
        playlist_ids = set()
        for playlist in container["playlists"]:
            members = playlist[member_key]
            if not playlist["id"] or playlist["id"] in playlist_ids or len(set(members)) != len(members) or not set(members) <= set(ids):
                raise BackupError("Un playlist conține referințe invalide.")
            playlist_ids.add(playlist["id"])
            limit = 128 if rows == "tracks" else 80
            if not isinstance(playlist["name"], str) or not playlist["name"].strip() or len(playlist["name"]) > limit:
                raise BackupError("Nume de playlist invalid.")
            if rows == "links":
                if type(playlist["favorite"]) is not bool or any(type(playlist[key]) is not int or playlist[key] < 0 for key in ["createdAt", "lastOpenedAt"]):
                    raise BackupError("Metadate playlist YouTube invalide.")
    canonical_urls = set()
    for link in youtube["links"]:
        video = re.fullmatch(r"https://www\.youtube\.com/watch\?v=[A-Za-z0-9_-]{11}", link["url"])
        playlist = re.fullmatch(r"https://www\.youtube\.com/playlist\?list=[A-Za-z0-9_-]{2,128}", link["url"])
        expected_kind = "YOUTUBE_VIDEO" if video else "YOUTUBE_PLAYLIST" if playlist else None
        if expected_kind is None or link["kind"] != expected_kind or link["url"] in canonical_urls:
            raise BackupError("Link YouTube necanonic sau duplicat; datele originale sunt păstrate.")
        canonical_urls.add(link["url"])
        if not isinstance(link["title"], str) or not link["title"].strip() or len(link["title"]) > 200 or type(link["favorite"]) is not bool:
            raise BackupError("Metadate YouTube invalide.")
        if any(type(link[key]) is not int or link[key] < 0 for key in ["addedAt", "lastOpenedAt"]):
            raise BackupError("Istoric YouTube invalid.")
    if len(set(music["favorites"])) != len(music["favorites"]) or not set(music["favorites"]) <= {row["id"] for row in music["tracks"]}:
        raise BackupError("Favoritele audio au referințe invalide.")
    if len({track["uri"] for track in music["tracks"]}) != len(music["tracks"]):
        raise BackupError("Biblioteca audio conține URI-uri duplicate.")
    for track in music["tracks"]:
        if urlsplit(track["uri"]).scheme != "content" or not track["title"] or type(track["durationMs"]) is not int or track["durationMs"] < 0:
            raise BackupError("Metadatele unei melodii nu sunt valide.")
        for key in ["title", "artist", "album"]:
            if not isinstance(track[key], str):
                raise BackupError("Metadate audio invalide.")
    if bg.get("kind") not in {"ORIGINAL", "IMAGE", "GIF", "VIDEO"} or bg.get("scale") not in {"FIT", "FILL"}:
        raise BackupError("Setări de fundal necunoscute.")
    if type(bg.get("animationEnabled")) is not bool or type(bg.get("dimming")) not in {int, float} or not 0 <= bg["dimming"] <= 1:
        raise BackupError("Setări de animație sau luminozitate invalide.")
    if (bg["kind"] == "ORIGINAL" and bg.get("uri") is not None) or (bg["kind"] != "ORIGINAL" and urlsplit(bg.get("uri") or "").scheme != "content"):
        raise BackupError("URI de fundal invalid.")


def source_media(data: dict) -> list[dict]:
    sources = {}
    for index, track in enumerate(data["music"]["tracks"]):
        sources.setdefault(track["uri"], {"uri": track["uri"], "kind": "audio", "label": f"Audio {index + 1}: {track['title']}"})
    bg = data["background"]
    if bg["kind"] != "ORIGINAL":
        sources.setdefault(bg["uri"], {"uri": bg["uri"], "kind": bg["kind"].lower(), "label": "Fundalul selectat în aplicația veche"})
    return list(sources.values())


def local_document_path(uri: str) -> Path | None:
    parsed = urlsplit(uri)
    if parsed.scheme != "content" or "/document/" not in parsed.path:
        return None
    document = unquote(parsed.path.split("/document/", 1)[1])
    if parsed.query or parsed.fragment:
        return None
    if parsed.netloc == "com.android.externalstorage.documents" and ":" in document:
        volume, relative = document.split(":", 1)
        parts = PurePosixPath(relative).parts
        if not parts or relative.startswith("/") or ".." in parts or "\x00" in relative:
            return None
        if volume == "primary":
            return Path("/storage/emulated/0").joinpath(*parts)
        if re.fullmatch(r"[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}", volume):
            return Path("/storage", volume).joinpath(*parts)
    if parsed.netloc == "com.android.providers.downloads.documents" and document.startswith("raw:"):
        raw = document.removeprefix("raw:")
        if raw.startswith(("/storage/", "/sdcard/")) and ".." not in PurePosixPath(raw).parts and "\x00" not in raw:
            return Path(raw)
    return None


class Adb:
    def __init__(self, serial: str | None):
        if not shutil.which("adb"):
            raise BackupError("Lipsește adb. În Termux: pkg install android-tools")
        if serial is None:
            result = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=15, check=True)
            devices = [line.split()[0] for line in result.stdout.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == "device"]
            if len(devices) != 1:
                raise BackupError("Conectează o singură tabletă prin Depanare wireless sau folosește --serial ADRESA:PORT.")
            serial = devices[0]
        self.prefix = ["adb", "-s", serial]

    def text(self, tokens: list[str]) -> str:
        result = subprocess.run(self.prefix + ["shell", shlex.join(tokens)], capture_output=True, timeout=30)
        if result.returncode:
            raise BackupError("O verificare ADB numai-citire a eșuat. Aplicația originală nu a fost modificată.")
        if len(result.stdout) > 4 * 1024**2:
            raise BackupError("Răspuns ADB prea mare.")
        return result.stdout.decode("utf-8", errors="replace").strip()

    def stream(self, tokens: list[str], target: Path, maximum: int, timeout: int = 120) -> None:
        error_path = target.with_name(target.name + ".stderr")
        try:
            with target.open("xb") as out, error_path.open("xb") as error:
                process = subprocess.Popen(self.prefix + ["exec-out", shlex.join(tokens)], stdout=out, stderr=error)
                started = time.monotonic()
                try:
                    while process.poll() is None:
                        if time.monotonic() - started > timeout or target.stat().st_size > maximum:
                            raise BackupError("Citirea a expirat sau depășește limita de dimensiune.")
                        time.sleep(0.2)
                    if process.returncode or target.stat().st_size > maximum:
                        raise BackupError("Sursa nu poate fi citită cu permisiunile disponibile.")
                    out.flush()
                    os.fsync(out.fileno())
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=10)
        finally:
            # Provider diagnostics can contain private URIs; never send them to stdout.
            if error_path.exists():
                error_path.unlink()

    def verify_original(self) -> dict:
        if self.text(["am", "get-current-user"]) != "0":
            raise BackupError("Acest ajutor acceptă numai profilul principal Android (utilizator 0).")
        details = self.text(["dumpsys", "package", PACKAGE])
        code = re.search(r"\bversionCode=(\d+)\b", details)
        if not code or int(code.group(1)) != EXPECTED_CODE:
            raise BackupError("Pachetul original ro.mdp.remix cu versionCode 3 nu a fost confirmat. Nu s-a schimbat nimic.")
        uid = self.text(["run-as", PACKAGE, "id", "-u"])
        if not uid.isdigit() or int(uid) < 10_000:
            raise BackupError("Aplicația originală nu permite run-as. Oprește aici: nu dezinstala și nu șterge datele.")
        paths = [line.removeprefix("package:") for line in self.text(["pm", "path", PACKAGE]).splitlines() if line.startswith("package:")]
        if not paths or any(not path.startswith("/data/app/") or not path.endswith(".apk") for path in paths):
            raise BackupError("Fișierele APK instalate nu au putut fi identificate sigur.")
        return {"package": PACKAGE, "versionCode": EXPECTED_CODE, "uid": int(uid), "apkPaths": paths}

    def snapshot(self, target: Path) -> dict[str, str]:
        directories = self.text(["run-as", PACKAGE, "sh", "-c", 'for p in files shared_prefs; do if [ -d "$p" ]; then echo "$p"; fi; done']).splitlines()
        if not directories or any(item not in {"files", "shared_prefs"} for item in directories):
            raise BackupError("Directoarele private nu au putut fi identificate; aplicația nu a fost modificată.")
        self.stream(["run-as", PACKAGE, "tar", "-cf", "-"] + directories, target, MAX_PRIVATE)
        return checked_private_tar(target)


def copy_bounded(source: Path, target: Path, remaining: int) -> None:
    if not source.is_file() or source.stat().st_size > remaining:
        raise BackupError("Fișierul lipsește sau depășește spațiul permis.")
    with source.open("rb") as src, target.open("xb") as out:
        copied = 0
        for block in iter(lambda: src.read(64 * 1024), b""):
            copied += len(block)
            if copied > remaining:
                raise BackupError("Fișierul depășește limita de dimensiune.")
            out.write(block)
        out.flush()
        os.fsync(out.fileno())


def validate_media(path: Path, kind: str) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise BackupError("Fișierul selectat este gol.")
    with path.open("rb") as source:
        header = source.read(20)
    if kind == "gif":
        if not header.startswith((b"GIF87a", b"GIF89a")):
            raise BackupError("Fundalul necesar este GIF, dar fișierul ales nu este GIF.")
    elif kind == "image":
        if not (header.startswith((b"\xff\xd8\xff", b"\x89PNG\r\n\x1a\n")) or (header.startswith(b"RIFF") and header[8:12] == b"WEBP")):
            raise BackupError("Imaginea necesară nu este JPEG, PNG sau WebP.")
    elif kind in {"audio", "video"}:
        if not shutil.which("ffprobe"):
            raise BackupError("Pentru verificarea audio/video: pkg install ffmpeg; apoi reia copia.")
        result = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type:stream_disposition=attached_pic", "-of", "json", str(path)], capture_output=True, timeout=60)
        streams = json.loads(result.stdout or b"{} ").get("streams", []) if result.returncode == 0 else []
        types = {stream.get("codec_type") for stream in streams if not stream.get("disposition", {}).get("attached_pic")}
        if (kind == "audio" and ("audio" not in types or "video" in types)) or (kind == "video" and "video" not in types):
            raise BackupError("Fișierul nu conține tipul audio/video necesar.")


def obtain_media(adb: Adb, item: dict, destination: Path, remaining: int, picker: bool) -> str:
    local = local_document_path(item["uri"])
    if local is not None:
        try:
            copy_bounded(local, destination, remaining)
            validate_media(destination, item["kind"])
            return "document_uri_shared_storage_path"
        except (OSError, BackupError, subprocess.SubprocessError, ValueError):
            destination.unlink(missing_ok=True)
    # Android versions/providers may deny both commands. Failure is NOT an empty file.
    for prefix in [["run-as", PACKAGE], []]:
        try:
            adb.stream(prefix + ["/system/bin/content", "read", "--uri", item["uri"]], destination, remaining)
            validate_media(destination, item["kind"])
            return "original_app_uid_provider" if prefix else "authorized_adb_provider"
        except (OSError, BackupError, subprocess.SubprocessError, ValueError):
            destination.unlink(missing_ok=True)
    if not picker or not shutil.which("termux-storage-get") or not sys.stdin.isatty():
        raise BackupError("Documentul nu este accesibil. Instalează Termux:API și pachetul termux-api pentru alegerea originalului.")
    # Only a foreground Android picker chooses the fallback; no arbitrary replacement.
    print("Alege EXACT fișierul original pentru: " + item["label"])
    input("Apasă Enter pentru selectorul Android (Anulează dacă originalul nu mai există): ")
    result = subprocess.run(["termux-storage-get", str(destination)], capture_output=True, timeout=900)
    if result.returncode or not destination.exists() or destination.stat().st_size > remaining:
        raise BackupError("Selectarea originalului a fost anulată sau fișierul este prea mare.")
    validate_media(destination, item["kind"])
    if input("Confirmi că ai ales exact ORIGINALUL, nu un înlocuitor? Scrie DA: ").strip().upper() != "DA":
        raise BackupError("Originalul nu a fost confirmat. Copia completă nu va fi declarată gata.")
    return "owner_confirmed_android_picker_original_identity_not_independently_verified"


def write_collection(output: Path, data: dict, mapping: dict[str, Path]) -> dict:
    validate_data(data)
    if len(source_media(data)) >= 10_000:
        raise BackupError("Prea multe surse media pentru import.")
    portable = copy.deepcopy(data)
    aliases = {}
    unique = {}
    for index, item in enumerate(source_media(data)):
        path = mapping[item["uri"]]
        digest = sha256(path)
        unique[digest] = path
        aliases[item["uri"]] = f"content://mdp.transfer/media/{digest}?source={index}"
    for track in portable["music"]["tracks"]:
        track["uri"] = aliases[track["uri"]]
    if portable["background"]["kind"] != "ORIGINAL":
        portable["background"]["uri"] = aliases[portable["background"]["uri"]]
    manifest = {"type": "mdp-remix-collection", "version": 1, "id": str(uuid.uuid4()), "data": portable,
                "media": [{"sha256": digest, "size": path.stat().st_size} for digest, path in unique.items()]}
    encoded = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(unique) >= 10_000 or len(encoded) > MAX_MANIFEST or sum(path.stat().st_size for path in unique.values()) + len(encoded) > MAX_MEDIA:
        raise BackupError("Colecția depășește limitele de import ale aplicației (2 GiB/10.000 intrări).")
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        archive.writestr("manifest.json", encoded)
        for digest, path in unique.items():
            archive.write(path, "media/" + digest)
    return manifest


def verify_collection(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or len(names) > 10_000:
            raise BackupError("Arhiva are intrări duplicate sau prea multe intrări.")
        manifest = json.loads(archive.read("manifest.json"))
        if manifest.get("type") != "mdp-remix-collection" or manifest.get("version") != 1:
            raise BackupError("Format de colecție invalid.")
        validate_data(manifest["data"])
        declared = {entry["sha256"]: entry["size"] for entry in manifest["media"]}
        if len(declared) != len(manifest["media"]) or set(names) != {"manifest.json"} | {"media/" + digest for digest in declared}:
            raise BackupError("Conținutul arhivei nu corespunde manifestului.")
        used = set()
        for item in source_media(manifest["data"]):
            match = re.fullmatch(r"content://mdp\.transfer/media/([0-9a-f]{64})\?source=(0|[1-9][0-9]{0,8})", item["uri"])
            if not match or int(match[2]) >= 10_000:
                raise BackupError("Un fișier nu are un URI portabil valid.")
            used.add(match[1])
        if used != set(declared):
            raise BackupError("Lipsesc fișiere sau există fișiere nereferențiate.")
        for digest, size in declared.items():
            checksum = hashlib.sha256()
            count = 0
            with archive.open("media/" + digest) as source:
                for block in iter(lambda: source.read(64 * 1024), b""):
                    count += len(block)
                    checksum.update(block)
            if count != size or checksum.hexdigest() != digest:
                raise BackupError("Verificarea integrală a fișierelor din colecție a eșuat.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Copie completă fără ștergerea aplicației originale v3; numai pentru aplicație debuggable.")
    parser.add_argument("--serial", help="Adresa:port din adb devices, dacă sunt mai multe dispozitive")
    parser.add_argument("--output-parent", type=Path, default=Path.home() / "mdp-backups")
    parser.add_argument("--no-picker", action="store_true", help="Oprește dacă un document nu se poate citi automat")
    args = parser.parse_args(argv)
    os.umask(0o077)
    report = {"status": "INCOMPLETE", "originalModified": False, "media": []}
    directory = None
    try:
        adb = Adb(args.serial)
        original = adb.verify_original()
        if sys.stdin.isatty():
            print("În aplicația VECHE oprește muzica, apoi revino în Termux. Nu modifica biblioteca/setările până la final.")
            input("Nu se va dezinstala sau modifica aplicația veche. Apasă Enter pentru copia numai-citire: ")
        args.output_parent.mkdir(parents=True, exist_ok=True)
        directory = args.output_parent / (time.strftime("mdp-v3-%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:8])
        directory.mkdir(mode=0o700)
        report["original"] = original
        private = directory / "legacy-private.tar"
        hashes = adb.snapshot(private)
        report["privateFiles"] = hashes
        data = load_legacy(private)
        if len(source_media(data)) >= 10_000:
            raise BackupError("Prea multe surse media pentru o singură colecție importabilă.")
        apk_dir = directory / "installed-apks"
        apk_dir.mkdir()
        for index, remote_path in enumerate(original["apkPaths"]):
            apk = apk_dir / f"{index:02d}.apk"
            adb.stream(["cat", remote_path], apk, MAX_PRIVATE)
            with zipfile.ZipFile(apk) as archive:
                if "AndroidManifest.xml" not in archive.namelist() or archive.testzip() is not None:
                    raise BackupError("APK-ul original copiat nu este integru.")
        media_dir = directory / "media"
        media_dir.mkdir()
        mapping = {}
        remaining = MAX_MEDIA
        # If a genuine default GIF is bundled, carry its exact bytes as a custom GIF.
        if data["background"]["kind"] == "ORIGINAL":
            for apk in sorted(apk_dir.iterdir()):
                with zipfile.ZipFile(apk) as archive:
                    asset = "assets/backgrounds/original_couple.gif"
                    if asset in archive.namelist():
                        asset_path = media_dir / "original-default.gif"
                        with archive.open(asset) as src, asset_path.open("xb") as out:
                            shutil.copyfileobj(src, out)
                        validate_media(asset_path, "gif")
                        uri = "content://mdp.legacy.original/default"
                        data["background"].update({"kind": "GIF", "uri": uri})
                        mapping[uri] = asset_path
                        remaining -= asset_path.stat().st_size
                        report["media"].append({"uri": uri, "method": "original_installed_apk_asset", "sha256": sha256(asset_path), "size": asset_path.stat().st_size})
                        break
        for index, item in enumerate(source_media(data)):
            if item["uri"] in mapping:
                continue
            destination = media_dir / f"source-{index:04d}"
            try:
                method = obtain_media(adb, item, destination, remaining, not args.no_picker)
            except (BackupError, OSError, subprocess.SubprocessError, ValueError) as failure:
                report["missing"] = {"index": index, "kind": item["kind"], "uri": item["uri"], "reason": str(failure)}
                raise BackupError("Un original audio/fundal nu a fost salvat. Copia rămâne INCOMPLETĂ; nu dezinstala aplicația veche.") from failure
            remaining -= destination.stat().st_size
            mapping[item["uri"]] = destination
            report["media"].append({"uri": item["uri"], "method": method, "sha256": sha256(destination), "size": destination.stat().st_size})
            print(f"Fișier {len(mapping)}/{len(source_media(data))} copiat și verificat.")
        final_snapshot = directory / "legacy-private-final-check.tar"
        if adb.snapshot(final_snapshot) != hashes:
            raise BackupError("Biblioteca/setările s-au schimbat în timpul copiei. Am păstrat ambele citiri; nu dezinstala. Reia când aplicația veche nu mai este modificată.")
        final_snapshot.unlink()
        collection = directory / "MdP-v3-complete.mdpremix"
        write_collection(collection, data, mapping)
        verify_collection(collection)
        checksums = {str(path.relative_to(directory)): sha256(path) for path in directory.rglob("*") if path.is_file()}
        report.update({"status": "COMPLETE", "collection": collection.name, "checksums": checksums,
                       "ownerConfirmedMappings": sum("owner_confirmed" in item["method"] for item in report["media"]),
                       "restoredInAndroid": False})
        write_json(directory / "backup-report.json", report)
        (directory / "COMPLETE.txt").write_text(
            "Copie de siguranță completă, fișiere recitite și SHA-256 verificate.\n"
            "Importul/restaurarea pe Android NU au fost testate de acest script.\n"
            "Păstrează originalul instalat până când ai importat și verificat copia în aplicația nouă.\n"
            "Copia conține date personale și audio; păstreaz-o privat și fă o a doua copie sigură.\n", encoding="utf-8")
        print("COPIE COMPLETĂ: " + str(collection))
        if report["ownerConfirmedMappings"]:
            print("Unele originale au fost confirmate de tine în selector; identitatea lor față de URI-ul inaccesibil nu poate fi verificată independent.")
        print("Nu dezinstala originalul. Importă colecția în noua aplicație separată și verifică playlist-urile, audio și fundalul înainte de orice înlocuire.")
        return 0
    except (BackupError, OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError, ET.ParseError, tarfile.TarError, zipfile.BadZipFile, KeyboardInterrupt) as failure:
        if directory is not None:
            report["status"] = "INCOMPLETE"
            report["reason"] = str(failure) or "Operațiune anulată."
            (directory / "COMPLETE.txt").unlink(missing_ok=True)
            report_path = directory / "backup-report.json"
            try:
                if report_path.exists():
                    report_path.rename(directory / ("backup-report-previous-" + uuid.uuid4().hex[:8] + ".json"))
                write_json(report_path, report)
            except OSError:
                pass  # A full disk must not trigger any writes to the old app.
            print("Progres păstrat în: " + str(directory), file=sys.stderr)
        print("OPRIT: " + str(failure), file=sys.stderr)
        print("Aplicația originală și datele ei nu au fost modificate. Nu o dezinstala.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
