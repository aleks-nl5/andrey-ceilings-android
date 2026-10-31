#!/usr/bin/env bash
set -euo pipefail

# DANGEROUS BY DESIGN: this test consumes every app-accessible /data block.
# Run ONLY after all normal tests, in an ephemeral Android emulator whose
# writable data image is discarded; NEVER run on a real user's device.
APP="com.andreyceilings.app"
RUNNER="com.andreyceilings.app.test/androidx.test.runner.AndroidJUnitRunner"
TEST="com.andreyceilings.app.SqlCipherRealFullPartitionEnospcTest#actualEnospcDuringIncomingRestoreStagePreservesWalAndKey"
FILLER="files/isolated_sqlcipher_full_partition_enospc.bin"
LOG="$(mktemp)"
cleanup() {
  # If the test process died while the filesystem was full, remove the exact
  # isolated file with run-as before allowing CI to exit (never delete DB/WAL).
  adb shell run-as "$APP" rm -f "$FILLER" >/dev/null 2>&1 || true
  rm -f "$LOG"
}
trap cleanup EXIT

# Exclude physical phones, even when someone runs this CI script manually.
HARDWARE="$(adb shell getprop ro.hardware | tr -d '\r\n')"
case "$HARDWARE" in
  ranchu|goldfish) echo "Verified disposable Android emulator: $HARDWARE" ;;
  *) echo "::error::Refusing ENOSPC test: ro.hardware=$HARDWARE is not a supported emulator"; exit 1 ;;
esac

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Only clean disposable emulator app data; all previous instrumentation and
# actual process-death recovery tests have already passed in this CI session.
adb shell pm clear "$APP"
echo "Running isolated, real-kernel ENOSPC proof on disposable emulator /data"

if ! timeout 360s adb shell am instrument -w \
  -e class "$TEST" \
  -e allowDisposableFullPartitionEnospc true \
  "$RUNNER" >"$LOG" 2>&1; then
  cat "$LOG"
  echo "::error::Genuine Android /data ENOSPC instrumentation failed"
  exit 1
fi
cat "$LOG"
if ! grep -q 'OK (1 test)' "$LOG"; then
  echo "::error::ENOSPC test did not complete exactly one passing test"
  exit 1
fi

# Recovery must not require adb root and must not leave the giant filler file.
if adb shell run-as "$APP" ls "$FILLER" >/dev/null 2>&1; then
  echo "::error::ENOSPC filler file leaked after instrumentation"
  exit 1
fi
echo "Genuine /data ENOSPC, unchanged SQLCipher WAL/key, recovery and cold reopen all verified"
