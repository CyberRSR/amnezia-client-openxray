package org.amnezia.vpn.protocol.awg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.VpnService.Builder
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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
private const val HEALTH_PROBE_TIMEOUT_MS = 3_000
private const val INITIAL_VPN_NETWORK_TIMEOUT_MS = 10_000L
private const val STABLE_CONNECTION_RESET_MS = 120_000L
private const val HEALTH_EVENT_LOG_INTERVAL_MS = 60_000L
private const val RETRY_JITTER_PERCENT = 10
private const val AWG_TRANSPORT_MESSAGE_OVERHEAD = 32
private const val IPV6_UDP_OVERHEAD = 48
private const val DNS_PORT = 53
private const val DNS_HEALTH_NAME = "example.com"

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

private enum class HealthCheckKind {
    DNS,
    TCP,
}

private data class HealthCheckTarget(
    val host: String,
    val port: Int,
    val kind: HealthCheckKind,
)

private val HEALTHCHECK_TARGETS = listOf(
    // Query the exact resolvers advertised on the Android VPN. A valid DNS
    // response proves useful payload flow and works on Android 8 without
    // depending on its outdated TLS trust store.
    HealthCheckTarget("1.1.1.1", DNS_PORT, HealthCheckKind.DNS),
    HealthCheckTarget("8.8.8.8", DNS_PORT, HealthCheckKind.DNS),
    // UDP/53 is intentionally not the only signal. Some mobile networks
    // temporarily suppress public DNS datagrams while ordinary VPN payload
    // (HTTPS/QUIC) continues to flow. A VPN-bound TCP connect distinguishes
    // that endpoint-specific condition from a genuinely dead VPN path.
    HealthCheckTarget("1.1.1.1", 443, HealthCheckKind.TCP),
    HealthCheckTarget("8.8.8.8", 443, HealthCheckKind.TCP),
)

internal enum class WwgHealthState {
    HEALTHY,
    PARTIAL,
    FAILED,
}

internal fun classifyWwgHealth(
    dnsSuccessCount: Int,
    dnsTotalCount: Int,
    tcpSuccessCount: Int,
    tcpTotalCount: Int,
): WwgHealthState {
    require(dnsSuccessCount in 0..dnsTotalCount) { "Invalid WWG DNS health counts" }
    require(tcpSuccessCount in 0..tcpTotalCount) { "Invalid WWG TCP health counts" }
    return when {
        tcpSuccessCount > 0 && dnsSuccessCount == dnsTotalCount -> WwgHealthState.HEALTHY
        tcpSuccessCount > 0 || dnsSuccessCount > 0 -> WwgHealthState.PARTIAL
        else -> WwgHealthState.FAILED
    }
}

private data class HealthCheckResult(
    val dnsSuccessCount: Int,
    val dnsTotalCount: Int,
    val tcpSuccessCount: Int,
    val tcpTotalCount: Int,
    val networkFound: Boolean,
) {
    val successCount: Int get() = dnsSuccessCount + tcpSuccessCount
    val totalCount: Int get() = dnsTotalCount + tcpTotalCount
    val isUsable: Boolean get() = successCount > 0
    val state: WwgHealthState get() = classifyWwgHealth(
        dnsSuccessCount,
        dnsTotalCount,
        tcpSuccessCount,
        tcpTotalCount,
    )
}

private data class UnderlayRuntime(
    val handle: Int,
    val relayPort: Int,
)

internal data class WwgRetryDelay(
    val attempt: Int,
    val delayMs: Long,
)

internal data class WwgEffectiveMtus(
    val underlay: Int,
    val overlay: Int,
    val adjusted: Boolean,
)

private fun alignToAwgBlock(size: Int): Int = (size + 15) and -16

internal fun minimumV3UnderlayMtu(overlay: Int, overlayS4: Int): Int =
    alignToAwgBlock(overlay) +
        AWG_TRANSPORT_MESSAGE_OVERHEAD +
        overlayS4.coerceAtLeast(0) +
        IPV6_UDP_OVERHEAD

