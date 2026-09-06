"""Name delivery APKs from the Gradle version. Does not change signing or write hash sidecars."""
from pathlib import Path
import re
import shutil
import sys
import zipfile
import os

ROOT = Path(__file__).resolve().parents[1]

def version():
    text = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'versionName\s*=\s*"(\d+\.\d+\.\d+)"', text)
    if not match:
        raise ValueError("Missing semantic versionName")
    return match.group(1)

if __name__ == "__main__":
    source, destination = Path(sys.argv[1]), Path(sys.argv[2])
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() or not {"AndroidManifest.xml", "classes.dex", "resources.arsc"}.issubset(archive.namelist()):
            raise ValueError("Invalid APK archive")
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / f"AI家庭账本-v{version()}.apk"
    shutil.copyfile(source, target)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"version={version()}\n")
    print(target.resolve())
