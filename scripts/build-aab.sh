#!/usr/bin/env bash
set -euo pipefail

# Run from any directory. Keep this script in the project (or pass --project-dir).
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="${ANDROID_PROJECT_DIR:-$(cd -- "$script_dir/.." && pwd)}"

gradle_task="${AAB_TASK:-${1:-:app:bundleRelease}}"
if [[ $# -gt 0 && -z "${AAB_TASK:-}" ]]; then
  shift
fi

if [[ ! -f "$project_dir/gradlew" ]]; then
  echo "Gradle wrapper not found: $project_dir/gradlew" >&2
  echo "Set ANDROID_PROJECT_DIR to the Android project root." >&2
  exit 2
fi

cd "$project_dir"
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.buildozer/android/platform/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.buildozer/android/platform/android-sdk"
fi

exec bash ./gradlew "$gradle_task" --console=plain "$@"
