#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")"
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
set +e
bash ./gradlew connectedDebugAndroidTest --stacktrace
test_status=$?
mkdir -p app/build/reports/androidTests/diagnostics
adb logcat -d -s AndroidRuntime > app/build/reports/androidTests/diagnostics/runtime.log
adb pull /sdcard/Android/data/com.familyledger.app/files/screenshots app/build/reports/androidTests/diagnostics/ || true
exit "$test_status"
