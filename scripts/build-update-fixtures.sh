#!/usr/bin/env bash
# Synthetic archive fixtures only: never install or publish them.
set -euo pipefail
cd "$(dirname "$0")/.."
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$sdk_root"
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' >/dev/null
build_tools="$sdk_root/build-tools/35.0.0"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
asset_dir="android/app/src/androidTest/assets"
mkdir -p "$asset_dir"
python3 - "$work_dir/AndroidManifest.xml" <<'PY'
from pathlib import Path
import re, sys
gradle = Path('android/app/build.gradle.kts').read_text(encoding='utf-8')
code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle)[1]) + 1
major, minor, patch = map(int, re.search(r'versionName\s*=\s*"([0-9.]+)"', gradle)[1].split('.'))
Path(sys.argv[1]).write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.familyledger.app" android:versionCode="{code}" android:versionName="{major}.{minor + 1}.0">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
    <application android:hasCode="false" android:label="Update validation fixture" />
</manifest>''', encoding='utf-8')
PY
"$build_tools/aapt2" link -I "$sdk_root/platforms/android-35/android.jar" --manifest "$work_dir/AndroidManifest.xml" -o "$work_dir/unsigned.apk"
"$build_tools/zipalign" -p -f 4 "$work_dir/unsigned.apk" "$work_dir/aligned.apk"
keytool -genkeypair -keystore "$work_dir/wrong-signer.jks" -storepass fixture-only -keypass fixture-only \
    -alias fixture -dname 'CN=Synthetic Update Test' -keyalg RSA -keysize 2048 -validity 2 -noprompt >/dev/null 2>&1
"$build_tools/apksigner" sign --ks android/keystore/dev-debug.jks --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$asset_dir/update-valid.apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" sign --ks "$work_dir/wrong-signer.jks" --ks-key-alias fixture \
    --ks-pass pass:fixture-only --key-pass pass:fixture-only --out "$asset_dir/update-wrong-signer.apk" "$work_dir/aligned.apk"
"$build_tools/apksigner" verify "$asset_dir/update-valid.apk"
"$build_tools/apksigner" verify "$asset_dir/update-wrong-signer.apk"
printf '%s\n' 'Generated two synthetic signed update fixtures (not release artifacts).'
