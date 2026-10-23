
#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_CORE="$ROOT/go-core"
mkdir -p "$ROOT/app/libs"

if ! command -v gomobile >/dev/null 2>&1; then
  echo "Installing gomobile..."
  GOBIN="$(go env GOPATH)/bin"
  export PATH="$GOBIN:$PATH"
  go install golang.org/x/mobile/cmd/gomobile@latest
fi

export PATH="$(go env GOPATH)/bin:$PATH"

cd "$GO_CORE"

# Add the mobile binding dependency to the module.
# go get golang.org/x/mobile/cmd/gomobile
go get -tool golang.org/x/mobile/cmd/gobind
go mod tidy

gomobile init

export ANDROID_API=23

gomobile bind \
  -target=android \
  -javapkg=com.andreyceilings.core \
  -o "$ROOT/app/libs/ceilings-core.aar" \
  "$GO_CORE/mobileapi"

echo "Generated: app/libs/ceilings-core.aar"
