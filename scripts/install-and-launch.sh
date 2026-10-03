#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PACKAGE="com.sumclartech.pdfix.debug"
ADB="${ADB:-adb}"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f local.properties ]]; then
  SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1)"
  SDK_DIR="${SDK_DIR//\\:/}"
  SDK_DIR="${SDK_DIR//\\\\/\\}"
fi
if [[ -z "$SDK_DIR" ]]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -d "$candidate" ]]; then
      SDK_DIR="$candidate"
      break
    fi
  done
fi
if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR" ]]; then
  echo "Error: Android SDK not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or add sdk.dir to local.properties." >&2
  exit 1
fi
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "Error: adb was not found. Install Android platform-tools or set ADB to its path." >&2
  exit 1
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  DEVICE_ARGS=(-s "$ANDROID_SERIAL")
else
  DEVICE_COUNT=0
  DEVICE_ID=""
  while IFS= read -r connected_device; do
    [[ -z "$connected_device" ]] && continue
    DEVICE_ID="$connected_device"
    DEVICE_COUNT=$((DEVICE_COUNT + 1))
  done < <("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }')

  if (( DEVICE_COUNT == 0 )); then
    echo "Error: no Android device is connected and authorized. Connect it and accept the USB debugging prompt." >&2
    exit 1
  elif (( DEVICE_COUNT > 1 )); then
    echo "Error: multiple Android devices are connected. Set ANDROID_SERIAL to the target device ID." >&2
    printf 'Connected device count: %s\n' "$DEVICE_COUNT" >&2
    exit 1
  fi
  DEVICE_ARGS=(-s "$DEVICE_ID")
fi

"$ADB" "${DEVICE_ARGS[@]}" get-state >/dev/null

bash ./gradlew :app:assembleDebug --no-daemon --console=plain

APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "Error: expected APK was not produced at $APK" >&2
  exit 1
fi

"$ADB" "${DEVICE_ARGS[@]}" install -r "$APK"
"$ADB" "${DEVICE_ARGS[@]}" shell am force-stop "$PACKAGE"
"$ADB" "${DEVICE_ARGS[@]}" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1

echo "Installed and launched $PACKAGE on $(${ADB} ${DEVICE_ARGS[*]} get-serialno)."
