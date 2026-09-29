#!/usr/bin/env bash

# https://developer.android.com/studio/run/emulator-acceleration
# `auto` can select CPU rendering. On macOS, require the host GPU and verify it.
mela_configure_emulator_graphics() {
  MELA_GPU_HOST_OS="$(uname -s)"
  MELA_GPU_HOST_ARCH="$(uname -m)"
  MELA_GPU_MODE="${MELA_EMULATOR_GPU:-host}"
  MELA_GPU_REQUIRED="${MELA_REQUIRE_HARDWARE_GPU:-1}"
  if [[ "$MELA_GPU_HOST_OS" == "Darwin" ]]; then
    if [[ "$MELA_GPU_MODE" != "host" || "$MELA_GPU_REQUIRED" != "1" ]]; then
      echo "macOS requires -gpu host and hardware verification. Software/auto rendering is disabled." >&2
      return 2
    fi
  fi
}

mela_verify_emulator_graphics() {
  local renderer="$1"
  local normalized
  normalized="$(printf '%s' "$renderer" | tr '[:upper:]' '[:lower:]')"
  if [[ "$MELA_GPU_HOST_OS" == "Darwin" || "$MELA_GPU_REQUIRED" == "1" ]]; then
    case "$normalized" in
      ""|*swiftshader*|*llvmpipe*|*lavapipe*|*software*|*swangle*)
        echo "Hardware graphics check failed: $renderer" >&2
        return 1
        ;;
    esac
    if [[ "$MELA_GPU_HOST_OS" == "Darwin" && "$MELA_GPU_HOST_ARCH" == "arm64" ]]; then
      if [[ "$normalized" != *"apple"* || "$normalized" != *"metal"* ]]; then
        echo "Could not verify Apple GPU/Metal rendering: $renderer" >&2
        return 1
      fi
    elif [[ "$MELA_GPU_HOST_OS" == "Darwin" ]]; then
      case "$normalized" in
        *apple*|*amd*|*ati\ *|*intel*|*nvidia*) ;;
        *) echo "Could not verify a Mac hardware GPU: $renderer" >&2; return 1 ;;
      esac
    fi
  fi
  echo "Renderer: $renderer"
}
