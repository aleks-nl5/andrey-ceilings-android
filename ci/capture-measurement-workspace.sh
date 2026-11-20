#!/usr/bin/env bash
set -euo pipefail

artifact_dir="${RUNNER_TEMP:-app/build}/p5a-workspace"
mkdir -p "$artifact_dir"
previous_ime_setting="$(adb shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
restore_display() {
  adb shell wm size reset
  adb shell wm density reset
  if [[ "$previous_ime_setting" == null ]]; then
    adb shell settings delete secure show_ime_with_hard_keyboard
  else
    adb shell settings put secure show_ime_with_hard_keyboard "$previous_ime_setting"
  fi
}
trap restore_display EXIT
adb shell wm size 720x1280
adb shell wm density 320
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell mkdir -p /data/local/tmp/p5a-workspace
adb shell am instrument -w -e class com.andreyceilings.app.MeasurementWorkspaceLayoutTest \
  -e p5aScreenshots true com.andreyceilings.app.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$artifact_dir/instrumentation.txt"
adb pull /data/local/tmp/p5a-workspace/. "$artifact_dir"
grep -q 'OK (4 tests)' "$artifact_dir/instrumentation.txt"
shopt -s nullglob
images=("$artifact_dir"/*.png)
[[ "${#images[@]}" == 28 ]]
