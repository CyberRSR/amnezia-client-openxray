package org.amnezia.awg

object GoBackend {
    external fun awgGetConfig(handle: Int): String?
    external fun awgGetRelayPort(handle: Int): Int
    external fun awgGetSocketV4(handle: Int): Int
    external fun awgGetSocketV6(handle: Int): Int
    external fun awgIsRelayHealthy(handle: Int): Boolean
    external fun awgTurnOff(handle: Int)
    external fun awgTurnOn(ifName: String, tunFd: Int, settings: String): Int
    external fun awgTurnOnNetstack(
        ifName: String,
        localAddresses: String,
        dnsServers: String,
        mtu: Int,
        settings: String,
        relayHost: String,
        relayPort: Int
    ): Int
    external fun awgVersion(): String
}
