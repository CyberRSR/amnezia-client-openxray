package org.amnezia.vpn.protocol.xray

import android.content.Context
import android.net.VpnService.Builder
import android.net.VpnService
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.CONNECTING
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTING
import org.amnezia.vpn.protocol.ProtocolState.RECONNECTING
import org.amnezia.vpn.protocol.ProtocolState.UNKNOWN
import org.amnezia.vpn.protocol.Status
import org.amnezia.vpn.protocol.StatusStep
import org.amnezia.vpn.protocol.StatusStepState
import org.amnezia.vpn.protocol.Statistics
import org.amnezia.vpn.protocol.openvpn.OpenVpn
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.net.activeTransportKey
import org.amnezia.vpn.util.net.describeActiveNetwork
import org.amnezia.vpn.util.net.ip
import org.amnezia.vpn.util.net.parseInetAddress
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

private const val XRAY_CONFIG_DATA = "xray_config_data"
private const val PROTOCOL = "protocol"
private const val HOST_NAME = "hostName"
private const val EXCLUDED_ADDRESSES = "excludedAddresses"
private const val TAG = "Oxray"
private const val STEP_OPENVPN = "openvpn"
private const val STEP_XRAY = "xray"
private const val STEP_READMCU = "readmcu"
private const val STEP_SPEEDTEST = "speedtest"
private const val STEP_HEALTHCHECK = "healthcheck"
private const val DIAGNOSTIC_DELAY_MS = 1200L
private const val DIAGNOSTIC_PROBE_TIMEOUT_MS = 6000L
private const val DIAGNOSTIC_CONNECT_TIMEOUT_MS = 3000
private const val DIAGNOSTIC_READ_TIMEOUT_MS = 4000
private const val DIAGNOSTIC_TCP_TIMEOUT_MS = 5000
private const val DIAGNOSTIC_CONNECT_VERIFICATION_RETRIES = 3
private const val DIAGNOSTIC_CONNECT_VERIFICATION_RETRY_DELAY_MS = 3000L
private const val CONNECTIVITY_MONITOR_INTERVAL_MS = 300_000L
private const val CONNECTIVITY_MONITOR_FAILURE_THRESHOLD = 3
private const val OPENVPN_START_TIMEOUT_MS = 45000L
internal const val OXRAY_CHAIN_MTU = 1280
private const val OXRAY_CHAIN_MSS = 1200
private const val OXRAY_OPENVPN_UNDERLAY_TAG = "amnezia-openvpn-underlay"
private const val STRATEGY_PREFS = "oxray_routing"
private val OXRAY_UNDERLAY_FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1", "9.9.9.9")
private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val DIAGNOSTIC_PROBE_URLS = listOf(
    "https://api.ipify.org/",
    "http://example.com/",
    "http://neverssl.com/"
)

class Oxray : Protocol() {

    private val openVpnState = MutableStateFlow(UNKNOWN)
    private val xrayState = MutableStateFlow(UNKNOWN)
    private val openVpn = OpenVpnUserspaceTunnel()

    private lateinit var scope: CoroutineScope
    private var xray: OxrayXray? = null
    private var protect: ((Int) -> Boolean)? = null
    private var sourceConfig: JSONObject? = null
    private var isStopping = false
    private var isStartingXray = false
    private var isXrayActive = false
    private var restartXrayForStrategy = false
    private var currentStrategyIndex = -1
    private var currentStrategyPosition = -1
    private var diagnosticsJob: Job? = null
    private var connectivityMonitorJob: Job? = null
    private var openVpnStartupJob: Job? = null
    private var connectionTransportKey = "UNKNOWN"
    private var strategySequence = emptyList<Int>()
    private var connectivityMonitorFailures = 0
    private var currentSocksProxy: SocksProxySettings? = null
    private var currentUnderlaySettings: OpenVpnUnderlaySettings? = null
    private var openVpnUnderlayMode = false

    private val routingStrategies = listOf(
        RoutingStrategy("sendThrough+protect", useSendThrough = true, protectDialerSockets = true),
        RoutingStrategy("sendThrough+no-protect", useSendThrough = true, protectDialerSockets = false),
        RoutingStrategy("plain+protect", useSendThrough = false, protectDialerSockets = true),
        RoutingStrategy("plain+no-protect", useSendThrough = false, protectDialerSockets = false)
    )

    override val statistics: Statistics
        get() = if (isXrayActive) xray?.statistics ?: Statistics.EMPTY_STATISTICS else openVpn.statistics

    override fun internalInit() {
        if (this::scope.isInitialized) {
            scope.cancel()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        openVpnState.value = UNKNOWN
        xrayState.value = UNKNOWN
        isStopping = false
        isStartingXray = false
        isXrayActive = false
        restartXrayForStrategy = false
        currentStrategyIndex = -1
        currentStrategyPosition = -1
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        openVpnStartupJob?.cancel()
        openVpnStartupJob = null
        xray = null
        connectionTransportKey = "UNKNOWN"
        strategySequence = routingStrategies.indices.toList()
        connectivityMonitorFailures = 0
        currentSocksProxy = null
        currentUnderlaySettings = null
        openVpnUnderlayMode = false

        openVpn.initialize(
            context = context,
            state = openVpnState,
            onError = { handleChildError("OpenVPN", it) }
        )

        scope.launch {
            openVpnState.drop(1).collect(::onOpenVpnStateChanged)
        }
        scope.launch {
            xrayState.drop(1).collect(::onXrayStateChanged)
        }
    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        this.protect = protect
        sourceConfig = JSONObject(config.toString())
        isStopping = false
        isStartingXray = false
        isXrayActive = false
        restartXrayForStrategy = false
        currentStrategyIndex = -1
        currentStrategyPosition = -1
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        openVpnStartupJob?.cancel()
        openVpnStartupJob = null
        connectionTransportKey = activeTransportKey(context)
        strategySequence = listOf(0)
        currentStrategyIndex = 0
        currentStrategyPosition = 0
        connectivityMonitorFailures = 0
        openVpnUnderlayMode = true
        state.value = CONNECTING
        emitProgress(
            message = "Preparing OpenVPN userspace underlay",
            openVpnStep = StatusStepState.ACTIVE,
            xrayStep = StatusStepState.PENDING,
            readMcuStep = StatusStepState.PENDING,
            speedTestStep = StatusStepState.PENDING,
            healthCheckStep = StatusStepState.PENDING,
            protocolState = CONNECTING
        )

        val underlay = withContext(Dispatchers.IO) {
            openVpn.startUserspace(config, protect)
        }
        currentUnderlaySettings = underlay
        if (isStopping) {
            return
        }
        emitProgress(
            message = "OpenVPN userspace underlay connected, starting Xray",
            openVpnStep = StatusStepState.SUCCESS,
            xrayStep = StatusStepState.ACTIVE,
            readMcuStep = StatusStepState.PENDING,
            speedTestStep = StatusStepState.PENDING,
            healthCheckStep = StatusStepState.PENDING,
            protocolState = CONNECTING
        )
        startXrayOverOpenVpn(config, underlay, vpnBuilder, protect)
    }

    override fun stopVpn() {
        isStopping = true
        isStartingXray = false
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        openVpnStartupJob?.cancel()
        openVpnStartupJob = null
        stopXray()
        openVpn.stopVpn()
        connectivityMonitorFailures = 0
        currentUnderlaySettings = null
        openVpnUnderlayMode = false
        state.value = DISCONNECTED
        emitStatus(Status.build { setState(DISCONNECTED) })
    }

    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        this.protect = protect
        val config = sourceConfig ?: return
        scope.launch {
            restartUserspaceChain(config, vpnBuilder, protect, "manual reconnect")
        }
    }

