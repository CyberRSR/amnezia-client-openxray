package org.amnezia.vpn.protocol

import org.amnezia.vpn.util.net.InetNetwork
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolConfigTest {

    @Test
    fun ipv4OnlyFamilyPolicyWinsAfterExcludedSplitTunnelAddsIpv6Default() {
        val config = ProtocolConfig.build(blockingMode = true) {
            addAddress(InetNetwork.parse("10.8.2.2/32"))
            addRoute(InetNetwork.parse("0.0.0.0/0"))
            excludeAddress(InetNetwork.parse("192.0.2.0/24"))
            setMtu(1240)
            disableIpv6()
        }

        assertTrue(config.addresses.all { it.isIpv4 })
        assertTrue(config.dnsServers.all { it.address.size == 4 })
        assertTrue(config.routes.all { it.inetNetwork.isIpv4 })
    }
}
