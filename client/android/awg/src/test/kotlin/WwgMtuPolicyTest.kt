package org.amnezia.vpn.protocol.awg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WwgMtuPolicyTest {

    @Test
    fun v2MtuIsNeverChanged() {
        val result = effectiveWwgMtus(1280, 1376, isV3 = false)

        assertEquals(1280, result.underlay)
        assertEquals(1376, result.overlay)
        assertFalse(result.adjusted)
    }

    @Test
    fun v3RaisesEntryMtuEnoughForNestedDatagram() {
        val result = effectiveWwgMtus(1280, 1280, isV3 = true, overlayS4 = 14)

        assertEquals(1374, result.underlay)
        assertEquals(1280, result.overlay)
        assertTrue(result.adjusted)
    }

    @Test
    fun v3PreservesAlreadySafeEntryAndExitMtus() {
        val result = effectiveWwgMtus(1380, 1280, isV3 = true, overlayS4 = 14)

        assertEquals(1380, result.underlay)
        assertEquals(1280, result.overlay)
        assertFalse(result.adjusted)
    }

    @Test
    fun v3AccountsForAwgBlockAlignment() {
        val result = effectiveWwgMtus(1240, 1240, isV3 = true, overlayS4 = 14)

        assertEquals(1342, result.underlay)
        assertEquals(1240, result.overlay)
        assertTrue(result.adjusted)
    }

    @Test
    fun v3NegativeS4CannotReduceRequiredEntryMtu() {
        assertEquals(1360, minimumV3UnderlayMtu(1280, -1))
    }
}