    override fun requestConnectionCheck(reason: String) {
        if (!this::scope.isInitialized || isStopping || !isXrayActive || state.value != CONNECTED) {
            return
        }

        diagnosticsJob?.cancel()
        diagnosticsJob = null
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null

        scope.launch(Dispatchers.IO) {
            if (openVpnUnderlayMode) {
                Log.i(TAG, "Skipping legacy OXray strategy check after wake in OpenVPN userspace underlay mode")
                return@launch
            }
            val strategy = routingStrategies.getOrNull(currentStrategyIndex) ?: return@launch
            val results = runCheckRound(strategy, CONNECTED, "Checking connection after wake")
            if (results.hasApplicationConnectivity() || results.hasUsableConnectivity()) {
                connectivityMonitorFailures = 0
                if (!isStopping && isXrayActive && state.value == CONNECTED) {
                    startConnectivityMonitor()
                }
            } else {
                connectivityMonitorFailures = 0
                reconnectActiveChain(
                    if (reason.isBlank()) "Connection check after wake failed"
                    else "Connection check failed after wake: $reason"
                )
            }
        }
    }

    private suspend fun restartUserspaceChain(
        config: JSONObject,
        vpnBuilder: Builder,
        protect: (Int) -> Boolean,
        reason: String
    ) {
        Log.w(TAG, "Restarting OXray userspace chain: $reason")
        stopXray()
        openVpn.stopVpn()
        isStopping = false
        isXrayActive = false
        currentStrategyIndex = 0
        currentStrategyPosition = 0
        openVpnUnderlayMode = true
        state.value = RECONNECTING

        val underlay = withContext(Dispatchers.IO) {
            openVpn.startUserspace(config, protect)
        }
        currentUnderlaySettings = underlay
        if (!isStopping) {
            startXrayOverOpenVpn(config, underlay, vpnBuilder, protect)
        }
    }

    private suspend fun startXrayOverOpenVpn(
        config: JSONObject,
        underlay: OpenVpnUnderlaySettings,
        vpnBuilder: Builder,
        protect: (Int) -> Boolean
    ) {
        isStartingXray = true
        xrayState.value = UNKNOWN
        restartXrayForStrategy = false
        currentSocksProxy = null
        xray = OxrayXray(protectDialerSockets = false).also {
            it.initialize(
                context = context,
                state = xrayState,
                onError = { error -> handleChildError("Xray", error) }
            )
        }

        try {
            val preparedConfig = prepareXrayConfigForOpenVpnUnderlay(config, underlay)
            Log.i(
                TAG,
                "Starting Xray over OpenVPN userspace underlay at ${underlay.host}:${underlay.port}"
            )
            withContext(Dispatchers.IO) {
                xray?.startVpn(preparedConfig, vpnBuilder, protect)
            }
            currentSocksProxy = extractSocksProxySettings(preparedConfig)
        } catch (e: Exception) {
            handleChildError("Xray", e.message ?: e.toString())
        } finally {
            isStartingXray = false
        }
    }

    private suspend fun onOpenVpnStateChanged(protocolState: ProtocolState) {
        if (openVpnUnderlayMode) {
            onOpenVpnUnderlayStateChanged(protocolState)
            return
        }

        when (protocolState) {
            CONNECTED -> {
                openVpnStartupJob?.cancel()
                openVpnStartupJob = null
                if (isStopping || isStartingXray || isXrayActive) {
                    return
                }

                emitProgress(
                    message = "OpenVPN connected, starting Xray",
                    openVpnStep = StatusStepState.SUCCESS,
                    xrayStep = StatusStepState.ACTIVE,
                    readMcuStep = StatusStepState.PENDING,
                    speedTestStep = StatusStepState.PENDING,
                    healthCheckStep = StatusStepState.PENDING,
                    protocolState = CONNECTING
                )
                startXrayWithStrategy(0)
            }

            RECONNECTING -> {
                diagnosticsJob?.cancel()
                diagnosticsJob = null
                connectivityMonitorJob?.cancel()
                connectivityMonitorJob = null
                stopXray()
                connectivityMonitorFailures = 0
                state.value = RECONNECTING
                emitProgress(
                    message = "Reconnecting OpenVPN",
                    openVpnStep = StatusStepState.ACTIVE,
                    xrayStep = StatusStepState.ACTIVE,
                    readMcuStep = StatusStepState.ACTIVE,
                    speedTestStep = StatusStepState.ACTIVE,
                    healthCheckStep = StatusStepState.ACTIVE,
                    protocolState = RECONNECTING
                )
            }

            DISCONNECTING -> {
                diagnosticsJob?.cancel()
                diagnosticsJob = null
                connectivityMonitorJob?.cancel()
                connectivityMonitorJob = null
                openVpnStartupJob?.cancel()
                openVpnStartupJob = null
                stopXray()
                connectivityMonitorFailures = 0
                state.value = DISCONNECTING
                emitStatus(Status.build { setState(DISCONNECTING) })
            }

            DISCONNECTED -> {
                diagnosticsJob?.cancel()
                diagnosticsJob = null
                connectivityMonitorJob?.cancel()
                connectivityMonitorJob = null
                openVpnStartupJob?.cancel()
                openVpnStartupJob = null
                stopXray()
                connectivityMonitorFailures = 0
                if (!isStopping) {
                    state.value = DISCONNECTED
                }
                emitStatus(Status.build { setState(DISCONNECTED) })
            }

            CONNECTING -> {
                state.value = CONNECTING
                emitProgress(
                    message = "Connecting OpenVPN",
                    openVpnStep = StatusStepState.ACTIVE,
                    xrayStep = StatusStepState.PENDING,
                    readMcuStep = StatusStepState.PENDING,
                    speedTestStep = StatusStepState.PENDING,
                    healthCheckStep = StatusStepState.PENDING,
                    protocolState = CONNECTING
                )
            }
            UNKNOWN -> {}
        }
    }

    private fun onXrayStateChanged(protocolState: ProtocolState) {
        when (protocolState) {
            CONNECTED -> {
                isXrayActive = true
                if (openVpnUnderlayMode) {
                    diagnosticsJob?.cancel()
                    diagnosticsJob = null
                    connectivityMonitorJob?.cancel()
                    connectivityMonitorJob = null
                    connectivityMonitorFailures = 0
                    state.value = CONNECTED
                    emitProgress(
                        message = "Connected through Xray over OpenVPN userspace underlay",
                        openVpnStep = StatusStepState.SUCCESS,
                        xrayStep = StatusStepState.SUCCESS,
                        readMcuStep = StatusStepState.SUCCESS,
                        speedTestStep = StatusStepState.SUCCESS,
                        healthCheckStep = StatusStepState.SUCCESS,
                        protocolState = CONNECTED
                    )
                    return
                }
                state.value = if (currentStrategyPosition > 0) RECONNECTING else CONNECTING
                emitProgress(
                    message = "Running connection checks",
                    openVpnStep = StatusStepState.SUCCESS,
                    xrayStep = StatusStepState.SUCCESS,
                    readMcuStep = StatusStepState.ACTIVE,
                    speedTestStep = StatusStepState.ACTIVE,
                    healthCheckStep = StatusStepState.ACTIVE,
                    protocolState = if (currentStrategyPosition > 0) RECONNECTING else CONNECTING
                )
                diagnosticsJob?.cancel()
                connectivityMonitorJob?.cancel()
                connectivityMonitorJob = null
                diagnosticsJob = scope.launch(Dispatchers.IO) {
                    delay(DIAGNOSTIC_DELAY_MS)
                    runDiagnosticsForCurrentStrategy()
                }
            }

            CONNECTING -> {
                state.value = CONNECTING
                emitProgress(
                    message = "Starting Xray tunnel",
                    openVpnStep = StatusStepState.SUCCESS,
                    xrayStep = StatusStepState.ACTIVE,
                    readMcuStep = StatusStepState.PENDING,
                    speedTestStep = StatusStepState.PENDING,
                    healthCheckStep = StatusStepState.PENDING,
                    protocolState = CONNECTING
                )
            }

            RECONNECTING -> {
                state.value = RECONNECTING
                emitProgress(
                    message = "Restarting Xray tunnel",
                    openVpnStep = StatusStepState.ACTIVE,
                    xrayStep = StatusStepState.ACTIVE,
                    readMcuStep = StatusStepState.ACTIVE,
                    speedTestStep = StatusStepState.ACTIVE,
                    healthCheckStep = StatusStepState.ACTIVE,
                    protocolState = RECONNECTING
                )
            }

            DISCONNECTING -> {
                state.value = DISCONNECTING
                emitStatus(Status.build { setState(DISCONNECTING) })
            }

            DISCONNECTED -> {
                diagnosticsJob?.cancel()
                diagnosticsJob = null
                connectivityMonitorJob?.cancel()
                connectivityMonitorJob = null
                val shouldRestart = restartXrayForStrategy
                restartXrayForStrategy = false
                isXrayActive = false

                if (shouldRestart) {
                    state.value = RECONNECTING
                    val nextStrategyPosition = currentStrategyPosition + 1
                    if (nextStrategyPosition < strategySequence.size) {
                        scope.launch {
                            startXrayWithStrategy(nextStrategyPosition)
                        }
                    } else {
                        handleChildError("Xray", "All OpenVPN chained routing strategies failed diagnostics; refusing direct Xray fallback")
                    }
                    return
                }

                if (!isStopping && openVpnState.value == DISCONNECTED) {
                    state.value = DISCONNECTED
                }
                if (!shouldRestart && state.value != RECONNECTING) {
                    emitStatus(Status.build { setState(this@Oxray.state.value) })
                }
            }

            UNKNOWN -> {}
        }
    }

