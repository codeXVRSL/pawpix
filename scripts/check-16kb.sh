#!/usr/bin/env bash
# Google Play's 16 KB page-size requirement (apps targeting Android 15+), checked on the release build:
#   scripts/check-16kb.sh app-release.aab app-release.apk bundletool.jar
# 1. Every 64-bit native library in the bundle has its ELF LOAD segments aligned to 16 KB or more.
# 2. The APKs Play would make from the bundle, and the universal APK, keep native libraries
#    uncompressed at 16 KB boundaries (zipalign -c -P 16).
# Exits non-zero on any misalignment, so the CI job fails.
set -euo pipefail
AAB=${1:?aab}; APK=${2:?apk}; BUNDLETOOL=${3:?bundletool.jar}
ZIPALIGN=$(ls "$ANDROID_HOME"/build-tools/*/zipalign | sort -V | tail -1)
WORK=$(mktemp -d)
bad=0

echo "== ELF alignment of the bundle's native libraries"
unzip -q -o "$AAB" '*/lib/*' -d "$WORK/aab" 2>/dev/null || true
libs=$(find "$WORK/aab" -name '*.so' | sort)
if [ -z "$libs" ]; then echo "No native libraries in the bundle."; fi
for so in $libs; do
  abi=$(basename "$(dirname "$so")")
  min=$(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }' | while read -r a; do echo $((a)); done | sort -n | head -1)
  case "$abi" in
    arm64-v8a|x86_64)
      if [ -z "$min" ] || [ "$min" -lt 16384 ]; then echo "::error::$abi/$(basename "$so"): LOAD segments aligned to ${min:-?} bytes, Play needs 16384"; bad=1
      else echo "ok  $abi/$(basename "$so") (LOAD align $min)"; fi ;;
    *) echo "--  $abi/$(basename "$so") (32-bit ABI, not required)" ;;
  esac
done

echo "== Zip alignment of the APKs Play would deliver, and the universal APK"
java -jar "$BUNDLETOOL" build-apks --bundle="$AAB" --output="$WORK/all.apks" > /dev/null
mkdir -p "$WORK/apks" && unzip -q "$WORK/all.apks" -d "$WORK/apks"
for f in $(find "$WORK/apks" -name '*.apk' | sort) "$APK"; do
  if "$ZIPALIGN" -c -P 16 4 "$f" > "$WORK/zipalign.txt" 2>&1; then echo "ok  $(basename "$f")"
  else echo "::error::$(basename "$f") is not 16 KB aligned:"; grep -v "(OK" "$WORK/zipalign.txt" | tail -20; bad=1; fi
done

[ $bad = 0 ] && echo "16 KB page size: all good."
exit $bad
