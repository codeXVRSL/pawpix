#!/usr/bin/env bash
# Runs EndToEndTest on a connected device/emulator and collects screenshots, the step log and logcat.
#   ./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
#   scripts/android-e2e.sh [out-dir]
# Exit code 0 only if the test passed. Results are in out-dir either way.
set -u
OUT=${1:-e2e-out}
mkdir -p "$OUT"
APP=com.pawpixel.app
APK=composeApp/build/outputs/apk/debug/composeApp-debug.apk
TAPK=composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk

adb install -r -g "$APK" > "$OUT/install.txt" 2>&1
adb install -r -g "$TAPK" >> "$OUT/install.txt" 2>&1
adb shell getprop ro.build.version.release > "$OUT/device.txt"
adb shell getprop ro.product.model >> "$OUT/device.txt"
# Let the freshly booted emulator settle (its launcher is often busy for a while), then unlock.
adb shell input keyevent 82 || true
sleep 30
adb shell settings put global window_animation_scale 0 || true
# Put the emulator in Naga City for the pet map test, with location switched on.
adb shell cmd location set-location-enabled true || true
adb emu geo fix 123.1948 13.6218 || true
sleep 2
adb emu geo fix 123.1948 13.6218 || true
adb logcat -c
adb shell am instrument -w -r -e class com.pawpixel.app.EndToEndTest \
  $APP.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrument.txt" 2>&1
adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1
# The useful part: crashes, our app, the test runner, ML Kit, Glance, and anything at error level.
grep -E "AndroidRuntime|FATAL|pawpixel|PawPixel|TestRunner|MlKit|mlkit|Glance|AppWidget| E/" "$OUT/logcat-full.txt" | tail -n 3000 > "$OUT/logcat.txt" || true
tail -n 4000 "$OUT/logcat-full.txt" > "$OUT/logcat-tail.txt"; rm -f "$OUT/logcat-full.txt"
adb exec-out run-as $APP tar c -C files e2e 2> "$OUT/pull-errors.txt" | tar x -C "$OUT" || true
cat "$OUT/e2e/steps.txt" 2>/dev/null
grep -E "^OK \(|FAILURES|INSTRUMENTATION_|Error" "$OUT/instrument.txt"
grep -q "^OK (" "$OUT/instrument.txt"
