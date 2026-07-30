package org.amnezia.vpn.protocol.awg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.VpnService.Builder
import android.os.SystemClock
import android.util.Base64
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
import kotlin.math.max
import kotlin.random.Random

private const val TAG = "Wwg"
private const val UNDERLAY_CONFIG_DATA = "awg_underlay_config_data"
private const val OVERLAY_CONFIG_DATA = "awg_overlay_config_data"
private const val AWG_CONFIG_DATA = "awg_config_data"
private const val PROTOCOL_VERSION = "protocol_version"
private const val AWG_V2 = "2"
private const val UNDERLAY_IF_NAME = "wwg0"
private const val OVERLAY_IF_NAME = "wwg1"
private const val RELAY_HOST = "127.0.0.1"
private const val HEADER_PROTECTION_KEY = "HeaderProtectionKey"

private const val AWG_HANDSHAKE_TIMEOUT_MS = 45_000L
private const val MONITOR_TICK_MS = 1_000L
private const val HEALTH_PROBE_INTERVAL_MS = 5_000L
private const val HEALTH_PROBE_TIMEOUT_MS = 2_000
private const val INITIAL_VPN_NETWORK_TIMEOUT_MS = 10_000L
private const val STABLE_CONNECTION_RESET_MS = 120_000L
private const val HEALTH_EVENT_LOG_INTERVAL_MS = 60_000L
private const val RETRY_JITTER_PERCENT = 10

private val RETRY_BACKOFF_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
private val AWG_V3_RANGE_FIELDS = listOf(
    "ContentPaddingAddition",
    "RekeyAfterTime",
    "RekeyTimeout",
    "RejectAfterTime",
    "KeepaliveTimeout",
    "MaxHandshakeAttempts",
)
private val AWG_V3_FIELDS = listOf(HEADER_PROTECTION_KEY) + AWG_V3_RANGE_FIELDS

private data class HealthCheckTarget(
    val host: String,
    val port: Int,
)

private val HEALTHCHECK_TARGETS = listOf(
    // Literal IPs keep DNS and HTTP/TLS failures from stretching a five-second
    // VPN-bound reachability round.
    HealthCheckTarget("1.1.1.1", 443),
    HealthCheckTarget("8.8.8.8", 53),
)

private data class HealthCheckResult(
    val successCount: Int,
    val totalCount: Int,
    val networkFound: Boolean,
) {
    val isUsable: Boolean get() = successCount > 0
}

internal data class WwgRetryDelay(
    val attempt: Int,
    val delayMs: Long,
)

internal class WwgRetryPolicy(
    private val backoffMs: LongArray = RETRY_BACKOFF_MS,
    private val jitterPercent: Int = RETRY_JITTER_PERCENT,
    private val randomLong: (Long, Long) -> Long = { from, until -> Random.nextLong(from, until) },
) {
    private val attempt = AtomicInteger(0)

    init {
        require(backoffMs.isNotEmpty()) { "WWG retry backoff must not be empty" }
        require(jitterPercent in 0..100) { "WWG retry jitter must be between 0 and 100 percent" }
    }

    val currentAttempt: Int
        get() = attempt.get()

    fun reset(): Boolean = attempt.getAndSet(0) != 0

    fun nextDelay(): WwgRetryDelay {
        val nextAttempt = attempt.incrementAndGet()
        val baseDelay = backoffMs[minOf(nextAttempt - 1, backoffMs.lastIndex)]
        val jitterLimit = if (jitterPercent == 0) 0L else max(1L, baseDelay * jitterPercent / 100)
        val jitter = if (jitterLimit == 0L) 0L else randomLong(-jitterLimit, jitterLimit + 1)
        return WwgRetryDelay(nextAttempt, max(0L, baseDelay + jitter))
    }
}

/**
 * WWG keeps the entry AWG v2/v3 tunnel in the official Go netstack and exposes
 * a loopback UDP relay. The exit AWG tunnel is the only Android VpnService TUN.
 */
