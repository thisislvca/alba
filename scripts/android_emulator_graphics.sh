#!/usr/bin/env bash

# https://developer.android.com/studio/run/emulator-acceleration
# `auto` can select CPU rendering. On macOS, require the host GPU and verify it.
alba_configure_emulator_graphics() {
  ALBA_GPU_HOST_OS="$(uname -s)"
  ALBA_GPU_HOST_ARCH="$(uname -m)"
  ALBA_GPU_MODE="${ALBA_EMULATOR_GPU:-host}"
  ALBA_GPU_REQUIRED="${ALBA_REQUIRE_HARDWARE_GPU:-1}"
  if [[ "$ALBA_GPU_HOST_OS" == "Darwin" ]]; then
    if [[ "$ALBA_GPU_MODE" != "host" || "$ALBA_GPU_REQUIRED" != "1" ]]; then
      echo "macOS requires -gpu host and hardware verification. Software/auto rendering is disabled." >&2
      return 2
    fi
  fi
}

alba_verify_emulator_graphics() {
  local renderer="$1"
  local normalized
  normalized="$(printf '%s' "$renderer" | tr '[:upper:]' '[:lower:]')"
  if [[ "$ALBA_GPU_HOST_OS" == "Darwin" || "$ALBA_GPU_REQUIRED" == "1" ]]; then
    case "$normalized" in
      ""|*swiftshader*|*llvmpipe*|*lavapipe*|*software*|*swangle*)
        echo "Hardware graphics check failed: $renderer" >&2
        return 1
        ;;
    esac
    if [[ "$ALBA_GPU_HOST_OS" == "Darwin" && "$ALBA_GPU_HOST_ARCH" == "arm64" ]]; then
      if [[ "$normalized" != *"apple"* || "$normalized" != *"metal"* ]]; then
        echo "Could not verify Apple GPU/Metal rendering: $renderer" >&2
        return 1
      fi
    elif [[ "$ALBA_GPU_HOST_OS" == "Darwin" ]]; then
      case "$normalized" in
        *apple*|*amd*|*ati\ *|*intel*|*nvidia*) ;;
        *) echo "Could not verify a Mac hardware GPU: $renderer" >&2; return 1 ;;
      esac
    fi
  fi
  echo "Renderer: $renderer"
}
