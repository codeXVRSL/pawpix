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
# In case the test stopped halfway through its large-text or dark-mode steps.
adb shell settings put system font_scale 1.0 || true
adb shell wm size reset || true; adb shell wm density reset || true
adb shell cmd uimode night no || true
# Cold start with the journey's pet saved (what an owner sees every morning): 5 launches, the app
# killed before each. "TotalTime" is from the tap to the first frame drawn.
for i in 1 2 3 4 5; do
  adb shell am force-stop $APP; sleep 2
  adb shell am start -W -n $APP/.MainActivity | tr -d '\r' | grep -E "^TotalTime" >> "$OUT/startup-raw.txt"
  sleep 3
done
awk '{print $2}' "$OUT/startup-raw.txt" | sort -n | awk '{a[NR]=$1} END {printf "cold start ms (sorted): "; for (i=1;i<=NR;i++) printf "%s ", a[i]; printf "| median %s\n", a[int((NR+1)/2)]}' > "$OUT/startup.txt"
cat "$OUT/startup.txt"
adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1
# The useful part: crashes, our app, the test runner, ML Kit, Glance, and anything at error level.
grep -E "AndroidRuntime|FATAL|pawpixel|PawPixel|TestRunner|MlKit|mlkit|Glance|AppWidget| E/" "$OUT/logcat-full.txt" | tail -n 3000 > "$OUT/logcat.txt" || true
tail -n 4000 "$OUT/logcat-full.txt" > "$OUT/logcat-tail.txt"; rm -f "$OUT/logcat-full.txt"
adb exec-out run-as $APP tar c -C files e2e 2> "$OUT/pull-errors.txt" | tar x -C "$OUT" || true
cat "$OUT/e2e/steps.txt" 2>/dev/null
grep -E "^OK \(|FAILURES|INSTRUMENTATION_|Error" "$OUT/instrument.txt"
grep -q "^OK (" "$OUT/instrument.txt"
