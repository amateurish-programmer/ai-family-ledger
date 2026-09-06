"""Build a source-only ZIP from explicit project roots; never package real ledgers."""
from pathlib import Path
import json
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = ["README.md", "AGENTS.md", ".gitignore", ".gitattributes"]
TREES = ["android", "docs", "scripts", ".github", "supabase"]
EXCLUDED_PARTS = {"build", ".gradle", "__pycache__", ".idea", ".temp", ".branches"}
ALLOWED_SUFFIXES = {".kt", ".kts", ".xml", ".md", ".yml", ".yaml", ".properties", ".ps1", ".py", ".json", ".sh", ".sql", ".ts", ".toml"}
EXACT_BINARY = {"android/gradle/wrapper/gradle-wrapper.jar", "android/keystore/dev-debug.jks"}
EXACT_SCRIPTS = {"android/gradlew", "android/gradlew.bat"}

def source_files():
    files = [ROOT / name for name in ROOT_FILES]
    for tree in TREES:
        for file in (ROOT / tree).rglob("*"):
            rel = file.relative_to(ROOT)
            if not file.is_file() or any(part in EXCLUDED_PARTS for part in rel.parts):
                continue
            if file.name == "local.properties" or file.name == ".env" or file.name.startswith(".env."):
                continue
            if file.suffix in ALLOWED_SUFFIXES or rel.as_posix() in EXACT_BINARY | EXACT_SCRIPTS:
                files.append(file)
    return sorted(set(files))

if __name__ == "__main__":
    files = source_files()
    names = [file.relative_to(ROOT).as_posix() for file in files]
    required = EXACT_BINARY | EXACT_SCRIPTS | {".github/workflows/android-ci.yml"}
    assert required.issubset(names), f"Missing required project files: {required - set(names)}"
    assert not any(name.endswith((".xlsx", ".xls", ".csv", ".ledger.json")) for name in names)
    out = ROOT / "dist"
    out.mkdir(exist_ok=True)
    archive = out / "ai-family-ledger-v0.6.0-source.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for file, name in zip(files, names):
            z.write(file, name)
    (out / "source-manifest.json").write_text(json.dumps(names, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Packaged {len(files)} source files: {archive}")
