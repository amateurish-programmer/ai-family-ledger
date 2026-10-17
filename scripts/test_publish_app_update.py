"""Synthetic fixtures only; never connect to a project or use a secret."""
import hashlib
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError, URLError
from unittest.mock import Mock, patch
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
        self.list_error = None
        self.remove_error = None
        self.before_list = None
        self.after_remove = None
        self.ignored_deletes = set()

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

    def list_page(self, prefix, limit, offset):
        self.events.append(("list", prefix, limit, offset))
        if self.before_list:
            self.before_list(prefix, limit, offset)
        if self.list_error:
            raise self.list_error
        entries = {}
        for path in self.objects:
            if not path.startswith(prefix + "/"):
                continue
            child, separator, _ = path[len(prefix) + 1:].partition("/")
            entries[child] = {"name": child, "id": None if separator else "object-" + child,
                              "metadata": None if separator else {}}
        return [entries[name] for name in sorted(entries)][offset:offset + limit]

    def remove(self, paths):
        self.events.append(("remove", tuple(paths)))
        if self.remove_error:
            raise self.remove_error
        for path in paths:
            if path not in self.ignored_deletes:
                self.objects.pop(path, None)
        if self.after_remove:
            self.after_remove(paths)


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
        self.reports = []

    def publish(self):
        output = io.StringIO()
        with redirect_stdout(output):
            result = publisher.publish(self.storage, self.root, self.gradle, self.notes)
        self.reports.extend(output.getvalue().splitlines())
        return result

    def seed_old_release(self, code=10, version="0.10.0"):
        path = f"releases/{code}/ai-family-ledger-v{version}.apk"
        self.storage.objects[path] = b"synthetic old package"
        return path

    def test_verified_publication_removes_old_apks_and_keeps_unrelated_objects(self):
        old = self.seed_old_release()
        unrelated = {"backup/ledger.json": b"synthetic backup", "releases/10/notes.json": b"{}",
                     "releases/10/unrelated.apk": b"other", "releases/010/ai-family-ledger-v0.10.0.apk": b"other",
                     "releases/test/ai-family-ledger-v0.10.0.apk": b"other",
                     "releases/10/nested/ai-family-ledger-v0.10.0.apk": b"other"}
        self.storage.objects.update(unrelated)
        manifest = self.publish()
        self.assertEqual(set(self.storage.objects), set(unrelated) | {"latest.json", manifest["apkPath"]})
        self.assertEqual({key: self.storage.objects[key] for key in unrelated}, unrelated)
        deletion = self.storage.events.index(("remove", (old,)))
        latest_put = self.storage.events.index(("put", "latest.json", "application/json", True))
        self.assertIn(("read", "latest.json"), self.storage.events[latest_put + 1:deletion])
        self.assertIn(("read", manifest["apkPath"]), self.storage.events[:latest_put])
        self.assertTrue(any(event[0] == "list" for event in self.storage.events[deletion + 1:]))
        self.assertIn("removed 1 old APK", self.reports[-1])
        self.assertIn("app-updates/" + manifest["apkPath"], self.reports[-1])

    def test_failed_apk_or_manifest_verification_never_starts_cleanup(self):
        for corrupt in ("corrupt_apk", "corrupt_latest"):
            with self.subTest(corrupt=corrupt):
                self.storage = MemoryStorage()
                old = self.seed_old_release()
                setattr(self.storage, corrupt, True)
                with self.assertRaises(publisher.PublishError):
                    self.publish()
                self.assertIn(old, self.storage.objects)
                self.assertFalse(any(event[0] in ("list", "remove") for event in self.storage.events))

    def test_failed_new_apk_keeps_previous_manifest_and_package_available(self):
        manifest = self.publish()
        self.gradle.write_text(self.gradle.read_text().replace("versionCode = 11", "versionCode = 12"))
        before = self.storage.objects["latest.json"]
        self.storage.events.clear()
        self.storage.corrupt_apk = True
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.assertEqual(self.storage.objects["latest.json"], before)
        self.assertEqual(self.storage.objects[manifest["apkPath"]], self.apk.read_bytes())
        self.assertFalse(any(event[0] in ("list", "remove") for event in self.storage.events))

    def test_lost_latest_upload_response_defers_cleanup_until_verified_retry(self):
        old = self.seed_old_release()
        original = self.storage.put
        def lose_latest_response(path, data, content_type, upsert):
            original(path, data, content_type, upsert)
            if path == "latest.json":
                raise publisher.PublishError("Storage network request failed")
        self.storage.put = lose_latest_response
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.assertIn(old, self.storage.objects)
        self.assertFalse(any(event[0] in ("list", "remove") for event in self.storage.events))
        self.storage.put = original
        self.assertEqual(self.publish()["versionCode"], 11)
        self.assertNotIn(old, self.storage.objects)

    def test_same_version_retry_finishes_cleanup_without_rewriting_release(self):
        first = self.publish()
        old = self.seed_old_release()
        self.storage.events.clear()
        self.assertEqual(self.publish(), first)
        self.assertNotIn(old, self.storage.objects)
        self.assertFalse(any(event[0] == "put" for event in self.storage.events))

    def test_pagination_is_completed_before_batched_deletion(self):
        old = {self.seed_old_release(code, f"0.{code}.{minor}")
               for code in (1, 2, 3, 4) for minor in (0, 1, 2)}
        with patch.object(publisher, "LIST_PAGE_SIZE", 2), patch.object(publisher, "DELETE_BATCH_SIZE", 2):
            manifest = self.publish()
        self.assertEqual(set(self.storage.objects), {"latest.json", manifest["apkPath"]})
        batches = [event[1] for event in self.storage.events if event[0] == "remove"]
        self.assertEqual(len(batches), 6)
        self.assertTrue(all(len(batch) <= 2 for batch in batches))
        self.assertEqual({path for batch in batches for path in batch}, old)
        first_delete = next(index for index, event in enumerate(self.storage.events) if event[0] == "remove")
        before = self.storage.events[:first_delete]
        self.assertIn(("list", "releases", 2, 4), before)
        self.assertIn(("list", "releases/4", 2, 2), before)

    def test_listing_failure_preserves_all_packages_and_identical_retry_can_recover(self):
        old = self.seed_old_release()
        self.storage.list_error = publisher.PublishError("Storage list request failed")
        with self.assertRaises(publisher.CleanupError) as failure:
            self.publish()
        manifest = json.loads(self.storage.objects["latest.json"])
        self.assertEqual(failure.exception.manifest, manifest)
        self.assertIn(old, self.storage.objects)
        self.assertIn(manifest["apkPath"], self.storage.objects)
        self.assertFalse(any(event[0] == "remove" for event in self.storage.events))
        self.storage.list_error = None
        self.assertEqual(self.publish(), manifest)
        self.assertNotIn(old, self.storage.objects)

    def test_delete_failure_does_not_rollback_successful_manifest(self):
        old = self.seed_old_release()
        self.storage.remove_error = publisher.PublishError("Storage cleanup request failed (HTTP 403)")
        with self.assertRaises(publisher.CleanupError):
            self.publish()
        manifest = json.loads(self.storage.objects["latest.json"])
        self.assertEqual(manifest["versionCode"], 11)
        self.assertIn(manifest["apkPath"], self.storage.objects)
        self.assertIn(old, self.storage.objects)
        self.assertEqual(sum(event[:2] == ("put", "latest.json") for event in self.storage.events), 1)
        self.assertEqual(self.reports, [])

    def test_delete_success_response_is_not_enough_when_old_apk_remains(self):
        old = self.seed_old_release()
        self.storage.ignored_deletes.add(old)
        with self.assertRaises(publisher.CleanupError):
            self.publish()
        self.assertIn(old, self.storage.objects)
        self.assertEqual(self.reports, [])

    def test_partial_cleanup_failure_can_resume_without_republishing_or_losing_active_apk(self):
        old = {self.seed_old_release(code, f"0.{code}.0") for code in (1, 2, 3)}
        def fail_next_batch(paths):
            self.storage.remove_error = publisher.PublishError("Storage cleanup request failed (HTTP 503)")
        self.storage.after_remove = fail_next_batch
        with patch.object(publisher, "DELETE_BATCH_SIZE", 1), self.assertRaises(publisher.CleanupError):
            self.publish()
        manifest = json.loads(self.storage.objects["latest.json"])
        self.assertEqual(len(old.intersection(self.storage.objects)), 2)
        self.assertIn(manifest["apkPath"], self.storage.objects)
        self.storage.events.clear()
        self.storage.after_remove = None
        self.storage.remove_error = None
        self.assertEqual(self.publish(), manifest)
        self.assertEqual(set(self.storage.objects), {"latest.json", manifest["apkPath"]})
        self.assertFalse(any(event[0] == "put" for event in self.storage.events))
        self.assertIn("removed 2 old APK", self.reports[-1])

    def test_same_or_higher_version_orphan_is_preserved_and_cleanup_fails_closed(self):
        for code in (11, 12):
            with self.subTest(code=code):
                self.storage = MemoryStorage()
                old = self.seed_old_release()
                uncertain = self.seed_old_release(code, "9.9.9")
                with self.assertRaises(publisher.CleanupError):
                    self.publish()
                self.assertIn(old, self.storage.objects)
                self.assertIn(uncertain, self.storage.objects)
                self.assertIn("releases/11/ai-family-ledger-v0.11.0.apk", self.storage.objects)
                self.assertFalse(any(event[0] == "remove" for event in self.storage.events))

    def test_pointer_change_during_listing_stops_deletion(self):
        old = self.seed_old_release()
        def advance(prefix, limit, offset):
            self.storage.before_list = None
            current = json.loads(self.storage.objects["latest.json"])
            current.update(versionCode=12, versionName="0.12.0", apkPath="releases/12/ai-family-ledger-v0.12.0.apk")
            self.storage.objects["latest.json"] = json.dumps(current).encode()
        self.storage.before_list = advance
        with self.assertRaises(publisher.CleanupError):
            self.publish()
        self.assertIn(old, self.storage.objects)
        self.assertEqual(json.loads(self.storage.objects["latest.json"])["versionCode"], 12)
        self.assertFalse(any(event[0] == "remove" for event in self.storage.events))

    def test_pointer_change_between_batches_prevents_further_deletes(self):
        old = {self.seed_old_release(code, f"0.{code}.0") for code in (1, 2, 3)}
        def advance(paths):
            current = json.loads(self.storage.objects["latest.json"])
            current.update(versionCode=12, versionName="0.12.0", apkPath="releases/12/ai-family-ledger-v0.12.0.apk")
            self.storage.objects[current["apkPath"]] = b"synthetic next package"
            self.storage.objects["latest.json"] = json.dumps(current).encode()
        self.storage.after_remove = advance
        with patch.object(publisher, "DELETE_BATCH_SIZE", 1), self.assertRaises(publisher.CleanupError):
            self.publish()
        batches = [event[1] for event in self.storage.events if event[0] == "remove"]
        self.assertEqual(len(batches), 1)
        self.assertEqual(len(old.intersection(self.storage.objects)), 2)
        self.assertIn("releases/12/ai-family-ledger-v0.12.0.apk", self.storage.objects)
        self.assertIn("releases/11/ai-family-ledger-v0.11.0.apk", self.storage.objects)

    def test_downgrade_rejection_never_cleans_newer_or_older_packages(self):
        self.publish()
        old = self.seed_old_release()
        self.storage.events.clear()
        self.gradle.write_text(self.gradle.read_text().replace("versionCode = 11", "versionCode = 9"))
        with self.assertRaises(publisher.PublishError):
            self.publish()
        self.assertIn(old, self.storage.objects)
        self.assertFalse(any(event[0] in ("list", "remove") for event in self.storage.events))

    def test_missing_active_apk_in_listing_prevents_any_delete(self):
        old = self.seed_old_release()
        def lose_active(prefix, limit, offset):
            self.storage.objects.pop("releases/11/ai-family-ledger-v0.11.0.apk", None)
        self.storage.before_list = lose_active
        with self.assertRaises(publisher.CleanupError):
            self.publish()
        self.assertIn(old, self.storage.objects)
        self.assertFalse(any(event[0] == "remove" for event in self.storage.events))

    def test_repeated_listing_page_fails_without_deleting(self):
        self.seed_old_release()
        original = self.storage.list_page
        self.storage.list_page = lambda prefix, limit, offset: original(prefix, limit, 0)
        with patch.object(publisher, "LIST_PAGE_SIZE", 1), self.assertRaises(publisher.CleanupError):
            self.publish()
        self.assertFalse(any(event[0] == "remove" for event in self.storage.events))

    def test_malformed_or_unsafe_listing_entries_fail_without_deletion(self):
        for page in ({"error": "private"}, ["bad"], [{"name": "10"}],
                     [{"name": "../backup", "id": None}], [{"name": "10", "id": True}]):
            with self.subTest(page=page):
                self.storage = MemoryStorage()
                old = self.seed_old_release()
                self.storage.list_page = lambda prefix, limit, offset: page
                with self.assertRaises(publisher.CleanupError):
                    self.publish()
                self.assertIn(old, self.storage.objects)
                self.assertFalse(any(event[0] == "remove" for event in self.storage.events))

    def test_newer_upload_during_cleanup_is_kept_and_prevents_false_success(self):
        self.seed_old_release()
        newer = "releases/12/ai-family-ledger-v0.12.0.apk"
        self.storage.after_remove = lambda paths: self.storage.objects.update({newer: b"synthetic pending package"})
        with self.assertRaises(publisher.CleanupError):
            self.publish()
        self.assertIn(newer, self.storage.objects)
        self.assertIn("releases/11/ai-family-ledger-v0.11.0.apk", self.storage.objects)
        self.assertEqual(self.reports, [])

    def test_cli_cleanup_error_reports_published_state_and_actionable_retry_without_secret(self):
        self.seed_old_release()
        self.storage.remove_error = RuntimeError("test-secret private response")
        output = io.StringIO()
        argv = [str(SCRIPT), "--apk-dir", str(self.root), "--gradle", str(self.gradle), "--notes", str(self.notes)]
        with patch.object(publisher, "Storage", return_value=self.storage), patch.object(publisher.sys, "argv", argv), \
                redirect_stdout(output), redirect_stderr(output):
            status = publisher.main()
        self.assertEqual(status, 2)
        self.assertIn("published", output.getvalue())
        self.assertIn("cleanup incomplete", output.getvalue())
        self.assertIn("rerun", output.getvalue())
        self.assertNotIn("Update publication failed", output.getvalue())
        self.assertNotIn("test-secret", output.getvalue())
        self.assertNotIn("private response", output.getvalue())

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

    def test_list_uses_authenticated_bucket_endpoint_and_explicit_pagination(self):
        entries = [{"name": "11", "id": None, "metadata": None}]
        response = io.BytesIO(json.dumps(entries).encode())
        response.status = 200
        self.opener.open.return_value = response
        self.assertEqual(self.storage.list_page("releases", 100, 200), entries)
        request = self.opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://fixture.supabase.co/storage/v1/object/list/app-updates")
        self.assertEqual(request.method, "POST")
        self.assertEqual(json.loads(request.data), {"prefix": "releases", "limit": 100, "offset": 200,
                                                   "sortBy": {"column": "name", "order": "asc"}})
        self.assertEqual(request.get_header("Authorization"), "Bearer test-secret")
        self.assertEqual(self.opener.open.call_args.kwargs["timeout"], 60)

    def test_delete_uses_exact_paths_in_fixed_update_bucket(self):
        response = io.BytesIO(b"[]")
        response.status = 200
        self.opener.open.return_value = response
        paths = ["releases/10/ai-family-ledger-v0.10.0.apk"]
        self.storage.remove(paths)
        request = self.opener.open.call_args.args[0]
        self.assertEqual(request.full_url, "https://fixture.supabase.co/storage/v1/object/app-updates")
        self.assertEqual(request.method, "DELETE")
        self.assertEqual(json.loads(request.data), {"prefixes": paths})
        self.assertEqual(request.get_header("Authorization"), "Bearer test-secret")
        self.assertEqual(request.get_header("Content-type"), "application/json")

    def test_cleanup_transport_rejects_out_of_scope_paths_and_oversize_batches(self):
        for paths in ([], ["latest.json"], ["releases/10"], ["releases/10/unrelated.apk"],
                      ["releases/10/../ai-family-ledger-v0.10.0.apk"],
                      ["releases/010/ai-family-ledger-v0.10.0.apk"],
                      ["releases/10/ai-family-ledger-v00.10.0.apk"],
                      ["releases/2100000001/ai-family-ledger-v0.10.0.apk"],
                      ["releases/10/ai-family-ledger-v0.10.0.apk"] * 101):
            with self.subTest(paths=paths[:1]), self.assertRaises(publisher.PublishError):
                self.storage.remove(paths)
        for prefix in ("", "backups", "releases/../backup", "releases/10/nested"):
            with self.subTest(prefix=prefix), self.assertRaises(publisher.PublishError):
                self.storage.list_page(prefix, 100, 0)
        self.opener.open.assert_not_called()

    def test_cleanup_transport_never_exposes_server_errors_or_credentials(self):
        for action in (lambda: self.storage.list_page("releases", 100, 0),
                       lambda: self.storage.remove(["releases/10/ai-family-ledger-v0.10.0.apk"])):
            self.error(403, b'{"message":"test-secret private response"}')
            with self.assertRaises(publisher.PublishError) as failure:
                action()
            self.assertIn("403", str(failure.exception))
            self.assertNotIn("test-secret", str(failure.exception))
            self.assertNotIn("private response", str(failure.exception))
            self.opener.open.side_effect = URLError("test-secret private response")
            with self.assertRaises(publisher.PublishError) as failure:
                action()
            self.assertNotIn("test-secret", str(failure.exception))
            self.assertNotIn("private response", str(failure.exception))

    def test_cleanup_transport_rejects_malformed_or_oversize_json(self):
        for payload in (b"not json", b"{}", b"[" + b" " * 1048576):
            with self.subTest(payload=payload[:8]):
                response = io.BytesIO(payload)
                response.status = 200
                self.opener.open.return_value = response
                with self.assertRaises(publisher.PublishError):
                    self.storage.list_page("releases", 100, 0)

    def test_redirect_is_rejected(self):
        handler = publisher.NoRedirect()
        with self.assertRaises(publisher.PublishError):
            handler.redirect_request(None, None, 302, "redirect", {}, "https://other.example")


if __name__ == "__main__":
    unittest.main()
