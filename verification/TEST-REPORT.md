# PDF Editor EZ verification

Checked September 24, 2026. Version 1.1.0, version code 2, minimum Android 8.0/API 26, target API 36. This is a local test delivery, not a published or production-signed Play release.

## Automated results

| Check | Result |
| --- | --- |
| JVM trial boundary and clock tests | 7 passed |
| JVM purchase-state tests | 6 passed |
| JVM RSA receipt-verification tests | 2 passed |
| Android PDF-engine tests | 12 passed |
| Android native existing-text editor tests | 9 passed |
| Android document-storage tests | 11 passed |
| Android form-draft tests | 4 passed |
| Android Activity/Compose integration tests | 4 passed |
| Android lint, debug | No errors |
| Optimized validation, version 1.1.0 | Verification pending |

The recorded Android suite completed with `OK (40 tests)` in 40.743 seconds; see `instrumentation-native.log`. It ran on an Android 16/API 36 x86_64 emulator with a verified 16,384-byte memory page size. The 15 JVM tests also passed, for 55 automated tests in this run. `build-native-debug.log` records the successful debug application/test build, JVM tests, and debug lint. Lint reports non-blocking dependency and library warnings; this is not a claim of zero warnings. Further conservative native-text audit fixes and regression tests require a subsequent run; this paragraph records the completed run, not those pending changes.

Coverage includes Unicode text and marks, rotated/cropped coordinates, page operations, forms, OCR/searchable output, metadata cleanup, password protection, target-size compression, removal of old content during redaction/correction, signed-document refusal, immutable originals, interrupted storage writes, revision retention, Trash, permanent deletion, and draft recovery across unrelated edits.

Native-text tests verify that old text is removed from page content while original fonts, sizes, color, vectors, images, and interactive forms remain; Tj/TJ continuation positions, character/word spacing, leading offsets, deletion, subset glyph availability, overflow/stale-selection refusal, page crop/rotation, signed/encrypted refusal, and shared-stream isolation are covered.

The four UI tests exercise immediate added-text placement, guided form answers, page rotation, PDF preview/export and FileProvider access; cold incoming-file import and Activity recreation; expired-trial edit blocking while export and original recovery remain available; and native Edit text selection, refusal of an oversized replacement without losing the draft, successful replacement, form preservation, and reanalysis/export.

Functional instrumentation runs against the debug variant. An attempted separately optimized white-box test harness encountered R8 ABI transformations; production optimization was retained, and no broad keep rules were added to make that harness work. The optimized validation APK is checked separately through the real interface and the artifact verifier. The test app has its own `.validation` package and a debug signing key.

## Build and artifact checks

The version 1.1.0 debug APK and instrumentation APK build successfully. Their exact hashes are recorded in `instrumentation-debug-artifacts.json`. The optimized version 1.1.0 APK/bundle and associated artifact reports are being regenerated separately; existing version 1.0 reports must not be presented as evidence for the new version.

Artifact checks cover API level, disabled backup/cleartext, private FileProvider configuration, absence of broad-storage/advertising permissions and known advertising SDK markers, and 64-bit ELF/ZIP 16 KB alignment. `artifact-debug.json` and `artifact-validation.json` identify the particular checked hashes and versions. A marker scan is supported by review of the declared dependencies; it is not a general security certification.

Reproduce the functional suite using the README's debug instrumentation instructions. The build launchers also expose `check` and `validation` modes. Full local build and instrumentation logs are retained in this directory in the working project; the portable source ZIP includes this summary, artifact reports, and screenshots.

## Historical optimized app walkthrough: version 1.0.0

The version 1.0.0 optimized app was cold-launched, used to add "Reviewed in EZ" to the sample PDF, complete three form fields, inspect page thumbnails, preview both export pages, and save the result through Android's actual Files picker. The saved PDF is 820,026 bytes. An independent parser, pypdf 6.19.0, confirmed two pages, the added text, and the expected name/email/answer values in the AcroForm fields. `optimized-export-check.json` records that historical result and output hash. The crash log remained empty during that walkthrough. This does not establish optimized runtime behavior of version 1.1.0 or its new native Edit text feature.

Seven numbered 1080×1920 screenshots show the real interface: desk, edited PDF, guided form, selected pages, prepared export, final-page preview, and trial settings. Earlier 720×1280 captures document the smaller-phone layout. The final wording polish changes the document count from "1 files" to "1 file"; it does not change the tested workflows.

## Before production

- Configure the publisher's upload signing key, hosted HTTPS privacy URL, support contact, and permanent one-time Play product and price. Follow `release/PLAY-STORE.md`, including the first internal upload and version-code increment.
- Test real Play purchases, pending/cancelled payments, acknowledgement, restoration, and revocation using license testers. Local tests do not establish that a Play product works.
- Test scanning and its first component download on physical phones, supported older Android versions, representative third-party PDFs/readers, accessibility settings, and low-storage/interruption behavior. One emulator does not cover all devices or documents.
- Complete the account's required testing, listing, Data safety, content rating, and Play review. No app has been uploaded or published by this project.

The local trial can restart after clearing app data or reinstalling. Correct/redact/compress can flatten content; existing-paragraph reflow and certificate signatures are not supplied. Documents must be exported before uninstalling or switching from the isolated test package to the Play app. These limits are also disclosed in the product and publishing documents.
