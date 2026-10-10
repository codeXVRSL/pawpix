#!/bin/bash
# Claude Code on the web: get the Android SDK and the Gradle caches ready, so `./gradlew :core:jvmTest`
# and `./gradlew :composeApp:assembleDebug` work in the session. Idempotent; the container is cached
# after this runs, so later sessions skip straight to the end.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_ZIP="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
MARKER="$SDK/.pawpixel-warm"

point_gradle_at_sdk() {
  echo "sdk.dir=$SDK" > local.properties # git-ignored
  if [ -n "${CLAUDE_ENV_FILE:-}" ]; then echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"; fi
}

# A warm container (cached after an earlier run) needs no network at all.
if [ -d "$SDK/platforms/android-36" ] && [ -f "$MARKER" ]; then
  point_gradle_at_sdk
  exit 0
fi

# The Android Gradle plugin, the SDK and AndroidX all come from Google's Maven (dl.google.com).
# Without it the build can't even configure; say so plainly and let the session start anyway.
if ! curl -sSf -o /dev/null --max-time 20 https://dl.google.com/dl/android/maven2/master-index.xml; then
  echo "session-start: dl.google.com is not reachable from this environment, so Gradle can't build PawPixel here." >&2
  echo "session-start: allow dl.google.com in the environment's network settings (Allowed domains), then start a new session." >&2
  exit 0
fi

# Android SDK: command-line tools, then the platform and build tools the app compiles against.
if [ ! -d "$SDK/platforms/android-36" ]; then
  mkdir -p "$SDK/cmdline-tools"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    tmp="$(mktemp -d)"
    curl -sSfL --retry 3 -o "$tmp/tools.zip" "$CMDLINE_TOOLS_ZIP" && unzip -q "$tmp/tools.zip" -d "$tmp" \
      || { echo "session-start: couldn't download the Android command-line tools; skipping the SDK." >&2; exit 0; }
    rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
    rm -rf "$tmp"
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses > /dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-36" "build-tools;36.0.0" "platform-tools" > /dev/null \
    || { echo "session-start: the Android SDK install failed; skipping the Gradle warm-up." >&2; exit 0; }
fi

# Point Gradle and the session at the SDK.
point_gradle_at_sdk

# Download the Gradle distribution, plugins and dependencies, and compile core and its tests once,
# so the first test run in the session is quick. Once per container (the marker survives in the
# cached container); a failure here is a warning, never a reason to hold the session up.
if [ ! -f "$MARKER" ]; then
  if ANDROID_HOME="$SDK" ./gradlew :core:compileTestKotlinJvm --quiet; then
    touch "$MARKER"
  else
    echo "session-start: the Gradle warm-up failed (see above); builds in this session may download or fail later." >&2
  fi
fi
