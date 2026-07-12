package org.amnezia.vpn.protocol.awg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.VpnService.Builder
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import org.amnezia.awg.GoBackend
import org.amnezia.vpn.protocol.BadConfigException
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.ProtocolState.RECONNECTING
import org.amnezia.vpn.protocol.ProtocolState.UNKNOWN
import org.amnezia.vpn.protocol.Statistics
import org.amnezia.vpn.protocol.VpnException
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.protocol.wireguard.WireguardConfig
import org.amnezia.vpn.util.LibraryLoader.loadSharedLibrary
import org.amnezia.vpn.util.Log
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "Wwg"
private const val UNDERLAY_CONFIG_DATA = "awg_underlay_config_data"
private const val OVERLAY_CONFIG_DATA = "awg_overlay_config_data"
private const val AWG_CONFIG_DATA = "awg_config_data"
private const val PROTOCOL_VERSION = "protocol_version"
private const val AWG_V2 = "2"
private const val UNDERLAY_IF_NAME = "wwg0"
private const val OVERLAY_IF_NAME = "wwg1"
private const val RELAY_HOST = "127.0.0.1"

private const val AWG_HANDSHAKE_TIMEOUT_MS = 45_000L
private const val MONITOR_TICK_MS = 1_000L
private const val HTTP_PROBE_INTERVAL_MS = 5_000L
private const val HTTP_PROBE_TIMEOUT_MS = 2_000
private const val INITIAL_VPN_NETWORK_TIMEOUT_MS = 10_000L
private const val FIRST_RETRY_DELAY_MS = 5_000L
private const val NEXT_RETRY_DELAY_MS = 10_000L

private val HEALTHCHECK_URLS = listOf(
    "https://www.gstatic.com/generate_204",
    "https://cp.cloudflare.com/generate_204"
)

/**
 * WWG keeps the entry AWG v2 tunnel in the official Go netstack and exposes a
 * loopback UDP relay. The exit AWG v2 tunnel is the only Android VpnService TUN.
 */
class Wwg : Protocol() {

    private val lifecycleMutex = Mutex()
    private val overlayState = MutableStateFlow(UNKNOWN)
    private val connectionCheckRequests = Channel<Unit>(Channel.CONFLATED)
    private val closingChain = AtomicBoolean(false)

    private lateinit var scope: CoroutineScope
    private var sourceConfig: JSONObject? = null
    private var protectSockets: ((Int) -> Boolean)? = null
    private var underlayHandle = -1
    private var overlay: WwgOverlayAwg? = null
    private var monitorJob: Job? = null
    private var restartJob: Job? = null
    private var generation = 0L

    @Volatile
    private var stopping = true

    override val statistics: Statistics
        get() {
            val underlayStats = statisticsForHandle(underlayHandle)
            val overlayStats = overlay?.statistics ?: Statistics.EMPTY_STATISTICS
            return Statistics.build {
                setRxBytes(underlayStats.rxBytes + overlayStats.rxBytes)
                setTxBytes(underlayStats.txBytes + overlayStats.txBytes)
            }
        }

    override fun internalInit() {
        if (!isInitialized) {
            loadSharedLibrary(context, "wg-go")
        }
        if (this::scope.isInitialized) {
            scope.cancel()
        }
        runBlocking(Dispatchers.IO) {
            lifecycleMutex.withLock {
                closeChainLocked()
            }
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        while (connectionCheckRequests.tryReceive().isSuccess) {
            // Drop wake requests left by the previous service generation.
        }
        overlayState.value = UNKNOWN
        stopping = false
    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        validateConfig(config)
        sourceConfig = JSONObject(config.toString())
        protectSockets = protect
        stopping = false

        lifecycleMutex.withLock {
            generation += 1
            val currentGeneration = generation
            closeChainLocked()
            startWithRetryLocked(JSONObject(config.toString()), currentGeneration, vpnBuilder)
        }
    }

    override fun stopVpn() {
        stopping = true
        generation += 1
        if (this::scope.isInitialized) {
            scope.cancel()
        }
        runBlocking(Dispatchers.IO) {
            lifecycleMutex.withLock {
                closeChainLocked()
            }
        }
        state.value = DISCONNECTED
    }

    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        if (sourceConfig == null) {
            throw VpnException("WWG reconnect config is empty")
        }
        protectSockets = protect
        scheduleRestart("VPN service requested reconnect", generation)
    }

