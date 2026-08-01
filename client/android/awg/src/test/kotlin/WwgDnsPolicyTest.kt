package org.amnezia.vpn.protocol.awg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WwgDnsPolicyTest {

    @Test
    fun buildsStandardRecursiveAQuery() {
        val query = buildDnsHealthQuery(0x1234, "example.com")

        assertEquals(0x12, query[0].toInt() and 0xff)
        assertEquals(0x34, query[1].toInt() and 0xff)
        assertEquals(0x01, query[2].toInt() and 0xff)
        assertEquals(1, query[5].toInt() and 0xff)
        assertEquals(29, query.size)
    }

    @Test
    fun acceptsOnlyMatchingCompleteDnsAnswer() {
        val response = ByteArray(12)
        response[0] = 0x12
        response[1] = 0x34
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        response[7] = 0x01

        assertTrue(isSuccessfulDnsHealthResponse(response, response.size, 0x1234))
        assertFalse(isSuccessfulDnsHealthResponse(response, response.size, 0x1235))

        response[2] = 0x83.toByte()
        assertFalse(isSuccessfulDnsHealthResponse(response, response.size, 0x1234))
    }

    @Test
    fun fullTunnelNeedsNoExtraDnsHostRoutes() {
        val routes = requiredDnsHostRoutes(
            listOf("1.1.1.1", "2001:4860:4860::8888"),
            listOf("0.0.0.0/0", "::/0"),
        )

        assertTrue(routes.isEmpty())
    }

    @Test
    fun splitTunnelGetsExplicitDnsHostRoutes() {
        val routes = requiredDnsHostRoutes(
            listOf("1.1.1.1", "2001:4860:4860::8888"),
            listOf("10.0.0.0/8"),
        )

        assertEquals(listOf("1.1.1.1/32", "2001:4860:4860::8888/128"), routes)
    }

    @Test
    fun dnsOutageDoesNotMasqueradeAsFullVpnFailure() {
        assertEquals(WwgHealthState.PARTIAL, classifyWwgHealth(0, 2, 1, 2))
        assertEquals(WwgHealthState.PARTIAL, classifyWwgHealth(1, 2, 0, 2))
        assertEquals(WwgHealthState.FAILED, classifyWwgHealth(0, 2, 0, 2))
        assertEquals(WwgHealthState.HEALTHY, classifyWwgHealth(2, 2, 1, 2))
    }
}
