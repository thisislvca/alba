#!/usr/bin/env bash
# Runs one module's device tests on a Gradle managed device for CI, retries only the tests that failed (once), and
# appends a summary to the job summary that names every flaky test (failed, then passed on retry).
#
# Usage: scripts/ci_device_tests.sh <module> <managed device, e.g. pixel9Api36> <summary title>
# Exits non-zero when a test still fails after its retry, or when the run produced no results.
set -uo pipefail
shopt -s nullglob

module="$1"
device="$2"
title="$3"
results="$module/build/outputs/androidTest-results/managedDevice/debug/$device"
attempts="$module/build/device-test-attempts"
gradle_args=(
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
  -Pandroid.experimental.testOptions.managedDevices.maxConcurrentDevices=1
  --max-workers=2 --no-parallel --console=plain
)

rm -rf "$attempts"
mkdir -p "$attempts/first" "$attempts/retry"

./gradlew ":$module:${device}DebugAndroidTest" "${gradle_args[@]}"
status=$?
for report in "$results"/TEST-*.xml; do cp "$report" "$attempts/first/"; done
first=("$attempts"/first/TEST-*.xml)

if (( status != 0 )) && (( ${#first[@]} > 0 )); then
  failed="$(node scripts/device_test_summary.mjs --failed "${first[@]}")"
  if [[ -n "$failed" ]]; then
    echo "Retrying the failed tests once: $failed"
    ./gradlew ":$module:${device}DebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=$failed" "${gradle_args[@]}"
    status=$?
    for report in "$results"/TEST-*.xml; do cp "$report" "$attempts/retry/"; done
  fi
fi

retry=("$attempts"/retry/TEST-*.xml)
node scripts/device_test_summary.mjs "$title" "${first[@]}" -- "${retry[@]}" >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
(( ${#first[@]} > 0 )) || status=1
exit "$status"
