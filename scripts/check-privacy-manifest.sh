#!/usr/bin/env bash
# Audits the iOS privacy manifests against what the built binaries actually call (macOS, CI "ios" job).
#   scripts/check-privacy-manifest.sh path/to/PawPixel.app
# For the app and each extension: lists Apple's "required reason" APIs its code links to (the app's
# Swift, the Kotlin/Native runtime and Compose, all linked in statically) and fails if one of those
# API categories is missing from the PrivacyInfo.xcprivacy bundled next to it. Categories declared
# but not used are only reported. Apple's list:
# https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
set -euo pipefail
APP=${1:?usage: check-privacy-manifest.sh path/to/App.app}

# category|kind|name  (kind: sym = linked C function/constant/ObjC class, sel = Objective-C selector)
RULES='
FileTimestamp|sym|_stat _stat$INODE64 _fstat _fstat$INODE64 _fstatat _fstatat$INODE64 _lstat _lstat$INODE64 _getattrlist _getattrlistbulk _fgetattrlist _getattrlistat _NSFileCreationDate _NSFileModificationDate _NSURLContentModificationDateKey _NSURLCreationDateKey
FileTimestamp|sel|creationDate modificationDate fileModificationDate
SystemBootTime|sym|_mach_absolute_time
SystemBootTime|sel|systemUptime
DiskSpace|sym|_statfs _statfs$INODE64 _statvfs _fstatfs _fstatfs$INODE64 _fstatvfs _NSFileSystemFreeSize _NSFileSystemSize _NSURLVolumeAvailableCapacityKey _NSURLVolumeAvailableCapacityForImportantUsageKey _NSURLVolumeAvailableCapacityForOpportunisticUsageKey _NSURLVolumeTotalCapacityKey
ActiveKeyboards|sel|activeInputModes
UserDefaults|sym|_OBJC_CLASS_$_NSUserDefaults
'

status=0
audit() { # bundle-dir executable-name
  local dir=$1 exe=$2 manifest="$1/PrivacyInfo.xcprivacy"
  local bins=("$dir/$exe")
  # Xcode debug builds put the code in <exe>.debug.dylib next to a small stub executable.
  [ -f "$dir/$exe.debug.dylib" ] && bins+=("$dir/$exe.debug.dylib")
  echo "== $(basename "$dir") (${bins[*]##*/})"
  if [ ! -f "$manifest" ]; then echo "::error::$(basename "$dir") has no PrivacyInfo.xcprivacy in its bundle"; status=1; return; fi
  local syms sels declared used=""
  syms=$(for b in "${bins[@]}"; do nm -u "$b" 2>/dev/null || true; done | sed 's/^ *//' | sort -u)
  sels=$(for b in "${bins[@]}"; do otool -v -s __TEXT __objc_methname "$b" 2>/dev/null || true; done | awk '{print $NF}' | sort -u)
  declared=$(plutil -convert json -o - "$manifest" | python3 -c 'import json,sys; print("\n".join(t["NSPrivacyAccessedAPIType"].replace("NSPrivacyAccessedAPICategory","") for t in json.load(sys.stdin).get("NSPrivacyAccessedAPITypes",[])))')
  while IFS='|' read -r cat kind names; do
    [ -z "$cat" ] && continue
    for n in $names; do
      if { [ "$kind" = sym ] && grep -qxF "$n" <<<"$syms"; } || { [ "$kind" = sel ] && grep -qxF "$n" <<<"$sels"; }; then
        echo "   uses $cat ($n)"
        used="$used $cat"
      fi
    done
  done <<<"$RULES"
  for cat in $(tr ' ' '\n' <<<"$used" | sort -u); do
    if ! grep -qxF "$cat" <<<"$declared"; then
      echo "::error::$(basename "$dir") uses the $cat API category but its PrivacyInfo.xcprivacy doesn't declare it"
      status=1
    fi
  done
  for cat in $declared; do
    grep -qw "$cat" <<<"$used" || echo "   note: declares $cat, not found in the binary (harmless; remove it if nothing needs it)"
  done
}

audit "$APP" "$(defaults read "$(cd "$APP" && pwd)/Info" CFBundleExecutable)"
for ext in "$APP"/PlugIns/*.appex; do
  [ -d "$ext" ] && audit "$ext" "$(defaults read "$(cd "$ext" && pwd)/Info" CFBundleExecutable)"
done
[ $status = 0 ] && echo "Privacy manifests declare every required-reason API the binaries use."
exit $status
