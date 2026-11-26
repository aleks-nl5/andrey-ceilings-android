#!/usr/bin/env bash
set -euo pipefail
APP="com.andreyceilings.app"
RUNNER="$APP.test/androidx.test.runner.AndroidJUnitRunner"
TEST="$APP.ProductionDatabaseProcessRestartTest"
crash_log="$(mktemp)"
verify_log="$(mktemp)"
trap 'rm -f "$crash_log" "$verify_log"' EXIT
adb shell run-as "$APP" rm -f cache/production_raw_restore_ready
timeout 180s adb shell am instrument -w -e class "$TEST#rawRecoveryAfterKeyReplacementAndKillProcess" "$RUNNER" >"$crash_log" 2>&1 || true
reached="$(adb shell run-as "$APP" cat cache/production_raw_restore_ready 2>/dev/null | tr -d '\r\n' || true)"
if [[ "$reached" != "READY_RAW_AFTER_KEY" ]] || grep -q 'OK (1 test)' "$crash_log"; then
  cat "$crash_log"
  echo "::error::Raw recovery did not reach the durable post-key-replacement checkpoint"
  exit 1
fi
timeout 180s adb shell am instrument -w -e class "$TEST#newProcessRecoversRawOriginalFilesAndKeystoreKey" "$RUNNER" >"$verify_log" 2>&1
if ! grep -q 'OK (1 test)' "$verify_log" || ! grep -q 'RAW_RECOVERY_PROCESS_RESTART_OK' "$verify_log"; then
  cat "$verify_log"
  echo "::error::Raw encrypted files/Keystore recovery failed after a real process restart"
  exit 1
fi
echo "Raw recovery preserves original encrypted data and key after SIGKILL following key replacement"
