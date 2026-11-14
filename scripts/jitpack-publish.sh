#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?JitPack must provide an Android SDK}"

# The JitPack image's legacy sdkmanager requires JAXB, which JDK 21 no longer ships.
# Pin Google's standalone tools and verify the checksum from developer.android.com/studio.
updater_tools_dir="$PWD/build/jitpack-tools/15859902"
updater_tools_zip="$updater_tools_dir/commandlinetools-linux-15859902_latest.zip"
updater_sdkmanager="$updater_tools_dir/cmdline-tools/bin/sdkmanager"
if [[ ! -x "$updater_sdkmanager" ]]; then
    mkdir -p "$updater_tools_dir"
    curl --fail --location --retry 3 \
        "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip" \
        --output "$updater_tools_zip"
    printf '%s  %s\n' \
        '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583' \
        "$updater_tools_zip" | sha256sum --check
    unzip -q -o "$updater_tools_zip" -d "$updater_tools_dir"
fi

# Accept the platform license without masking sdkmanager failures under pipefail.
{ yes || true; } | "$updater_sdkmanager" --sdk_root="$ANDROID_HOME" "platforms;android-37.0"

# Keep setup and publication in the same fail-fast command: JitPack may continue
# after a failing before_install hook.
./gradlew --no-daemon --console=plain \
    :updater:publishToMavenLocal :updater-compose:publishToMavenLocal
python3 scripts/prepare-jitpack-metadata.py "$GROUP.$ARTIFACT" "$VERSION"