    private fun handleChildError(child: String, message: String) {
        if (isStopping) {
            return
        }

        if (child == "OpenVPN" &&
            (message.contains("TRANSPORT_ERROR", ignoreCase = true)
                || message.contains("NETWORK_RECV_ERROR", ignoreCase = true)
                || message.contains("NETWORK_EOF_ERROR", ignoreCase = true))
        ) {
            diagnosticsJob?.cancel()
            diagnosticsJob = null
            connectivityMonitorJob?.cancel()
            connectivityMonitorJob = null
            connectivityMonitorFailures = 0
            reconnectActiveChain("OpenVPN transport error, reconnecting")
            return
        }

        isStopping = true
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        openVpnStartupJob?.cancel()
        openVpnStartupJob = null
        connectivityMonitorFailures = 0
        if (child == "OpenVPN") {
            emitProgress(
                message = "OpenVPN tunnel failed",
                openVpnStep = StatusStepState.FAILURE,
                xrayStep = StatusStepState.PENDING,
                readMcuStep = StatusStepState.PENDING,
                speedTestStep = StatusStepState.PENDING,
                healthCheckStep = StatusStepState.FAILURE,
                protocolState = DISCONNECTED
            )
        } else {
            emitProgress(
                message = "Xray tunnel failed",
                openVpnStep = StatusStepState.SUCCESS,
                xrayStep = StatusStepState.FAILURE,
                readMcuStep = StatusStepState.PENDING,
                speedTestStep = StatusStepState.PENDING,
                healthCheckStep = StatusStepState.FAILURE,
                protocolState = DISCONNECTED
            )
        }
        onError("OXray $child error: $message")
        stopXray()
        openVpn.stopVpn()
        state.value = DISCONNECTED
    }

    private fun stopXray() {
        xray?.let { xrayProtocol ->
            if (isXrayActive || isStartingXray || xrayState.value != UNKNOWN) {
                xrayProtocol.stopVpn()
            }
        }
        xray = null
        currentSocksProxy = null
        isXrayActive = false
        xrayState.value = UNKNOWN
    }

    private fun emitProgress(
        message: String,
        openVpnStep: StatusStepState,
        xrayStep: StatusStepState,
        readMcuStep: StatusStepState,
        speedTestStep: StatusStepState,
        healthCheckStep: StatusStepState,
        protocolState: ProtocolState = state.value
    ) {
        emitStatus(
            Status.build {
                setState(protocolState)
                setMessage(message)
                setSteps(
                    listOf(
                        StatusStep(STEP_OPENVPN, openVpnStep),
                        StatusStep(STEP_XRAY, xrayStep),
                        StatusStep(STEP_READMCU, readMcuStep),
                        StatusStep(STEP_SPEEDTEST, speedTestStep),
                        StatusStep(STEP_HEALTHCHECK, healthCheckStep)
                    )
                )
            }
        )
    }

    private fun emitProbeProgress(protocolState: ProtocolState, results: ProbeRoundResult, message: String) {
        val statesByUrl = results.urlResults.associate { result ->
            result.url to if (result.success) StatusStepState.SUCCESS else StatusStepState.FAILURE
        }
        val failedChecks = buildList {
            if (statesByUrl["https://api.ipify.org/"] == StatusStepState.FAILURE) {
                add("ipify")
            }
            if (statesByUrl["http://example.com/"] == StatusStepState.FAILURE) {
                add("example")
            }
            if (statesByUrl["http://neverssl.com/"] == StatusStepState.FAILURE) {
                add("neverssl")
            }
        }
        val statusMessage = when {
            results.hasApplicationConnectivity() -> "$message OK"
            results.hasUsableConnectivity() -> "$message transport OK, site checks failed"
            failedChecks.isEmpty() -> "$message failed"
            else -> "$message failed: ${failedChecks.joinToString(", ")}"
        }

        emitProgress(
            message = statusMessage,
            openVpnStep = StatusStepState.SUCCESS,
            xrayStep = StatusStepState.SUCCESS,
            readMcuStep = statesByUrl["http://example.com/"] ?: StatusStepState.PENDING,
            speedTestStep = statesByUrl["http://neverssl.com/"] ?: StatusStepState.PENDING,
            healthCheckStep = when {
                results.hasApplicationConnectivity() && protocolState == CONNECTED -> StatusStepState.SUCCESS
                results.hasApplicationConnectivity() -> StatusStepState.ACTIVE
                results.hasUsableConnectivity() && protocolState == CONNECTED -> StatusStepState.SUCCESS
                results.hasUsableConnectivity() -> StatusStepState.ACTIVE
                else -> StatusStepState.FAILURE
            },
            protocolState = protocolState
        )
    }

    private fun emitProbeCycleStarted(protocolState: ProtocolState, message: String) {
        emitProgress(
            message = message,
            openVpnStep = StatusStepState.SUCCESS,
            xrayStep = StatusStepState.SUCCESS,
            readMcuStep = StatusStepState.ACTIVE,
            speedTestStep = StatusStepState.ACTIVE,
            healthCheckStep = StatusStepState.ACTIVE,
            protocolState = protocolState
        )
    }

    private suspend fun runCheckRound(
        strategy: RoutingStrategy,
        protocolState: ProtocolState,
        message: String
    ): ProbeRoundResult {
        emitProbeCycleStarted(protocolState, message)
        val results = runProbeRound(strategy.name)
        emitProbeProgress(protocolState, results, message)
        return results
    }

    private fun createOpenVpnConfig(config: JSONObject): JSONObject =
        JSONObject(config.toString()).apply {
            put(PROTOCOL, "OPENVPN")
            put("killSwitchOption", false)
        }

