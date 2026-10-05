#!/usr/bin/env bash
# Renders every HTML graphic to PNG at its exact size with the bundled Chromium (Playwright).
set -euo pipefail
cd "$(dirname "$0")"
python3 build.py > /tmp/sizes.txt
OUT=../facebook
mkdir -p "$OUT"
while read -r name w h; do
  node "${SHOT:-/tmp/claude-0/-home-user-pawpix/7c11aafe-f1fb-5d58-8d79-caf4d08b23ce/scratchpad/shot.mjs}" "$PWD/html/$name.html" "$OUT/$name.png" "$w" "$h" 1 | tail -1
done < /tmp/sizes.txt
