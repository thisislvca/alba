#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/android_emulator_graphics.sh"
mela_configure_emulator_graphics

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "Set ANDROID_SDK_ROOT or ANDROID_HOME to the Android SDK path." >&2
  exit 2
fi

if [[ -z "${JAVA_HOME:-}" ]] && [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
AVD_NAME="${MELA_AVD_NAME:-Mela_API_37}"
ARTIFACT_DIR="${MELA_ARTIFACT_DIR:-.artifacts/android-tests}"
EMULATOR_LOG="$ARTIFACT_DIR/emulator.log"
EMULATOR_PID=""
EMULATOR_PORT="${MELA_EMULATOR_PORT:-5556}"
if [[ ! "$EMULATOR_PORT" =~ ^[0-9]+$ ]] || (( EMULATOR_PORT < 5554 || EMULATOR_PORT > 5682 || EMULATOR_PORT % 2 != 0 )); then
  echo "MELA_EMULATOR_PORT must be an even port from 5554 through 5682." >&2
  exit 2
fi
EMULATOR_SERIAL="emulator-$EMULATOR_PORT"

mkdir -p "$ARTIFACT_DIR"

cleanup() {
  local exit_status=$?
  local cleanup_serial="$EMULATOR_SERIAL"
  trap - EXIT INT TERM

  if [[ -z "$EMULATOR_PID" ]]; then
    exit "$exit_status"
  fi

  if [[ -n "$cleanup_serial" ]]; then
    "$ADB" -s "$cleanup_serial" emu kill >/dev/null 2>&1 || true
  fi

  if [[ -n "$EMULATOR_PID" ]] && kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    for _ in {1..20}; do
      kill -0 "$EMULATOR_PID" >/dev/null 2>&1 || break
      sleep 1
    done
  fi
  if [[ -n "$EMULATOR_PID" ]] && kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    kill "$EMULATOR_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "$EMULATOR_PID" ]]; then
    wait "$EMULATOR_PID" >/dev/null 2>&1 || true
  fi

  exit "$exit_status"
}
trap cleanup EXIT INT TERM

if "$ADB" devices | awk -v serial="$EMULATOR_SERIAL" '$1 == serial { found=1 } END { exit !found }'; then
  echo "$EMULATOR_SERIAL is already in use. Choose another MELA_EMULATOR_PORT." >&2
  exit 2
fi

"$EMULATOR" \
    -avd "$AVD_NAME" \
    -port "$EMULATOR_PORT" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -read-only -no-snapshot -gpu "$MELA_GPU_MODE" \
    -accel on \
    -cores 2 >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

wait_for_boot() {
for _ in {1..90}; do
  if ! kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    echo "The emulator exited before boot completed. See $EMULATOR_LOG." >&2
    exit 1
  fi
  if [[ -n "$EMULATOR_SERIAL" ]] && [[ "$($ADB -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    return 0
  fi
  sleep 2
done
echo "The emulator did not finish booting. See $EMULATOR_LOG." >&2
return 1
}
wait_for_boot

# Optional recovery for hung Android system services. This reboot preserves
# user data and never suppresses ANRs.
if [[ "${MELA_REBOOT_EMULATOR:-0}" == "1" ]]; then
  "$ADB" -s "$EMULATOR_SERIAL" reboot
  sleep 2
  wait_for_boot
fi
"$ADB" -s "$EMULATOR_SERIAL" shell input keyevent KEYCODE_WAKEUP
"$ADB" -s "$EMULATOR_SERIAL" shell wm dismiss-keyguard

RENDERER="$($ADB -s "$EMULATOR_SERIAL" shell dumpsys SurfaceFlinger 2>/dev/null | grep -m 1 '^GLES:' || true)"
mela_verify_emulator_graphics "$RENDERER"
if [[ "$(uname -s)" == Darwin ]]; then
  ABI="$($ADB -s "$EMULATOR_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
  EXPECTED_ABI=x86_64
  [[ "$(uname -m)" != arm64 ]] || EXPECTED_ABI=arm64-v8a
  [[ "$ABI" == "$EXPECTED_ABI" ]] || { echo "Native image required: $ABI" >&2; exit 1; }
fi


if (( $# )); then
  TEST_TASKS=("$@")
else
  TEST_TASKS=(:engine:testDebugUnitTest :protocol-icloud-web:testDebugUnitTest :app:testDebugUnitTest
    :engine:connectedDebugAndroidTest :protocol-icloud-web:connectedDebugAndroidTest :app:connectedDebugAndroidTest :app:lintDebug)
fi
ANDROID_SERIAL="$EMULATOR_SERIAL" ./gradlew --max-workers=2 --no-parallel --console=plain "${TEST_TASKS[@]}"

# AGP retrieves additional test output before uninstalling the test application.
while IFS= read -r capture; do
  cp "$capture" "$ARTIFACT_DIR/"
done < <(find app/build/outputs/connected_android_test_additional_output -type f -name 'features-*.png' 2>/dev/null)
