#!/usr/bin/env bash
# Unstages the goldens whose pixels did not change, so the bot commits only real pixel changes.
#
# Re-recording on Linux can re-encode a PNG (compression, metadata) without changing a pixel. The file differs byte for
# byte from the committed one, yet it is not a change worth a commit or a new CI run. Run from the repository root after
# the goldens are staged, against the committed HEAD. See AGENTS.md "Screenshot goldens".
#
#   drop-identical-goldens.sh [--require-change]
#
# Keeps new and removed goldens, and every changed golden whose dimensions or decoded RGBA pixels differ from HEAD's;
# restores the others to HEAD. Logs how many were skipped, adds that to the step summary, and sets GITHUB_ENV
# GOLDEN_REENCODES_SKIPPED (for the pull request comment) and GITHUB_OUTPUT remaining=true|false. With --require-change
# it fails when no pixel change remains, the way the update job fails when the goldens are unchanged.
set -euo pipefail

goldens=app/src/test/screenshots
require_change=false
if [ "${1:-}" = "--require-change" ]; then require_change=true; fi

for tool in git identify convert cmp; do
  if ! command -v "$tool" > /dev/null; then
    echo "::error::$tool is needed to compare goldens by pixels." >&2
    exit 2
  fi
done

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT

# Pixel equality: same dimensions and the same decoded 8-bit RGBA samples. `[0]` selects the first frame.
same_pixels() {
  local old="$1" new="$2" old_size new_size
  old_size="$(identify -format '%wx%h' "${old}[0]")" || { echo "::error::Cannot read $old" >&2; exit 1; }
  new_size="$(identify -format '%wx%h' "${new}[0]")" || { echo "::error::Cannot read $new" >&2; exit 1; }
  [ "$old_size" = "$new_size" ] || return 1
  convert "${old}[0]" -depth 8 "RGBA:$scratch/old.rgba" || { echo "::error::Cannot decode $old" >&2; exit 1; }
  convert "${new}[0]" -depth 8 "RGBA:$scratch/new.rgba" || { echo "::error::Cannot decode $new" >&2; exit 1; }
  cmp -s "$scratch/old.rgba" "$scratch/new.rgba"
}

skipped=0
kept=0
# -z: NUL-separated "status" and "path" records, so paths with spaces or newlines are safe.
while IFS= read -r -d '' status && IFS= read -r -d '' path; do
  if [ "$status" = M ]; then
    git show "HEAD:$path" > "$scratch/committed.png"
    if same_pixels "$scratch/committed.png" "$path"; then
      git restore --source=HEAD --staged --worktree -- "$path"
      echo "Skipped re-encoded golden with unchanged pixels: $path"
      skipped=$((skipped + 1))
      continue
    fi
  fi
  kept=$((kept + 1))
done < <(git diff --cached --name-status -z --no-renames -- "$goldens")

echo "Goldens: skipped $skipped re-encoded image(s) with unchanged pixels; kept $kept (pixel changes, new and removed)."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "Skipped $skipped re-encoded golden(s) whose pixels did not change; kept $kept (pixel changes, new and removed)." >> "$GITHUB_STEP_SUMMARY"
fi
if [ -n "${GITHUB_ENV:-}" ]; then echo "GOLDEN_REENCODES_SKIPPED=$skipped" >> "$GITHUB_ENV"; fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  if [ "$kept" -gt 0 ]; then echo "remaining=true" >> "$GITHUB_OUTPUT"; else echo "remaining=false" >> "$GITHUB_OUTPUT"; fi
fi

if [ "$kept" -eq 0 ] && [ "$require_change" = true ]; then
  echo "::error::Recorded goldens have the same pixels as the pull request head's (only $skipped re-encoded), yet this" \
    "run's screenshots differed. The merge with the base branch changed how a screen looks, or rendering is not" \
    "deterministic. Update the branch and push again."
  exit 1
fi
