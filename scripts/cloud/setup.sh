#!/usr/bin/env bash
# Provisions a Claude Code cloud session (Ubuntu, JDK 21 preinstalled) to build Alba and run its JVM tests:
# the Android SDK packages CI installs, in /opt/android-sdk. Paste this file as the cloud environment's setup
# script; the SessionStart hook (scripts/cloud/session-start.sh) runs it too when the SDK is missing.
# Safe to run again: it skips what is already installed.
set -euo pipefail

sdk="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
# Same packages as CI (.github/workflows/ci.yml); no emulator, device tests run in CI.
packages=(platform-tools "platforms;android-37.0" "build-tools;37.0.0")
tools_zip=commandlinetools-linux-16111833_latest.zip # cmdline-tools 23.0
tools_sha1=e025545c62a8e64c7559119566a569fb1dec5f60

sudo=""
if [ "$(id -u)" != 0 ] && command -v sudo >/dev/null; then sudo="sudo"; fi

if [ ! -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp="$(mktemp -d)"
  curl -fsSL "https://dl.google.com/android/repository/$tools_zip" -o "$tmp/tools.zip"
  echo "$tools_sha1  $tmp/tools.zip" | sha1sum -c --quiet
  $sudo mkdir -p "$sdk/cmdline-tools"
  $sudo unzip -q "$tmp/tools.zip" -d "$tmp"
  $sudo rm -rf "$sdk/cmdline-tools/latest"
  $sudo mv "$tmp/cmdline-tools" "$sdk/cmdline-tools/latest"
  rm -rf "$tmp"
fi

sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
yes | $sudo "$sdkmanager" --sdk_root="$sdk" --licenses >/dev/null || true
$sudo "$sdkmanager" --sdk_root="$sdk" --install "${packages[@]}" >/dev/null
$sudo chmod -R a+rwX "$sdk"

echo "Android SDK ready in $sdk"
