# Headless Android verification device

Prepared on 2026-09-24 in WSL Ubuntu 22.04 using the official Android SDK packages:

- Android Emulator 37.1.11 (build 15917651).
- `system-images;android-36;google_apis_ps16k;x86_64`, revision 7.
- AVD `pdf_editor_ez_api36_16kb` in `/tmp/pdf-editor-ez-emulator/avd`.
- ADB serial `emulator-5580`, display 720 × 1280 at 320 dpi, two virtual CPU cores, 2 GB guest RAM.
- No emulator window, no audio, software graphics, cold boot, emulator usage metrics disabled.

`environment.json` records the successful boot check: Android 16 / API 36, x86_64, and `getconf PAGE_SIZE` returning **16384**. This is an emulator test environment; it does not establish behavior on every physical ARM64 device or validate real Google Play purchases.

The emulator process runs as WSL root only to access the existing `/dev/kvm`; no user groups or device permissions were changed. Ordinary WSL-user ADB commands work. No physical phone settings were changed. The SDK was installed under `/home/mikasa910x/.buildozer/android/platform/android-sdk`.

`launch.json` records the executable, arguments, and process identifier. `avd-config.ini` records the device configuration. The old SDK manager generated a duplicated relative system-image path, which was corrected to the installed image's absolute path. `emulator.log` retains that first failed attempt followed by the successful boot. The SDK installer also downloaded the emulator twice while resolving its image dependency; installation completed successfully.

Always specify the emulator serial so commands do not target a connected phone:

```bash
/home/mikasa910x/.buildozer/android/platform/android-sdk/platform-tools/adb -s emulator-5580 shell getconf PAGE_SIZE
```

After all runtime tests and screenshots finish, release its memory with:

```bash
/home/mikasa910x/.buildozer/android/platform/android-sdk/platform-tools/adb -s emulator-5580 emu kill
```

Official guidance: [Android 16 KB page-size testing](https://developer.android.com/guide/practices/page-sizes), [headless emulator command-line options](https://developer.android.com/studio/run/emulator-commandline).
