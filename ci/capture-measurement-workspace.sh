#!/usr/bin/env bash
set -euo pipefail

app=com.andreyceilings.app
device_dir=cache/p5a-workspace
artifact_dir="${RUNNER_TEMP:-app/build}/p5a-workspace"
mkdir -p "$artifact_dir"
previous_ime_setting="$(adb shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
restore_display() {
  adb shell run-as "$app" rm -rf "$device_dir"
  adb shell wm size reset
  adb shell wm density reset
  if [[ "$previous_ime_setting" == null ]]; then
    adb shell settings delete secure show_ime_with_hard_keyboard
  else
    adb shell settings put secure show_ime_with_hard_keyboard "$previous_ime_setting"
  fi
}
trap restore_display EXIT
adb shell wm density 320
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell run-as "$app" rm -rf "$device_dir"
adb shell run-as "$app" mkdir -p "$device_dir"
for display in short tall; do
  case "$display" in
    short) size=720x1280 ;;
    tall) size=720x1600 ;;
  esac
  adb shell wm size "$size"
  adb shell am instrument -w -e class com.andreyceilings.app.MeasurementWorkspaceLayoutTest \
    -e p5aScreenshots true -e p5aDisplay "$display" "$app.test/androidx.test.runner.AndroidJUnitRunner" \
    | tee "$artifact_dir/instrumentation-$display.txt"
done
adb shell wm size 720x1280
adb shell am instrument -w \
  -e class 'com.andreyceilings.app.OverlayDragTouchTest#editorNormalLight,com.andreyceilings.app.OverlayDragTouchTest#editorFullscreenDark' \
  -e p5aSelectedScreenshots true "$app.test/androidx.test.runner.AndroidJUnitRunner" \
  | tee "$artifact_dir/instrumentation-selections.txt"
adb exec-out run-as "$app" tar -C "$device_dir" -cf - . | tar -xf - -C "$artifact_dir"
grep -q 'OK (5 tests)' "$artifact_dir/instrumentation-short.txt"
grep -q 'OK (5 tests)' "$artifact_dir/instrumentation-tall.txt"
grep -q 'OK (2 tests)' "$artifact_dir/instrumentation-selections.txt"
# Screenshots are evidence, not a fixed-count ABI. POINT/LINE/AREA selection
# screenshots were added after the original 104-image count, so the old equality
# check incorrectly failed even when all instrumentation suites passed.
# Verify key output files from each route, theme, orientation and gesture suite,
# while allowing additional diagnostic/acceptance frames as coverage grows.
require_png() {
  local image="$artifact_dir/$1"
  if [[ ! -s "$image" ]]; then
    echo "::error::Missing or empty P5A screenshot: $1" >&2
    exit 1
  fi
}
for display in short tall; do
  for case_name in \
    measurements-light-portrait \
    measurements-dark-landscape \
    objects-dark-portrait \
    objects-light-landscape; do
    require_png "$display-$case_name-normal-contour.png"
  done
  require_png "$display-measurements-light-portrait-l-shape-normal.png"
  require_png "$display-measurements-dark-portrait-l-shape-normal.png"
done
for prefix in short-light-editor short-dark-fullscreen; do
  for geometry in point line area; do
    require_png "$prefix-selected-$geometry.png"
    require_png "$prefix-$geometry-dialog.png"
  done
done
for stage in single-tap-point single-tap-line single-tap-drag-area; do
  require_png "short-dark-fullscreen-$stage.png"
done
shopt -s nullglob
images=("$artifact_dir"/*.png)
echo "P5A instrumentation succeeded; archived ${#images[@]} non-blocking PNG evidence files."
