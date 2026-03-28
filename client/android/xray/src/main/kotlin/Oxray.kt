package org.amnezia.vpn.protocol.xray

import android.content.Context
import android.net.VpnService.Builder
import android.net.VpnService
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
import java.net.Socket
import java.net.URL

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
private const val DIAGNOSTIC_PROBE_TIMEOUT_MS = 3000L
private const val DIAGNOSTIC_CONNECT_TIMEOUT_MS = 1500
private const val DIAGNOSTIC_READ_TIMEOUT_MS = 1500
private const val DIAGNOSTIC_TCP_TIMEOUT_MS = 5000
private const val CONNECTIVITY_MONITOR_INTERVAL_MS = 3000L
private const val CONNECTIVITY_MONITOR_FAILURE_THRESHOLD = 3
private const val STRATEGY_PREFS = "oxray_routing"
private val DIAGNOSTIC_PROBE_URLS = listOf(
    "https://readmcu.com/ru/catalog/",
    "https://www.speedtest.net/"
)

class Oxray : Protocol() {

    private val openVpnState = MutableStateFlow(UNKNOWN)
    private val xrayState = MutableStateFlow(UNKNOWN)
    private val openVpn = OxrayOpenVpn()

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
    private var connectionTransportKey = "UNKNOWN"
    private var strategySequence = emptyList<Int>()
    private var connectivityMonitorFailures = 0

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
        xray = null
        connectionTransportKey = "UNKNOWN"
        strategySequence = routingStrategies.indices.toList()
        connectivityMonitorFailures = 0

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
        connectionTransportKey = activeTransportKey(context)
        strategySequence = buildStrategySequence(config)
        connectivityMonitorFailures = 0
        state.value = CONNECTING
        emitProgress(
            message = "Preparing OpenVPN tunnel",
            openVpnStep = StatusStepState.ACTIVE,
            xrayStep = StatusStepState.PENDING,
            readMcuStep = StatusStepState.PENDING,
            speedTestStep = StatusStepState.PENDING,
            healthCheckStep = StatusStepState.PENDING,
            protocolState = CONNECTING
        )

