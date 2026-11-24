#!/usr/bin/env bash
set -euo pipefail

# Preserve infrastructure diagnostics without masking the original failure.
collect_instrumentation_diagnostics() {
  local status=$?
  local artifact_dir="${RUNNER_TEMP:-app/build}/android-instrumentation-diagnostics"
  mkdir -p "$artifact_dir"
  # Distinguish a vanished emulator/ADB transport from a genuine assertion or
  # SQLCipher failure. Only transport loss is eligible for one fresh-AVD retry.
  if timeout 10s adb -s emulator-5554 shell true >/dev/null 2>&1; then
    timeout 10s adb -s emulator-5554 logcat -d -v threadtime \
      'ActivityManager:W' 'AndroidRuntime:E' '*:S' \
      > "$artifact_dir/android-failure-logcat.txt" || true
    timeout 10s adb -s emulator-5554 shell dumpsys activity lastanr \
      > "$artifact_dir/android-last-anr.txt" || true
  else
    touch "$artifact_dir/emulator-disconnected.marker"
    echo "::warning::Android emulator is unreachable after instrumentation failure."
    timeout 10s adb devices -l || true
  fi
  exit "$status"
}
trap collect_instrumentation_diagnostics ERR

# reactivecircus/android-emulator-runner treats each YAML script line as an
# independent shell command, so keep shell continuations here (not in YAML).
#
# The crash tests are excluded from the normal AndroidJUnitRunner session.
# Each destructive SIGKILL proof runs in its own adb instrumentation session,
# and is only accepted when a new process recovers the encrypted DB/key.
timeout --kill-after=30s 45m gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.andreyceilings.app.IsolatedProcessDeathProof \
  --stacktrace --warning-mode all

# AGP's connected test task may remove the APKs from the emulator. Reinstall
# without wiping app data before isolated, destructive instrumentation.
timeout 90s adb install -r app/build/outputs/apk/debug/app-debug.apk
timeout 90s adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Keep the following encrypted DB recovery/integrity proofs mandatory.
bash ci/verify-sqlcipher-process-death.sh
bash ci/verify-production-v9-upgrade.sh

# This is intentionally LAST. It fills the throwaway emulator's /data to real
# ENOSPC and immediately frees it before verifying SQLCipher WAL + Keystore.
# The destructive test is excluded from ordinary Android instrumentation by
# the same IsolatedProcessDeathProof isolation gate as SIGKILL tests.
bash ci/verify-sqlcipher-enospc.sh