    private fun prepareXrayConfig(config: JSONObject, strategy: RoutingStrategy): JSONObject {
        val xrayConfig = JSONObject(config.toString()).apply {
            put(PROTOCOL, "XRAY")
        }
        val preferredDnsServers = buildPreferredDnsServers(xrayConfig, strategy)
        applyDnsServers(xrayConfig, preferredDnsServers)

        val openVpnHost = config.optString(HOST_NAME).takeIf { it.isNotBlank() }
        val openVpnRemoteIp = resolveIp(openVpnHost)
        val xrayRemoteHost = extractXrayRemoteHost(xrayConfig)
        val xrayRemoteIp = resolveIp(xrayRemoteHost)
        val tunnelServerAddress = openVpn.tunnelServerAddress?.takeIf { it.isNotBlank() }
        val tunnelLocalAddress = openVpn.tunnelLocalAddress?.takeIf { it.isNotBlank() }

        openVpnRemoteIp?.let {
            val excludedAddresses = xrayConfig.optJSONArray(EXCLUDED_ADDRESSES) ?: JSONArray()
            appendUnique(excludedAddresses, it)
            xrayConfig.put(EXCLUDED_ADDRESSES, excludedAddresses)
        }

        if (strategy.useSendThrough) {
            tunnelLocalAddress?.let { applySendThrough(xrayConfig, it) }
            xrayConfig.put("mtu", OXRAY_CHAIN_MTU.toString())
        } else {
            removeSendThrough(xrayConfig)
        }

        if (tunnelServerAddress != null &&
            openVpnRemoteIp != null &&
            xrayRemoteIp != null &&
            openVpnRemoteIp == xrayRemoteIp
        ) {
            replaceXrayRemoteHost(xrayConfig, tunnelServerAddress)
            xrayConfig.put(HOST_NAME, tunnelServerAddress)
        } else if (!xrayRemoteHost.isNullOrBlank()) {
            xrayConfig.put(HOST_NAME, xrayRemoteHost)
        }

        Log.i(
            TAG,
            "Prepared Xray config for '${strategy.name}': dns=$preferredDnsServers, " +
                "openVpnRemote=${openVpnRemoteIp ?: "-"}, xrayRemote=${xrayRemoteIp ?: xrayRemoteHost ?: "-"}, " +
                "tunnelLocal=${tunnelLocalAddress ?: "-"}, tunnelServer=${tunnelServerAddress ?: "-"}"
        )

        return xrayConfig
    }