internal fun effectiveWwgMtus(
    underlay: Int,
    overlay: Int,
    isV3: Boolean,
    overlayS4: Int = 0,
): WwgEffectiveMtus {
    // The entry netstack carries the complete exit AWG datagram. If its MTU is
    // smaller than that datagram, Linux conntrack has to reassemble a fragment
    // stream before forwarding it to the exit server. Under load those
    // reassemblies time out and manifest as stalled TLS/QUIC and reconnects.
    //
    // Include the AWG transport header/tag, S4 and the larger IPv6+UDP header
    // so both IPv4 and IPv6 exit endpoints remain fragment-free.
    val effectiveUnderlay = if (isV3) {
        maxOf(underlay, minimumV3UnderlayMtu(overlay, overlayS4))
    } else {
        underlay
    }
    val effectiveOverlay = overlay
    return WwgEffectiveMtus(
        underlay = effectiveUnderlay,
        overlay = effectiveOverlay,
        adjusted = effectiveUnderlay != underlay || effectiveOverlay != overlay,
    )
}

internal fun buildDnsHealthQuery(transactionId: Int, name: String = DNS_HEALTH_NAME): ByteArray {
    require(transactionId in 0..0xffff) { "DNS transaction ID is out of range" }
    val labels = name.trim('.').split('.').filter { it.isNotEmpty() }
    require(labels.isNotEmpty() && labels.all { it.length in 1..63 }) { "Invalid DNS health name" }

    return ByteArrayOutputStream().apply {
        write(transactionId ushr 8)
        write(transactionId and 0xff)
        write(0x01) // recursion desired
        write(0x00)
        write(0x00)
        write(0x01) // one question
        repeat(6) { write(0x00) }
        labels.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            write(bytes.size)
            write(bytes)
        }
        write(0x00)
        write(0x00)
        write(0x01) // QTYPE=A
        write(0x00)
        write(0x01) // QCLASS=IN
    }.toByteArray()
}

internal fun isSuccessfulDnsHealthResponse(response: ByteArray, length: Int, transactionId: Int): Boolean {
    if (length < 12 || length > response.size) return false
    val responseId = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
    val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
    val answerCount = ((response[6].toInt() and 0xff) shl 8) or (response[7].toInt() and 0xff)
    return responseId == transactionId &&
        flags and 0x8000 != 0 && // response
        flags and 0x0200 == 0 && // not truncated
        flags and 0x000f == 0 && // NOERROR
        answerCount > 0
}

