package com.sumclartech.paperlane.billing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchasePolicyTest {
    private val receipt = PurchasePolicy.Receipt(
        packageName = "com.sumclartech.pdfix",
        products = listOf("pdfix_lifetime"),
        token = "play-issued-token",
        status = PurchasePolicy.Status.PURCHASED,
    )

    private fun accepts(record: PurchasePolicy.Receipt = receipt, verified: Boolean = true) =
        PurchasePolicy.canGrant(record, "com.sumclartech.pdfix", "pdfix_lifetime", verified)

    @Test fun verifiedPurchasedLifetimeUnlocks() = assertTrue(accepts())

    @Test fun pendingPurchaseNeverUnlocksEvenWhenSigned() {
        assertFalse(accepts(receipt.copy(status = PurchasePolicy.Status.PENDING)))
        assertFalse(accepts(receipt.copy(status = PurchasePolicy.Status.UNSPECIFIED)))
    }

    @Test fun unsignedOrMismatchedReceiptsFailClosed() {
        assertFalse(accepts(verified = false))
        assertFalse(accepts(receipt.copy(packageName = "another.app")))
        assertFalse(accepts(receipt.copy(products = listOf("another_product"))))
        assertFalse(accepts(receipt.copy(token = "")))
    }

    @Test fun networkFailurePreservesVerifiedOfflinePurchase() {
        assertTrue(PurchasePolicy.reconcileInventory(true, false, false))
        assertFalse(PurchasePolicy.reconcileInventory(false, false, false))
    }

    @Test fun successfulEmptyInventoryRevokesRefundedOrRemovedPurchase() {
        assertFalse(PurchasePolicy.reconcileInventory(true, true, false))
    }

    @Test fun successfulInventoryRestoresVerifiedPurchase() {
        assertTrue(PurchasePolicy.reconcileInventory(false, true, true))
    }
}
