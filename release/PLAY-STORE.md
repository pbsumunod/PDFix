# Publish PDFix on Google Play

This guide prepares a release for the publisher to review and upload. It does not publish the app or create charges. The final app name is **PDFix**, package `com.sumclartech.pdfix`; the one-time price remains the publisher's choice. Requirements below were checked against Google's documentation on September 24, 2026. Recheck Console prompts at submission.

## 1. Complete the publisher configuration

Copy `publisher.properties.example` to `publisher.properties` in the project root. Confirm the support email, host `release/privacy-policy.html` on a public HTTPS page, and set `privacyUrl` to that page. The privacy URL must work without sign-in.

In Play Console, create the app and complete the developer/account verification, payments profile, and app setup shown in the dashboard. Keep `playProductConfigured=false` initially. Google's [billing setup guide](https://developer.android.com/google/play/billing/getting-ready) requires publishing a billing-enabled version to a track, including internal testing, before configuring products. The bootstrap sequence below handles that first upload without pretending a paid product is already configured.

When the app's Base64 licensing/in-app billing RSA public key is available in Console, set `playBillingPublicKey`. This public key is safe to embed in the app; it is not the upload keystore password or a service-account credential. No price should be hardcoded in app marketing or code. See [BILLING.md](BILLING.md) for the purchase setup and test cases.

## 2. Create and protect the upload key

Use your existing registered upload key for an existing app. For a new app, use Android Studio's signed-bundle wizard or create an upload keystore with JDK `keytool`. From the project directory, this command prompts for passwords; no password is included in the command or shell history:

```powershell
keytool -genkeypair -v -keystore upload-keystore.jks -storetype JKS -keyalg RSA -keysize 3072 -validity 10000 -alias upload
```

Enter real publisher certificate details. Keep an encrypted backup of the keystore and its passwords separately. Never commit or share private keys, password files, or account credentials. The project ignores `*.jks`, `*.keystore`, and `signing.properties`.

For the current Gradle configuration, copy `signing.properties.example` to `signing.properties`, point `storeFile` at the upload keystore, set `keyAlias=upload`, and provide the correct signing passwords privately. This local file contains secrets: do not include it in a source ZIP, screenshots, logs, or support messages. The sample contains no real passwords. For CI, inject the configuration from your secret manager and remove it afterward.

When a new app uses Play App Signing, Google manages the distribution signing key and your upload key authorizes submitted bundles. Review Google's [app-signing guide](https://developer.android.com/studio/publish/app-signing) before selecting or changing keys.

## 3. Make the first internal upload, then configure billing

For a new Console app, configure the owner-controlled upload keystore and HTTPS privacy URL first. Run `Build First Play Upload.cmd` or:

```powershell
.\scripts\build.ps1 -Mode bootstrap
```

For WSL/Linux, run `bash scripts/build.sh bootstrap`. Upload `app/build/outputs/bundle/bootstrap/app-bootstrap.aab` to **internal testing**, enable Play App Signing as prompted, and publish that test release. This build uses the real package `com.sumclartech.pdfix` and the owner's upload key, but deliberately embeds empty billing product/key settings so checkout stays disabled. Its version name ends in `-setup`. It is solely for initial Console setup: never promote it to production.

After Console enables billing features, configure and activate a **one-time product**, ID `pdfix_lifetime`, with a permanent Buy purchase option, your chosen countries, and your chosen price. Do not create a subscription or rental. Set the real public billing verification key and `playProductConfigured=true` in `publisher.properties` only after this setup is complete.

After a bootstrap upload, set `versionCode` in `release-version.properties` to a value higher than every previously uploaded bundle. Keep increasing it for each new uploaded build. Set `versionName` to your public release label. The bootstrap and release variants share a package, so their uploaded version codes must not collide.

## 4. Build the configured release

From Windows with JDK 17 and Android SDK 36 configured:

```powershell
.\scripts\build.ps1 -Mode check
.\scripts\build.ps1 -Mode play
```

From a configured WSL/Linux checkout:

```bash
bash scripts/build.sh check
bash scripts/build.sh play
```

Upload `app/build/outputs/bundle/release/app-release.aab` only after release configuration and validation pass. The release build requires publisher configuration and the owner-controlled upload key. Keep `release-version.properties`'s `versionCode` higher than every prior uploaded version. Preserve the source revision, test results, bundle hash, and signing certificate fingerprint with your release record.

The `debug` and `validation` builds use different application IDs and a debug key. In particular, `app-validation.aab` is a local test artifact, not a production upload. An AAB cannot be directly installed with ADB; use the APK variant for local installation or install a Play-distributed build for billing tests.

## 5. Prepare the listing and declarations

- Review `store-listing.md` against the final UI; remove anything not implemented and tested. Current supported input limits are 80 MiB and 250 pages.
- Upload `artwork/play-icon-512.png` and `artwork/feature-graphic-1024x500.png`. The editable SVG sources are included. The icon is a full square 512×512 PNG; the feature graphic is a 1024×500 RGB PNG without transparency, following Google's [icon requirements](https://developer.android.com/distribute/google-play/resources/icon-design-specifications) and [preview asset guidance](https://support.google.com/googleplay/android-developer/answer/9866151).
- Add screenshots from the real release candidate, including the editor, form flow, export preview, and trial/unlock terms. Artwork is not a substitute for genuine app screenshots.
- Complete content rating, target audience, app access, advertising declaration, and every other required Console questionnaire truthfully. This app does not include ads or an app login. Choose your actual intended audience rather than an assumed age range.
- Complete Data safety from the final dependencies and data flows. Documents are processed locally, but Google ML Kit collects technical diagnostics/usage information and Google Play handles purchases. Do not answer “no data collected” solely because PDFs stay local. Consult [ML Kit's disclosure guidance](https://developers.google.com/ml-kit/android-data-disclosure), the [privacy page](privacy-policy.html), and the merged release manifest.
- Confirm the HTTPS privacy URL and support email match the values bundled into the release.
- Resolve actual Console compatibility, target API, native-library, and pre-launch findings before rollout. The project targets API 36; a target number alone does not establish that every release requirement has passed.

## 6. Test through Google Play

Create an internal-testing release using the correctly signed production-package bundle. Add license testers and distribute the test opt-in link yourself. Install using the same Google account enrolled in the test. Verify localized pricing and successful, cancelled, pending, restored, and revoked purchases. A local debug APK does not prove real billing works.

On physical devices, verify import, scan, forms, edits, history restoration, crop/rotation, permanent deletion, offline use, and export in another PDF reader. Review all pages of a protected, corrected, redacted, or compressed export. Check low-storage failures and app interruption. Confirm that trial expiry still permits reading, recovery, and exporting existing work. See [PRODUCT.md](PRODUCT.md) and [BILLING.md](BILLING.md) for the acceptance lists.

Some newer personal developer accounts must complete a closed test with at least 12 continuously opted-in testers for 14 days before applying for production access. This requirement depends on the account and is separate from internal testing; follow the account dashboard and Google's [current testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).

## 7. Publisher review and rollout

After required testing and production access are complete, create the production release, select distribution countries, supply honest release notes, review the summary, and submit using the publisher's account. Address any review feedback. For an update, use a staged rollout when available and inspect stability before increasing availability.

No production-signing key, Play Console credentials, real payment test, or publication is supplied by this source project. The local trial is installation-based and can restart after clearing app data or reinstalling. The app has no server that enforces one trial per person; do not claim otherwise.
