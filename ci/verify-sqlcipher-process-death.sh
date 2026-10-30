#!/usr/bin/env bash
set -euo pipefail

# The crash is EXPECTED, but only if the exact checkpoint was durably written
# and a NEW instrumentation invocation proves that encrypted recovery worked.
# Never accept an arbitrary test crash as success.
APP="com.andreyceilings.app"
RUNNER="com.andreyceilings.app.test/androidx.test.runner.AndroidJUnitRunner"
TEST="com.andreyceilings.app.SqlCipherProcessDeathRecoveryTest"
MARKER="cache/restore_crash_probe_checkpoint.txt"

run_verified_crash() {
  local method="$1"
  local checkpoint="$2"
  local crash_log
  local verify_log
  crash_log="$(mktemp)"
  verify_log="$(mktemp)"
  echo "Starting isolated SQLCipher SIGKILL check at $checkpoint"

  # The JUnit process may itself terminate, or the runner may report failure,
  # when Android recognizes the crash in the other app process.
  timeout 120s adb shell am instrument -w -e class "$TEST#$method" "$RUNNER" \
    >"$crash_log" 2>&1 || true

  local reached
  reached="$(adb shell run-as "$APP" cat "$MARKER" 2>/dev/null | tr -d '\r\n' || true)"
  if [[ "$reached" != "$checkpoint" ]]; then
    cat "$crash_log"
    echo "::error::SIGKILL proof did not reach $checkpoint (marker='$reached')"
    exit 1
  fi
  if grep -q 'OK (1 test)' "$crash_log"; then
    cat "$crash_log"
    echo "::error::Crash invocation returned as a normally completed JUnit test"
    exit 1
  fi
  echo "Confirmed durable checkpoint $reached; checking cold-process recovery"

  if ! timeout 120s adb shell am instrument -w \
      -e class "$TEST#verifyRecoveryAndRestoreBaseline" "$RUNNER" \
      >"$verify_log" 2>&1; then
    cat "$verify_log"
    echo "::error::Cold-process SQLCipher recovery test invocation failed"
    exit 1
  fi
  if ! grep -q 'OK (1 test)' "$verify_log"; then
    cat "$verify_log"
    echo "::error::Recovery assertions did not pass"
    exit 1
  fi
  echo "Verified database rows, Keystore key, integrity and clean rollback for $checkpoint"
  rm -f "$crash_log" "$verify_log"
}

run_verified_crash "crashAfterImportedFileInstalled" "AFTER_IMPORTED_FILE_INSTALLED"
run_verified_crash "crashAfterKeystoreKeyReplaced" "AFTER_KEY_REPLACED"
echo "Both independent, real-process SQLCipher recovery proofs passed"
