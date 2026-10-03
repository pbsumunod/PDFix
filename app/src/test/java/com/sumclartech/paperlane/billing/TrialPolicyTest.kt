package com.sumclartech.paperlane.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrialPolicyTest {
    private val start = 1_750_000_000_000L
    private val day = TrialPolicy.DAY_MILLIS
    private fun clock(wall: Long = start, elapsed: Long = 0L, boot: Int = 7) =
        TrialPolicy.Clock(wall, elapsed, boot)

    @Test fun firstLaunchReceivesThirtyFullDays() {
        val trial = TrialPolicy.evaluate(null, clock())
        assertEquals(30, trial.daysRemaining)
        assertFalse(trial.expired)
        assertEquals(30 * day, trial.remainingMillis)
    }

    @Test fun lastMillisecondStillBelongsToTrialButExactDeadlineDoesNot() {
        val original = TrialPolicy.evaluate(null, clock()).snapshot
        val before = TrialPolicy.evaluate(original, clock(start + 30 * day - 1))
        val at = TrialPolicy.evaluate(original, clock(start + 30 * day))
        assertEquals(1, before.daysRemaining)
        assertEquals(1L, before.remainingMillis)
        assertTrue(at.expired)
        assertEquals(0, at.daysRemaining)
    }

    @Test fun rollingWallClockBackCannotRecoverUsedDays() {
        val original = TrialPolicy.evaluate(null, clock()).snapshot
        val twentyDaysLater = TrialPolicy.evaluate(original, clock(start + 20 * day, 20 * day))
        val rolledBack = TrialPolicy.evaluate(twentyDaysLater.snapshot, clock(start, 21 * day))
        assertEquals(9, rolledBack.daysRemaining)
    }

    @Test fun unchangedWallClockStillExpiresUsingMonotonicTime() {
        val original = TrialPolicy.evaluate(null, clock()).snapshot
        assertTrue(TrialPolicy.evaluate(original, clock(start, 30 * day)).expired)
    }

    @Test fun rebootDoesNotCompareUnrelatedUptimeReadings() {
        val original = TrialPolicy.evaluate(null, clock(elapsed = day)).snapshot
        val nextBoot = TrialPolicy.evaluate(original, clock(start + day, 20 * day, boot = 8))
        assertEquals(29, nextBoot.daysRemaining)
    }

    @Test fun expiryStaysExpiredAfterClockRollbackAndReboot() {
        val original = TrialPolicy.evaluate(null, clock()).snapshot
        val expired = TrialPolicy.evaluate(original, clock(start + 31 * day, 31 * day))
        assertTrue(TrialPolicy.evaluate(expired.snapshot, clock(start - day, 0L, boot = 8)).expired)
    }

    @Test fun unknownBootCounterUsesPersistedWallHighWater() {
        val original = TrialPolicy.evaluate(null, clock(boot = -1)).snapshot
        val advanced = TrialPolicy.evaluate(original, clock(start + 2 * day, day, boot = -1))
        val rollback = TrialPolicy.evaluate(advanced.snapshot, clock(start, 25 * day, boot = -1))
        assertEquals(28, rollback.daysRemaining)
    }
}
