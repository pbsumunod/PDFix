package com.sumclartech.paperlane.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.sumclartech.paperlane.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped Google Play one-time unlock. Own this in a ViewModel and call close in onCleared.
 * Connect once and refresh on Activity resume. No product is ever consumed or auto-renewed.
 */
class BillingManager(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("paperlane_billing", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val verifier = PlaySignatureVerifier(BuildConfig.PLAY_BILLING_PUBLIC_KEY)
    private val productId = BuildConfig.PLAY_PRODUCT_ID
    private var trial = updateTrial()
    private var owned = readCachedPurchase()?.let(::isVerifiedPurchase) == true
    private val mutableState = MutableStateFlow(
        BillingState(
            isEntitled = owned,
            canEdit = owned || !trial.expired,
            trialDaysRemaining = trial.daysRemaining,
            expired = !owned && trial.expired,
        ),
    )
    val state: StateFlow<BillingState> = mutableState.asStateFlow()

    private var closed = false
    private var connecting = false
    private var queryingInventory = false
    private var queryAgain = false
    private var restoreRequested = false
    private var queryingProduct = false
    private var inventoryEpoch = 0L
    private var productDetails: ProductDetails? = null
    private var selectedOffer: ProductDetails.OneTimePurchaseOfferDetails? = null
    private val acknowledgementsInFlight = mutableSetOf<String>()

    private val client = BillingClient.newBuilder(app)
        .setListener { result, purchases -> onMain { onPurchasesUpdated(result, purchases) } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        scope.launch {
            while (true) {
                delay(60_000L)
                publishTrial()
            }
        }
    }

    fun connect() = onMain {
        if (client.isReady) {
            refreshPlayData()
        } else if (!connecting) {
            connecting = true
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) = onMain {
                    connecting = false
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        refreshPlayData()
                    } else {
                        mutableState.value = mutableState.value.copy(
                            billingReady = false,
                            // Offline editing needs no Play connection. Surface a startup
                            // failure only after an explicit restore request.
                            message = if (restoreRequested) billingError(result.responseCode) else mutableState.value.message,
                        )
                    }
                }

                override fun onBillingServiceDisconnected() = onMain {
                    connecting = false
                    mutableState.value = mutableState.value.copy(billingReady = false)
                }
            })
        }
    }

    /** Recheck both trial expiry and Play ownership whenever the app returns to the foreground. */
    fun refresh() = onMain {
        publishTrial()
        connect()
    }

    /** Recheck the exact deadline at the edit boundary; call from the UI/main thread. */
    fun canEditNow(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call canEditNow on the main thread" }
        if (!closed) publishTrial()
        return mutableState.value.canEdit
    }

    fun restore() = onMain {
        restoreRequested = true
        mutableState.value = mutableState.value.copy(message = "Checking Google Play…")
        refresh()
    }

    fun buy(activity: Activity) = onMain {
        if (owned) {
            mutableState.value = mutableState.value.copy(message = "Lifetime access is already unlocked.")
            return@onMain
        }
        if (!verifier.isConfigured || productId.isBlank()) {
            mutableState.value = mutableState.value.copy(
                message = "Purchases are not available in this build.",
                billingReady = false,
            )
            return@onMain
        }
        if (mutableState.value.pending) {
            mutableState.value = mutableState.value.copy(message = "Your payment is still pending in Google Play.")
            return@onMain
        }
        val details = productDetails
        val offer = selectedOffer
        if (!client.isReady || details == null || offer == null) {
            mutableState.value = mutableState.value.copy(message = "Connect to Google Play, then try again.")
            connect()
            return@onMain
        }
        if (activity.isFinishing || activity.isDestroyed) return@onMain
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        // Legacy products may omit this value. Billing 8 rejects explicitly empty tokens.
        offer.offerToken?.takeIf { it.isNotBlank() }?.let(item::setOfferToken)
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item.build())).build(),
        )
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> mutableState.value = mutableState.value.copy(message = null)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> mutableState.value = mutableState.value.copy(message = billingError(result.responseCode))
        }
    }

    fun clearMessage() = onMain { mutableState.value = mutableState.value.copy(message = null) }

    fun close() = onMain {
        closed = true
        scope.cancel()
        client.endConnection()
    }

    private fun refreshPlayData() {
        queryProduct()
        queryInventory()
    }

    private fun queryProduct() {
        if (queryingProduct || productId.isBlank()) return
        queryingProduct = true
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, detailsResult ->
            onMain {
                queryingProduct = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetails = detailsResult.productDetailsList.firstOrNull { it.productId == productId }
                    // The Console product must have a permanent BUY option, never a rental.
                    selectedOffer = productDetails?.oneTimePurchaseOfferDetailsList
                        ?.firstOrNull { it.rentalDetails == null && it.offerId == null }
                        ?: productDetails?.oneTimePurchaseOfferDetails?.takeIf { it.rentalDetails == null }
                    mutableState.value = mutableState.value.copy(
                        priceLabel = selectedOffer?.formattedPrice,
                        billingReady = selectedOffer != null && verifier.isConfigured,
                    )
                } else {
                    productDetails = null
                    selectedOffer = null
                    mutableState.value = mutableState.value.copy(billingReady = false, priceLabel = null)
                }
            }
        }
    }

    private fun queryInventory() {
        if (queryingInventory) {
            queryAgain = true
            return
        }
        queryingInventory = true
        val epoch = inventoryEpoch
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            onMain {
                queryingInventory = false
                // A purchase callback may have granted a newer purchase while this query ran.
                if (epoch != inventoryEpoch) {
                    queryAgain = false
                    queryInventory()
                    return@onMain
                }
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    applyInventory(purchases)
                } else {
                    // Retain offline, signature-verified entitlement on all transport failures.
                    owned = PurchasePolicy.reconcileInventory(owned, false, false)
                    if (restoreRequested) {
                        mutableState.value = mutableState.value.copy(message = billingError(result.responseCode))
                    }
                    publishTrial()
                }
                restoreRequested = false
                if (queryAgain) {
                    queryAgain = false
                    queryInventory()
                }
            }
        }
    }

    private fun applyInventory(purchases: List<Purchase>) {
        val matching = purchases.filter { productId in it.products && it.packageName == app.packageName }
        val verified = matching.firstOrNull(::isVerifiedPurchase)
        val wasOwned = owned
        val wasPending = mutableState.value.pending
        owned = PurchasePolicy.reconcileInventory(owned, true, verified != null)
        val pending = matching.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        if (verified != null) {
            cachePurchase(verified)
            acknowledge(verified)
        } else {
            preferences.edit().remove("purchase_json").remove("purchase_signature").apply()
        }
        val invalid = matching.any {
            it.purchaseState == Purchase.PurchaseState.PURCHASED && !isVerifiedPurchase(it)
        }
        val message = when {
            verified != null && (restoreRequested || !wasOwned) -> "Lifetime access restored."
            pending -> "Payment pending. Editing unlocks when Google Play confirms payment."
            invalid -> "This purchase could not be verified. Please contact support."
            restoreRequested -> "No lifetime purchase found for this Google Play account."
            wasOwned && !owned -> "Google Play no longer reports an active lifetime purchase."
            wasPending && !pending -> "The pending payment did not complete. You can try again."
            else -> mutableState.value.message
        }
        mutableState.value = mutableState.value.copy(pending = pending, message = message)
        publishTrial()
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                inventoryEpoch++
                val matching = purchases.orEmpty().filter {
                    productId in it.products && it.packageName == app.packageName
                }
                val verified = matching.firstOrNull(::isVerifiedPurchase)
                when {
                    verified != null -> {
                        owned = true
                        cachePurchase(verified)
                        mutableState.value = mutableState.value.copy(
                            pending = false,
                            message = "Lifetime access unlocked. Thank you!",
                        )
                        acknowledge(verified)
                        publishTrial()
                    }
                    matching.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> {
                        mutableState.value = mutableState.value.copy(
                            pending = true,
                            message = "Payment pending. Editing unlocks when Google Play confirms payment.",
                        )
                    }
                    matching.isNotEmpty() -> mutableState.value = mutableState.value.copy(
                        message = "This purchase could not be verified. Please contact support.",
                    )
                    else -> queryInventory()
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                mutableState.value = mutableState.value.copy(message = "Purchase cancelled.")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> mutableState.value = mutableState.value.copy(message = billingError(result.responseCode))
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged || !acknowledgementsInFlight.add(purchase.purchaseToken)) return
        client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
        ) { result ->
            onMain {
                acknowledgementsInFlight.remove(purchase.purchaseToken)
                if (result.responseCode != BillingClient.BillingResponseCode.OK && owned) {
                    mutableState.value = mutableState.value.copy(
                        message = "Access unlocked. Reconnect soon so Google Play can finish confirming your purchase.",
                    )
                    // Retry while running; resume and restore also retry unacknowledged purchases.
                    scope.launch {
                        delay(30_000L)
                        if (client.isReady) queryInventory()
                    }
                }
            }
        }
    }

    private fun isVerifiedPurchase(purchase: Purchase): Boolean = PurchasePolicy.canGrant(
        receipt = PurchasePolicy.Receipt(
            packageName = purchase.packageName,
            products = purchase.products,
            token = purchase.purchaseToken,
            status = when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> PurchasePolicy.Status.PURCHASED
                Purchase.PurchaseState.PENDING -> PurchasePolicy.Status.PENDING
                else -> PurchasePolicy.Status.UNSPECIFIED
            },
        ),
        expectedPackage = app.packageName,
        expectedProduct = productId,
        signatureVerified = verifier.verify(purchase.originalJson, purchase.signature),
    )

    private fun cachePurchase(purchase: Purchase) {
        preferences.edit()
            .putString("purchase_json", purchase.originalJson)
            .putString("purchase_signature", purchase.signature)
            .apply()
    }

    private fun readCachedPurchase(): Purchase? = runCatching {
        val json = preferences.getString("purchase_json", null) ?: return null
        val signature = preferences.getString("purchase_signature", null) ?: return null
        Purchase(json, signature)
    }.getOrNull()

    private fun publishTrial() {
        trial = updateTrial()
        mutableState.value = mutableState.value.copy(
            isEntitled = owned,
            canEdit = owned || !trial.expired,
            trialDaysRemaining = trial.daysRemaining,
            expired = !owned && trial.expired,
        )
    }

    private fun updateTrial(): TrialPolicy.Result {
        val previous = if (preferences.contains("trial_start")) {
            TrialPolicy.Snapshot(
                startedWallMillis = preferences.getLong("trial_start", 0L),
                effectiveWallMillis = preferences.getLong("trial_high_water", 0L),
                lastElapsedMillis = preferences.getLong("trial_elapsed", 0L),
                bootCount = preferences.getInt("trial_boot", -1),
            )
        } else null
        val bootCount = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        val result = TrialPolicy.evaluate(
            previous,
            TrialPolicy.Clock(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount),
        )
        preferences.edit()
            .putLong("trial_start", result.snapshot.startedWallMillis)
            .putLong("trial_high_water", result.snapshot.effectiveWallMillis)
            .putLong("trial_elapsed", result.snapshot.lastElapsedMillis)
            .putInt("trial_boot", result.snapshot.bootCount)
            .apply()
        return result
    }

    private fun billingError(code: Int): String = when (code) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play payments are unavailable on this device."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "Lifetime access is not available in your Google Play region yet."
        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "Could not reach Google Play. Check your connection and try again."
        BillingClient.BillingResponseCode.USER_CANCELED -> "Purchase cancelled."
        else -> "Google Play could not complete this request. Please try again."
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!closed) action()
        } else {
            handler.post { if (!closed) action() }
        }
    }
}
