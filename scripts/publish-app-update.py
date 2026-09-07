"""Publish a verified, immutable APK then atomically replace its public pointer.

Credentials are read only from the environment. No APK hash sidecar is written.
The GitHub publish job serializes writers; do not run an additional writer.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, HTTPRedirectHandler, build_opener
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAX_APK_BYTES = 50 * 1024 * 1024
MAX_JSON_BYTES = 32 * 1024
APK_TYPE = "application/vnd.android.package-archive"
PACKAGE = "com.familyledger.app"


class PublishError(Exception):
    """A fixed, public-safe error message, never a server response or URL."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise PublishError("Storage redirects are not allowed")


def bounded_read(stream, limit):
    chunks = []
    count = 0
    while True:
        chunk = stream.read(min(65536, limit + 1 - count))
        if not chunk:
            return b"".join(chunks)
        count += len(chunk)
        if count > limit:
            raise PublishError("Storage object exceeds size limit")
        chunks.append(chunk)


class Storage:
    def __init__(self, project_url, key, opener=None):
        if not re.fullmatch(r"https://[a-z0-9-]+\.supabase\.co", project_url) or not key or key.strip() != key:
            raise PublishError("Missing or invalid publishing environment")
        self.base = project_url + "/storage/v1/object/"
        self.key = key
        self.opener = opener or build_opener(NoRedirect())

    def request(self, path, limit, data=None, content_type=None, upsert=False):
        writing = data is not None
        prefix = "" if writing else "public/"
        url = self.base + prefix + "app-updates/" + quote(path, safe="/")
        headers = {"Cache-Control": "max-age=0"}
        if writing:
            headers.update({"Authorization": "Bearer " + self.key, "apikey": self.key,
                            "Content-Type": content_type, "x-upsert": str(upsert).lower()})
        else:
            # Avoid a CDN-cached pointer or previous object when verifying publication.
            url += "?verify=" + uuid.uuid4().hex
        request = Request(url, data=data, headers=headers, method="POST" if writing else "GET")
        try:
            with self.opener.open(request, timeout=60) as response:
                if not 200 <= response.status < 300:
                    raise PublishError("Storage request returned an unexpected status")
                return bounded_read(response, limit)
        except HTTPError as error:
            # Storage can wrap an object 404 in HTTP 400. Never mistake a proxy,
            # missing bucket, permission error, or an arbitrary 404 for a first release.
            try:
                body = json.loads(bounded_read(error, MAX_JSON_BYTES))
                missing = isinstance(body, dict) and (
                    body.get("code") == "NoSuchKey" or
                    (str(body.get("statusCode")) == "404" and body.get("message") == "Object not found")
                )
            except Exception:
                missing = False
            finally:
                error.close()
            if not writing and error.code in (400, 404) and missing:
                return None
            raise PublishError("Storage request failed (HTTP " + str(error.code) + ")") from None
        except PublishError:
            raise
        except Exception:
            raise PublishError("Storage network request failed") from None

    def read(self, path, limit):
        return self.request(path, limit)

    def put(self, path, data, content_type, upsert):
        self.request(path, MAX_JSON_BYTES, data, content_type, upsert)


