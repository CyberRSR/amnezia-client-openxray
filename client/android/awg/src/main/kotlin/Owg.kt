package org.amnezia.vpn.protocol.awg

import android.net.VpnService.Builder
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.ProtocolState.UNKNOWN
import org.amnezia.vpn.protocol.Statistics
import org.amnezia.vpn.protocol.VpnException
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.protocol.wireguard.WireguardConfig
import org.amnezia.vpn.protocol.openvpn.OpenVpnUdpRelayHandle
import org.amnezia.vpn.protocol.openvpn.OpenVpnUserspaceTunnel
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.net.InetNetwork
import org.amnezia.vpn.util.net.parseInetAddress
import org.amnezia.vpn.util.optStringOrNull
import org.json.JSONObject

private const val TAG = "Owg"
private const val OPENVPN_CONFIG_DATA = "openvpn_config_data"
private const val AWG_CONFIG_DATA = "awg_config_data"
private const val OPENVPN_START_TIMEOUT_MS = 45_000L
private const val AWG_HANDSHAKE_TIMEOUT_MS = 45_000L
private const val HEALTHCHECK_TIMEOUT_MS = 10_000L
private const val HEALTHCHECK_CONNECT_TIMEOUT_MS = 5_000
private const val HEALTHCHECK_READ_TIMEOUT_MS = 5_000

private val HEALTHCHECK_URLS = listOf(
    "https://www.gstatic.com/generate_204",
    "http://connectivitycheck.gstatic.com/generate_204",
    "http://example.com/"
)

class Owg : Protocol() {

    private val openVpnState = MutableStateFlow(UNKNOWN)
    private val awgState = MutableStateFlow(UNKNOWN)
    private val openVpn = OpenVpnUserspaceTunnel()

    private lateinit var scope: CoroutineScope
    private var awg: Awg? = null
    private var sourceConfig: JSONObject? = null
    private var udpRelay: OpenVpnUdpRelayHandle? = null
    @Volatile
    private var stopping = false

    override val statistics: Statistics
        get() {
            val openVpnStats = openVpn.statistics
            val awgStats = awg?.statistics ?: Statistics.EMPTY_STATISTICS
            return Statistics.build {
                setRxBytes(openVpnStats.rxBytes + awgStats.rxBytes)
                setTxBytes(openVpnStats.txBytes + awgStats.txBytes)
            }
        }

    override fun internalInit() {
        if (this::scope.isInitialized) {
            scope.cancel()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        openVpnState.value = UNKNOWN
        awgState.value = UNKNOWN
        openVpn.initialize(context, openVpnState, ::onChildError, onStatusChanged)
    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        require(config.has(OPENVPN_CONFIG_DATA)) { "OWG config is missing OpenVPN data" }
        require(config.has(AWG_CONFIG_DATA)) { "OWG config is missing AmneziaWG data" }

        stopping = false
        sourceConfig = JSONObject(config.toString())
        Log.i(TAG, "Starting OWG: OpenVPN userspace underlay first")

        try {
            withTimeout(OPENVPN_START_TIMEOUT_MS) {
                openVpn.startUserspace(config, protect)
            }

            val awgConfigData = config.getJSONObject(AWG_CONFIG_DATA)
            val remoteHost = awgConfigData.getString("hostName").trim()
            val remotePort = awgConfigData.getInt("port")
            udpRelay = openVpn.startUdpRelay(remoteHost, remotePort)

            val relay = udpRelay ?: throw VpnStartException("OpenVPN UDP relay was not created")
            val relayedConfig = buildRelayedAwgConfig(config, relay)
            val relayedAwg = RelayedAwg(relay.host, relay.port).also {
                it.initialize(context, awgState, ::onChildError, onStatusChanged)
            }
            awg = relayedAwg

            Log.i(TAG, "Starting AmneziaWG v2 through OpenVPN UDP relay ${relay.host}:${relay.port}")
            relayedAwg.startVpn(relayedConfig, vpnBuilder, protect)

            waitForState(awgState, CONNECTED, AWG_HANDSHAKE_TIMEOUT_MS, "AmneziaWG handshake timeout")
            if (!healthCheck()) {
                throw VpnStartException("OWG health check failed")
            }

            Log.i(TAG, "OWG health check passed")
            state.value = CONNECTED
        } catch (e: Exception) {
            stopVpn()
            throw e
        }
    }

    override fun stopVpn() {
        stopping = true
        runCatching { awg?.stopVpn() }
        awg = null
        runCatching { udpRelay?.close() }
        udpRelay = null
        runCatching { openVpn.stopVpn() }
        state.value = DISCONNECTED
    }

    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        val config = sourceConfig ?: throw VpnException("Reconnect config is empty")
        scope.launch {
            stopVpn()
            startVpn(JSONObject(config.toString()), vpnBuilder, protect)
        }
    }

    private fun onChildError(message: String) {
        if (!stopping) {
            Log.e(TAG, message)
            onError(message)
            state.value = DISCONNECTED
        }
    }

    private suspend fun waitForState(
        flow: MutableStateFlow<ProtocolState>,
        expected: ProtocolState,
        timeoutMs: Long,
        timeoutMessage: String
    ) {
        try {
            withTimeout(timeoutMs) {
                flow.first { it == expected }
            }
        } catch (_: TimeoutCancellationException) {
            throw VpnStartException(timeoutMessage)
        }
    }

    private fun buildRelayedAwgConfig(config: JSONObject, relay: OpenVpnUdpRelayHandle): JSONObject {
        val result = JSONObject(config.toString())
        val awgConfigData = JSONObject(result.getJSONObject(AWG_CONFIG_DATA).toString()).apply {
            put("hostName", relay.host)
            put("port", relay.port)
            put("isObfuscationEnabled", true)
        }
        result.put(AWG_CONFIG_DATA, awgConfigData)

        if (result.optStringOrNull("dns1").isNullOrBlank()) {
            openVpn.tunnelDnsServers.getOrNull(0)?.let { result.put("dns1", it) }
        }
        if (result.optStringOrNull("dns2").isNullOrBlank()) {
            openVpn.tunnelDnsServers.getOrNull(1)?.let { result.put("dns2", it) }
        }

        return result
    }

    private suspend fun healthCheck(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(HEALTHCHECK_TIMEOUT_MS) {
                    for (url in HEALTHCHECK_URLS) {
                        if (probe(url)) {
                            return@withTimeout true
                        }
                    }
                    false
                }
            } catch (_: TimeoutCancellationException) {
                false
            }
        }

    private fun probe(url: String): Boolean =
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = HEALTHCHECK_CONNECT_TIMEOUT_MS
                readTimeout = HEALTHCHECK_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
            }
            connection.inputStream.use { it.read(ByteArray(1)) }
            val code = connection.responseCode
            connection.disconnect()
            code in 200..399
        } catch (e: Exception) {
            Log.w(TAG, "OWG probe failed for $url: ${e.message ?: e}")
            false
        }
}

private class RelayedAwg(
    private val relayHost: String,
    private val relayPort: Int
) : Awg() {
    override fun parseConfig(config: JSONObject): WireguardConfig {
        val configData = config.getJSONObject(AWG_CONFIG_DATA)
        return WireguardConfig.build {
            setUseProtocolExtension(true)
            configExtensionParameters(configData)
            configWireguard(config, configData)
            excludeRoute(InetNetwork(parseInetAddress(relayHost)))
            configSplitTunneling(config)
            configAppSplitTunneling(config)
        }
    }
}
