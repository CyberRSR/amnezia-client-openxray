package org.amnezia.vpn.protocol.awg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WwgRetryPolicyTest {

    @Test
    fun backoffIsCappedAndResettable() {
        val policy = WwgRetryPolicy(randomLong = { _, _ -> 0L })

        val delays = (1..6).map { policy.nextDelay() }

        assertEquals(listOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 120_000L),
            delays.map { it.delayMs })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), delays.map { it.attempt })
        assertEquals(6, policy.currentAttempt)
        assertTrue(policy.reset())
        assertEquals(0, policy.currentAttempt)
        assertFalse(policy.reset())
        assertEquals(1, policy.nextDelay().attempt)
    }

    @Test
    fun jitterStaysInsideConfiguredBounds() {
        val lowerBound = WwgRetryPolicy(
            backoffMs = longArrayOf(10_000L),
            jitterPercent = 10,
            randomLong = { from, _ -> from },
        )
        val upperBound = WwgRetryPolicy(
            backoffMs = longArrayOf(10_000L),
            jitterPercent = 10,
            randomLong = { _, until -> until - 1 },
        )

        assertEquals(9_000L, lowerBound.nextDelay().delayMs)
        assertEquals(11_000L, upperBound.nextDelay().delayMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBackoffIsRejected() {
        WwgRetryPolicy(backoffMs = longArrayOf())
    }
}
