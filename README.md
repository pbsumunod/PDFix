# PDFix

A native Android PDF editor with local document processing, a 30-day editing trial, and an optional one-time Google Play unlock. The production application ID is `com.sumclartech.pdfix`. The source namespace remains `com.sumclartech.paperlane`.

## Open and test

Open this directory in Android Studio with JDK 17 and Android SDK 36 installed. The minimum Android version is 8.0/API 26. Dependencies and Gradle require an internet connection on the first build.

On Windows, run `Build Android App.cmd`, or from this directory:

```powershell
.\scripts\build.ps1 -Mode debug
.\scripts\build.ps1 -Mode check
```

`debug` runs JVM tests and builds the debug APK. `check` also runs Android lint and compiles the instrumentation test APK. Compiling device tests does not execute them. With an authorized phone or emulator connected, run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

When Java is unavailable on Windows, the build launcher tries the existing `Ubuntu-22.04` WSL distribution. From a configured Linux/WSL checkout:

```bash
bash scripts/build.sh debug
bash scripts/build.sh check
bash ./gradlew :app:connectedDebugAndroidTest --console=plain
```

Set `ANDROID_HOME` to the SDK directory if it is not already configured. The Linux helper recognizes the existing Buildozer SDK location when present. A phone visible to Windows ADB is not automatically visible to WSL ADB; use one ADB installation consistently or connect an Android emulator in that environment.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Use `scripts/Install on Phone.cmd` after enabling USB debugging and approving the phone's authorization prompt, or install the APK manually. The installer preserves existing app data and never uninstalls an app to resolve signing conflicts.

## Release candidate capabilities

The app provides PDF reading, page navigation, PDF/image import, document scanning, local OCR, editing of supported existing text, new text placement, drawn signatures, baked vector marks, page organization, form completion, local profile values, saved revisions, recoverable Trash, permanent deletion, and export preparation. See [PRODUCT.md](release/PRODUCT.md) for the detailed scope and validation checklist.

These distinctions matter:

- Imports are private working copies. Existing source files are unchanged.
- The limit is 80 MiB and 250 pages. Password-protected imports require an unlocked copy.
- Added marks become page content; they are not movable PDF comment objects after saving.
- Edit text replaces supported existing horizontal text while retaining its original font, size, color, and position. It edits the PDF's text content directly and preserves surrounding vectors, images, and form fields. Replacement text must fit the original space and use characters available in that font; unsupported layouts, damaged fonts, missing characters, and unsuitable replacements are refused. Paragraph reflow, automatic shrinking, and silent font substitution are not provided. Scanned image text and some PDF text structures are unavailable for this tool.
- Add text places new text onto a page without replacing its existing content.
- Replace area replaces a selected visual region using a flattened copy and replacement text. It does not reflow existing paragraphs.
- Read displays extracted text with adjustable type size. It does not anchor annotations to reflowed text.
- Drawn signatures are visual signatures, not digital certificates.
- Size-target compression and redaction use raster export; check the result before sharing. Do not infer that every target size preserves readable detail.
- The optional Google scanner may download components before first use. Bundled Latin-script OCR processes content on the device. Google SDK diagnostics are disclosed in the privacy policy.

The library keeps the untouched import and up to ten editing versions. Deleting from Trash permanently removes the private original, versions, reading position, and associated private sharing copies. Files previously exported to other locations remain there. Android backup/device transfer is disabled for private app data: export important documents before uninstalling or clearing storage.

To edit existing text, choose **Edit text**, tap an outlined block, then **Save text**. Clearing its contents offers **Delete text**. If a document was previously flattened with Replace area, compression, or redaction, restore a text-bearing version from History if one is available. Editing a supported block may replace a whole line because PDF text is stored in separate drawing operations.

## Trial and purchases

The 30-day trial starts on first launch of this installation. When it ends, reading, recovery, and exporting existing work remain available; creating edits requires the optional lifetime unlock. There is no automatic charge, subscription, or advertising.

The price is configured by the publisher in Google Play Console and displayed from Google's product information. The default one-time product ID is `pdfix_lifetime`. The trial is local: clearing app data or reinstalling can restart it. There is no server-enforced one-trial-per-person guarantee. See [BILLING.md](release/BILLING.md).

## Prepare for Google Play

Follow [PLAY-STORE.md](release/PLAY-STORE.md) for publisher configuration, upload signing, testing, and submission. No account publication is automated.

`validation` produces a minified, debug-signed test APK and bundle using an application ID ending in `.validation`. These artifacts are only for local validation and must not be uploaded as the production app. A real Play release requires the publisher's upload key, public billing verification key, hosted privacy URL, and configured one-time product.

For the first Console setup, `Build First Play Upload.cmd` or `scripts/build.ps1 -Mode bootstrap` creates an upload-key-signed bundle with the real production package and checkout deliberately disabled. It requires the owner's signing configuration and HTTPS privacy URL. Upload it only to internal testing to enable billing setup, then activate the product, configure the billing key, increase `versionCode` in `release-version.properties`, and build the normal release. Never promote the `-setup` bootstrap build to production. See the Play guide for the exact order.

```powershell
.\scripts\build.ps1 -Mode validation
.\scripts\build.ps1 -Mode play
```

The production bundle, after publisher configuration succeeds, is `app/build/outputs/bundle/release/app-release.aab`. An AAB is an upload format, not an APK to tap-install.

Store copy is in [store-listing.md](release/store-listing.md), the privacy page is [privacy-policy.html](release/privacy-policy.html), and editable artwork plus exported PNGs are in [release/artwork](release/artwork). Actual app captures are in [screenshots](verification/screenshots/README.md). See the [verification report](verification/TEST-REPORT.md) for the recorded version 1.1.0 results: 55 passing automated tests (40 Android and 15 JVM), pending audit reruns, the historical version 1.0 optimized-app walkthrough, and checks still required before production.

Third-party notices are bundled under `app/src/main/assets/licenses`. The PDF font asset is DejaVu Sans, even where its legacy filename says `Inter-Regular.ttf`.
