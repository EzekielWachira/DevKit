#!/usr/bin/env bash
#
# Renders every documented chart on a connected device and pulls the pictures
# into `docs-assets/`.
#
# A chart is a Compose composable, so there is no way to draw one without a
# composition, a density and a frame clock — which means a device. The rendering
# is therefore done by an instrumentation test, `DocsAssetCaptureTest`, and this
# script is what runs it and collects the output.
#
# ### Why `am instrument` rather than `connectedAndroidTest`
#
# Gradle uninstalls both APKs when a connected test finishes, and uninstalling
# an app deletes its external files directory — which is where the pictures
# were just written. The test passes and the output vanishes. Driving the
# instrumentation directly leaves the app installed long enough to pull them.
#
# Usage:  ./scripts/capture-docs-assets.sh

set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID=io.devkit
TEST_ID=io.devkit.test
CLASS=io.devkit.DocsAssetCaptureTest
ON_DEVICE="/sdcard/Android/data/$APP_ID/files/docs-assets"
OUT="docs-assets"

if [ -z "$(adb devices | sed -n '2p')" ]; then
  echo "error: no device or emulator attached" >&2
  exit 1
fi

echo "==> Installing the app and its instrumentation"
./gradlew :app:installDebug :app:installDebugAndroidTest

echo
echo "==> Rendering every documented chart"
# `am instrument` prints failures rather than exiting non-zero, so the output is
# inspected. A capture run that silently produced nothing would otherwise look
# exactly like a successful one.
LOG=$(mktemp)
trap 'rm -f "$LOG"' EXIT
adb shell am instrument -w -e class "$CLASS" \
  "$TEST_ID/androidx.test.runner.AndroidJUnitRunner" | tee "$LOG"

if grep -qE "^(FAILURES|INSTRUMENTATION_CODE: 0)" "$LOG" || grep -q "Error" "$LOG"; then
  echo "error: the capture run reported a failure" >&2
  exit 1
fi

echo
echo "==> Collecting"
rm -rf "$OUT"
mkdir -p "$OUT"
adb pull "$ON_DEVICE/." "$OUT" > /dev/null

COUNT=$(find "$OUT" -type f \( -name '*.svg' -o -name '*.png' \) | wc -l | tr -d ' ')
if [ "$COUNT" -eq 0 ]; then
  echo "error: nothing was pulled from $ON_DEVICE" >&2
  exit 1
fi

echo
echo "==> $COUNT files in $OUT/"
find "$OUT" -type f \( -name '*.svg' -o -name '*.png' \) | sort | while read -r f; do
  printf '    %-52s %s\n' "${f#$OUT/}" "$(du -h "$f" | cut -f1)"
done
echo
echo "Vector: $(find "$OUT" -name '*.svg' | wc -l | tr -d ' ')   Raster: $(find "$OUT" -name '*.png' | wc -l | tr -d ' ')"
