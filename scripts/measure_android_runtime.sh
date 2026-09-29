#!/usr/bin/env bash
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/android_emulator_graphics.sh"
mela_configure_emulator_graphics

echo "Emulator smoke measurements only. Use scripts/benchmark_android.sh on a phone for performance results."

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
APKSIGNER="$SDK_ROOT/build-tools/37.0.0/apksigner"
AVD_NAME="${MELA_AVD_NAME:-Mela_API_37}"
RUN_LABEL="${MELA_MEASUREMENT_LABEL:-measurement}"
SETTLE_SECONDS="${MELA_MEASUREMENT_SETTLE_SECONDS:-6}"
BITMAP_READY_KB="${MELA_MEASUREMENT_BITMAP_READY_KB:-1024}"
READY_TIMEOUT_SECONDS="${MELA_MEASUREMENT_READY_TIMEOUT_SECONDS:-30}"
ARTIFACT_DIR="${MELA_MEASUREMENT_DIR:-.artifacts/optimization/$RUN_LABEL}"
EMULATOR_LOG="$ARTIFACT_DIR/emulator.log"
RESULTS="$ARTIFACT_DIR/metrics.tsv"
RAW_DIR="$ARTIFACT_DIR/raw"
UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="$ARTIFACT_DIR/app-release-benchmark.apk"
INPUT_APK="${MELA_MEASUREMENT_APK:-}"
DEBUG_KEYSTORE="${MELA_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
PACKAGE_NAME="${MELA_MEASUREMENT_PACKAGE:-com.mannaworks.mela}"
ACTIVITY_NAME="dev.mela.app.MainActivity"
EMULATOR_PID=""
ANIMATION_KEYS=(window_animation_scale transition_animation_scale animator_duration_scale)
ORIGINAL_ANIMATION_SCALES=()
EMULATOR_PORT="${MELA_EMULATOR_PORT:-5558}"
if [[ ! "$EMULATOR_PORT" =~ ^[0-9]+$ ]] || (( EMULATOR_PORT < 5554 || EMULATOR_PORT > 5682 || EMULATOR_PORT % 2 != 0 )); then
  echo "MELA_EMULATOR_PORT must be an even port from 5554 through 5682." >&2
  exit 2
fi
EMULATOR_SERIAL="emulator-$EMULATOR_PORT"
VALIDATOR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/validate_android_measurement.mjs"

command -v bun >/dev/null || { echo "Install Bun to validate runtime measurements." >&2; exit 2; }

mkdir -p "$ARTIFACT_DIR" "$RAW_DIR"
# Do not leave a previous successful report looking like this run's result.
rm -f "$RESULTS"

