#!/usr/bin/env bash
#
# Records short clips of real gestures on real charts, for the documentation.
#
# A picture can show what a chart looks like. It cannot show the crosshair
# following a finger, a 3D scene turning under a drag, or a map zooming about
# the point being pinched — and those are the parts of ChartKit a still cannot
# argue for.
#
# ChartKit is an Android library on androidx.compose, not Compose Multiplatform,
# so there is no honest way to put a live chart in a web page: a JavaScript
# reimplementation would be a different chart wearing this one's name. A
# recording of the real thing is the truthful alternative.
#
# `DocsClipCaptureTest` performs the gestures, one per test method, slowly
# enough to be filmed. This starts `screenrecord` around each invocation.
#
# Usage:  ./scripts/record-docs-clips.sh [clipName ...]

set -euo pipefail

cd "$(dirname "$0")/.."

TEST_ID=io.devkit.test
CLASS=io.devkit.DocsClipCaptureTest
OUT="docs-assets/clips"
DEVICE_MP4=/sdcard/docs-clip.mp4

CLIPS=("$@")
if [ ${#CLIPS[@]} -eq 0 ]; then
  CLIPS=(clipCrosshair clipCamera3D clipMap clipDrillDown)
fi

if [ -z "$(adb devices | sed -n '2p')" ]; then
  echo "error: no device or emulator attached" >&2
  exit 1
fi

echo "==> Installing the app and its instrumentation"
./gradlew :app:installDebug :app:installDebugAndroidTest

mkdir -p "$OUT"

for clip in "${CLIPS[@]}"; do
  # Named explicitly rather than derived. A rule that turns `clipCamera3D` into
  # something readable has to know that "3D" is one word, and a rule that knows
  # that is longer than the list.
  # Named after the documentation page the clip belongs on, which is the same
  # contract the still captures use: `scripts/build_docs.py` shows
  # `docs-assets/clips/<page slug>.mp4` on the page of that name, and nothing
  # lists the pairings anywhere else.
  case "$clip" in
    clipCrosshair) name=crosshair ;;
    clipCamera3D)  name=3d-scatter ;;
    clipMap)       name=geographic-maps ;;
    clipDrillDown) name=hierarchy-state-and-breadcrumbs ;;
    *)
      echo "error: no documentation page mapped for '$clip'" >&2
      exit 1
      ;;
  esac
  echo
  echo "==> $name"

  adb shell rm -f "$DEVICE_MP4" || true

  # Clear the screen first, and do not start recording until the test's own
  # activity is in front.
  #
  # `screenrecord` films the display, not the app: anything the emulator happens
  # to be showing goes into the file. Starting it before the test put a whole
  # other screen of the sample app into a clip — a FillKit checkout form, in the
  # documentation, under a heading about charts. Recording only between "the
  # test is on screen" and "the test has finished" is what keeps a clip to its
  # subject.
  adb shell am force-stop io.devkit || true
  adb shell input keyevent KEYCODE_HOME
  sleep 1

  LOG=$(mktemp)
  ( adb shell am instrument -w -e class "$CLASS#$clip" \
      "$TEST_ID/androidx.test.runner.AndroidJUnitRunner" > "$LOG" 2>&1 ) &
  INSTRUMENTATION=$!

  ready=0
  for _ in $(seq 1 100); do
    if adb shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*io\.devkit"; then
      ready=1
      break
    fi
    sleep 0.2
  done
  if [ "$ready" -eq 0 ]; then
    echo "error: the test activity never came to the front" >&2
    wait $INSTRUMENTATION || true
    cat "$LOG" >&2
    exit 1
  fi

  # 720p-ish and 4 Mbps: small enough to serve on a documentation page, sharp
  # enough to read an axis label. The default 20 Mbps at full resolution
  # produces files far too large to put in a repository.
  adb shell screenrecord --size 720x1560 --bit-rate 4000000 --time-limit 40 "$DEVICE_MP4" &
  RECORDER=$!

  wait $INSTRUMENTATION || true
  tail -4 "$LOG"
  if ! grep -q "^OK" "$LOG"; then
    echo "error: $clip did not pass; not keeping its recording" >&2
    adb shell pkill -INT -f screenrecord || true
    rm -f "$LOG"
    exit 1
  fi
  rm -f "$LOG"

  # screenrecord finalises the container on SIGINT; killing it any harder
  # leaves an unplayable file.
  adb shell pkill -INT -f screenrecord || true
  wait $RECORDER 2>/dev/null || true
  sleep 2

  adb pull "$DEVICE_MP4" "$OUT/$name.mp4" > /dev/null
  printf '    %-24s %s\n' "$name.mp4" "$(du -h "$OUT/$name.mp4" | cut -f1)"
done

echo
echo "==> $(find "$OUT" -name '*.mp4' | wc -l | tr -d ' ') clips in $OUT/"
