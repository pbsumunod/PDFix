# PDF Editor EZ 1.1.0

`PDF-Editor-EZ-test.apk` is the optimized Android test build. Transfer it to an Android 8.0 or newer phone and open it to install. Android may ask you to allow installation from the app opening the APK.

## Try editing existing text

1. Open a PDF, or choose **Tools → Practice PDF** to use the built-in sample.
2. Choose **Edit text** in the editor's bottom toolbar. Tap an outlined text block.
3. Change the text and choose **Save text**. Clearing the whole block changes the button to **Delete text**.
4. Review the page. Use **History** to recover an earlier version, or prepare an export and choose **Preview every page** before saving or sharing it.

**Edit text** changes the original PDF text content for supported horizontal text blocks. It keeps the original font, size, color, and surrounding content without turning the document into images. A block may contain a whole line, depending on how the PDF was created. Replacement text must fit the original space and use characters available in the original font. The app refuses unsupported layouts, damaged or unsuitable fonts, missing characters, and replacements that are too wide. It does not reflow paragraphs, automatically shrink text, or silently substitute fonts. Scanned image text and some PDF text structures are unavailable for this tool.

The other two text tools have different purposes:

- **Add text** places new text on a page.
- **Replace area** rebuilds a selected visual area using a flattened copy and new text. This raster workflow can remove selectable original text and interactive fields across the document.

If an earlier version was flattened with **Correct**, **Replace area**, or redaction, open **History** and restore a version that still contains the original text, if one is available, before using **Edit text**. The original import remains available for recovery until the document is permanently deleted from Trash.

Supported inputs are limited to **80 MiB and 250 pages**. Password-protected files require an unlocked copy; signed PDFs cannot be edited. Always review the prepared file before sending it.

## Trial and source project

The app includes a 30-day editing trial. This local test build cannot take payments because the publisher's Google Play product and verification key have not been configured. Reading, recovering, and exporting existing documents remain available after the trial.

This build uses the separate package `com.sumclartech.pdfix.validation`. It does not replace the future Play Store app. Export any documents you want to keep before switching to the production app or uninstalling.

`PDF-Editor-EZ-source.zip` contains the Android Studio project, build launchers, store artwork, privacy policy, and publishing instructions. Extract it and open `PDF-Editor-EZ/README.md`. Follow `release/PLAY-STORE.md` to configure the upload key, privacy URL, permanent one-time product, and release bundle.

The test APK and validation bundle are not production Play uploads. Live Google Play purchasing, physical-device scanning, and the publisher's required testing still need to be completed before release. See `verification/TEST-REPORT.md` for the checks actually performed.
