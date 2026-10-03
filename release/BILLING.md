# PDFix — trial and lifetime purchase

## Product contract

- 30 calendar days of editing from the first launch on this installation, with no account or card required.
- After that, **one voluntary payment** unlocks editing permanently for the purchasing Google Play account. This is a non-consumable one-time product, not a subscription. No automatic charge when the trial ends; no recurring fees; no ads.
- Reading and exporting existing documents remain available when the trial ends. Gate editing at the operation boundary as well as at the entry button; never prevent a user from recovering or exporting their existing work.
- Display the localized price returned by Google Play and label the action “Unlock forever”. Do not substitute an invented price when Play cannot supply one.

## Before charging real users

1. Create the app in Play Console with package `com.sumclartech.pdfix`, complete the payments profile, and upload a signed App Bundle to an internal testing track.
2. Create and activate the **one-time product** `pdfix_lifetime`. Configure a permanent **Buy** purchase option, available regions, and your chosen price. Do not create a subscription or rental. The app does not consume this purchase.
3. Copy this app's base64 **licensing/in-app billing RSA public key** from Play Console to `playBillingPublicKey` in the local `publisher.properties` file. This is a public verification key, not the upload signing certificate or a service-account credential. The root Gradle build maps it to `BuildConfig.PLAY_BILLING_PUBLIC_KEY`.
4. Confirm `BuildConfig.PLAY_PRODUCT_ID` matches `pdfix_lifetime` exactly. A blank or malformed verification key disables purchase checkout and never grants paid entitlement. The free trial remains usable.
5. Add license testers in Play Console, enroll in the internal track, and install the Play-distributed build using the tester's purchasing Google account. Product visibility can take time after activation.
6. Run every scenario below against the actual Play build before production. A successful local APK build or JVM test does not validate your Console configuration or real purchases.

## Required device checks

| Scenario | Expected behavior |
| --- | --- |
| Fresh install with no internet | Editing works during the 30-day trial; no invented price or purchase success |
| Trial deadline reached | Editing locks; documents remain readable and existing work remains exportable |
| Wall clock rolled backward during the same boot | Trial continues to elapse using monotonic time |
| Successful test payment | Verified lifetime access, success message, purchase acknowledged |
| User cancels checkout | No paid entitlement granted |
| Pending payment | Pending state shown; pending transaction grants no paid entitlement |
| Pending payment completes while app closed | Resume/restore discovers, verifies, grants, and acknowledges purchase |
| Pending payment cancelled | Successful inventory clears pending state |
| Interrupted purchase/acknowledgement | Resume/restore recovers the purchase and retries acknowledgement |
| Relaunch offline after valid purchase | Cached signed receipt preserves access |
| Reinstall or second device, same Play account | Restore purchases recovers lifetime access while online |
| Different Google Play account with no purchase | Successful empty inventory removes prior cached lifetime access |
| Refund/revoke in Play Console | Next successful Play inventory removes the entitlement if Play no longer reports it |
| Inventory/network error | Previously verified entitlement remains available |
| Invalid signature, wrong product, wrong app package | Paid entitlement is not granted |

Use the pure JVM clock tests for the 30-day boundary instead of introducing a production trial reset or purchase bypass. Google Play license testers provide accelerated pending-payment and acknowledgement tests. Keep screenshots and results with the release record.

## Integration

Dependency: `com.android.billingclient:billing:8.3.0` plus Kotlin coroutines Android. This implementation uses Billing 8's product result object, one-time offer token, pending purchases, and service reconnection APIs.

Create one `BillingManager(applicationContext)` in an Activity-retained ViewModel. Collect `state` using lifecycle-aware Compose collection. Call `connect()` once, `refresh()` on resume, `buy(activity)` only in response to the purchase button, `restore()` in response to restore, and `close()` from `onCleared()`. Do not close/recreate it during a configuration change.

`state.isEntitled` means a verified lifetime purchase. `state.canEdit` includes the trial. `state.expired` means the trial ended and no lifetime purchase exists. `priceLabel` is null until Play supplies a valid price. `billingReady` requires a configured RSA key, a Play connection, and an available product. `pending` never implies ownership. `message` supplies short status/error text; `clearMessage()` dismisses it.

Call `canEditNow()` on the main thread at the start of each edit operation to recheck the exact local trial deadline. The UI state also updates every minute and on resume.

Initial Play connection failures stay quiet so offline document work is not interrupted. Explicit purchase/restore actions show connection errors.

`BillingManager` verifies the original purchase JSON with Play's RSA signature, checks app package/product/token and PURCHASED state, persists the signed receipt for offline use, and acknowledges verified unacknowledged purchases. It retries failed acknowledgement while open and checks again on resume/restore. A successful full inventory can revoke cached access; failed queries never revoke it. Purchase update callbacks cannot be overwritten by an older in-flight inventory response.

## Explicit limitations and future hardening

The trial is local and installation-based. Clearing app data or reinstalling can restart it. A persisted wall-clock high-water mark and Android's monotonic clock resist ordinary date rollback; they do not provide a tamper-proof identity or clock. A large forward clock change can expire the local trial permanently. If Android does not expose the boot counter, only wall-clock high-water protection is available across readings. Keep billing preferences excluded from backup/device transfer with the app's backup policy.

Client-side verification is vulnerable on modified/rooted clients and does not prove the current refund state while offline. A valid cached signed receipt can preserve access while offline until the next successful Play inventory. A refunded purchase may remain owned unless the developer also revokes it. This app does not run a purchase backend, real-time developer notifications, or account-level trial tracking. Do not market it as enforcing “one trial per person” or immediate offline refund revocation.

For stronger enforcement, add a secure backend that verifies purchase tokens with the Google Play Developer API, binds them to an authenticated app account, acknowledges purchases, and processes Real-time Developer Notifications and voided purchases. Keep all service-account credentials on that server. This requires an account/data-retention design and updated privacy disclosures. Server-side acknowledgement also avoids automatic refunds when a newly paid user stays offline beyond Play's acknowledgement deadline.

Google requires acknowledgement within three days after PURCHASED (the clock does not start while pending). This client acknowledges immediately after verification, but prolonged disconnection can prevent completion. Confirm successful acknowledgement in Play's testing tools before launch.

## Official references

- [Play Billing integration and acknowledgement](https://developer.android.com/google/play/billing/integrate)
- [Billing 8.3.0 release notes](https://developer.android.com/google/play/billing/release-notes#8-3-0)
- [One-time product purchase options and offers](https://developer.android.com/google/play/billing/one-time-product-multi-purchase-options-offers)
- [Testing Google Play Billing](https://developer.android.com/google/play/billing/test)
- [Purchase verification and abuse prevention](https://developer.android.com/google/play/billing/security)
- [One-time purchase lifecycle](https://developer.android.com/google/play/billing/lifecycle/one-time)
