#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
: "${SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the device to use}"
ADB="$SDK_ROOT/platform-tools/adb"
MODE="${1:-measure}"
[[ "$MODE" == "measure" || "$MODE" == "profile" ]] || { echo "Usage: $0 [measure|profile]" >&2; exit 2; }
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state)" == device ]] || exit 2
QEMU="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$QEMU" == 1 && "$(uname -s)" == Darwin ]]; then
  source scripts/android_emulator_graphics.sh
  mela_configure_emulator_graphics
  RENDERER="$("$ADB" -s "$ANDROID_SERIAL" shell dumpsys SurfaceFlinger | grep -m 1 '^GLES:' || true)"
  mela_verify_emulator_graphics "$RENDERER"
  ABI="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
  EXPECTED_ABI=x86_64
  [[ "$(uname -m)" != arm64 ]] || EXPECTED_ABI=arm64-v8a
  [[ "$ABI" == "$EXPECTED_ABI" ]] || { echo "Native image required: $ABI" >&2; exit 1; }
fi
if [[ "$MODE" == measure ]]; then
  if [[ "$QEMU" == 1 ]]; then
    echo "Use a physical phone for performance measurements. Profile generation can use an emulator." >&2
    exit 2
  fi
  for key in window_animation_scale transition_animation_scale animator_duration_scale; do
    value="$("$ADB" -s "$ANDROID_SERIAL" shell settings get global "$key" | tr -d '\r')"
    [[ "$value" == 1 || "$value" == 1.0 || "$value" == null ]] || {
      echo "Set $key to 1x in Android Developer options before measuring." >&2; exit 2;
    }
  done
fi
# This installs only com.mannaworks.mela.benchmark; it never signs with the release key.
export MELA_SENTRY_DSN=''
export MELA_SENTRY_PROPERTIES=/tmp/mela-benchmark-no-sentry.properties
export MELA_SIGNING_PROPERTIES=/tmp/mela-benchmark-no-signing.properties
if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
ARTIFACT_DIR=".artifacts/benchmarks"
mkdir -p "$ARTIFACT_DIR"
git rev-parse HEAD > "$ARTIFACT_DIR/commit.txt"
"$ADB" -s "$ANDROID_SERIAL" shell getprop ro.product.model > "$ARTIFACT_DIR/device.txt"
"$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk >> "$ARTIFACT_DIR/device.txt"
if [[ "$MODE" == profile ]]; then
  MARKER="$ARTIFACT_DIR/profile.started"
  touch "$MARKER"
  ./gradlew :benchmark:connectedProfileAndroidTest --max-workers=2 --no-parallel --console=plain
  profiles=()
  while IFS= read -r file; do profiles+=("$file"); done < <(find benchmark/build/outputs -type f -name '*-baseline-prof.txt' -newer "$MARKER")
  [[ ${#profiles[@]} == 1 ]] || { echo "Expected one generated profile; found ${#profiles[@]}." >&2; exit 1; }
  test -s "${profiles[0]}"
  cp "${profiles[0]}" app/src/main/baseline-prof.txt
  echo "Updated app/src/main/baseline-prof.txt. Review it, then run $0 measure on a phone."
else
  ./gradlew :benchmark:connectedBenchmarkAndroidTest --console=plain
fi