def validate_manifest(value):
    fields = {"schemaVersion", "versionCode", "versionName", "minSdk", "packageName", "apkPath",
              "sizeBytes", "sha256", "notes", "publishedAt"}
    if not isinstance(value, dict) or set(value) != fields:
        raise PublishError("Invalid update manifest fields")
    for name, low, high in (("schemaVersion", 1, 1), ("versionCode", 1, 2100000000),
                            ("minSdk", 26, 2147483647), ("sizeBytes", 1, MAX_APK_BYTES)):
        if type(value[name]) is not int or not low <= value[name] <= high:
            raise PublishError("Invalid update manifest integer")
    if (not isinstance(value["versionName"], str) or len(value["versionName"]) > 32 or
            not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", value["versionName"])):
        raise PublishError("Invalid update version name")
    expected = f'releases/{value["versionCode"]}/AI家庭账本-v{value["versionName"]}.apk'
    if value["packageName"] != PACKAGE or value["apkPath"] != expected:
        raise PublishError("Invalid update package or object path")
    if not isinstance(value["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", value["sha256"]):
        raise PublishError("Invalid update checksum")
    # Android String.length counts UTF-16 units, including two units per emoji.
    if (not isinstance(value["notes"], str) or sum(2 if ord(char) > 0xFFFF else 1 for char in value["notes"]) > 4000 or
            any((ord(char) < 32 or 127 <= ord(char) <= 159) and char not in "\n\r\t" for char in value["notes"])):
        raise PublishError("Invalid update notes")
    timestamp = value["publishedAt"]
    if not isinstance(timestamp, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", timestamp):
        raise PublishError("Invalid update publication time")
    try:
        datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except ValueError:
        raise PublishError("Invalid update publication time") from None
    return value


def decode_manifest(data):
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise PublishError("Duplicate update manifest field")
            result[key] = value
        return result

    try:
        if isinstance(data, str):
            encoded = data.encode("utf-8")
        elif isinstance(data, (bytes, bytearray)):
            encoded = data
        else:
            raise PublishError("Invalid update manifest input")
        if len(encoded) > MAX_JSON_BYTES:
            raise PublishError("Update manifest exceeds size limit")
        return validate_manifest(json.loads(encoded.decode("utf-8"), object_pairs_hook=unique_object))
    except (ValueError, UnicodeError, TypeError):
        raise PublishError("Invalid update manifest JSON") from None


def local_release(apk_dir, gradle, notes):
    text = gradle.read_text(encoding="utf-8")
    metadata = {}
    for field in ("versionCode", "versionName", "minSdk", "applicationId"):
        pattern = rf'^\s*{field}\s*=\s*' + (r'"([^"\n]+)"' if field in ("versionName", "applicationId") else r'(\d+)\b')
        matches = re.findall(pattern, text, re.MULTILINE)
        if len(matches) != 1:
            raise PublishError("Missing or ambiguous Gradle release metadata")
        metadata[field] = matches[0] if field in ("versionName", "applicationId") else int(matches[0])
    apks = list(apk_dir.rglob("*.apk"))
    expected_name = f'AI家庭账本-v{metadata["versionName"]}.apk'
    if len(apks) != 1 or apks[0].name != expected_name:
        raise PublishError("Expected exactly one APK matching the Gradle version")
    with apks[0].open("rb") as source:
        apk_bytes = bounded_read(source, MAX_APK_BYTES)
    try:
        with zipfile.ZipFile(io.BytesIO(apk_bytes)) as archive:
            if not {"AndroidManifest.xml", "classes.dex", "resources.arsc"}.issubset(archive.namelist()) or archive.testzip():
                raise PublishError("Invalid APK archive")
    except zipfile.BadZipFile:
        raise PublishError("Invalid APK archive") from None
    with notes.open("rb") as source:
        note_text = bounded_read(source, 16004).decode("utf-8").strip()
    manifest = validate_manifest({
        "schemaVersion": 1, "versionCode": metadata["versionCode"], "versionName": metadata["versionName"],
        "minSdk": metadata["minSdk"], "packageName": metadata["applicationId"],
        "apkPath": f'releases/{metadata["versionCode"]}/{expected_name}', "sizeBytes": len(apk_bytes),
        "sha256": hashlib.sha256(apk_bytes).hexdigest(), "notes": note_text,
        "publishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
    })
    return manifest, apk_bytes


def verify_apk(data, manifest):
    if data is None or len(data) != manifest["sizeBytes"] or hashlib.sha256(data).hexdigest() != manifest["sha256"]:
        raise PublishError("Remote APK does not match this release; refusing publication")


def publish(storage, apk_dir, gradle, notes):
    manifest, apk_bytes = local_release(apk_dir, gradle, notes)
    previous_bytes = storage.read("latest.json", MAX_JSON_BYTES)
    previous = decode_manifest(previous_bytes) if previous_bytes is not None else None
    if previous:
        if previous["versionCode"] > manifest["versionCode"]:
            raise PublishError("Refusing to downgrade latest version")
        if previous["versionCode"] == manifest["versionCode"]:
            manifest["publishedAt"] = previous["publishedAt"]
            if manifest != previous:
                raise PublishError("Existing version metadata or APK differs; increment the version")
            verify_apk(storage.read(manifest["apkPath"], MAX_APK_BYTES), manifest)
            return previous
    existing_apk = storage.read(manifest["apkPath"], MAX_APK_BYTES)
    if existing_apk is not None:
        verify_apk(existing_apk, manifest)
    else:
        try:
            storage.put(manifest["apkPath"], apk_bytes, APK_TYPE, False)
        except PublishError:
            # A response may be lost after a complete upload; immutable bytes decide
            # whether a retry is safe. An absent/different object still fails closed.
            verify_apk(storage.read(manifest["apkPath"], MAX_APK_BYTES), manifest)
    verify_apk(storage.read(manifest["apkPath"], MAX_APK_BYTES), manifest)
    # Recheck the pointer immediately before advancing it. CI serializes all writers.
    if storage.read("latest.json", MAX_JSON_BYTES) != previous_bytes:
        raise PublishError("Latest changed during publication; retry the serialized job")
    payload = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    storage.put("latest.json", payload, "application/json", True)
    readback = storage.read("latest.json", MAX_JSON_BYTES)
    if readback is None or decode_manifest(readback) != manifest:
        raise PublishError("Latest manifest readback verification failed")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, default=ROOT / "dist/apk")
    parser.add_argument("--gradle", type=Path, default=ROOT / "android/app/build.gradle.kts")
    parser.add_argument("--notes", type=Path, default=ROOT / "docs/update-notes.txt")
    args = parser.parse_args()
    try:
        storage = Storage(os.environ.get("SUPABASE_URL", ""), os.environ.get("SUPABASE_UPDATE_SERVICE_KEY", ""))
        result = publish(storage, args.apk_dir, args.gradle, args.notes)
    except PublishError as error:
        print("Update publication failed: " + str(error), file=sys.stderr)
        return 1
    except Exception:
        # Do not emit exception chains that can include credentials or server bodies.
        print("Update publication failed: local release input could not be processed", file=sys.stderr)
        return 1
    print(f'Published update v{result["versionName"]} (code {result["versionCode"]}, {result["sizeBytes"]} bytes)')
    return 0


if __name__ == "__main__":
    sys.exit(main())
