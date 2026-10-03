package com.sumclartech.paperlane.billing

/** Purchase eligibility without Android dependencies, also used for cached receipts. */
object PurchasePolicy {
    enum class Status { PURCHASED, PENDING, UNSPECIFIED }

    data class Receipt(
        val packageName: String,
        val products: List<String>,
        val token: String,
        val status: Status,
    )

    fun canGrant(
        receipt: Receipt,
        expectedPackage: String,
        expectedProduct: String,
        signatureVerified: Boolean,
    ): Boolean = signatureVerified &&
        receipt.status == Status.PURCHASED &&
        receipt.packageName == expectedPackage &&
        expectedProduct.isNotBlank() && expectedProduct in receipt.products &&
        receipt.token.isNotBlank()

    /** A transport failure is never evidence of a refund. A successful inventory is. */
    fun reconcileInventory(
        previouslyEntitled: Boolean,
        querySucceeded: Boolean,
        containsVerifiedPurchase: Boolean,
    ): Boolean = if (querySucceeded) containsVerifiedPurchase else previouslyEntitled
}