    override fun requestConnectionCheck(reason: String) {
        Log.i(TAG, "Immediate connection check requested: $reason")
        connectionCheckRequests.trySend(Unit)
    }

    private suspend fun startWithRetryLocked(config: JSONObject, expectedGeneration: Long, initialBuilder: Builder?) {
        var attempt = 0
        while (currentCoroutineContext().isActive && !stopping && generation == expectedGeneration) {
            try {
                val builder = if (attempt == 0 && initialBuilder != null) initialBuilder else newVpnBuilder()
                startChainLocked(config, builder, expectedGeneration)
                state.value = CONNECTED
                launchMonitor(expectedGeneration)
                Log.i(TAG, "WWG generation $expectedGeneration connected")
                return
            } catch (e: CancellationException) {
                closeChainLocked()
                throw e
            } catch (e: Exception) {
                closeChainLocked()
                if (isFatalConfigurationError(e)) {
                    Log.e(TAG, "Fatal WWG start error: ${e.message ?: e}")
                    throw e
                }

                attempt += 1
                state.value = RECONNECTING
                val retryDelay = if (attempt == 1) FIRST_RETRY_DELAY_MS else NEXT_RETRY_DELAY_MS
                Log.w(TAG, "WWG transient start failure; retry in ${retryDelay / 1000}s: ${e.message ?: e}")
                delay(retryDelay)
            }
        }
    }

    private suspend fun startChainLocked(config: JSONObject, vpnBuilder: Builder, expectedGeneration: Long) {
        check(!stopping && generation == expectedGeneration) { "WWG generation is obsolete" }

        val underlayData = config.getJSONObject(UNDERLAY_CONFIG_DATA)
        val overlayData = config.getJSONObject(OVERLAY_CONFIG_DATA)
        val underlayConfig = WwgConfigParser().parseUnderlay(config, underlayData)
        val overlayHost = overlayData.getString("hostName").trim()
        val overlayPort = overlayData.getInt("port")
        val localAddresses = underlayConfig.addresses.joinToString(",")

        Log.i(TAG, "Starting WWG underlay netstack")
        underlayHandle = GoBackend.awgTurnOnNetstack(
            UNDERLAY_IF_NAME,
            localAddresses,
            "",
            underlayConfig.mtu,
            underlayConfig.toWgUserspaceString(),
            overlayHost,
            overlayPort
        )
        if (underlayHandle < 0) {
            underlayHandle = -1
            throw VpnStartException("WWG underlay netstack creation failed")
        }

        protectBackendSockets(underlayHandle)
        val relayPort = GoBackend.awgGetRelayPort(underlayHandle)
        if (relayPort !in 1..65535 || !GoBackend.awgIsRelayHealthy(underlayHandle)) {
            throw VpnStartException("WWG netstack relay is not healthy")
        }

        val relayedConfig = buildRelayedOverlayConfig(config, relayPort)
        val currentOverlay = WwgOverlayAwg().also { child ->
            overlayState.value = UNKNOWN
            child.initialize(
                context,
                overlayState,
                { message -> onOverlayError(message, expectedGeneration) },
                onStatusChanged
            )
        }
        overlay = currentOverlay

        Log.i(TAG, "Starting WWG overlay through $RELAY_HOST:$relayPort")
        currentOverlay.startVpn(relayedConfig, vpnBuilder, protectSockets ?: ::rejectProtection)
        waitForState(overlayState, CONNECTED, AWG_HANDSHAKE_TIMEOUT_MS, "WWG overlay handshake timeout")

        if (!healthCheck(INITIAL_VPN_NETWORK_TIMEOUT_MS)) {
            throw VpnStartException("WWG VPN-bound health check failed")
        }
    }

