# PDFix — Google Play listing draft

Publisher must confirm this copy against the final release UI, support contact, and actual Play product configuration before publishing. Remove any feature not present and tested; do not present roadmap items as available features.

**App name:** PDFix

**Short description:** Scan, sign and organize PDFs locally. Try editing, then unlock once.

**Suggested category:** Productivity

**Suggested tags:** PDF, Productivity, Document editor, Scanner, Forms

**Release name:** 1.1.0 – PDF editing and forms

**Release notes:** Work with PDFs on your device: edit supported text, fill forms, sign, organize pages, scan documents, and preview exports. Includes a 30-day editing trial and optional one-time unlock.

**News or educational app:** No. PDFix is a productivity tool.

**Audience recommendation:** Intended for adults/general productivity use, not children. Confirm the intended audience and minor-access restriction in Play Console before selecting them.

**Support email:** sumclartech@gmail.com

**Privacy policy:** https://pdfix-legal.vercel.app/ (public HTTPS page; use this URL in Play Console and `publisher.properties`).

## Full description draft

Finish everyday PDF paperwork on your Android phone with PDFix.

Open a document, make the changes you need, and share a PDF copy. Document processing happens on your device, with no PDFix account required.

WORK WITH YOUR DOCUMENTS

• Import PDFs from Android's file picker or another app.
• Read documents and return to your last viewed page.
• Edit supported existing text while preserving its original font and surrounding content.
• Add text, annotations, and a drawn signature.
• Complete supported fillable forms with locally saved contact details and drafts.
• Organize pages and create a sharing copy.
• Scan paper documents and recognize printed Latin-script text on your device.
• Preview your prepared document before saving or sharing it.

KEEP CONTROL OF YOUR FILES

• Imported originals remain available for recovery.
• Saved revision history helps you undo changes.
• Deleted documents go to recoverable Trash.
• Reuse locally saved contact details when completing paperwork.
• Exported PDFs can be opened in other PDF readers.

TRY EDITING, THEN CHOOSE

Editing is available for a 30-day trial from your first launch on this installation. Afterward, you can choose a one-time Google Play purchase to unlock editing. There is no recurring subscription and the trial never charges you automatically. You can still read and export existing documents after the trial ends. The price is shown by Google Play before you buy.

No advertising. No required app account. Documents are processed locally. Google Play handles purchases; Google SDKs may send technical diagnostic and usage information as described in the privacy policy. The optional scanner may require an initial component download and a supported Google Play services device.

PLEASE NOTE

Edit text changes supported existing horizontal text directly, keeping its font and style without flattening the document. Replacement text must fit the original space and use characters available in the original font. Unsupported text or replacements are refused; paragraph reflow and automatic font substitution are not supported. Scanned image text cannot be edited this way.

Add text places new content. Marks become saved page content. Replace area replaces a selected region using a flattened copy and replacement text. Redaction and size-target compression also use raster output, which can remove selectable text and interactive fields. Preview all pages before sharing. A drawn signature is a visual signature, not a certificate-based digital signature. Password-protected PDFs require an unlocked copy. Supported imports are limited to 80 MiB and 250 pages.

Documents are stored as private app copies. Export files you want to keep before uninstalling or clearing app data. Automatic cloud synchronization is not included.

Questions or feedback: sumclartech@gmail.com

## Submission notes — not part of the listing

- Confirm every feature above with the actual UI; storage capabilities alone do not establish a user-visible feature.
- Complete Play Data safety using the merged dependency manifest and current SDK disclosures. Do not declare blanket “no data collected” solely because PDFs stay local; see [ML Kit disclosure guidance](https://developers.google.com/ml-kit/android-data-disclosure).
- Use screenshots from the actual release candidate. Demonstrate import, editing, preview/share, saved history, and the trial/purchase screen.
- Set the one-time product ID to `pdfix_lifetime` unless `publisher.properties` changes it consistently.
- See `BILLING.md` for trial, verification, restoration, and offline purchase behavior.
