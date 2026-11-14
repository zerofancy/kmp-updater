#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?JitPack must provide an Android SDK}"
updater_sdkmanager="$(command -v sdkmanager || true)"
if [[ -z "$updater_sdkmanager" ]]; then
    for candidate in "$ANDROID_HOME"/cmdline-tools/*/bin/sdkmanager "$ANDROID_HOME"/tools/bin/sdkmanager; do
        if [[ -x "$candidate" ]]; then
            updater_sdkmanager="$candidate"
            break
        fi
    done
fi
if [[ -z "$updater_sdkmanager" ]]; then
    echo "Android sdkmanager was not found" >&2
    exit 1
fi

# Accept the platform license without masking sdkmanager failures under pipefail.
{ yes || true; } | "$updater_sdkmanager" --sdk_root="$ANDROID_HOME" "platforms;android-37.0"
