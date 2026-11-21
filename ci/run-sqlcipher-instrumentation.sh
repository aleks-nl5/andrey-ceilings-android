#!/usr/bin/env bash
set -euo pipefail

# Keep diagnostic screenshots when the main connectedAndroidTest suite fails.
# The normal screenshot collector runs only after this suite succeeds, so without
# this trap the most useful failing frame would disappear with the emulator.
collect_failed_workspace_capture() {
  local status=$?
  local artifact_dir="${RUNNER_TEMP:-app/build}/p5a-workspace"
  mkdir -p "$artifact_dir"
  # Distinguish a vanished emulator/ADB transport from a genuine assertion or
  # SQLCipher failure. Only transport loss is eligible for one fresh-AVD retry.
  if ! timeout 10s adb -s emulator-5554 shell true >/dev/null 2>&1; then
    touch "$artifact_dir/emulator-disconnected.marker"
    echo "::warning::Android emulator is unreachable after instrumentation failure."
    adb devices -l || true
  fi
  if adb shell run-as com.andreyceilings.app test -d cache/p5a-workspace >/dev/null 2>&1; then
    adb exec-out run-as com.andreyceilings.app tar -C cache/p5a-workspace -cf - . \
      | tar -xf - -C "$artifact_dir" || true
  fi
  exit "$status"
}
trap collect_failed_workspace_capture ERR

# reactivecircus/android-emulator-runner treats each YAML script line as an
# independent shell command, so keep shell continuations here (not in YAML).
#
# The crash tests are excluded from the normal AndroidJUnitRunner session.
# Each destructive SIGKILL proof runs in its own adb instrumentation session,
# and is only accepted when a new process recovers the encrypted DB/key.
# Mandatory focused pointer smoke check: fail near the recurring issue before
# spending ~20 minutes on the rest. On success, keep the entire original suite,
# including both of these tests; no exclusions or assertion retries.
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.andreyceilings.app.AreaFeatureTouchTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.andreyceilings.app.IsolatedProcessDeathProof \
  --stacktrace --warning-mode all

gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.andreyceilings.app.IsolatedProcessDeathProof \
  --stacktrace --warning-mode all

# AGP's connected test task may remove the APKs from the emulator. Reinstall
# without wiping app data before isolated, destructive instrumentation.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

bash ci/capture-measurement-workspace.sh

bash ci/verify-sqlcipher-process-death.sh
bash ci/verify-production-v9-upgrade.sh

# This is intentionally LAST. It fills the throwaway emulator's /data to real
# ENOSPC and immediately frees it before verifying SQLCipher WAL + Keystore.
# The destructive test is excluded from ordinary Android instrumentation by
# the same IsolatedProcessDeathProof isolation gate as SIGKILL tests.
bash ci/verify-sqlcipher-enospc.sh
