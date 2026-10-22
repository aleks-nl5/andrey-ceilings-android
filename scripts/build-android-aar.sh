#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$ROOT/app/libs"

if ! command -v gomobile >/dev/null 2>&1; then
  echo "gomobile was not found. Install it with:" >&2
  echo "  go install golang.org/x/mobile/cmd/gomobile@latest" >&2
  exit 1
fi

gomobile bind \
  -target=android \
  -javapkg=com.andreyceilings.core \
  -o "$ROOT/app/libs/ceilings-core.aar" \
  "$ROOT/go-core/mobileapi"

echo "Generated: app/libs/ceilings-core.aar"