        openVpn.startVpn(createOpenVpnConfig(config), newVpnBuilder(), protect)
    }

    override fun stopVpn() {
        isStopping = true
        isStartingXray = false
        diagnosticsJob?.cancel()
        diagnosticsJob = null
        connectivityMonitorJob?.cancel()
        connectivityMonitorJob = null
        stopXray()
        openVpn.stopVpn()
        connectivityMonitorFailures = 0
        state.value = DISCONNECTED
        emitStatus(Status.build { setState(DISCONNECTED) })
    }

    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        this.protect = protect
        openVpn.reconnectVpn(newVpnBuilder(), protect)
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
            val strategy = routingStrategies.getOrNull(currentStrategyIndex) ?: return@launch
            val results = runCheckRound(strategy, CONNECTED, "Checking connection after wake")
            if (results.any { it.success }) {
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

    private suspend fun onOpenVpnStateChanged(protocolState: ProtocolState) {
        when (protocolState) {
            CONNECTED -> {
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
                        handleChildError("Xray", "All routing strategies failed diagnostics")
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

    private fun emitProbeProgress(protocolState: ProtocolState, results: List<ProbeResult>, message: String) {
        val statesByUrl = results.associate { result ->
            result.url to if (result.success) StatusStepState.SUCCESS else StatusStepState.FAILURE
        }
        val failedChecks = buildList {
            if (statesByUrl["https://readmcu.com/ru/catalog/"] == StatusStepState.FAILURE) {
                add("readmcu")
            }
            if (statesByUrl["https://www.speedtest.net/"] == StatusStepState.FAILURE) {
                add("speedtest")
            }
        }
        val statusMessage = if (failedChecks.isEmpty()) {
            "$message OK"
        } else {
            "$message failed: ${failedChecks.joinToString(", ")}"
        }

        emitProgress(
            message = statusMessage,
            openVpnStep = StatusStepState.SUCCESS,
            xrayStep = StatusStepState.SUCCESS,
            readMcuStep = statesByUrl["https://readmcu.com/ru/catalog/"] ?: StatusStepState.PENDING,
            speedTestStep = statesByUrl["https://www.speedtest.net/"] ?: StatusStepState.PENDING,
            healthCheckStep = if (protocolState == CONNECTED) StatusStepState.SUCCESS else StatusStepState.ACTIVE,
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
    ): List<ProbeResult> {
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
        val preferredDnsServers = buildPreferredDnsServers(xrayConfig)
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

    private fun buildPreferredDnsServers(config: JSONObject): List<String> {
        val dnsServers = linkedSetOf<String>()
        openVpn.tunnelDnsServers.forEach { if (it.isNotBlank()) dnsServers += it }

        config.optJSONArray("dnsServers")?.let { dnsArray ->
            for (index in 0 until dnsArray.length()) {
                dnsArray.optString(index)
                    .takeIf { it.isNotBlank() }
                    ?.let(dnsServers::add)
            }
        }

        config.optString("dns1")
            .takeIf { it.isNotBlank() }
            ?.let(dnsServers::add)
        config.optString("dns2")
            .takeIf { it.isNotBlank() }
            ?.let(dnsServers::add)

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
            .takeIf { it in routingStrategies.indices }

    private fun savePreferredStrategyIndex(config: JSONObject, strategyIndex: Int) {
        strategyPrefs()
            .edit()
            .putInt(strategyPreferenceKey(config), strategyIndex)
            .apply()
    }

    private fun buildStrategySequence(config: JSONObject): List<Int> {
        val defaultIndex = routingStrategies.indexOfFirst { it.name == "plain+protect" }
            .takeIf { connectionTransportKey == "CELLULAR" && it >= 0 }
            ?: 0
        val preferredIndex = loadPreferredStrategyIndex(config) ?: defaultIndex
        val sequence = buildList {
            add(preferredIndex)
            routingStrategies.indices
                .filterNot { it == preferredIndex }
                .forEach(::add)
        }

        Log.i(
            TAG,
            "OXray strategy order for transport=$connectionTransportKey, host=${config.optString(HOST_NAME)}: " +
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
            xray?.startVpn(prepareXrayConfig(config, strategy), newVpnBuilder(), protect)
        } catch (e: Exception) {
            handleChildError("Xray", e.message ?: e.toString())
        } finally {
            isStartingXray = false
        }
    }

    private suspend fun runDiagnosticsForCurrentStrategy() {
        val strategy = routingStrategies.getOrNull(currentStrategyIndex) ?: return

        val protocolState = if (currentStrategyPosition > 0) RECONNECTING else CONNECTING
        val results = runCheckRound(strategy, protocolState, "Checking sites on ${strategy.name}")
        if (results.any { it.success }) {
            sourceConfig?.let { savePreferredStrategyIndex(it, currentStrategyIndex) }
            connectivityMonitorFailures = 0
            isXrayActive = true
            state.value = CONNECTED
            startConnectivityMonitor()
        } else {
            Log.w(TAG, "Strategy '${strategy.name}' failed diagnostics, trying next routing mode")
            restartXrayForStrategy = true
            xray?.stopVpn()
        }
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
                if (results.any { it.success }) {
                    if (connectivityMonitorFailures > 0) {
                        Log.i(TAG, "Connectivity monitor recovered on strategy '${strategy.name}'")
                    }
                    connectivityMonitorFailures = 0
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

    private suspend fun runProbeRound(strategyName: String): List<ProbeResult> = coroutineScope {
        val results = DIAGNOSTIC_PROBE_URLS.map { probe ->
            async(Dispatchers.IO) { probeUrl(probe) }
        }.awaitAll()
        results.forEach {
            Log.i(TAG, "Strategy '$strategyName' diagnostic: $it")
        }

        buildTcpProbeTargets().map { (host, port) -> probeTcp(host, port) }.forEach {
            Log.i(TAG, "Strategy '$strategyName' tcp diagnostic: $it")
        }

        results
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
        stopXray()
        openVpn.reconnectVpn(newVpnBuilder(), protect)
    }

    private suspend fun probeUrl(url: String): ProbeResult {
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
                        detail = "HTTP $code, ${System.currentTimeMillis() - start} ms, bytes=$bytes"
                    )
                } finally {
                    connection.disconnect()
                }
            }
        }.getOrElse { error ->
            ProbeResult(
                url = url,
                success = false,
                detail = "${error::class.java.simpleName}: ${error.message}"
            )
        }
    }

    private fun buildTcpProbeTargets(): List<Pair<String, Int>> {
        val targets = linkedSetOf<Pair<String, Int>>()
        buildPreferredDnsServers(sourceConfig ?: JSONObject()).forEach { dnsServer ->
            resolveIp(dnsServer)?.let { targets += it to 53 }
        }
        extractXrayRemoteHost(sourceConfig ?: JSONObject())
            ?.let(::resolveIp)
            ?.let { remoteHost ->
                extractXrayRemotePort(sourceConfig ?: JSONObject())?.let { remotePort ->
                    targets += remoteHost to remotePort
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

    private suspend fun probeTcp(host: String, port: Int): ProbeResult {
        val label = "tcp://$host:$port"
        val start = System.currentTimeMillis()
        return runCatching {
            withTimeout(DIAGNOSTIC_PROBE_TIMEOUT_MS) {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), DIAGNOSTIC_TCP_TIMEOUT_MS)
                }
                ProbeResult(
                    url = label,
                    success = true,
                    detail = "TCP connect ok, ${System.currentTimeMillis() - start} ms"
                )
            }
        }.getOrElse { error ->
            ProbeResult(
                url = label,
                success = false,
                detail = "${error::class.java.simpleName}: ${error.message}"
            )
        }
    }

    private fun newVpnBuilder(): Builder =
        (context as? VpnService)?.Builder()
            ?: throw IllegalStateException("OXray requires a VpnService context")
}

private class OxrayOpenVpn : OpenVpn() {
    override fun configureOpenVpn(configBuilder: org.amnezia.vpn.protocol.openvpn.OpenVpnConfig.Builder, config: JSONObject) {
        config.optString(HOST_NAME)
            .takeIf { it.isNotBlank() }
            ?.let { remoteServer ->
                configBuilder.excludeRoute(org.amnezia.vpn.util.net.InetNetwork(parseInetAddress(remoteServer)))
            }
    }
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

private data class ProbeResult(
    val url: String,
    val success: Boolean,
    val detail: String
)
