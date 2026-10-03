# PDFix product scope

Product: PDFix. Android application ID: `com.sumclartech.pdfix`.

This document separates implemented foundations from release claims. A feature belongs in the store listing only after it is available in the UI and verified in the release build. Root integration owns the final shipped-feature checklist below.

## Product promise

Finish everyday paperwork on a phone, keep document processing on the device, and see exactly which file will be shared. Give users a 30-day editing trial, followed by an optional one-time Google Play unlock. Existing documents remain available for reading and exporting after the trial. No recurring subscription, automatic trial charge, app account, or advertising.

## Implemented document foundation

- Import a selected PDF into private app storage. Imported files are copies; external source files are unchanged.
- Validate PDF headers, readability, and engine compatibility before publishing an import or edited revision. Password-protected files require an unlocked copy. Maximum input size is 80 MiB, with up to 250 pages.
- Keep the untouched original and up to ten saved editing revisions. Switching a revision uses an atomic metadata update; generated files are flushed before publication.
- Support undo, history restoration, original restoration, recoverable trash, permanent deletion from Trash, and stable sharing copies. Interrupted permanent deletions resume from durable markers after restart.
- Save a local name/contact/address/company profile and the last viewed page for each document.
- Exclude private app data from Android backup and device transfer in the application configuration. Users must export files they want to keep before clearing app data or uninstalling.

Storage intentionally uses app-private copies. Editing an external PDF in place through a storage provider is a future workflow, not a present claim.

## Implemented UI scope: device verification required

The release candidate includes viewing, scan/import, editing supported existing text, new text placement, drawn signatures, baked vector marks, page operations, AcroForm questions with reusable local profile values and saved drafts, local OCR/searchable output, export previews, saved history, and transparent trial/purchase status. Verify each surfaced operation with representative files before publishing. Read displays extracted text with adjustable type size; it is not annotation-linked document reflow.

**Edit text** rewrites one original top-level PDF text-showing operation (`Tj` or `TJ`, often an entire line) using the same font resource, size, color, and text/graphics state. It preserves the starting baseline and the final text advance so following content stays in place. Empty replacement deletes the selected text. It does not overlay or rasterize the document; other vectors, images, and form fields remain in the PDF.

The native editor supports ordinary horizontal left-to-right Type 1, TrueType, and Type 0 text where the original font can encode the replacement. Existing subset-font character codes can be reused. The replacement must fit the original occupied width. It refuses overflow, damaged fonts, missing glyphs, stale selections, and unsupported formatting; there is no reflow, font substitution, automatic shrinking, or silent fallback. Text inside Form XObjects, Type 3 fonts, vertical/right-to-left or complex-script text, skewed/individually rotated text, invisible/clipping text, ActualText, optional-layer runs, and quote-show operators are not editable. A single `TJ` operation that places text in widely separated positions must be refused rather than collapsing its internal layout. Whole-page rotation and cropping are supported. Signed and encrypted documents cannot be edited. Runtime test results belong in the verification report, not in capability claims inferred from this implementation description.

**Add text** places new text onto the page without changing the underlying original text.

Replace area replaces a selected visual region by rasterizing the document and adding replacement text. It does not edit or reflow existing paragraphs. Saved marks are baked page content, not independently editable PDF comment objects. Redaction exports rasterized pages after removing selected pixels; compression can also create image-only output. Originals and history remain privately recoverable until permanent deletion, so share the prepared export rather than an earlier version.

The initial version does not provide unrestricted existing-paragraph editing, editing scanned text in its original layout, certificate-based signatures, PDF-to-Office conversion, automatic cloud synchronization, or accessible reflow with annotation anchoring. These require separate implementations and tests.

## Evidence informing priorities

These are qualitative user reports, not market-wide surveys or proof that a feature is unique.

| Signal | Source | Product response or research direction |
| --- | --- | --- |
| Users want annotations stored in ordinary PDFs that work with their existing folders. | [Android user request, May 24, 2024](https://www.reddit.com/r/androidapps/comments/1czwgcg/is_there_an_alternative_for_xodo_thats_able_to/) and [renewed request, June 4, 2026](https://www.reddit.com/r/androidapps/comments/1tx0676/xodo_alternatives_free_or_1_time_purchase/) | Portable PDFs and predictable exports first; storage-provider editing is future work. |
| Unexpected save paywalls interrupt urgent work. | [Android discussion, April 27, 2023](https://www.reddit.com/r/androidapps/comments/13125co/now_that_xodo_is_paid_whats_next_best_free_pdf/) | Show trial and purchase status before editing; preserve reading and export access. |
| Offline forms, no ads, one-time pricing, and correct display in other readers matter together. | [Detailed Android request, December 23, 2025](https://www.reddit.com/r/androidapps/comments/1ptjonv/offline_pdf_editor_free_or_onetime_purchase/) | Test fill/sign/export compatibility; keep processing local and payment terms clear. |
| Highlighting while text is enlarged is a concrete phone workflow. | [Reader request, April 23, 2026](https://www.reddit.com/r/androidapps/comments/1stah6s/pdf_readereditor_app_for_android/) | Validate comfortable phone zoom and selection; investigate reflow annotations later. |
| Screen readers can expose a PDF page as one long line. | [AppleVis report, September 21, 2024](https://www.applevis.com/forum/android/pdf-android-talkback) | Test TalkBack controls now; structured reading order and accessible reflow need further work. |

## Prioritized research after launch

1. **Guided form completion:** reuse a local profile, jump between fields, and preview the final recipient-visible PDF. Automatic field detection is already available in competitors; validate whether this combined workflow saves measurable effort.
2. **Safe sharing:** extend raster redaction with OCR-assisted discovery and verification, and investigate structural redaction that preserves unaffected vector content. A painted rectangle over an unchanged source must never be called redaction.
3. **Phone reading:** enlarge/reflow text while preserving useful highlights and original-page references; test TalkBack with real users.
4. **Exact size export:** let a user target an upload limit and inspect legibility before sharing. Do not promise every document can fit every limit without loss.
5. **Existing-folder editing:** clear conflict handling, provider permissions, stable source identity, and recoverable writes before enabling in-place updates.

## Privacy boundaries

Document processing is local. This is not the same as an app that never uses the network. Google Play Billing uses Google's services; the optional scanner can download components; ML Kit documents SDK diagnostics and usage information. The [privacy draft](privacy-policy.html) and Play Data safety answers must disclose those SDK behaviors. See Google's [ML Kit disclosure guidance](https://developers.google.com/ml-kit/android-data-disclosure), [bundled text-recognition model documentation](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), and [document scanner installation behavior](https://developers.google.com/ml-kit/vision/doc-scanner/android).

## Release acceptance

- UI actions match the final store listing, supported-file limits, and trial restrictions.
- Storage tests pass on a device or emulator, including interrupted commits and invalid generated files.
- Imported scans, ordinary PDFs, rotated pages, filled forms, and exported signatures display in a second independent reader.
- Native text edits remove the selected original text from extraction, preserve following text positions and unaffected content, and survive undo/reopening. Font-subset, overflow, stale-selection, rotation/crop, and unsupported-text cases must be verified; a refused edit must leave the previous revision intact.
- App restart, backgrounding, low space, cancellation, and sharing do not silently lose edits.
- Offline viewing/editing and the scanner's first-download behavior are tested separately.
- Play licensing testers verify pending, successful, cancelled, restored, revoked, and already-owned purchases.
- Publisher confirms support contact, hosts the privacy policy, completes accurate Data safety declarations, supplies store artwork, and signs the release through its own Play account.