internal fun requiredDnsHostRoutes(dnsServers: List<String>, allowedIps: List<String>): List<String> {
    val normalizedRoutes = allowedIps.map { it.trim().lowercase() }.toSet()
    val hasIpv4Default = "0.0.0.0/0" in normalizedRoutes
    val hasIpv6Default = "::/0" in normalizedRoutes
    return dnsServers.mapNotNull { rawDns ->
        val dns = rawDns.trim()
        when {
            dns.isEmpty() -> null
            ':' in dns && !hasIpv6Default && "${dns.lowercase()}/128" !in normalizedRoutes -> "$dns/128"
            ':' !in dns && !hasIpv4Default && "$dns/32" !in normalizedRoutes -> "$dns/32"
            else -> null
        }
    }.distinct()
}

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

    @Suppress("UNUSED_PARAMETER")
    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        if (sourceConfig == null) {
            throw VpnException("WWG reconnect config is empty")
        }
        protectSockets = protect
        // ConnectivityManager may report a validated underlying network again
        // after a short radio/Wi-Fi transition even though both WWG layers are
        // already carrying traffic. Keep the established TUN and verify useful
        // DNS payload first; the monitor performs a transactional restart only
        // after two complete failed rounds.
        Log.i(
            TAG,
            "WWG_EVENT event=reconnect_check generation=$generation " +
                "source=vpn_service action=verify_before_restart"
        )
        state.value = CONNECTED
        connectionCheckRequests.trySend(Unit)
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

        val effectiveConfig = prepareEffectiveConfig(config, expectedGeneration)
        val underlayRuntime = startUnderlay(effectiveConfig)
        underlayHandle = underlayRuntime.handle

        val relayedConfig = buildRelayedOverlayConfig(
            effectiveConfig,
            underlayRuntime.relayPort,
            expectedGeneration,
        )
        val currentOverlay = WwgOverlayAwg().also { child ->
            overlayState.value = UNKNOWN
            child.initialize(
                context,
                overlayState,
                { message -> onOverlayError(message, generation) },
                onStatusChanged
            )
        }
        overlay = currentOverlay

        Log.i(TAG, "Starting WWG overlay through $RELAY_HOST:${underlayRuntime.relayPort}")
        currentOverlay.startVpn(relayedConfig, vpnBuilder, protectSockets ?: ::rejectProtection)
        waitForState(overlayState, CONNECTED, AWG_HANDSHAKE_TIMEOUT_MS, "WWG overlay handshake timeout")

        val initialHealth = healthCheck(INITIAL_VPN_NETWORK_TIMEOUT_MS, expectedGeneration)
        if (!initialHealth.isUsable) {
            Log.w(
                TAG,
                "WWG_EVENT event=initial_health_degraded generation=$expectedGeneration " +
                    "networkFound=${initialHealth.networkFound} action=keep_tunnel"
            )
        }
    }

    private fun prepareEffectiveConfig(config: JSONObject, expectedGeneration: Long): JSONObject {
        val effectiveConfig = JSONObject(config.toString())
        val underlayData = effectiveConfig.getJSONObject(UNDERLAY_CONFIG_DATA)
        val overlayData = effectiveConfig.getJSONObject(OVERLAY_CONFIG_DATA)
        val configuredUnderlayMtu = underlayData.optString("mtu", "1280").toInt()
        val configuredOverlayMtu = overlayData.optString("mtu", "1280").toInt()
        val isV3 = usesAwgV3(underlayData)
        val overlayS4 = overlayData.optString("S4", "0").toIntOrNull() ?: 0
        val effectiveMtus = effectiveWwgMtus(
            configuredUnderlayMtu,
            configuredOverlayMtu,
            isV3,
            overlayS4,
        )
        if (effectiveMtus.underlay !in 576..1500) {
            throw BadConfigException(
                "WWG v3 entry MTU ${effectiveMtus.underlay} required for exit MTU " +
                    "$configuredOverlayMtu and S4=$overlayS4 exceeds the supported range"
            )
        }
        underlayData.put("mtu", effectiveMtus.underlay.toString())
        overlayData.put("mtu", effectiveMtus.overlay.toString())
        if (effectiveMtus.adjusted) {
            Log.w(
                TAG,
                "WWG_EVENT event=mtu_adjusted generation=$expectedGeneration mode=v3 " +
                    "configuredUnderlay=$configuredUnderlayMtu configuredOverlay=$configuredOverlayMtu " +
                    "effectiveUnderlay=${effectiveMtus.underlay} effectiveOverlay=${effectiveMtus.overlay} " +
                    "overlayS4=$overlayS4 reason=avoid_nested_fragmentation"
            )
        }
        Log.i(
            TAG,
            "WWG_EVENT event=mtu_selected generation=$expectedGeneration " +
                "mode=${if (isV3) "v3" else "v2"} underlay=${effectiveMtus.underlay} " +
                "overlay=${effectiveMtus.overlay} overlayS4=$overlayS4"
        )
        return effectiveConfig
    }

    private fun startUnderlay(effectiveConfig: JSONObject): UnderlayRuntime {
        val underlayData = effectiveConfig.getJSONObject(UNDERLAY_CONFIG_DATA)
        val overlayData = effectiveConfig.getJSONObject(OVERLAY_CONFIG_DATA)
        val underlayConfig = WwgConfigParser().parseUnderlay(effectiveConfig, underlayData)
        val overlayHost = overlayData.getString("hostName").trim()
        val overlayPort = overlayData.getInt("port")
        val localAddresses = underlayConfig.addresses.joinToString(",")

        Log.i(TAG, "Starting WWG underlay netstack")
        val handle = GoBackend.awgTurnOnNetstack(
            UNDERLAY_IF_NAME,
            localAddresses,
            "",
            underlayConfig.mtu,
            underlayConfig.toWgUserspaceString(),
            overlayHost,
            overlayPort
        )
        if (handle < 0) {
            throw VpnStartException("WWG underlay netstack creation failed")
        }

        try {
            protectBackendSockets(handle)
            val relayPort = GoBackend.awgGetRelayPort(handle)
            if (relayPort !in 1..65535 || !GoBackend.awgIsRelayHealthy(handle)) {
                throw VpnStartException("WWG netstack relay is not healthy")
            }
            return UnderlayRuntime(handle, relayPort)
        } catch (e: Exception) {
            runCatching { GoBackend.awgTurnOff(handle) }
            throw e
        }
    }

    /**
     * Replaces the nested chain without first closing the active Android TUN.
     * Closing that TUN caused Android to destroy WwgService and cancel the
     * delayed retry, leaving the device offline indefinitely.
     */
    private suspend fun replaceChainLocked(
        config: JSONObject,
        vpnBuilder: Builder,
        expectedGeneration: Long,
    ) {
        check(!stopping && generation == expectedGeneration) { "WWG generation is obsolete" }
        val currentOverlay = overlay ?: throw VpnStartException("WWG overlay is unavailable for replacement")
        val previousUnderlayHandle = underlayHandle
        val effectiveConfig = prepareEffectiveConfig(config, expectedGeneration)
        val replacementUnderlay = startUnderlay(effectiveConfig)

        try {
            val relayedConfig = buildRelayedOverlayConfig(
                effectiveConfig,
                replacementUnderlay.relayPort,
                expectedGeneration,
            )
            overlayState.value = UNKNOWN
            Log.i(
                TAG,
                "WWG_EVENT event=overlay_swap generation=$expectedGeneration " +
                    "strategy=establish_before_stop"
            )
            currentOverlay.replaceWwgVpn(
                relayedConfig,
                vpnBuilder,
                protectSockets ?: ::rejectProtection,
            )
            waitForState(overlayState, CONNECTED, AWG_HANDSHAKE_TIMEOUT_MS, "WWG overlay replacement handshake timeout")
            underlayHandle = replacementUnderlay.handle

            if (previousUnderlayHandle >= 0 && previousUnderlayHandle != replacementUnderlay.handle) {
                runCatching { GoBackend.awgTurnOff(previousUnderlayHandle) }
            }

            val health = healthCheck(INITIAL_VPN_NETWORK_TIMEOUT_MS, expectedGeneration)
            if (!health.isUsable) {
                Log.w(
                    TAG,
                    "WWG_EVENT event=post_restart_health_degraded generation=$expectedGeneration " +
                        "networkFound=${health.networkFound} action=keep_tunnel"
                )
            }
        } catch (e: Exception) {
            runCatching { GoBackend.awgTurnOff(replacementUnderlay.handle) }
            throw e
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
                when (result.state) {
                    WwgHealthState.HEALTHY -> {
                        failedFullProbeRounds = 0
                    }
                    WwgHealthState.PARTIAL -> {
                        failedFullProbeRounds = 0
                        if (shouldLogHealthEvent(
                                "partial:$expectedGeneration:${result.successCount}/${result.totalCount}",
                                now
                            )
                        ) {
                            Log.w(
                                TAG,
                                "WWG_EVENT event=health_partial generation=$expectedGeneration " +
                                    "success=${result.successCount} total=${result.totalCount} " +
                                    "dns=${result.dnsSuccessCount}/${result.dnsTotalCount} " +
                                    "tcp=${result.tcpSuccessCount}/${result.tcpTotalCount}"
                            )
                        }
                    }
                    WwgHealthState.FAILED -> {
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

        var claimedRestartGeneration = expectedGeneration
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
                    // Keep the in-flight marker claimed for the replacement
                    // generation as well. Rapid network callbacks must not
                    // queue a second restart while this swap is still waiting
                    // for its handshake or retrying a failed replacement.
                    restartRequestedGeneration.compareAndSet(expectedGeneration, restartGeneration)
                    claimedRestartGeneration = restartGeneration
                    connectedAtElapsed = 0L
                    state.value = RECONNECTING
                    Log.w(
                        TAG,
                        "WWG_EVENT event=restart generation=$restartGeneration " +
                            "sourceGeneration=$expectedGeneration attempt=$attempt delayMs=$retryDelay " +
                            "reason=${logValue(reason)}"
                    )
                    delay(retryDelay)

                    while (currentCoroutineContext().isActive && !stopping && generation == restartGeneration) {
                        try {
                            replaceChainLocked(
                                JSONObject(config.toString()),
                                newVpnBuilder(),
                                restartGeneration,
                            )
                            state.value = CONNECTED
                            connectedAtElapsed = SystemClock.elapsedRealtime()
                            launchMonitor(restartGeneration)
                            Log.i(
                                TAG,
                                "WWG_EVENT event=connected generation=$restartGeneration " +
                                    "retryAttempt=${retryPolicy.currentAttempt} restartStrategy=overlay_swap"
                            )
                            return@withLock
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (isFatalConfigurationError(e)) {
                                Log.e(
                                    TAG,
                                    "WWG_EVENT event=fatal_restart_error generation=$restartGeneration " +
                                        "reason=${logValue(e.message ?: e.toString())}"
                                )
                                onError(e.message ?: e.toString())
                                state.value = DISCONNECTED
                                return@withLock
                            }

                            val (retryAttempt, nextDelay) = retryPolicy.nextDelay()
                            Log.w(
                                TAG,
                                "WWG_EVENT event=restart_retry generation=$restartGeneration " +
                                    "attempt=$retryAttempt delayMs=$nextDelay " +
                                    "reason=${logValue(e.message ?: e.toString())}"
                            )
                            delay(nextDelay)
                        }
                    }
                }
            } finally {
                restartRequestedGeneration.compareAndSet(claimedRestartGeneration, -1L)
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

    private fun buildRelayedOverlayConfig(
        config: JSONObject,
        relayPort: Int,
        expectedGeneration: Long,
    ): JSONObject {
        val result = JSONObject(config.toString())
        val overlayData = JSONObject(result.getJSONObject(OVERLAY_CONFIG_DATA).toString()).apply {
            put("hostName", RELAY_HOST)
            put("port", relayPort)
            put("isObfuscationEnabled", true)
        }

        // Android sends these resolver packets into the exit TUN. The exit AWG
        // datagrams then use the localhost relay backed by the entry netstack,
        // which guarantees DNS traverses overlay and underlay in that order.
        // Explicit host routes also preserve that invariant for split profiles
        // whose allowed_ips do not contain a default route.
        val allowedIps = overlayData.getJSONArray("allowed_ips")
        val allowedIpStrings = (0 until allowedIps.length()).map { allowedIps.getString(it) }
        val dnsServers = listOf(result.optString("dns1"), result.optString("dns2"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val addedRoutes = requiredDnsHostRoutes(dnsServers, allowedIpStrings)
        addedRoutes.forEach(allowedIps::put)
        overlayData.put("allowed_ips", allowedIps)
        Log.i(
            TAG,
            "WWG_EVENT event=dns_route generation=$expectedGeneration chain=overlay_then_underlay " +
                "dnsCount=${dnsServers.size} addedHostRoutes=${addedRoutes.size}"
        )
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
            ?: return@withContext HealthCheckResult(0, 2, 0, 2, false)
        val results = supervisorScope {
            HEALTHCHECK_TARGETS.map { target ->
                async { target to probe(network, target, expectedGeneration) }
            }.awaitAll()
        }
        HealthCheckResult(
            dnsSuccessCount = results.count { (target, success) ->
                target.kind == HealthCheckKind.DNS && success
            },
            dnsTotalCount = results.count { it.first.kind == HealthCheckKind.DNS },
            tcpSuccessCount = results.count { (target, success) ->
                target.kind == HealthCheckKind.TCP && success
            },
            tcpTotalCount = results.count { it.first.kind == HealthCheckKind.TCP },
            networkFound = true,
        )
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
        when (target.kind) {
            HealthCheckKind.DNS -> {
                val transactionId = ThreadLocalRandom.current().nextInt(0x10000)
                val query = buildDnsHealthQuery(transactionId)
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.soTimeout = HEALTH_PROBE_TIMEOUT_MS
                    val resolver = InetAddress.getByName(target.host)
                    // Connecting the datagram socket both pins the return
                    // path to this resolver and avoids accepting unrelated
                    // packets from a different endpoint.
                    socket.connect(resolver, target.port)
                    socket.send(DatagramPacket(query, query.size))

                    val response = ByteArray(1500)
                    val packet = DatagramPacket(response, response.size)
                    socket.receive(packet)
                    if (!packet.address.equals(resolver) ||
                        !isSuccessfulDnsHealthResponse(response, packet.length, transactionId)
                    ) {
                        throw VpnException("Invalid DNS health response")
                    }
                }
            }
            HealthCheckKind.TCP -> {
                Socket().use { socket ->
                    network.bindSocket(socket)
                    socket.connect(
                        InetSocketAddress(InetAddress.getByName(target.host), target.port),
                        HEALTH_PROBE_TIMEOUT_MS,
                    )
                }
            }
        }
        true
    } catch (e: Exception) {
        val now = SystemClock.elapsedRealtime()
        if (shouldLogHealthEvent(
                "probe:$expectedGeneration:${target.host}:${target.port}:${target.kind}",
                now
            )
        ) {
            Log.w(
                TAG,
                "WWG_EVENT event=probe_failure generation=$expectedGeneration " +
                    "target=${target.host} transport=${target.kind.name.lowercase()} port=${target.port} " +
                    (if (target.kind == HealthCheckKind.DNS) "qname=$DNS_HEALTH_NAME " else "") +
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

    fun replaceWwgVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        replaceVpnWithConfig(config, vpnBuilder, protect)
    }
}
