#!/usr/bin/env bash
set -euo pipefail
# Disposable runner only: the previously installed debug test app has a different certificate.
adb uninstall com.andreyceilings.app.test >/dev/null 2>&1 || true
adb uninstall com.andreyceilings.app >/dev/null 2>&1 || true
gradle --no-daemon -PproductionReleaseSmoke=true :app:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.andreyceilings.app.ProductionDatabaseStartupTest \
  --stacktrace --warning-mode all