    private fun prepareXrayConfigForOpenVpnUnderlay(
        config: JSONObject,
        underlay: OpenVpnUnderlaySettings
    ): JSONObject {
        val xrayConfig = JSONObject(config.toString()).apply {
            put(PROTOCOL, "XRAY")
            put("mtu", OXRAY_CHAIN_MTU.toString())
            put("oxrayOpenVpnUnderlay", true)
        }

        val xrayJsonConfig = xrayConfig.optJSONObject(XRAY_CONFIG_DATA)
            ?: throw IllegalArgumentException("xray_config_data not found")
        val outbounds = xrayJsonConfig.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Xray outbounds not found")
        if (outbounds.length() == 0) {
            throw IllegalArgumentException("Xray outbounds are empty")
        }

        val cleanedOutbounds = JSONArray()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("tag") == OXRAY_OPENVPN_UNDERLAY_TAG) {
                continue
            }
            outbound.remove("sendThrough")
            cleanedOutbounds.put(outbound)
        }

        val mainOutbound = cleanedOutbounds.getJSONObject(0)
        mainOutbound.put(
            "proxySettings",
            JSONObject()
                .put("tag", OXRAY_OPENVPN_UNDERLAY_TAG)
                .put("transportLayer", true)
        )

        val server = JSONObject()
            .put("address", underlay.host)
            .put("port", underlay.port)
            .put(
                "users",
                JSONArray().put(
                    JSONObject()
                        .put("user", underlay.username)
                        .put("pass", underlay.password)
                )
            )
        val underlayOutbound = JSONObject()
            .put("tag", OXRAY_OPENVPN_UNDERLAY_TAG)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        cleanedOutbounds.put(underlayOutbound)
        xrayJsonConfig.put("outbounds", cleanedOutbounds)

        val dnsServers = buildOpenVpnUnderlayDnsServers(config, xrayConfig)
        applyDnsServers(xrayConfig, dnsServers)

        extractXrayRemoteHost(xrayConfig)?.takeIf { it.isNotBlank() }?.let { remoteHost ->
            xrayConfig.put(HOST_NAME, remoteHost)
        }

        Log.i(
            TAG,
            "Prepared Xray config for OpenVPN underlay: proxyTag=$OXRAY_OPENVPN_UNDERLAY_TAG, " +
                "socks=${underlay.host}:${underlay.port}, remote=${xrayConfig.optString(HOST_NAME)}, " +
                "dns=$dnsServers"
        )
        return xrayConfig
    }

    private fun buildOpenVpnUnderlayDnsServers(sourceConfig: JSONObject, xrayConfig: JSONObject): List<String> {
        val remoteHosts = linkedSetOf<String>()
        sourceConfig.optString(HOST_NAME)
            .takeIf { it.isNotBlank() }
            ?.let(remoteHosts::add)
        extractXrayRemoteHost(sourceConfig)
            ?.takeIf { it.isNotBlank() }
            ?.let(remoteHosts::add)
        extractXrayRemoteHost(xrayConfig)
            ?.takeIf { it.isNotBlank() }
            ?.let(remoteHosts::add)

        val remoteIps = remoteHosts.mapNotNullTo(linkedSetOf()) { resolveIp(it) }

        val candidates = linkedSetOf<String>()
        appendDnsCandidates(sourceConfig, candidates)
        openVpn.tunnelDnsServers.forEach { dns ->
            normalizeIpv4DnsCandidate(dns)?.let(candidates::add)
        }
        OXRAY_UNDERLAY_FALLBACK_DNS.forEach { dns ->
            normalizeIpv4DnsCandidate(dns)?.let(candidates::add)
        }

        val filtered = candidates.filterNot { dns ->
            remoteHosts.any { host -> dns.equals(host, ignoreCase = true) } || dns in remoteIps
        }

        return filtered.ifEmpty { OXRAY_UNDERLAY_FALLBACK_DNS }
    }

    private fun appendDnsCandidates(config: JSONObject, candidates: MutableSet<String>) {
        config.optJSONArray("dnsServers")?.let { dnsArray ->
            for (index in 0 until dnsArray.length()) {
                normalizeIpv4DnsCandidate(dnsArray.optString(index))?.let(candidates::add)
            }
        }
        normalizeIpv4DnsCandidate(config.optString("dns1"))?.let(candidates::add)
        normalizeIpv4DnsCandidate(config.optString("dns2"))?.let(candidates::add)
    }

    private fun normalizeIpv4DnsCandidate(candidate: String): String? {
        var dns = candidate.trim()
        if (dns.isBlank()) {
            return null
        }
        if (dns.startsWith("tcp://", ignoreCase = true)) {
            dns = dns.substringAfter("://")
        }
        if (dns.contains("://")) {
            return null
        }
        dns = dns.substringBefore("#").trim()
        if (!IPV4_REGEX.matches(dns)) {
            return null
        }
        val normalized = runCatching { parseInetAddress(dns).ip }.getOrNull() ?: return null
        return normalized.takeIf { isPublicIpv4DnsCandidate(it) }
    }

    private fun isPublicIpv4DnsCandidate(address: String): Boolean {
        val inetAddress = runCatching { parseInetAddress(address) }.getOrNull() as? Inet4Address ?: return false
        val bytes = inetAddress.address.map { it.toInt() and 0xff }
        val first = bytes[0]
        val second = bytes[1]
        return when {
            first == 0 -> false
            first == 10 -> false
            first == 100 && second in 64..127 -> false
            first == 127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun buildPreferredDnsServers(config: JSONObject, strategy: RoutingStrategy): List<String> {
        val directCandidates = linkedSetOf<String>()
        config.optJSONArray("dnsServers")?.let { dnsArray ->
            for (index in 0 until dnsArray.length()) {
                dnsArray.optString(index)
                    .takeIf { it.isNotBlank() }
                    ?.let(directCandidates::add)
            }
        }
        config.optString("dns1")
            .takeIf { it.isNotBlank() }
            ?.let(directCandidates::add)
        config.optString("dns2")
            .takeIf { it.isNotBlank() }
            ?.let(directCandidates::add)

        val tunnelCandidates = linkedSetOf<String>()
        openVpn.tunnelDnsServers.forEach { dns ->
            if (dns.isNotBlank()) {
                tunnelCandidates += dns
            }
        }

        if (!strategy.useSendThrough) {
            return (tunnelCandidates + directCandidates).toList()
        }

        val remoteHost = extractXrayRemoteHost(config)
        val remoteIp = resolveIp(remoteHost)
        val filteredDirectCandidates = directCandidates
            .filterNot { matchesRemoteHost(it, remoteHost, remoteIp) }
        val filteredTunnelCandidates = tunnelCandidates
            .filterNot { matchesRemoteHost(it, remoteHost, remoteIp) }

        val dnsServers = linkedSetOf<String>()

        filteredDirectCandidates.forEach { dns ->
            if (!isPrivateDnsCandidate(dns)) {
                dnsServers += dns
            }
        }

        filteredTunnelCandidates.forEach { dns ->
            if (isPrivateDnsCandidate(dns)) {
                dnsServers += dns
            }
        }

        filteredDirectCandidates.forEach(dnsServers::add)
        filteredTunnelCandidates.forEach(dnsServers::add)

        return dnsServers.toList()
    }

    private fun applyDnsServers(config: JSONObject, dnsServers: List<String>) {
        val dnsArray = JSONArray()
        dnsServers.forEach(dnsArray::put)
        config.put("dnsServers", dnsArray)
        config.put("dns1", dnsServers.getOrElse(0) { "" })
        config.put("dns2", dnsServers.getOrElse(1) { "" })
    }

    private fun extractXrayRemoteHost(config: JSONObject): String? =
        config.optJSONObject(XRAY_CONFIG_DATA)
            ?.optJSONArray("outbounds")
            ?.optJSONObject(0)
            ?.optJSONObject("settings")
            ?.optJSONArray("vnext")
            ?.optJSONObject(0)
            ?.optString("address")
            ?.takeIf { it.isNotBlank() }

    private fun replaceXrayRemoteHost(config: JSONObject, host: String) {
        val xrayConfig = config.optJSONObject(XRAY_CONFIG_DATA) ?: return
        val outbounds = xrayConfig.optJSONArray("outbounds") ?: return
        val outbound = outbounds.optJSONObject(0) ?: return
        val settings = outbound.optJSONObject("settings") ?: return
        val vnext = settings.optJSONArray("vnext") ?: return
        val remote = vnext.optJSONObject(0) ?: return

        remote.put("address", host)
    }

    private fun applySendThrough(config: JSONObject, localAddress: String) {
        val xrayConfig = config.optJSONObject(XRAY_CONFIG_DATA) ?: return
        val outbounds = xrayConfig.optJSONArray("outbounds") ?: return
        val outbound = outbounds.optJSONObject(0) ?: return
        outbound.put("sendThrough", localAddress)
    }

    private fun removeSendThrough(config: JSONObject) {
        val xrayConfig = config.optJSONObject(XRAY_CONFIG_DATA) ?: return
        val outbounds = xrayConfig.optJSONArray("outbounds") ?: return
        val outbound = outbounds.optJSONObject(0) ?: return
        outbound.remove("sendThrough")
    }

    private fun resolveIp(address: String?): String? = address
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { parseInetAddress(it).ip }.getOrNull()
        }

    private fun isPrivateDnsCandidate(address: String): Boolean {
        val inetAddress = runCatching { parseInetAddress(address) }.getOrNull() ?: return false
        return when (inetAddress) {
            is Inet4Address -> {
                val bytes = inetAddress.address
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                when {
                    first == 10 -> true
                    first == 172 && second in 16..31 -> true
                    first == 192 && second == 168 -> true
                    first == 169 && second == 254 -> true
                    inetAddress.isLoopbackAddress -> true
                    else -> false
                }
            }
            is Inet6Address -> {
                inetAddress.isLoopbackAddress ||
                    inetAddress.isLinkLocalAddress ||
                    inetAddress.isSiteLocalAddress ||
                    ((inetAddress.address[0].toInt() and 0xfe) == 0xfc)
            }
            else -> false
        }
    }

    private suspend fun onOpenVpnUnderlayStateChanged(protocolState: ProtocolState) {
        when (protocolState) {
            CONNECTED -> {
                openVpnStartupJob?.cancel()
                openVpnStartupJob = null
                if (isStopping) {
                    return
                }

                if (isXrayActive) {
                    connectivityMonitorFailures = 0
                    state.value = CONNECTED
                    emitProgress(
                        message = "Connected through Xray over OpenVPN userspace underlay",
                        openVpnStep = StatusStepState.SUCCESS,
                        xrayStep = StatusStepState.SUCCESS,
                        readMcuStep = StatusStepState.SUCCESS,
                        speedTestStep = StatusStepState.SUCCESS,
                        healthCheckStep = StatusStepState.SUCCESS,
                        protocolState = CONNECTED
                    )
                    return
                }

                if (!isStartingXray) {
                    val config = sourceConfig ?: return
                    val underlay = currentUnderlaySettings ?: return
                    val activeProtect = protect ?: return
                    emitProgress(
                        message = "OpenVPN userspace underlay reconnected, starting Xray",
                        openVpnStep = StatusStepState.SUCCESS,
                        xrayStep = StatusStepState.ACTIVE,
                        readMcuStep = StatusStepState.PENDING,
                        speedTestStep = StatusStepState.PENDING,
                        healthCheckStep = StatusStepState.PENDING,
                        protocolState = CONNECTING
                    )
                    startXrayOverOpenVpn(config, underlay, newVpnBuilder(), activeProtect)
                }
            }

            RECONNECTING -> {
                diagnosticsJob?.cancel()
                diagnosticsJob = null
                connectivityMonitorJob?.cancel()
                connectivityMonitorJob = null
                connectivityMonitorFailures = 0
                state.value = RECONNECTING
                emitProgress(
                    message = "Reconnecting OpenVPN userspace underlay",
                    openVpnStep = StatusStepState.ACTIVE,
                    xrayStep = if (isXrayActive) StatusStepState.SUCCESS else StatusStepState.ACTIVE,
                    readMcuStep = StatusStepState.ACTIVE,
                    speedTestStep = StatusStepState.ACTIVE,
                    healthCheckStep = StatusStepState.ACTIVE,
                    protocolState = RECONNECTING
                )
            }

            DISCONNECTING -> {
                state.value = DISCONNECTING
                emitStatus(Status.build { setState(DISCONNECTING) })
            }

            DISCONNECTED -> {
                currentUnderlaySettings = null
                if (!isStopping) {
                    reconnectActiveChain("OpenVPN userspace underlay disconnected")
                }
            }

            CONNECTING -> {
                state.value = CONNECTING
                emitProgress(
                    message = "Connecting OpenVPN userspace underlay",
                    openVpnStep = StatusStepState.ACTIVE,
                    xrayStep = if (isXrayActive) StatusStepState.SUCCESS else StatusStepState.PENDING,
                    readMcuStep = StatusStepState.PENDING,
                    speedTestStep = StatusStepState.PENDING,
                    healthCheckStep = StatusStepState.PENDING,
                    protocolState = CONNECTING
                )
            }
            UNKNOWN -> {}
        }
    }

    private fun matchesRemoteHost(candidate: String, remoteHost: String?, remoteIp: String?): Boolean {
        if (candidate.isBlank()) {
            return false
        }
        if (!remoteHost.isNullOrBlank() && candidate.equals(remoteHost, ignoreCase = true)) {
            return true
        }

        val candidateIp = resolveIp(candidate)
        return !candidateIp.isNullOrBlank() && !remoteIp.isNullOrBlank() && candidateIp == remoteIp
    }

    private fun appendUnique(array: JSONArray, value: String) {
        for (index in 0 until array.length()) {
            if (array.optString(index) == value) {
                return
            }
        }
        array.put(value)
    }

    private fun strategyPrefs() = context.getSharedPreferences(STRATEGY_PREFS, Context.MODE_PRIVATE)

    private fun strategyPreferenceKey(config: JSONObject): String {
        val host = config.optString(HOST_NAME).ifBlank { "unknown" }
        return "strategy.$connectionTransportKey.$host"
    }

    private fun loadPreferredStrategyIndex(config: JSONObject): Int? =
        strategyPrefs()
            .getInt(strategyPreferenceKey(config), -1)
            .takeIf { it in routingStrategies.indices && routingStrategies[it].useSendThrough }

    private fun savePreferredStrategyIndex(config: JSONObject, strategyIndex: Int) {
        strategyPrefs()
            .edit()
            .putInt(strategyPreferenceKey(config), strategyIndex)
            .apply()
    }

    private fun buildStrategySequence(config: JSONObject): List<Int> {
        val chainedStrategyIndices = routingStrategies.indices
            .filter { routingStrategies[it].useSendThrough }
        val defaultIndex = if (connectionTransportKey == "CELLULAR") {
            routingStrategies.indexOfFirst { it.name == "sendThrough+no-protect" }
                .takeIf { it >= 0 }
                ?: chainedStrategyIndices.firstOrNull()
                ?: 0
        } else {
            chainedStrategyIndices.firstOrNull() ?: 0
        }
        val preferredIndex = if (connectionTransportKey == "CELLULAR") {
            val savedIndex = loadPreferredStrategyIndex(config)
            if (savedIndex != null && savedIndex != defaultIndex) {
                Log.i(
                    TAG,
                    "Ignoring saved OXray strategy '${routingStrategies[savedIndex].name}' on CELLULAR; " +
                        "forcing '${routingStrategies[defaultIndex].name}' first to route Xray through the OpenVPN upstream"
                )
            }
            defaultIndex
        } else {
            loadPreferredStrategyIndex(config) ?: defaultIndex
        }
        val sequence = buildList {
            add(preferredIndex)
            chainedStrategyIndices
                .filterNot { it == preferredIndex }
                .forEach(::add)
        }

        Log.i(
            TAG,
            "OXray chained strategy order for transport=$connectionTransportKey, host=${config.optString(HOST_NAME)}: " +
                sequence.map { routingStrategies[it].name }
        )
        return sequence
    }

    private suspend fun startXrayWithStrategy(position: Int) {
        val config = sourceConfig ?: return
        val protect = protect ?: return
        val strategyIndex = strategySequence.getOrNull(position) ?: return
        val strategy = routingStrategies.getOrNull(strategyIndex) ?: return

        currentStrategyIndex = strategyIndex
        currentStrategyPosition = position
        isStartingXray = true
        restartXrayForStrategy = false
        xrayState.value = UNKNOWN
        currentSocksProxy = null
        xray = OxrayXray(strategy.protectDialerSockets).also {
            it.initialize(
                context = context,
                state = xrayState,
                onError = { error -> handleChildError("Xray", error) }
            )
        }

        Log.i(
            TAG,
            "Starting Xray strategy '${strategy.name}' " +
                "(sendThrough=${strategy.useSendThrough}, protect=${strategy.protectDialerSockets})"
        )
        Log.i(TAG, "Active network snapshot before '${strategy.name}': ${describeActiveNetwork(context)}")
        emitProgress(
            message = "Starting Xray strategy ${strategy.name}",
            openVpnStep = StatusStepState.SUCCESS,
            xrayStep = StatusStepState.ACTIVE,
            readMcuStep = StatusStepState.PENDING,
            speedTestStep = StatusStepState.PENDING,
            healthCheckStep = StatusStepState.ACTIVE,
            protocolState = if (currentStrategyPosition > 0) RECONNECTING else CONNECTING
        )

        try {
            val preparedConfig = prepareXrayConfig(config, strategy)
            val vpnBuilder = newVpnBuilder()
            withContext(Dispatchers.IO) {
                xray?.startVpn(preparedConfig, vpnBuilder, protect)
            }
            currentSocksProxy = extractSocksProxySettings(preparedConfig)
        } catch (e: Exception) {
            handleChildError("Xray", e.message ?: e.toString())
        } finally {
            isStartingXray = false
        }
    }

    private suspend fun runDiagnosticsForCurrentStrategy() {
        val strategy = routingStrategies.getOrNull(currentStrategyIndex) ?: return

        val protocolState = if (currentStrategyPosition > 0) RECONNECTING else CONNECTING
        var results = runCheckRound(strategy, protocolState, "Checking sites on ${strategy.name}")
        if (results.hasApplicationConnectivity()) {
            sourceConfig?.let { savePreferredStrategyIndex(it, currentStrategyIndex) }
            connectivityMonitorFailures = 0
            isXrayActive = true
            state.value = CONNECTED
            startConnectivityMonitor()
            return
        }

        if (results.hasUsableConnectivity()) {
            for (attempt in 1..DIAGNOSTIC_CONNECT_VERIFICATION_RETRIES) {
                if (isStopping || xrayState.value != CONNECTED) {
                    return
                }

                Log.w(
                    TAG,
                    "Strategy '${strategy.name}' has transport-level connectivity but HTTP checks still fail; " +
                        "retrying verification $attempt/$DIAGNOSTIC_CONNECT_VERIFICATION_RETRIES"
                )

                delay(DIAGNOSTIC_CONNECT_VERIFICATION_RETRY_DELAY_MS)
                results = runCheckRound(
                    strategy,
                    protocolState,
                    "Verifying internet access on ${strategy.name} ($attempt/$DIAGNOSTIC_CONNECT_VERIFICATION_RETRIES)"
                )

                if (results.hasApplicationConnectivity()) {
                    sourceConfig?.let { savePreferredStrategyIndex(it, currentStrategyIndex) }
                    connectivityMonitorFailures = 0
                    isXrayActive = true
                    state.value = CONNECTED
                    startConnectivityMonitor()
                    return
                }

                if (!results.hasUsableConnectivity()) {
                    break
                }
            }
        }

        if (results.hasUsableConnectivity()) {
            Log.w(
                TAG,
                "Strategy '${strategy.name}' reached only transport-level connectivity after retries; " +
                    "HTTP site probes still fail, keeping the OpenVPN-underlay chain up"
            )
            sourceConfig?.let { savePreferredStrategyIndex(it, currentStrategyIndex) }
            connectivityMonitorFailures = 0
            isXrayActive = true
            state.value = CONNECTED
            startConnectivityMonitor()
            return
        } else {
            Log.w(TAG, "Strategy '${strategy.name}' failed diagnostics, trying next chained routing mode")
        }
        restartXrayForStrategy = true
        xray?.stopVpn()
    }

    private fun startConnectivityMonitor() {
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(CONNECTIVITY_MONITOR_INTERVAL_MS)

                if (isStopping || !isXrayActive || state.value != CONNECTED) {
                    break
                }

                val strategy = routingStrategies.getOrNull(currentStrategyIndex) ?: break
                val results = runCheckRound(strategy, CONNECTED, "Health check")
                if (results.hasApplicationConnectivity()) {
                    sourceConfig?.let { savePreferredStrategyIndex(it, currentStrategyIndex) }
                    if (connectivityMonitorFailures > 0) {
                        Log.i(TAG, "Connectivity monitor recovered on strategy '${strategy.name}'")
                    }
                    connectivityMonitorFailures = 0
                    continue
                }

                if (results.hasUsableConnectivity()) {
                    if (connectivityMonitorFailures > 0) {
                        Log.i(
                            TAG,
                            "Connectivity monitor recovered at transport level on strategy '${strategy.name}'"
                        )
                    }
                    connectivityMonitorFailures = 0
                    Log.w(
                        TAG,
                        "Health check HTTP probes failed on strategy '${strategy.name}', " +
                            "but OpenVPN-underlay TCP probes still pass; keeping VPN up"
                    )
                    continue
                }

                connectivityMonitorFailures += 1
                Log.w(
                    TAG,
                    "Connectivity monitor failed on strategy '${strategy.name}', " +
                        "attempt $connectivityMonitorFailures/$CONNECTIVITY_MONITOR_FAILURE_THRESHOLD"
                )

                if (connectivityMonitorFailures >= CONNECTIVITY_MONITOR_FAILURE_THRESHOLD) {
                    connectivityMonitorFailures = 0
                    reconnectActiveChain("Connectivity monitor probes failed")
                    break
                }
            }
        }
    }

    private suspend fun runProbeRound(strategyName: String): ProbeRoundResult = coroutineScope {
        val results = DIAGNOSTIC_PROBE_URLS.map { probe ->
            async(Dispatchers.IO) { probeUrl(probe) }
        }.awaitAll()
        results.forEach {
            Log.i(TAG, "Strategy '$strategyName' diagnostic: $it")
        }

        val tcpResults = buildTcpProbeTargets().map { target ->
            async(Dispatchers.IO) { probeTcp(target) }
        }.awaitAll()
        tcpResults.forEach {
            Log.i(TAG, "Strategy '$strategyName' tcp diagnostic: $it")
        }

        ProbeRoundResult(urlResults = results, tcpResults = tcpResults)
    }

    private fun reconnectActiveChain(reason: String) {
        if (isStopping) {
            return
        }

        val protect = protect ?: return
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        connectivityMonitorFailures = 0
        restartXrayForStrategy = false

        Log.w(TAG, "Reconnecting OXray after health-check failure: $reason")

        state.value = RECONNECTING
        emitProgress(
            message = reason,
            openVpnStep = StatusStepState.ACTIVE,
            xrayStep = StatusStepState.ACTIVE,
            readMcuStep = StatusStepState.ACTIVE,
            speedTestStep = StatusStepState.ACTIVE,
            healthCheckStep = StatusStepState.ACTIVE,
            protocolState = RECONNECTING
        )
        val config = sourceConfig ?: return
        scope.launch {
            restartUserspaceChain(config, newVpnBuilder(), protect, reason)
        }
    }

    private fun scheduleOpenVpnStartupTimeout(reason: String) {
        openVpnStartupJob?.cancel()
        openVpnStartupJob = scope.launch {
            delay(OPENVPN_START_TIMEOUT_MS)

            if (isStopping || openVpnState.value == CONNECTED || isXrayActive) {
                return@launch
            }

            openVpnStartupJob = null
            val activeNetwork = describeActiveNetwork(context)
            val message = "OpenVPN did not connect after ${OPENVPN_START_TIMEOUT_MS / 1000}s during $reason; $activeNetwork"
            Log.w(TAG, message)
            handleChildError("OpenVPN", message)
        }
    }

    private suspend fun probeUrl(url: String): ProbeResult {
        currentSocksProxy?.let { socks ->
            return probeUrlThroughTunnel(url, socks)
        }

        val start = System.currentTimeMillis()
        return runCatching {
            withTimeout(DIAGNOSTIC_PROBE_TIMEOUT_MS) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = DIAGNOSTIC_CONNECT_TIMEOUT_MS
                connection.readTimeout = DIAGNOSTIC_READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "AmneziaVPN-OXray-Diag/1.0")

                try {
                    val code = connection.responseCode
                    val stream = if (code >= 400) connection.errorStream else connection.inputStream
                    val bytes = stream?.use { input ->
                        val buffer = ByteArray(512)
                        input.read(buffer).coerceAtLeast(0)
                    } ?: 0
                    ProbeResult(
                        url = url,
                        success = code > 0,
                        detail = "HTTP $code, ${System.currentTimeMillis() - start} ms, bytes=$bytes",
                        category = ProbeCategory.HTTP
                    )
                } finally {
                    connection.disconnect()
                }
            }
        }.getOrElse { error ->
            ProbeResult(
                url = url,
                success = false,
                detail = "${error::class.java.simpleName}: ${error.message}",
                category = ProbeCategory.HTTP
            )
        }
    }

    private suspend fun probeUrlThroughTunnel(url: String, socks: SocksProxySettings): ProbeResult {
        val start = System.currentTimeMillis()
        return runCatching {
            withTimeout(DIAGNOSTIC_PROBE_TIMEOUT_MS) {
                val parsedUrl = URL(url)
                val port = when {
                    parsedUrl.port > 0 -> parsedUrl.port
                    parsedUrl.protocol.equals("https", ignoreCase = true) -> 443
                    else -> 80
                }
                openSocksTunnel(socks, parsedUrl.host, port).use { rawSocket ->
                    rawSocket.soTimeout = DIAGNOSTIC_READ_TIMEOUT_MS

                    val checkedSocket = if (parsedUrl.protocol.equals("https", ignoreCase = true)) {
                        val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(rawSocket, parsedUrl.host, port, true) as SSLSocket
                        sslSocket.useClientMode = true
                        sslSocket.soTimeout = DIAGNOSTIC_READ_TIMEOUT_MS
                        sslSocket.startHandshake()
                        sslSocket
                    } else {
                        rawSocket
                    }

                    checkedSocket.use { socket ->
                        val requestPath = parsedUrl.file.takeIf { it.isNotBlank() } ?: "/"
                        val request = buildString {
                            append("GET ").append(requestPath).append(" HTTP/1.1\r\n")
                            append("Host: ").append(parsedUrl.host).append("\r\n")
                            append("User-Agent: AmneziaVPN-OXray-Diag/1.0\r\n")
                            append("Connection: close\r\n")
                            append("\r\n")
                        }
                        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()

                        val input = socket.getInputStream()
                        val statusLine = readAsciiLine(input)
                        val statusCode = statusLine
                            .split(' ')
                            .getOrNull(1)
                            ?.toIntOrNull()
                            ?: 0

                        while (true) {
                            val headerLine = readAsciiLine(input)
                            if (headerLine.isEmpty()) {
                                break
                            }
                        }

                        val buffer = ByteArray(512)
                        val bytes = input.read(buffer).coerceAtLeast(0)
                        ProbeResult(
                            url = url,
                            success = statusCode > 0,
                            detail = "HTTP $statusCode via local SOCKS tunnel, ${System.currentTimeMillis() - start} ms, bytes=$bytes",
                            category = ProbeCategory.HTTP
                        )
                    }
                }
            }
        }.getOrElse { error ->
            ProbeResult(
                url = url,
                success = false,
                detail = "${error::class.java.simpleName}: ${error.message}",
                category = ProbeCategory.HTTP
            )
        }
    }

    private fun buildTcpProbeTargets(): List<TcpProbeTarget> {
        val targets = linkedSetOf<TcpProbeTarget>()
        val strategy = routingStrategies.getOrNull(currentStrategyIndex)
            ?: routingStrategies.first()
        buildPreferredDnsServers(sourceConfig ?: JSONObject(), strategy).forEach { dnsServer ->
            resolveIp(dnsServer)?.let { targets += TcpProbeTarget(it, 53, ProbeCategory.DNS_TCP) }
        }
        extractXrayRemoteHost(sourceConfig ?: JSONObject())
            ?.let(::resolveIp)
            ?.let { remoteHost ->
                extractXrayRemotePort(sourceConfig ?: JSONObject())?.let { remotePort ->
                    targets += TcpProbeTarget(remoteHost, remotePort, ProbeCategory.REMOTE_TCP)
                }
            }
        return targets.toList()
    }

    private fun extractXrayRemotePort(config: JSONObject): Int? =
        config.optJSONObject(XRAY_CONFIG_DATA)
            ?.optJSONArray("outbounds")
            ?.optJSONObject(0)
            ?.optJSONObject("settings")
            ?.optJSONArray("vnext")
            ?.optJSONObject(0)
            ?.optInt("port")
            ?.takeIf { it > 0 }

    private suspend fun probeTcp(target: TcpProbeTarget): ProbeResult {
        currentSocksProxy?.let { socks ->
            return probeTcpThroughTunnel(target, socks)
        }

        val label = "tcp://${target.host}:${target.port}"
        val start = System.currentTimeMillis()
        return runCatching {
            withTimeout(DIAGNOSTIC_PROBE_TIMEOUT_MS) {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(target.host, target.port),
                        DIAGNOSTIC_TCP_TIMEOUT_MS
                    )
                }
                ProbeResult(
                    url = label,
                    success = true,
                    detail = "TCP connect ok, ${System.currentTimeMillis() - start} ms",
                    category = target.category
                )
            }
        }.getOrElse { error ->
            ProbeResult(
                url = label,
                success = false,
                detail = "${error::class.java.simpleName}: ${error.message}",
                category = target.category
            )
        }
    }

    private suspend fun probeTcpThroughTunnel(target: TcpProbeTarget, socks: SocksProxySettings): ProbeResult {
        val label = "tcp://${target.host}:${target.port}"
        val start = System.currentTimeMillis()
        return runCatching {
            withTimeout(DIAGNOSTIC_PROBE_TIMEOUT_MS) {
                openSocksTunnel(socks, target.host, target.port).use {
                    ProbeResult(
                        url = label,
                        success = true,
                        detail = "TCP connect via local SOCKS tunnel, ${System.currentTimeMillis() - start} ms",
                        category = target.category
                    )
                }
            }
        }.getOrElse { error ->
            ProbeResult(
                url = label,
                success = false,
                detail = "${error::class.java.simpleName}: ${error.message}",
                category = target.category
            )
        }
    }

    private fun openSocksTunnel(socks: SocksProxySettings, targetHost: String, targetPort: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(socks.host, socks.port), DIAGNOSTIC_TCP_TIMEOUT_MS)
            socket.soTimeout = DIAGNOSTIC_READ_TIMEOUT_MS

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()
            val methodReply = readExact(input, 2)
            if (methodReply[0].toInt() != 0x05 || methodReply[1].toInt() != 0x02) {
                throw java.io.IOException(
                    "SOCKS auth method negotiation failed: ${methodReply.joinToString(",") { (it.toInt() and 0xff).toString() }}"
                )
            }

            val usernameBytes = socks.username.toByteArray(Charsets.UTF_8)
            val passwordBytes = socks.password.toByteArray(Charsets.UTF_8)
            require(usernameBytes.size in 1..255) { "SOCKS username length is invalid" }
            require(passwordBytes.size in 1..255) { "SOCKS password length is invalid" }

            val authRequest = ByteArrayOutputStream()
            authRequest.write(0x01)
            authRequest.write(usernameBytes.size)
            authRequest.write(usernameBytes)
            authRequest.write(passwordBytes.size)
            authRequest.write(passwordBytes)
            output.write(authRequest.toByteArray())
            output.flush()

            val authReply = readExact(input, 2)
            if (authReply[1].toInt() != 0x00) {
                throw java.io.IOException("SOCKS authentication failed: status=${authReply[1].toInt() and 0xff}")
            }

            val connectRequest = ByteArrayOutputStream()
            connectRequest.write(0x05)
            connectRequest.write(0x01)
            connectRequest.write(0x00)

            when {
                IPV4_REGEX.matches(targetHost) -> {
                    connectRequest.write(0x01)
                    targetHost.split('.').forEach { octet ->
                        connectRequest.write(octet.toInt())
                    }
                }
                targetHost.contains(':') -> {
                    connectRequest.write(0x04)
                    connectRequest.write(java.net.InetAddress.getByName(targetHost).address)
                }
                else -> {
                    val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
                    require(hostBytes.size in 1..255) { "SOCKS target host is too long" }
                    connectRequest.write(0x03)
                    connectRequest.write(hostBytes.size)
                    connectRequest.write(hostBytes)
                }
            }

            connectRequest.write((targetPort shr 8) and 0xff)
            connectRequest.write(targetPort and 0xff)
            output.write(connectRequest.toByteArray())
            output.flush()

            val connectReply = readExact(input, 4)
            if (connectReply[1].toInt() != 0x00) {
                throw java.io.IOException("SOCKS connect failed: reply=${connectReply[1].toInt() and 0xff}")
            }

            when (connectReply[3].toInt() and 0xff) {
                0x01 -> readExact(input, 4)
                0x03 -> {
                    val length = readExact(input, 1)[0].toInt() and 0xff
                    readExact(input, length)
                }
                0x04 -> readExact(input, 16)
                else -> throw java.io.IOException("SOCKS reply has unknown ATYP=${connectReply[3].toInt() and 0xff}")
            }
            readExact(input, 2)
            return socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun extractSocksProxySettings(config: JSONObject): SocksProxySettings? {
        val xrayConfig = config.optJSONObject(XRAY_CONFIG_DATA) ?: return null
        val inbounds = xrayConfig.optJSONArray("inbounds") ?: return null
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (!inbound.optString("protocol").equals("socks", ignoreCase = true)) {
                continue
            }

            val settings = inbound.optJSONObject("settings")
            val account = settings
                ?.optJSONArray("accounts")
                ?.optJSONObject(0)

            val port = inbound.optInt("port")
            val username = account?.optString("user").orEmpty()
            val password = account?.optString("pass").orEmpty()
            if (port > 0 && username.isNotBlank() && password.isNotBlank()) {
                return SocksProxySettings(port = port, username = username, password = password)
            }
        }
        return null
    }

    private fun readAsciiLine(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) {
                break
            }
            if (value == '\n'.code) {
                break
            }
            if (value != '\r'.code) {
                output.write(value)
            }
        }
        return output.toString(Charsets.US_ASCII.name())
    }

    private fun readExact(input: java.io.InputStream, byteCount: Int): ByteArray {
        val buffer = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = input.read(buffer, offset, byteCount - offset)
            if (read < 0) {
                throw java.io.EOFException("Unexpected EOF while reading $byteCount bytes from SOCKS tunnel")
            }
            offset += read
        }
        return buffer
    }

    private fun newVpnBuilder(): Builder =
        (context as? VpnService)?.Builder()
            ?: throw IllegalStateException("OXray requires a VpnService context")
}

