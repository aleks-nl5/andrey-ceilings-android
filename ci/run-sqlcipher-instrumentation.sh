#!/usr/bin/env bash
set -euo pipefail

# reactivecircus/android-emulator-runner treats each YAML script line as an
# independent shell command, so keep shell continuations here (not in YAML).
#
# The crash tests are excluded from the normal AndroidJUnitRunner session.
# Each destructive SIGKILL proof runs in its own adb instrumentation session,
# and is only accepted when a new process recovers the encrypted DB/key.
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.andreyceilings.app.IsolatedProcessDeathProof \
  --stacktrace --warning-mode all

# AGP's connected test task may remove the APKs from the emulator. Reinstall
# without wiping app data before isolated, destructive instrumentation.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

bash ci/verify-sqlcipher-process-death.sh

# This is intentionally LAST. It fills the throwaway emulator's /data to real
# ENOSPC and immediately frees it before verifying SQLCipher WAL + Keystore.
# The destructive test is excluded from ordinary Android instrumentation by
# the same IsolatedProcessDeathProof isolation gate as SIGKILL tests.
bash ci/verify-sqlcipher-enospc.sh
