package com.sumclartech.paperlane.billing

data class BillingState(
    /** True only for a verified lifetime purchase, not for the free trial. */
    val isEntitled: Boolean = false,
    val canEdit: Boolean = true,
    val trialDaysRemaining: Int = 30,
    /** True only when editing requires a purchase: the trial expired and no purchase exists. */
    val expired: Boolean = false,
    /** Localized price from Play. Never display a hard-coded currency or fallback price. */
    val priceLabel: String? = null,
    val pending: Boolean = false,
    val billingReady: Boolean = false,
    val message: String? = null,
)