cleanup() {
  local exit_status=$?
  local cleanup_serial="$EMULATOR_SERIAL"
  trap - EXIT INT TERM

  if [[ -z "$EMULATOR_PID" ]]; then
    exit "$exit_status"
  fi

  # -no-snapshot still persists AVD settings. Do not leave manual previews motionless.
  for index in "${!ORIGINAL_ANIMATION_SCALES[@]}"; do
    if [[ "${ORIGINAL_ANIMATION_SCALES[$index]}" == "null" ]]; then
      "$ADB" -s "$cleanup_serial" shell settings delete global "${ANIMATION_KEYS[$index]}" >/dev/null 2>&1 || true
    else
      "$ADB" -s "$cleanup_serial" shell settings put global "${ANIMATION_KEYS[$index]}" "${ORIGINAL_ANIMATION_SCALES[$index]}" >/dev/null 2>&1 || true
    fi
  done

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
if [[ ! -x "$APKSIGNER" ]]; then
  echo "Missing apksigner at $APKSIGNER." >&2
  exit 2
fi
if [[ ! -f "$DEBUG_KEYSTORE" ]]; then
  echo "Missing measurement keystore at $DEBUG_KEYSTORE." >&2
  exit 2
fi

if [[ -n "$INPUT_APK" ]]; then
  if [[ ! -f "$INPUT_APK" ]]; then
    echo "Missing measurement APK at $INPUT_APK." >&2
    exit 2
  fi
  cp "$INPUT_APK" "$SIGNED_APK"
else
  ./gradlew :app:assembleRelease --max-workers=2 --no-parallel --console=plain
  if [[ -f "app/build/outputs/apk/release/app-release.apk" ]]; then
    UNSIGNED_APK="app/build/outputs/apk/release/app-release.apk"
  fi
  "$APKSIGNER" sign \
    --ks "$DEBUG_KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$SIGNED_APK" \
    "$UNSIGNED_APK"
fi
"$APKSIGNER" verify "$SIGNED_APK"

"$EMULATOR" \
    -avd "$AVD_NAME" \
    -port "$EMULATOR_PORT" \
    -no-snapshot \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu "$MELA_GPU_MODE" \
    -accel on \
    -cores 2 >"$EMULATOR_LOG" 2>&1 &
EMULATOR_PID=$!

for _ in {1..90}; do
  if ! kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    echo "The emulator exited before boot completed. See $EMULATOR_LOG." >&2
    exit 1
  fi
  if [[ -n "$EMULATOR_SERIAL" ]] && [[ "$($ADB -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  sleep 2
done

if [[ -z "$EMULATOR_SERIAL" ]] || [[ "$($ADB -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
  echo "The emulator did not finish booting. See $EMULATOR_LOG." >&2
  exit 1
fi

RENDERER="$($ADB -s "$EMULATOR_SERIAL" shell dumpsys SurfaceFlinger 2>/dev/null | grep -m 1 '^GLES:' || true)"
mela_verify_emulator_graphics "$RENDERER"
if [[ "$(uname -s)" == Darwin ]]; then
  ABI="$("$ADB" -s "$EMULATOR_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
  EXPECTED_ABI=x86_64
  [[ "$(uname -m)" != arm64 ]] || EXPECTED_ABI=arm64-v8a
  [[ "$ABI" == "$EXPECTED_ABI" ]] || { echo "Native image required: $ABI" >&2; exit 1; }
fi

"$ADB" -s "$EMULATOR_SERIAL" shell input keyevent KEYCODE_WAKEUP
"$ADB" -s "$EMULATOR_SERIAL" shell wm dismiss-keyguard

# Keep the workload comparable even after accessibility testing on this AVD.
"$ADB" -s "$EMULATOR_SERIAL" shell settings put system font_scale 1.0
for animation_key in "${ANIMATION_KEYS[@]}"; do
  ORIGINAL_ANIMATION_SCALES+=("$("$ADB" -s "$EMULATOR_SERIAL" shell settings get global "$animation_key" | tr -d '\r')")
done
for animation_key in "${ANIMATION_KEYS[@]}"; do
  "$ADB" -s "$EMULATOR_SERIAL" shell settings put global "$animation_key" 0
done
"$ADB" -s "$EMULATOR_SERIAL" install -r "$SIGNED_APK" >/dev/null
"$ADB" -s "$EMULATOR_SERIAL" shell cmd package compile -m speed -f "$PACKAGE_NAME" >/dev/null
"$ADB" -s "$EMULATOR_SERIAL" shell pm clear "$PACKAGE_NAME" >/dev/null

measure_start() {
  local name="$1"
  local raw_file="$RAW_DIR/$name.txt"
  "$ADB" -s "$EMULATOR_SERIAL" shell am start -W -S -n "$PACKAGE_NAME/$ACTIVITY_NAME" \
    | tr -d '\r' | tee "$raw_file" >/dev/null
  bun "$VALIDATOR" --startup "$raw_file"
}

FIRST_START_MS="$(measure_start first-start)"
COLD_VALUES=()
for index in 1 2 3 4 5; do
  COLD_VALUES+=("$(measure_start "cold-start-$index")")
done
COLD_MEDIAN_MS="$(printf '%s\n' "${COLD_VALUES[@]}" | sort -n | awk 'NR == 3 { print; exit }')"

sleep "$SETTLE_SECONDS"
for ((waited = 0; waited <= READY_TIMEOUT_SECONDS; waited += 1)); do
  "$ADB" -s "$EMULATOR_SERIAL" shell dumpsys meminfo "$PACKAGE_NAME" \
    | tr -d '\r' >"$RAW_DIR/meminfo.txt"
  READY_BITMAP_KB="$(awk '/Bitmap \(malloced\):/ { print $4; exit }' "$RAW_DIR/meminfo.txt")"
  if [[ "$READY_BITMAP_KB" =~ ^[0-9]+$ ]] && ((READY_BITMAP_KB >= BITMAP_READY_KB)); then
    break
  fi
  if ((waited == READY_TIMEOUT_SECONDS)); then
    echo "The fixture thumbnails did not become ready within ${READY_TIMEOUT_SECONDS}s." >&2
    exit 1
  fi
  sleep 1
done
TOTAL_PSS_KB="$(awk '/TOTAL PSS:/ { print $3; exit }' "$RAW_DIR/meminfo.txt")"
TOTAL_RSS_KB="$(awk '/TOTAL PSS:/ { for (field = 1; field <= NF; field++) if ($field == "RSS:") { print $(field + 1); exit } }' "$RAW_DIR/meminfo.txt")"
CATALOG_DB_KB="$(awk '/mela-catalog\.db$/ { print $2; exit }' "$RAW_DIR/meminfo.txt")"
WORK_DB_KB="$(awk '/androidx\.work\.workdb$/ { print $2; exit }' "$RAW_DIR/meminfo.txt")"
BITMAP_COUNT="$(awk '/Bitmap \(malloced\):/ { print $3; exit }' "$RAW_DIR/meminfo.txt")"
BITMAP_KB="$(awk '/Bitmap \(malloced\):/ { print $4; exit }' "$RAW_DIR/meminfo.txt")"

"$ADB" -s "$EMULATOR_SERIAL" shell dumpsys gfxinfo "$PACKAGE_NAME" reset >/dev/null
SCREEN_SIZE="$($ADB -s "$EMULATOR_SERIAL" shell wm size | tr -d '\r' | awk -F': ' '/Physical size:/ { print $2; exit }')"
if [[ ! "$SCREEN_SIZE" =~ ^[1-9][0-9]*x[1-9][0-9]*$ ]]; then
  echo "Could not determine emulator display size." >&2
  exit 1
fi
"$ADB" -s "$EMULATOR_SERIAL" shell dumpsys window >"$RAW_DIR/window-before-scroll.txt"
if ! grep -F "mCurrentFocus=" "$RAW_DIR/window-before-scroll.txt" | grep -F "$PACKAGE_NAME/$ACTIVITY_NAME" >/dev/null; then
  echo "Alba is not the focused window; refusing to measure another surface." >&2
  exit 1
fi
SCREEN_WIDTH="${SCREEN_SIZE%x*}"
SCREEN_HEIGHT="${SCREEN_SIZE#*x}"
CENTER_X="$((SCREEN_WIDTH / 2))"
LOW_Y="$((SCREEN_HEIGHT * 4 / 5))"
HIGH_Y="$((SCREEN_HEIGHT / 5))"
for _ in 1 2 3; do
  "$ADB" -s "$EMULATOR_SERIAL" shell input swipe "$CENTER_X" "$LOW_Y" "$CENTER_X" "$HIGH_Y" 350
  "$ADB" -s "$EMULATOR_SERIAL" shell input swipe "$CENTER_X" "$HIGH_Y" "$CENTER_X" "$LOW_Y" 350
done
"$ADB" -s "$EMULATOR_SERIAL" shell dumpsys gfxinfo "$PACKAGE_NAME" | tr -d '\r' >"$RAW_DIR/gfxinfo.txt"
TOTAL_FRAMES="$(awk -F': ' '/Total frames rendered:/ { print $2; exit }' "$RAW_DIR/gfxinfo.txt")"
JANKY_FRAMES="$(awk '/Janky frames:/ { print $3; exit }' "$RAW_DIR/gfxinfo.txt")"
P50_FRAME_MS="$(awk -F': ' '/50th percentile:/ { gsub(/ms/, "", $2); print $2; exit }' "$RAW_DIR/gfxinfo.txt")"
P90_FRAME_MS="$(awk -F': ' '/90th percentile:/ { gsub(/ms/, "", $2); print $2; exit }' "$RAW_DIR/gfxinfo.txt")"
P95_FRAME_MS="$(awk -F': ' '/95th percentile:/ { gsub(/ms/, "", $2); print $2; exit }' "$RAW_DIR/gfxinfo.txt")"
GPU_MEMORY_BYTES="$(awk '/Total GPU memory usage:/ { getline; print $1; exit }' "$RAW_DIR/gfxinfo.txt")"
bun "$VALIDATOR" "$RAW_DIR"

APK_BYTES="$(wc -c < "$SIGNED_APK" | tr -d ' ')"
DEX_BYTES="$(unzip -l "$SIGNED_APK" 'classes*.dex' | awk '$4 ~ /^classes([0-9]+)?\.dex$/ { total += $1 } END { print total + 0 }')"

{
  printf 'metric\tvalue\tunit\n'
  printf 'renderer\t%s\ttext\n' "$RENDERER"
  printf 'apk_bytes\t%s\tbytes\n' "$APK_BYTES"
  printf 'dex_uncompressed_bytes\t%s\tbytes\n' "$DEX_BYTES"
  printf 'first_start_total\t%s\tms\n' "$FIRST_START_MS"
  for index in "${!COLD_VALUES[@]}"; do
    printf 'cold_start_%s_total\t%s\tms\n' "$((index + 1))" "${COLD_VALUES[$index]}"
  done
  printf 'cold_start_median\t%s\tms\n' "$COLD_MEDIAN_MS"
  printf 'total_pss\t%s\tKB\n' "$TOTAL_PSS_KB"
  printf 'total_rss\t%s\tKB\n' "$TOTAL_RSS_KB"
  printf 'catalog_db_size\t%s\tKB\n' "$CATALOG_DB_KB"
  printf 'work_db_size\t%s\tKB\n' "$WORK_DB_KB"
  printf 'bitmap_count\t%s\tbitmaps\n' "$BITMAP_COUNT"
  printf 'bitmap_size\t%s\tKB\n' "$BITMAP_KB"
  printf 'gpu_memory\t%s\tbytes\n' "$GPU_MEMORY_BYTES"
  printf 'scroll_total_frames\t%s\tframes\n' "$TOTAL_FRAMES"
  printf 'scroll_janky_frames\t%s\tframes\n' "$JANKY_FRAMES"
  printf 'scroll_p50_frame\t%s\tms\n' "$P50_FRAME_MS"
  printf 'scroll_p90_frame\t%s\tms\n' "$P90_FRAME_MS"
  printf 'scroll_p95_frame\t%s\tms\n' "$P95_FRAME_MS"
} >"$RESULTS"

cat "$RESULTS"
