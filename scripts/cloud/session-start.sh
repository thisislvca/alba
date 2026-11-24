#!/usr/bin/env bash
# SessionStart hook: in a Claude Code cloud session, makes sure the Android SDK is there and exports what
# Gradle needs, the same way CI sets it up. Does nothing on a developer's own machine.
set -euo pipefail

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

root="$(cd "$(dirname "$0")/../.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-/opt/android-sdk}"

if [ ! -d "$sdk/platforms/android-37.0" ]; then
  # The environment's setup script normally did this already; this covers environments without it.
  ANDROID_SDK_ROOT="$sdk" bash "$root/scripts/cloud/setup.sh" >&2
fi

java_home="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=\"$java_home\""
    echo "export ANDROID_HOME=\"$sdk\""
    echo "export ANDROID_SDK_ROOT=\"$sdk\""
    echo "export PATH=\"$sdk/platform-tools:\$PATH\""
    # As in CI: no Sentry upload or DSN in local builds.
    echo "export ALBA_SENTRY_DSN=''"
    echo "export ALBA_SENTRY_PROPERTIES=/tmp/alba-ci-no-sentry.properties"
  } >>"$CLAUDE_ENV_FILE"
fi

echo "Cloud session ready: JDK at $java_home, Android SDK at $sdk. Device tests run in CI, not here."
