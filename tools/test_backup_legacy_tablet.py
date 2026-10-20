"""Host-only tests. These tests never run ADB or touch an Android device."""
import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import backup_legacy_tablet as backup


class LegacyBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def data(self):
        return {
            "music": {"version": 1, "tracks": [
                {"id": "one", "title": "Muzică originală", "artist": "Artist", "album": "Album", "durationMs": 123,
                 "uri": "content://audio/one"},
                {"id": "two", "title": "A doua", "artist": "Artist", "album": "Album", "durationMs": 456,
                 "uri": "content://audio/two"},
            ], "favorites": ["two"], "playlists": [{"id": "p", "name": "Ordinea mea", "trackIds": ["two", "one"]}]},
            "youtube": {"version": 1, "links": [
                {"id": "yt", "title": "Setul original", "url": "https://www.youtube.com/playlist?list=PLmdp_original", "kind": "YOUTUBE_PLAYLIST",
                 "favorite": True, "addedAt": 100, "lastOpenedAt": 200}],
                "playlists": [{"id": "yp", "name": "YouTube personal", "linkIds": ["yt"], "favorite": True, "createdAt": 10, "lastOpenedAt": 20}]},
            "english": False, "recorderDesignId": "red", "panelTransparency": 27, "brandSyncEnabled": False,
            "background": {"version": 1, "uri": "content://background/gif", "kind": "GIF", "scale": "FIT", "dimming": 0.4,
                           "animationEnabled": True},
        }

    def tar(self, files, name="private.tar"):
        path = self.root / name
        with tarfile.open(path, "w") as archive:
            for key, value in files.items():
                raw = value if isinstance(value, bytes) else value.encode()
                item = tarfile.TarInfo(key)
                item.size = len(raw)
                archive.addfile(item, io.BytesIO(raw))
        return path

    def test_private_snapshot_checksums_and_legacy_preferences(self):
        data = self.data()
        xml = '<map><boolean name="english" value="true"/><string name="recorder_design">studio_pro</string></map>'
        bg_xml = '<map><string name="background_settings_json">' + json.dumps(data["background"]) + '</string></map>'
        private = self.tar({"files/music-library.json": json.dumps(data["music"]),
                            "files/youtube-link-library.json": json.dumps(data["youtube"]),
                            "shared_prefs/mdp_settings.xml": xml,
                            "shared_prefs/mdp_background_v1.xml": bg_xml})
        checksums = backup.checked_private_tar(private)
        self.assertEqual(4, len(checksums))
        loaded = backup.load_legacy(private)
        self.assertEqual(data["music"], loaded["music"])
        self.assertTrue(loaded["english"])
        self.assertEqual("studio_pro", loaded["recorderDesignId"])
        self.assertEqual(data["background"], loaded["background"])

    def test_android_backup_file_takes_priority_and_defaults_match_app(self):
        private = self.tar({"shared_prefs/mdp_settings.xml": '<map><boolean name="english" value="false"/></map>',
                            "shared_prefs/mdp_settings.xml.bak": '<map><boolean name="english" value="true"/></map>'})
        loaded = backup.load_legacy(private)
        self.assertTrue(loaded["english"])
        self.assertEqual("black", loaded["recorderDesignId"])
        self.assertEqual([], loaded["music"]["tracks"])
        self.assertEqual("ORIGINAL", loaded["background"]["kind"])

    def test_private_archive_rejects_traversal_and_links(self):
        with self.assertRaises(backup.BackupError):
            backup.checked_private_tar(self.tar({"../escape": b"x"}, "bad.tar"))
        path = self.root / "link.tar"
        with tarfile.open(path, "w") as archive:
            item = tarfile.TarInfo("files/link")
            item.type = tarfile.SYMTYPE
            item.linkname = "/data/system/secret"
            archive.addfile(item)
        with self.assertRaises(backup.BackupError):
            backup.checked_private_tar(path)

    def test_xml_entities_are_rejected(self):
        with self.assertRaises(backup.BackupError):
            backup.preferences(b'<!DOCTYPE map [<!ENTITY secret "no">]><map/>')

    def test_document_path_decoding_is_limited_to_known_storage_roots(self):
        self.assertEqual(Path("/storage/emulated/0/Music/song.mp3"), backup.local_document_path(
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3"))
        self.assertEqual(Path("/storage/ABCD-1234/Music/song.mp3"), backup.local_document_path(
            "content://com.android.externalstorage.documents/document/ABCD-1234%3AMusic%2Fsong.mp3"))
        self.assertIsNone(backup.local_document_path("content://com.android.externalstorage.documents/document/primary%3A..%2Fsecret"))
        self.assertIsNone(backup.local_document_path("content://com.android.providers.downloads.documents/document/raw%3A%2Fdata%2Fsecret"))
        self.assertIsNone(backup.local_document_path("content://untrusted/document/primary%3AMusic%2Fsong.mp3"))

    def make_collection(self):
        audio = self.root / "audio"
        gif = self.root / "gif"
        audio.write_bytes(b"original PCM bytes for both track aliases")
        gif.write_bytes(b"GIF89a-original-background")
        data = self.data()
        output = self.root / "complete.mdpremix"
        manifest = backup.write_collection(output, data, {"content://audio/one": audio, "content://audio/two": audio,
                                                          "content://background/gif": gif})
        return output, manifest

    def test_collection_matches_android_contract_and_preserves_identical_byte_aliases(self):
        output, manifest = self.make_collection()
        backup.verify_collection(output)
        self.assertEqual("mdp-remix-collection", manifest["type"])
        self.assertEqual(2, len(manifest["media"]))
        tracks = manifest["data"]["music"]["tracks"]
        self.assertNotEqual(tracks[0]["uri"], tracks[1]["uri"])
        self.assertTrue(tracks[0]["uri"].endswith("?source=0"))
        self.assertTrue(tracks[1]["uri"].endswith("?source=1"))
        self.assertEqual(["two", "one"], manifest["data"]["music"]["playlists"][0]["trackIds"])
        self.assertEqual(["two"], manifest["data"]["music"]["favorites"])
        self.assertEqual(27, manifest["data"]["panelTransparency"])
        self.assertFalse(manifest["data"]["brandSyncEnabled"])
        self.assertEqual(self.data()["youtube"], manifest["data"]["youtube"])

    def test_missing_media_does_not_create_collection(self):
        with self.assertRaises(KeyError):
            backup.write_collection(self.root / "missing.mdpremix", self.data(), {})
        self.assertFalse((self.root / "missing.mdpremix").exists())

    def test_archive_media_corruption_is_detected(self):
        output, manifest = self.make_collection()
        altered = self.root / "altered.mdpremix"
        with zipfile.ZipFile(output) as source, zipfile.ZipFile(altered, "w") as target:
            for name in source.namelist():
                target.writestr(name, source.read(name) if name == "manifest.json" else b"corrupted")
        with self.assertRaises(backup.BackupError):
            backup.verify_collection(altered)

    def test_invalid_playlist_references_block_backup(self):
        data = self.data()
        data["music"]["playlists"][0]["trackIds"].append("missing")
        with self.assertRaises(backup.BackupError):
            backup.validate_data(data)

    def test_success_and_missing_media_are_reported_without_any_real_adb(self):
        data = self.data()
        private = self.tar({"files/music-library.json": json.dumps(data["music"]),
                            "files/youtube-link-library.json": json.dumps(data["youtube"]),
                            "shared_prefs/mdp_background_v1.xml": '<map><string name="background_settings_json">' +
                            json.dumps(data["background"]) + '</string></map>'})
        apk = self.root / "original.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"original binary manifest fixture")
        class FakeAdb:
            def __init__(self, serial): pass
            def verify_original(self):
                return {"package": backup.PACKAGE, "versionCode": 3, "uid": 10001, "apkPaths": ["/data/app/example/base.apk"]}
            def snapshot(self, target):
                shutil.copyfile(private, target)
                return backup.checked_private_tar(target)
            def stream(self, tokens, target, maximum, timeout=120):
                self.tokens = tokens
                shutil.copyfile(apk, target)
        def obtain(adb, item, destination, remaining, picker):
            destination.write_bytes(b"GIF89a" if item["kind"] == "gif" else b"original audio bytes")
            return "original_app_uid_provider"
        out = self.root / "backup-success"
        with patch.object(backup, "Adb", FakeAdb), patch.object(backup, "obtain_media", obtain), patch("sys.stdin.isatty", return_value=False):
            self.assertEqual(0, backup.main(["--output-parent", str(out)]))
        completed = next(out.iterdir())
        self.assertTrue((completed / "COMPLETE.txt").exists())
        report = json.loads((completed / "backup-report.json").read_text())
        self.assertEqual("COMPLETE", report["status"])
        self.assertFalse(report["originalModified"])
        self.assertFalse(report["restoredInAndroid"])
        backup.verify_collection(completed / "MdP-v3-complete.mdpremix")
        out = self.root / "backup-missing"
        with patch.object(backup, "Adb", FakeAdb), patch.object(backup, "obtain_media", side_effect=backup.BackupError("Missing original")), patch("sys.stdin.isatty", return_value=False):
            self.assertEqual(1, backup.main(["--output-parent", str(out)]))
        failed = next(out.iterdir())
        self.assertFalse((failed / "COMPLETE.txt").exists())
        self.assertTrue((failed / "legacy-private.tar").exists())
        report = json.loads((failed / "backup-report.json").read_text())
        self.assertEqual("INCOMPLETE", report["status"])
        self.assertEqual("content://audio/one", report["missing"]["uri"])
        self.assertFalse(report["originalModified"])

    def test_original_application_changed_during_copy_blocks_complete(self):
        data = self.data()
        private = self.tar({"files/music-library.json": json.dumps(data["music"])})
        apk = self.root / "original.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
        class ChangedAdb:
            calls = 0
            def __init__(self, serial): pass
            def verify_original(self):
                return {"package": backup.PACKAGE, "versionCode": 3, "uid": 10001, "apkPaths": ["/data/app/example/base.apk"]}
            def snapshot(self, target):
                shutil.copyfile(private, target)
                self.calls += 1
                return {"files/music-library.json": str(self.calls)}
            def stream(self, tokens, target, maximum, timeout=120):
                shutil.copyfile(apk, target)
        def obtain(adb, item, destination, remaining, picker):
            destination.write_bytes(b"audio")
            return "original_app_uid_provider"
        out = self.root / "changed"
        with patch.object(backup, "Adb", ChangedAdb), patch.object(backup, "obtain_media", obtain), patch("sys.stdin.isatty", return_value=False):
            self.assertEqual(1, backup.main(["--output-parent", str(out)]))
        directory = next(out.iterdir())
        self.assertFalse((directory / "COMPLETE.txt").exists())
        self.assertTrue((directory / "legacy-private-final-check.tar").exists())
        self.assertEqual("INCOMPLETE", json.loads((directory / "backup-report.json").read_text())["status"])


if __name__ == "__main__":
    unittest.main()