    private fun launchMonitor(expectedGeneration: Long) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var failedProbeRounds = 0
            var nextHttpProbeAt = SystemClock.elapsedRealtime() + HTTP_PROBE_INTERVAL_MS

            while (isActive && !stopping && generation == expectedGeneration) {
                val requested = withTimeoutOrNull(MONITOR_TICK_MS) {
                    connectionCheckRequests.receive()
                    true
                } ?: false

                val handle = underlayHandle
                if (handle < 0 || !GoBackend.awgIsRelayHealthy(handle)) {
                    Log.e(TAG, "WWG relay fatal health failure")
                    scheduleRestart("netstack relay failed", expectedGeneration)
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                if (!requested && now < nextHttpProbeAt) {
                    continue
                }
                nextHttpProbeAt = now + HTTP_PROBE_INTERVAL_MS

                if (healthCheck(0)) {
                    failedProbeRounds = 0
                } else {
                    failedProbeRounds += 1
                    Log.w(TAG, "WWG VPN-bound probe failed ($failedProbeRounds/2)")
                    if (failedProbeRounds >= 2) {
                        scheduleRestart("two consecutive VPN-bound probes failed", expectedGeneration)
                        return@launch
                    }
                }
            }
        }
    }

    private fun scheduleRestart(reason: String, expectedGeneration: Long) {
        if (stopping || generation != expectedGeneration || restartJob?.isActive == true) {
            return
        }

        restartJob = scope.launch {
            lifecycleMutex.withLock {
                if (stopping || generation != expectedGeneration) {
                    return@withLock
                }

                val config = sourceConfig ?: return@withLock
                generation += 1
                val restartGeneration = generation
                state.value = RECONNECTING
                Log.w(TAG, "WWG restart generation $restartGeneration: $reason")
                closeChainLocked()
                startWithRetryLocked(JSONObject(config.toString()), restartGeneration, null)
            }
        }
    }

    private fun onOverlayError(message: String, expectedGeneration: Long) {
        if (stopping || closingChain.get() || generation != expectedGeneration || state.value != CONNECTED) {
            return
        }
        Log.e(TAG, "WWG overlay error: $message")
        scheduleRestart("overlay error: $message", expectedGeneration)
    }

    private fun closeChainLocked() {
        closingChain.set(true)
        try {
            monitorJob?.cancel()
            monitorJob = null

            // Required teardown order: overlay -> relay -> underlay.
            runCatching { overlay?.stopVpn() }
            overlay = null
            overlayState.value = UNKNOWN

            val handle = underlayHandle
            underlayHandle = -1
            if (handle >= 0) {
                // awgTurnOff closes the relay before closing the netstack device.
                runCatching { GoBackend.awgTurnOff(handle) }
            }
        } finally {
            closingChain.set(false)
        }
    }

    private fun protectBackendSockets(handle: Int) {
        val protect = protectSockets ?: throw VpnStartException("WWG socket protector is unavailable")
        val sockets = listOf(GoBackend.awgGetSocketV4(handle), GoBackend.awgGetSocketV6(handle)).filter { it >= 0 }
        if (sockets.isEmpty() || sockets.any { !protect(it) }) {
            throw VpnStartException("Protect WWG underlay sockets: permission not granted or revoked")
        }
    }

    private fun buildRelayedOverlayConfig(config: JSONObject, relayPort: Int): JSONObject {
        val result = JSONObject(config.toString())
        val overlayData = JSONObject(result.getJSONObject(OVERLAY_CONFIG_DATA).toString()).apply {
            put("hostName", RELAY_HOST)
            put("port", relayPort)
            put("isObfuscationEnabled", true)
        }
        result.put(AWG_CONFIG_DATA, overlayData)
        return result
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

    private suspend fun healthCheck(networkWaitMs: Long): Boolean = withContext(Dispatchers.IO) {
        val network = findVpnNetwork(networkWaitMs) ?: return@withContext false
        HEALTHCHECK_URLS.any { url -> probe(network, url) }
    }

    private suspend fun findVpnNetwork(waitMs: Long): Network? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val deadline = SystemClock.elapsedRealtime() + waitMs
        do {
            connectivityManager.allNetworks.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }?.let { return it }
            if (waitMs == 0L) {
                return null
            }
            delay(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private fun probe(network: Network, url: String): Boolean = try {
        val connection = network.openConnection(URL(url)) as HttpURLConnection
        connection.connectTimeout = HTTP_PROBE_TIMEOUT_MS
        connection.readTimeout = HTTP_PROBE_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code in 200..399
    } catch (e: Exception) {
        Log.w(TAG, "WWG VPN-bound probe failed for $url: ${e.message ?: e}")
        false
    }

    private fun validateConfig(config: JSONObject) {
        if (!config.has(UNDERLAY_CONFIG_DATA) || !config.has(OVERLAY_CONFIG_DATA)) {
            throw BadConfigException("WWG requires underlay and overlay AmneziaWG v2 configs")
        }
        validateLayer(config.getJSONObject(UNDERLAY_CONFIG_DATA), "underlay")
        validateLayer(config.getJSONObject(OVERLAY_CONFIG_DATA), "overlay")
    }

    private fun validateLayer(config: JSONObject, name: String) {
        if (config.optString(PROTOCOL_VERSION) != AWG_V2) {
            throw BadConfigException("WWG $name protocol_version must be 2")
        }

        val requiredStrings = listOf(
            "hostName", "client_ip", "client_priv_key", "server_pub_key",
            "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4"
        )
        requiredStrings.forEach { key ->
            if (config.optString(key).isBlank()) {
                throw BadConfigException("WWG $name is missing $key")
            }
        }
        if (config.optInt("port") !in 1..65535) {
            throw BadConfigException("WWG $name has an invalid port")
        }
        if (!config.has("allowed_ips") || config.getJSONArray("allowed_ips").length() == 0) {
            throw BadConfigException("WWG $name has no allowed_ips")
        }
        val mtu = config.optString("mtu", "1280").toIntOrNull()
        if (mtu == null || mtu !in 576..1500) {
            throw BadConfigException("WWG $name has an invalid MTU")
        }
    }

    private fun isFatalConfigurationError(error: Exception): Boolean =
        error is BadConfigException || error is JSONException || error is IllegalArgumentException ||
            error.message?.contains("permission not granted", ignoreCase = true) == true

    private fun statisticsForHandle(handle: Int): Statistics {
        if (handle < 0) {
            return Statistics.EMPTY_STATISTICS
        }
        val config = GoBackend.awgGetConfig(handle) ?: return Statistics.EMPTY_STATISTICS
        var rx = 0L
        var tx = 0L
        config.lineSequence().forEach { line ->
            when {
                line.startsWith("rx_bytes=") -> rx += line.substringAfter('=').toLongOrNull() ?: 0L
                line.startsWith("tx_bytes=") -> tx += line.substringAfter('=').toLongOrNull() ?: 0L
            }
        }
        return Statistics.build {
            setRxBytes(rx)
            setTxBytes(tx)
        }
    }

    private fun newVpnBuilder(): Builder = (context as VpnService).Builder()

    private fun rejectProtection(socket: Int): Boolean = false
}

private class WwgConfigParser : Awg() {
    fun parseUnderlay(config: JSONObject, configData: JSONObject): WireguardConfig =
        WireguardConfig.build {
            setUseProtocolExtension(true)
            configExtensionParameters(configData)
            configWireguard(config, configData)
        }
}

private class WwgOverlayAwg : Awg() {
    override val ifName: String = OVERLAY_IF_NAME
}
