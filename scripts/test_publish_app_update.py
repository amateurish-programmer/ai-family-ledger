"""Synthetic fixtures only; never connect to a project or use a secret."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError, URLError
from unittest.mock import Mock
import zipfile

SCRIPT = Path(__file__).with_name("publish-app-update.py")
if SCRIPT.exists():
    spec = importlib.util.spec_from_file_location("publisher", SCRIPT)
    publisher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(publisher)
else:
    publisher = None


class MemoryStorage:
    def __init__(self):
        self.objects = {}
        self.events = []
        self.corrupt_apk = False
        self.corrupt_latest = False

    def read(self, path, limit):
        self.events.append(("read", path))
        return self.objects.get(path)

    def put(self, path, data, content_type, upsert):
        self.events.append(("put", path, content_type, upsert))
        if not upsert and path in self.objects:
            raise publisher.PublishError("Object upload failed")
        if (self.corrupt_apk and path.endswith(".apk")) or (self.corrupt_latest and path == "latest.json"):
            data = b"corrupt"
        self.objects[path] = data


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(publisher, "APK publisher has not been implemented")
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.gradle = self.root / "build.gradle.kts"
        self.gradle.write_text('applicationId = "com.familyledger.app"\nminSdk = 26\nversionCode = 11\nversionName = "0.11.0"\n', encoding="utf-8")
        self.apk = self.root / "AI家庭账本-v0.11.0.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
                archive.writestr(name, b"synthetic")
        self.notes = self.root / "notes.txt"
        self.notes.write_text("设置中检查更新。", encoding="utf-8")
        self.storage = MemoryStorage()

    def publish(self):
        return publisher.publish(self.storage, self.root, self.gradle, self.notes)

    def test_first_publish_verifies_apk_before_exposing_latest(self):
        result = self.publish()
        path = "releases/11/ai-family-ledger-v0.11.0.apk"
        manifest = json.loads(self.storage.objects["latest.json"])
        self.assertEqual(manifest["versionCode"], 11)
        self.assertEqual(manifest["versionName"], "0.11.0")
        self.assertEqual(manifest["sha256"], hashlib.sha256(self.apk.read_bytes()).hexdigest())
        self.assertEqual(manifest["apkPath"], path)
        self.assertEqual(result, manifest)
        apk_put = self.storage.events.index(("put", path, "application/vnd.android.package-archive", False))
        latest_put = self.storage.events.index(("put", "latest.json", "application/json", True))
        self.assertIn(("read", path), self.storage.events[apk_put + 1:latest_put])
        self.assertEqual(self.storage.events[-1], ("read", "latest.json"))

    def test_storage_key_is_ascii_while_delivery_name_remains_chinese(self):
        manifest = self.publish()
        self.assertEqual(self.apk.name, "AI家庭账本-v0.11.0.apk")
        self.assertEqual(manifest["apkPath"], "releases/11/ai-family-ledger-v0.11.0.apk")
        self.assertTrue(all(path.isascii() for path in self.storage.objects))
        with self.assertRaises(publisher.PublishError):
            publisher.validate_manifest(dict(manifest, apkPath="releases/11/AI家庭账本-v0.11.0.apk"))

    def test_identical_retry_does_not_rewrite_objects(self):
        first = self.publish()
        self.storage.events.clear()
        self.assertEqual(self.publish(), first)
        self.assertFalse(any(event[0] == "put" for event in self.storage.events))

    def test_same_version_different_content_or_notes_rejected(self):
        self.publish()
        self.notes.write_text("changed", encoding="utf-8")
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.notes.write_text("设置中检查更新。", encoding="utf-8")
        with zipfile.ZipFile(self.apk, "a") as archive:
            archive.writestr("extra", b"different")
        with self.assertRaises(publisher.PublishError):
            self.publish()

    def test_lower_version_never_changes_latest(self):
        self.publish()
        before = self.storage.objects["latest.json"]
        self.gradle.write_text(self.gradle.read_text().replace("versionCode = 11", "versionCode = 10"))
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.assertEqual(self.storage.objects["latest.json"], before)

    def test_orphan_apk_same_bytes_can_finish_publish(self):
        self.storage.objects["releases/11/ai-family-ledger-v0.11.0.apk"] = self.apk.read_bytes()
        self.publish()
        self.assertIn("latest.json", self.storage.objects)

    def test_orphan_apk_different_bytes_cannot_be_overwritten(self):
        self.storage.objects["releases/11/ai-family-ledger-v0.11.0.apk"] = b"other"
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.assertNotIn("latest.json", self.storage.objects)

    def test_corrupt_uploaded_apk_prevents_latest(self):
        self.storage.corrupt_apk = True
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.assertNotIn("latest.json", self.storage.objects)

    def test_latest_readback_corruption_fails(self):
        self.storage.corrupt_latest = True
        with self.assertRaises(publisher.PublishError):
            self.publish()

    def test_malformed_existing_manifest_fails_closed(self):
        self.storage.objects["latest.json"] = b'{"versionCode":1}'
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.assertFalse(any(event[0] == "put" for event in self.storage.events))

    def test_ambiguous_apks_invalid_archive_and_oversize_notes_rejected(self):
        (self.root / "extra.apk").write_bytes(b"extra")
        with self.assertRaises(publisher.PublishError):
            self.publish()
        (self.root / "extra.apk").unlink()
        self.notes.write_text("x" * 4001)
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.notes.write_text("ok")
        self.apk.write_bytes(b"not an apk")
        with self.assertRaises(publisher.PublishError):
            self.publish()

    def test_manifest_rejects_unsafe_contract_fields(self):
        manifest = self.publish()
        cases = {"schemaVersion": True, "versionCode": True, "versionName": "0.11", "minSdk": 25,
                 "packageName": "other", "apkPath": "https://evil.example/app.apk", "sizeBytes": 52428801,
                 "sha256": "A" * 64, "notes": "x" * 4001, "publishedAt": "yesterday"}
        for key, value in cases.items():
            with self.subTest(key=key), self.assertRaises(publisher.PublishError):
                publisher.validate_manifest(dict(manifest, **{key: value}))

    def test_version_name_is_canonical_ascii_and_at_most_32_characters(self):
        manifest = self.publish()
        for version in ("00.11.0", "0.01.0", "0.11.00", "０.11.0", "0.١١.0", "1" * 29 + ".0.0"):
            with self.subTest(version=version), self.assertRaises(publisher.PublishError):
                publisher.validate_manifest(dict(manifest, versionName=version,
                    apkPath=f"releases/11/ai-family-ledger-v{version}.apk"))
        for version in ("0.0.0", "1.22.333", "1" * 28 + ".0.0"):
            candidate = dict(manifest, versionName=version, apkPath=f"releases/11/ai-family-ledger-v{version}.apk")
            self.assertEqual(publisher.validate_manifest(candidate), candidate)

    def test_notes_reject_iso_controls_but_keep_line_breaks_and_tabs(self):
        manifest = self.publish()
        for code in (*range(0, 9), 11, 12, *range(14, 32), *range(127, 160)):
            with self.subTest(code=code), self.assertRaises(publisher.PublishError):
                publisher.validate_manifest(dict(manifest, notes="a" + chr(code) + "b"))
        accepted = dict(manifest, notes="第一行\n第二行\r\n\t说明")
        self.assertEqual(publisher.validate_manifest(accepted), accepted)

    def test_notes_length_matches_android_utf16_units(self):
        manifest = self.publish()
        with self.assertRaises(publisher.PublishError):
            publisher.validate_manifest(dict(manifest, notes="😀" * 2001))
        accepted = dict(manifest, notes="😀" * 2000)
        self.assertEqual(publisher.validate_manifest(accepted), accepted)

    def test_duplicate_json_keys_fail_instead_of_using_last_value(self):
        self.publish()
        raw = self.storage.objects["latest.json"]
        duplicate = b'{"versionCode":10,' + raw[1:]
        with self.assertRaises(publisher.PublishError):
            publisher.decode_manifest(duplicate)

    def test_decoder_limits_bytes_without_relying_on_transport(self):
        self.publish()
        raw = self.storage.objects["latest.json"]
        for value in (raw + b" " * (32769 - len(raw)),
                      raw.decode("utf-8") + " " * 32768):
            with self.subTest(kind=type(value).__name__), self.assertRaises(publisher.PublishError):
                publisher.decode_manifest(value)
        self.assertEqual(publisher.decode_manifest(raw + b" " * (32768 - len(raw)))["versionCode"], 11)


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(publisher, "APK publisher has not been implemented")
        self.opener = Mock()
        self.storage = publisher.Storage("https://fixture.supabase.co", "test-secret", opener=self.opener)

    def error(self, status, body):
        self.opener.open.side_effect = HTTPError("https://fixture.supabase.co", status, "private error", {}, io.BytesIO(body))

    def test_only_explicit_storage_object_not_found_is_absent(self):
        for status, body in [(400, b'{"statusCode":"404","error":"not_found","message":"Object not found"}'),
                             (404, b'{"code":"NoSuchKey","message":"Object not found"}')]:
            self.error(status, body)
            self.assertIsNone(self.storage.read("latest.json", 16384))
        for status, body in [(404, b"proxy error"), (404, b'{"code":"NoSuchBucket"}'),
                             (403, b'{"statusCode":"404","message":"Object not found"}')]:
            self.error(status, body)
            with self.assertRaises(publisher.PublishError):
                self.storage.read("latest.json", 16384)

    def test_failures_do_not_reveal_network_error_or_secret(self):
        self.opener.open.side_effect = URLError("test-secret private response")
        with self.assertRaises(publisher.PublishError) as failure:
            self.storage.read("latest.json", 16384)
        self.assertNotIn("test-secret", str(failure.exception))
        self.assertNotIn("private response", str(failure.exception))

    def test_streamed_download_is_bounded(self):
        response = io.BytesIO(b"123456")
        response.status = 200
        self.opener.open.return_value = response
        with self.assertRaises(publisher.PublishError):
            self.storage.read("latest.json", 5)

    def test_upload_headers_and_timeout(self):
        response = io.BytesIO(b"{}")
        response.status = 200
        self.opener.open.return_value = response
        self.storage.put("latest.json", b"{}", "application/json", True)
        request = self.opener.open.call_args.args[0]
        self.assertEqual(request.method, "POST")
        self.assertEqual(request.get_header("X-upsert"), "true")
        self.assertEqual(request.get_header("Cache-control"), "max-age=0")
        self.assertEqual(self.opener.open.call_args.kwargs["timeout"], 60)

    def test_redirect_is_rejected(self):
        handler = publisher.NoRedirect()
        with self.assertRaises(publisher.PublishError):
            handler.redirect_request(None, None, 302, "redirect", {}, "https://other.example")


if __name__ == "__main__":
    unittest.main()
