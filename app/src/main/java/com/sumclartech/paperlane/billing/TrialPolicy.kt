package com.sumclartech.paperlane.billing

/** A local, offline trial clock. This does not identify users across installs. */
object TrialPolicy {
    const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    const val DURATION_MILLIS = 30L * DAY_MILLIS

    data class Clock(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int)

    data class Snapshot(
        val startedWallMillis: Long,
        val effectiveWallMillis: Long,
        val lastElapsedMillis: Long,
        val bootCount: Int,
    )

    data class Result(val snapshot: Snapshot, val remainingMillis: Long) {
        val expired: Boolean get() = remainingMillis == 0L
        val daysRemaining: Int
            get() = if (expired) 0 else ((remainingMillis - 1L) / DAY_MILLIS + 1L).toInt()
    }

    fun evaluate(previous: Snapshot?, now: Clock): Result {
        val wall = now.wallMillis.coerceAtLeast(0L)
        val elapsed = now.elapsedMillis.coerceAtLeast(0L)
        if (previous == null) {
            return Result(Snapshot(wall, wall, elapsed, now.bootCount), DURATION_MILLIS)
        }
        // Elapsed realtime keeps the trial moving when the wall clock is rolled back.
        // Only compare monotonic readings when Android confirms the same boot.
        val sameBoot = now.bootCount >= 0 && now.bootCount == previous.bootCount
        val advance = if (sameBoot && elapsed >= previous.lastElapsedMillis) {
            elapsed - previous.lastElapsedMillis
        } else 0L
        val monotonicWall = saturatedAdd(previous.effectiveWallMillis, advance)
        val effectiveWall = maxOf(wall, previous.effectiveWallMillis, monotonicWall)
        val start = previous.startedWallMillis.coerceAtLeast(0L)
        val used = (effectiveWall - start).coerceAtLeast(0L)
        val remaining = (DURATION_MILLIS - used).coerceIn(0L, DURATION_MILLIS)
        return Result(Snapshot(start, effectiveWall, elapsed, now.bootCount), remaining)
    }

    private fun saturatedAdd(value: Long, advance: Long): Long =
        if (value > Long.MAX_VALUE - advance) Long.MAX_VALUE else value + advance
}
