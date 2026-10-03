# Runtime verification — 2026-09-24

The version **1.1.0, version code 2** debug application passed **40 Android instrumentation tests** on the official Android 16 / API 36 x86_64 16 KB system image. `getconf PAGE_SIZE` returned `16384`. The complete recorded run took **40.743 seconds**; see `instrumentation-native.log` and the exact APK SHA-256 values in `instrumentation-debug-artifacts.json`. Additional conservative native-text audit fixes and regressions are pending a rerun; these results describe the recorded APKs.

- 12 PDF engine tests: real annotations/Unicode, rotated and cropped coordinates, forms, page operations, permanent redaction/correction, signed-document edit refusal, password protection, metadata removal, image orientation, compression, and bundled native OCR producing searchable output.
- 9 native-text editor tests: actual text removal with font/color/vector/image/form preservation; Tj/TJ positioning and kerning; character/word spacing and horizontal scaling; leading offsets and deletion; available/missing subset glyphs; width/stale-selection guards; crop/rotation coordinates; shared-stream isolation; and signed/encrypted refusal.
- 11 document storage tests: safe imports, rejected input cleanup, revision retention, immutable exports, trash/recovery, and original-byte preservation.
- 4 form draft tests: persistence and recovery, revision isolation, and carrying unchanged pending answers across edits while respecting saved/flattened forms.
- 4 Compose UI tests: a complete practice-document text/form/page/export flow; cold ACTION_VIEW import with Activity recreation; expired-trial edit refusal while existing work exports and original recovery remains available; and native Edit text selection, oversized-edit refusal with draft retention, successful replacement, form preservation, and reanalysis/export.

Historical version 1.0.0 runs passed 30 instrumentation tests in 49.863 seconds, including an independent three-test UI run in 35.543 seconds (`instrumentation-ui.log`). An earlier version 1.0 run exposed a creation snackbar intercepting a toolbar tap. That was fixed without adding an artificial waiting workaround. These historical logs are not the version 1.1.0 native-edit run.

The current pure JVM billing/trial suite passed **15 tests**, with zero failures/errors/skips: 7 trial policy, 6 purchase policy, and 2 RSA signature-verification tests. The XML reports are timestamped September 24, 2026, 15:32:17–18 UTC. Reports remain under `app/build/reports/tests/testDebugUnitTest/`. `build-native-debug.log` records `BUILD SUCCESSFUL`, the debug/test APK builds, JVM tests, and debug lint. There are **55 passing automated tests** in the recorded version 1.1.0 run.

## Scope of optimized-build verification

These 40 white-box instrumentation tests target **debug**, not the optimized validation build. The historical attempted minified instrumentation harness failed before tests: R8 changed shared Kotlin method parameter order and app constructor types, while a separately compiled test APK could only apply renamed symbols. The failure is recorded in `instrumentation-validation-initial-crash.log`. No broad keep rules or disabled optimization were added to production to make that harness run.

The validation APK retains the same minification/resource shrinking rules as release and uses an isolated application ID plus local debug signing. The existing black-box launch/edit/export walkthrough and screenshots are explicitly **version 1.0.0 historical evidence** until a version 1.1.0 walkthrough is recorded. Do not describe the 40-test debug result as a minified-instrumentation pass.

This emulator verifies x86_64 runtime behavior and 16 KB native loading. It does not establish compatibility with every physical ARM64 device, validate purchases with a real Play account, or constitute Play Store approval. Real license-tester purchase/restore/refund testing remains part of publisher setup in `release/BILLING.md`.