class Wwg : Protocol() {

    private val lifecycleMutex = Mutex()
    private val overlayState = MutableStateFlow(UNKNOWN)
    private val connectionCheckRequests = Channel<Unit>(Channel.CONFLATED)
    private val closingChain = AtomicBoolean(false)
    private val retryPolicy = WwgRetryPolicy()
    private val restartRequestedGeneration = AtomicLong(-1L)

    private lateinit var scope: CoroutineScope
    private var sourceConfig: JSONObject? = null
    private var protectSockets: ((Int) -> Boolean)? = null
    private var underlayHandle = -1
    private var overlay: WwgOverlayAwg? = null
    private var monitorJob: Job? = null
    private var restartJob: Job? = null
    private var generation = 0L
    private var connectedAtElapsed = 0L
    private val healthEventLogTimes = mutableMapOf<String, Long>()

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
        retryPolicy.reset()
        restartRequestedGeneration.set(-1L)
        connectedAtElapsed = 0L
        synchronized(healthEventLogTimes) {
            healthEventLogTimes.clear()
        }
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
            restartRequestedGeneration.set(-1L)
            closeChainLocked()
            startWithRetryLocked(JSONObject(config.toString()), currentGeneration, vpnBuilder)
        }
    }

    override fun stopVpn() {
        stopping = true
        generation += 1
        restartRequestedGeneration.set(-1L)
        connectedAtElapsed = 0L
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
        var useInitialBuilder = initialBuilder != null
        while (currentCoroutineContext().isActive && !stopping && generation == expectedGeneration) {
            try {
                val builder = if (useInitialBuilder) initialBuilder!! else newVpnBuilder()
                useInitialBuilder = false
                startChainLocked(config, builder, expectedGeneration)
                state.value = CONNECTED
                connectedAtElapsed = SystemClock.elapsedRealtime()
                launchMonitor(expectedGeneration)
                Log.i(
                    TAG,
                    "WWG_EVENT event=connected generation=$expectedGeneration " +
                        "retryAttempt=${retryPolicy.currentAttempt}"
                )
                return
            } catch (e: CancellationException) {
                closeChainLocked()
                throw e
            } catch (e: Exception) {
                closeChainLocked()
                if (isFatalConfigurationError(e)) {
                    Log.e(
                        TAG,
                        "WWG_EVENT event=fatal_start_error generation=$expectedGeneration " +
                            "reason=${logValue(e.message ?: e.toString())}"
                    )
                    throw e
                }

                state.value = RECONNECTING
                val (attempt, retryDelay) = retryPolicy.nextDelay()
                Log.w(
                    TAG,
                    "WWG_EVENT event=start_retry generation=$expectedGeneration attempt=$attempt " +
                        "delayMs=$retryDelay reason=${logValue(e.message ?: e.toString())}"
                )
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

        if (!healthCheck(INITIAL_VPN_NETWORK_TIMEOUT_MS, expectedGeneration).isUsable) {
            throw VpnStartException("WWG VPN-bound health check failed")
        }
    }

    private fun launchMonitor(expectedGeneration: Long) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var failedFullProbeRounds = 0
            var nextHealthProbeAt = SystemClock.elapsedRealtime() + HEALTH_PROBE_INTERVAL_MS

            while (isActive && !stopping && generation == expectedGeneration) {
                val requested = withTimeoutOrNull(MONITOR_TICK_MS) {
                    connectionCheckRequests.receive()
                    true
                } ?: false

                val handle = underlayHandle
                if (handle < 0 || !GoBackend.awgIsRelayHealthy(handle)) {
                    Log.e(
                        TAG,
                        "WWG_EVENT event=health_failure generation=$expectedGeneration " +
                            "scope=relay reason=relay_unhealthy"
                    )
                    scheduleRestart("netstack relay failed", expectedGeneration)
                    return@launch
                }

                val now = SystemClock.elapsedRealtime()
                if (connectedAtElapsed > 0L
                    && now - connectedAtElapsed >= STABLE_CONNECTION_RESET_MS
                    && retryPolicy.reset()
                ) {
                    Log.i(
                        TAG,
                        "WWG_EVENT event=backoff_reset generation=$expectedGeneration " +
                            "stableMs=${now - connectedAtElapsed}"
                    )
                }
                if (!requested && now < nextHealthProbeAt) {
                    continue
                }
                nextHealthProbeAt = now + HEALTH_PROBE_INTERVAL_MS

                val result = healthCheck(0, expectedGeneration)
                when {
                    result.successCount == result.totalCount -> {
                        failedFullProbeRounds = 0
                    }
                    result.successCount > 0 -> {
                        failedFullProbeRounds = 0
                        if (shouldLogHealthEvent(
                                "partial:$expectedGeneration:${result.successCount}/${result.totalCount}",
                                now
                            )
                        ) {
                            Log.w(
                                TAG,
                                "WWG_EVENT event=health_partial generation=$expectedGeneration " +
                                    "success=${result.successCount} total=${result.totalCount}"
                            )
                        }
                    }
                    else -> {
                        failedFullProbeRounds += 1
                        Log.w(
                            TAG,
                            "WWG_EVENT event=health_failure generation=$expectedGeneration " +
                                "scope=vpn round=$failedFullProbeRounds/2 " +
                                "networkFound=${result.networkFound}"
                        )
                    }
                }
                if (failedFullProbeRounds >= 2) {
                    scheduleRestart("two consecutive full VPN-bound probe rounds failed", expectedGeneration)
                    return@launch
                }
            }
        }
    }

    private fun scheduleRestart(reason: String, expectedGeneration: Long) {
        if (stopping || generation != expectedGeneration
            || !restartRequestedGeneration.compareAndSet(-1L, expectedGeneration)
        ) {
            return
        }

        restartJob = scope.launch {
            try {
                lifecycleMutex.withLock {
                    if (stopping || generation != expectedGeneration) {
                        return@withLock
                    }

                    val config = sourceConfig ?: return@withLock
                    val (attempt, retryDelay) = retryPolicy.nextDelay()
                    generation += 1
                    val restartGeneration = generation
                    connectedAtElapsed = 0L
                    state.value = RECONNECTING
                    Log.w(
                        TAG,
                        "WWG_EVENT event=restart generation=$restartGeneration " +
                            "sourceGeneration=$expectedGeneration attempt=$attempt delayMs=$retryDelay " +
                            "reason=${logValue(reason)}"
                    )
                    closeChainLocked()
                    delay(retryDelay)
                    restartRequestedGeneration.compareAndSet(expectedGeneration, -1L)
                    startWithRetryLocked(JSONObject(config.toString()), restartGeneration, null)
                }
            } finally {
                restartRequestedGeneration.compareAndSet(expectedGeneration, -1L)
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

    private suspend fun closeChainLocked() {
        closingChain.set(true)
        try {
            val monitor = monitorJob
            monitorJob = null
            if (monitor != null) {
                if (monitor === currentCoroutineContext()[Job]) {
                    monitor.cancel()
                } else {
                    monitor.cancelAndJoin()
                }
            }

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

    private suspend fun healthCheck(
        networkWaitMs: Long,
        expectedGeneration: Long
    ): HealthCheckResult = withContext(Dispatchers.IO) {
        val network = findVpnNetwork(networkWaitMs)
            ?: return@withContext HealthCheckResult(0, HEALTHCHECK_TARGETS.size, false)
        val successes = HEALTHCHECK_TARGETS.count { target ->
            probe(network, target, expectedGeneration)
        }
        HealthCheckResult(successes, HEALTHCHECK_TARGETS.size, true)
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

    private fun probe(network: Network, target: HealthCheckTarget, expectedGeneration: Long): Boolean = try {
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(target.host, target.port), HEALTH_PROBE_TIMEOUT_MS)
        }
        true
    } catch (e: Exception) {
        val now = SystemClock.elapsedRealtime()
        if (shouldLogHealthEvent(
                "probe:$expectedGeneration:${target.host}:${target.port}",
                now
            )
        ) {
            Log.w(
                TAG,
                "WWG_EVENT event=probe_failure generation=$expectedGeneration " +
                    "target=${target.host} transport=tcp port=${target.port} " +
                    "reason=${logValue(e.message ?: e.toString())}"
            )
        }
        false
    }

    private fun shouldLogHealthEvent(key: String, now: Long): Boolean = synchronized(healthEventLogTimes) {
        val previous = healthEventLogTimes[key]
        if (previous != null && now - previous < HEALTH_EVENT_LOG_INTERVAL_MS) {
            return@synchronized false
        }
        healthEventLogTimes[key] = now
        true
    }

    private fun logValue(value: String): String =
        value.replace(Regex("[\\r\\n\\s]+"), "_").take(240)

    private fun validateConfig(config: JSONObject) {
        if (!config.has(UNDERLAY_CONFIG_DATA) || !config.has(OVERLAY_CONFIG_DATA)) {
            throw BadConfigException("WWG requires underlay and overlay AmneziaWG configs")
        }
        val underlay = config.getJSONObject(UNDERLAY_CONFIG_DATA)
        val overlay = config.getJSONObject(OVERLAY_CONFIG_DATA)
        val underlayV3 = usesAwgV3(underlay)
        val overlayV3 = usesAwgV3(overlay)
        if (underlayV3 != overlayV3) {
            throw BadConfigException("WWG cannot mix AmneziaWG v2 and v3 layers")
        }
        validateLayer(underlay, "underlay", underlayV3)
        validateLayer(overlay, "overlay", overlayV3)
    }

    private fun validateLayer(config: JSONObject, name: String, isV3: Boolean) {
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

        if (isV3) {
            val headerProtectionKey = config.optString(HEADER_PROTECTION_KEY).trim()
            if (!isValidHeaderProtectionKey(headerProtectionKey)) {
                throw BadConfigException("WWG $name has an invalid 32-byte HeaderProtectionKey")
            }
            for (padding in listOf("S1", "S2", "S3", "S4")) {
                val value = config.optString(padding).toIntOrNull()
                if (value == null || value < 8) {
                    throw BadConfigException("WWG $name AmneziaWG v3 requires $padding >= 8")
                }
            }
            for (field in AWG_V3_RANGE_FIELDS + "persistent_keep_alive") {
                val value = config.optString(field).trim()
                if (!isValidUint32Range(value)) {
                    throw BadConfigException("WWG $name has an invalid $field range")
                }
            }
        }
    }

    private fun usesAwgV3(config: JSONObject): Boolean =
        AWG_V3_FIELDS.any { field -> config.optString(field).isNotBlank() }

    private fun isValidHeaderProtectionKey(value: String): Boolean = try {
        val decoded = Base64.decode(value, Base64.DEFAULT)
        decoded.size == 32
            && Base64.encodeToString(decoded, Base64.NO_WRAP).trimEnd('=') == value.trim().trimEnd('=')
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun isValidUint32Range(value: String): Boolean {
        if (value.isEmpty()) {
            return true
        }
        if (value.equals("(off)", ignoreCase = true)) {
            return true
        }
        val parts = value.split('-')
        if (parts.size !in 1..2 || parts.any { it.isEmpty() }) {
            return false
        }
        val lo = parts[0].toLongOrNull() ?: return false
        val hi = if (parts.size == 2) parts[1].toLongOrNull() ?: return false else lo
        return lo in 0L..0xFFFF_FFFFL && hi in lo..0xFFFF_FFFFL
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
