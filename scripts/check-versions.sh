#!/usr/bin/env bash
# The store version lives in gradle.properties (pawpixel.versionName). iosApp/project.yml repeats it
# for local Xcode builds; this fails if the two drift apart. Prints the version.
set -euo pipefail
cd "$(dirname "$0")/.."
android=$(sed -n 's/^pawpixel\.versionName=//p' gradle.properties)
ios=$(sed -n 's/^ *MARKETING_VERSION: *"\{0,1\}\([^"]*\)"\{0,1\}.*/\1/p' iosApp/project.yml | head -1)
if ! [[ "$android" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then echo "::error::pawpixel.versionName '$android' must look like 1.2.3"; exit 1; fi
if [ "$android" != "$ios" ]; then
  echo "::error::gradle.properties says $android but iosApp/project.yml MARKETING_VERSION says $ios"
  exit 1
fi
echo "$android"