private class OxrayXray(
    private val protectDialerSockets: Boolean
) : Xray() {
    override fun shouldProtectDialerSockets(): Boolean = protectDialerSockets
}

private data class RoutingStrategy(
    val name: String,
    val useSendThrough: Boolean,
    val protectDialerSockets: Boolean
)

private enum class ProbeCategory {
    HTTP,
    DNS_TCP,
    REMOTE_TCP
}

private data class TcpProbeTarget(
    val host: String,
    val port: Int,
    val category: ProbeCategory
)

private data class SocksProxySettings(
    val host: String = "127.0.0.1",
    val port: Int,
    val username: String,
    val password: String
)

private data class ProbeRoundResult(
    val urlResults: List<ProbeResult>,
    val tcpResults: List<ProbeResult>
) {
    fun hasApplicationConnectivity(): Boolean = urlResults.any { it.success }

    fun hasUsableConnectivity(): Boolean {
        if (hasApplicationConnectivity()) {
            return true
        }

        val hasDnsTcp = tcpResults.any { it.success && it.category == ProbeCategory.DNS_TCP }
        val hasRemoteTcp = tcpResults.any { it.success && it.category == ProbeCategory.REMOTE_TCP }
        return hasDnsTcp && hasRemoteTcp
    }
}

private data class ProbeResult(
    val url: String,
    val success: Boolean,
    val detail: String,
    val category: ProbeCategory
)
