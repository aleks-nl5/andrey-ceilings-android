#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_CORE="$ROOT/go-core"
X_MOBILE_VERSION="${X_MOBILE_VERSION:-v0.0.0-20261011034528-197770444867}"
mkdir -p "$ROOT/app/libs"

if ! command -v gomobile >/dev/null 2>&1; then
  echo "Installing gomobile ${X_MOBILE_VERSION}..."
  GOBIN="$(go env GOPATH)/bin"
  export PATH="$GOBIN:$PATH"
  GOTOOLCHAIN=auto go install "golang.org/x/mobile/cmd/gomobile@${X_MOBILE_VERSION}"
fi

export PATH="$(go env GOPATH)/bin:$PATH"

cd "$GO_CORE"

# Keep the gomobile/gobind module revision fixed for reproducible Android builds.
GOTOOLCHAIN=auto go get -tool "golang.org/x/mobile/cmd/gobind@${X_MOBILE_VERSION}"
GOTOOLCHAIN=auto go mod tidy

GOTOOLCHAIN=auto gomobile init

GOTOOLCHAIN=auto gomobile bind \
  -target=android \
  -androidapi=23 \
  -javapkg=com.andreyceilings.core \
  -o "$ROOT/app/libs/ceilings-core.aar" \
  "$GO_CORE/mobileapi"

echo "Generated: app/libs/ceilings-core.aar"
