#!/usr/bin/env bash
set -euo pipefail
sdk_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
server_root="${1:-$sdk_root/../updater-server}"
export KMP_UPDATER_FIXTURE_SOURCE="$sdk_root/scripts/server-fixture"
cd -- "$server_root"
exec ./gradlew --init-script "$sdk_root/scripts/server-fixture.gradle" runSdkFixture --console=plain
