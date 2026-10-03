#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.buildozer/android/platform/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.buildozer/android/platform/android-sdk"
fi
case "${1:-debug}" in
  debug) bash ./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain ;;
  check) bash ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --console=plain ;;
  validation) bash ./gradlew :app:assembleValidation :app:bundleValidation :app:lintValidation --no-daemon --console=plain ;;
  play) bash ./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease --no-daemon --console=plain ;;
  bootstrap) bash ./gradlew :app:bundleBootstrap --no-daemon --console=plain ;;
  *) echo 'Usage: build.sh debug|check|validation|bootstrap|play'; exit 2 ;;
esac
