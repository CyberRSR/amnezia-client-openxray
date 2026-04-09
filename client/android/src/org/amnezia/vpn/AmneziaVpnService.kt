package org.amnezia.vpn

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import androidx.annotation.MainThread
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.LazyThreadSafetyMode.NONE
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.amnezia.vpn.protocol.BadConfigException
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.CONNECTING
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTING
import org.amnezia.vpn.protocol.ProtocolState.RECONNECTING
import org.amnezia.vpn.protocol.ProtocolState.UNKNOWN
import org.amnezia.vpn.protocol.Status
import org.amnezia.vpn.protocol.VpnException
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.protocol.putStatus
import org.amnezia.vpn.util.LoadLibraryException
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.Prefs
import org.amnezia.vpn.util.net.NetworkState
import org.amnezia.vpn.util.net.TrafficStats
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "AmneziaVpnService"

const val ACTION_DISCONNECT = "org.amnezia.vpn.action.disconnect"
const val ACTION_CONNECT = "org.amnezia.vpn.action.connect"

const val MSG_VPN_CONFIG = "VPN_CONFIG"
const val MSG_ERROR = "ERROR"
const val MSG_SAVE_LOGS = "SAVE_LOGS"
const val MSG_CLIENT_NAME = "CLIENT_NAME"

const val AFTER_PERMISSION_CHECK = "AFTER_PERMISSION_CHECK"
private const val PREFS_CONFIG_KEY = "LAST_CONF"
private const val PREFS_SERVER_NAME = "LAST_SERVER_NAME"
private const val PREFS_SERVER_INDEX = "LAST_SERVER_INDEX"
// private const val STATISTICS_SENDING_TIMEOUT = 1000L
private const val TRAFFIC_STATS_UPDATE_TIMEOUT = 1000L
private const val DISCONNECT_TIMEOUT = 5000L
private const val STOP_SERVICE_TIMEOUT = 5000L
private const val UNEXPECTED_DISCONNECT_RECOVERY_DELAY_MS = 1500L
private const val UNEXPECTED_DISCONNECT_RECOVERY_MAX_ATTEMPTS = 4
private const val SLEEP_GUARD_WAKE_LOCK_TAG = "AmneziaVPN:SleepGuard"
private const val SLEEP_GUARD_WIFI_LOCK_TAG = "AmneziaVPN:WifiGuard"

@SuppressLint("Registered")
open class AmneziaVpnService : VpnService() {

    private lateinit var mainScope: CoroutineScope
    private lateinit var connectionScope: CoroutineScope
    private var isServiceBound = false
    private var vpnProto: VpnProto? = null
    private var protocolState = MutableStateFlow(UNKNOWN)
    private var currentStatus = Status.build { setState(UNKNOWN) }
    private var serverName: String? = null
    private var serverIndex: Int = -1

    private val isConnected
        get() = protocolState.value == CONNECTED

    private val isDisconnected
        get() = protocolState.value == DISCONNECTED

    private val isUnknown
        get() = protocolState.value == UNKNOWN

    private var connectionJob: Job? = null
    private var disconnectionJob: Job? = null
    private var trafficStatsUpdateJob: Job? = null
    // private var statisticsSendingJob: Job? = null
    private lateinit var networkState: NetworkState
    private lateinit var trafficStats: TrafficStats
    private var controlReceiver: BroadcastReceiver? = null
    private var notificationStateReceiver: BroadcastReceiver? = null
    private var screenOnReceiver: BroadcastReceiver? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    private var deviceIdleModeReceiver: BroadcastReceiver? = null
    private val clientMessengers = ConcurrentHashMap<Messenger, IpcMessenger>()
    private var pendingStopServiceJob: Job? = null
    private var unexpectedDisconnectRecoveryJob: Job? = null
    private var unexpectedDisconnectRecoveryAttempts = 0
    private var disconnectRequested = false
    private var sleepGuardWakeLock: PowerManager.WakeLock? = null
    private var sleepGuardWifiLock: WifiManager.WifiLock? = null

    private val isActivityConnected
        get() = clientMessengers.any { it.value.name == ACTIVITY_MESSENGER_NAME }

    private val connectionExceptionHandler = CoroutineExceptionHandler { _, e ->
        connectionJob?.cancel()
        connectionJob = null
        disconnectionJob?.cancel()
        disconnectionJob = null
        protocolState.value = DISCONNECTED
        when (e) {
            is IllegalArgumentException,
            is VpnStartException,
            is VpnException -> onError(e.message ?: e.toString())

            is JSONException,
            is BadConfigException -> onError("VPN config format error: ${e.message}")

            is LoadLibraryException -> onError("${e.message}. Caused: ${e.cause?.message}")

            is UnknownHostException -> onError("Unknown host")

            else -> throw e
        }
    }

    private val actionMessageHandler: Handler by lazy(NONE) {
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val action = msg.extractIpcMessage<Action>()
                Log.d(TAG, "Handle action: $action")
                when (action) {
                    Action.REGISTER_CLIENT -> {
                        val clientName = msg.data.getString(MSG_CLIENT_NAME)
                        val messenger = IpcMessenger(msg.replyTo, clientName)
                        clientMessengers[msg.replyTo] = messenger
                        Log.d(TAG, "Messenger client '$clientName' was registered")
                        // if (clientName == ACTIVITY_MESSENGER_NAME && isConnected) launchSendingStatistics()
                    }

                    Action.UNREGISTER_CLIENT -> {
                        clientMessengers.remove(msg.replyTo)?.let {
                            Log.d(TAG, "Messenger client '${it.name}' was unregistered")
                            // if (it.name == ACTIVITY_MESSENGER_NAME) stopSendingStatistics()
                        }
                    }

                    Action.CONNECT -> {
                        connect(msg.data.getString(MSG_VPN_CONFIG))
                    }

                    Action.DISCONNECT -> {
                        disconnect()
                    }

                    Action.REQUEST_STATUS -> {
                        clientMessengers[msg.replyTo]?.let { clientMessenger ->
                            clientMessenger.send {
                                ServiceEvent.STATUS.packToMessage {
                                    putStatus(currentStatusFor(this@AmneziaVpnService.protocolState.value))
                                }
                            }
                        }
                    }

                    Action.NOTIFICATION_PERMISSION_GRANTED -> {
                        enableNotification()
                    }

                    Action.SET_SAVE_LOGS -> {
                        Log.saveLogs = msg.data.getBoolean(MSG_SAVE_LOGS)
                    }
                }
            }
        }
    }

    private val vpnServiceMessenger: Messenger by lazy(NONE) {
        Messenger(actionMessageHandler)
    }

    /**
     * Notification setup
     */
    private val foregroundServiceTypeCompat
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> FOREGROUND_SERVICE_TYPE_MANIFEST
            else -> 0
        }

    private val serviceNotification: ServiceNotification by lazy(NONE) { ServiceNotification(this) }

    /**
     * Service overloaded methods
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Create Amnezia VPN service")
        mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + connectionExceptionHandler)
        loadServerData()
        launchProtocolStateHandler()
        networkState = NetworkState(this, ::reconnect)
        trafficStats = TrafficStats()
        registerBroadcastReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isAlwaysOn = intent != null && intent.action == SERVICE_INTERFACE

        if (isAlwaysOn) {
            Log.d(TAG, "Start service via Always-on")
            connect()
        } else if (intent?.getBooleanExtra(AFTER_PERMISSION_CHECK, false) == true) {
            Log.d(TAG, "Start service after permission check")
            connect()
        } else {
            Log.d(TAG, "Start service")
            connect(intent?.getStringExtra(MSG_VPN_CONFIG))
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            serviceNotification.buildNotification(serverName, vpnProto?.label, currentStatusFor(protocolState.value)),
            foregroundServiceTypeCompat
        )
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind by $intent")
        if (intent?.action == SERVICE_INTERFACE) return super.onBind(intent)
        isServiceBound = true
        return vpnServiceMessenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind by $intent")
        if (intent?.action != SERVICE_INTERFACE) {
            if (clientMessengers.isEmpty()) {
                isServiceBound = false
                if (isUnknown || (isDisconnected && disconnectRequested)) stopService()
            }
        }
        return true
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind by $intent")
        if (intent?.action != SERVICE_INTERFACE) {
            isServiceBound = true
        }
        super.onRebind(intent)
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke")
        // Calls to onRevoke() method may not happen on the main thread of the process
        mainScope.launch {
            disconnect()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroy service")
        cancelPendingStopService()
        cancelUnexpectedDisconnectRecovery()
        releaseSleepGuards()
        unregisterBroadcastReceivers()
        runBlocking {
            disconnect()
            disconnectionJob?.join()
        }
        connectionScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun stopService() {
        Log.d(TAG, "Stop service")
        cancelPendingStopService()
        cancelUnexpectedDisconnectRecovery()
        releaseSleepGuards()
        // the coroutine below will be canceled during the onDestroy call
        mainScope.launch {
            delay(STOP_SERVICE_TIMEOUT)
            Log.w(TAG, "Stop service timeout, kill process")
            Process.killProcess(Process.myPid())
        }
        stopSelf()
    }

    private fun registerBroadcastReceivers() {
        Log.d(TAG, "Register broadcast receivers")
        controlReceiver = registerBroadcastReceiver(
            arrayOf(ACTION_CONNECT, ACTION_DISCONNECT), ContextCompat.RECEIVER_NOT_EXPORTED
        ) {
            it?.action?.let { action ->
                Log.v(TAG, "Broadcast request received: $action")
                when (action) {
                    ACTION_CONNECT -> connect()
                    ACTION_DISCONNECT -> disconnect()
                    else -> Log.w(TAG, "Unknown action received: $action")
                }
            }
        }

        notificationStateReceiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            registerBroadcastReceiver(
                arrayOf(
                    NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED,
                    NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED
                )
            ) {
                val state = it?.getBooleanExtra(NotificationManager.EXTRA_BLOCKED_STATE, false)
                Log.v(TAG, "Notification state changed: ${it?.action}, blocked = $state")
                if (state == false) {
                    enableNotification()
                } else {
                    disableNotification()
                }
            }
        } else null

        registerScreenStateBroadcastReceivers()
    }

    private fun registerScreenStateBroadcastReceivers() {
        if (screenOnReceiver != null || screenOffReceiver != null || deviceIdleModeReceiver != null) return

        Log.d(TAG, "Register screen state broadcast receivers")
        screenOnReceiver = registerBroadcastReceiver(Intent.ACTION_SCREEN_ON) {
            if (isConnected && serviceNotification.isNotificationEnabled()) startTrafficStatsUpdateJob()
            handleWakeOrResume("screen on")
        }

        screenOffReceiver = registerBroadcastReceiver(Intent.ACTION_SCREEN_OFF) {
            stopTrafficStatsUpdateJob()
            updateSleepGuards()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceIdleModeReceiver = registerBroadcastReceiver(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                val isIdle = getSystemService<PowerManager>()?.isDeviceIdleMode == true
                Log.d(TAG, "Device idle mode changed: idle=$isIdle")
                if (isIdle) {
                    updateSleepGuards()
                } else {
                    handleWakeOrResume("device idle ended")
                }
            }
        }
    }

    private fun unregisterScreenStateBroadcastReceivers() {
        Log.d(TAG, "Unregister screen state broadcast receivers")
        unregisterBroadcastReceiver(screenOnReceiver)
        unregisterBroadcastReceiver(screenOffReceiver)
        unregisterBroadcastReceiver(deviceIdleModeReceiver)
        screenOnReceiver = null
        screenOffReceiver = null
        deviceIdleModeReceiver = null
    }

    private fun unregisterBroadcastReceivers() {
        Log.d(TAG, "Unregister broadcast receivers")
        unregisterBroadcastReceiver(controlReceiver)
        unregisterBroadcastReceiver(notificationStateReceiver)
        unregisterScreenStateBroadcastReceivers()
        controlReceiver = null
        notificationStateReceiver = null
    }

    /**
     * Methods responsible for processing VPN connection
     */
    private fun launchProtocolStateHandler() {
        mainScope.launch {
            // drop first default UNKNOWN state
            protocolState.drop(1).collect { protocolState ->
                Log.d(TAG, "Protocol state changed: $protocolState")
                currentStatus = currentStatusFor(protocolState)

                serviceNotification.updateNotification(serverName, vpnProto?.label, currentStatus)

                clientMessengers.send {
                    ServiceEvent.STATUS_CHANGED.packToMessage {
                        putStatus(currentStatus)
                    }
                }

                VpnStateStore.store { VpnState(protocolState, serverName, serverIndex, vpnProto) }

                when (protocolState) {
                    CONNECTED -> {
                        cancelPendingStopService()
                        cancelUnexpectedDisconnectRecovery()
                        unexpectedDisconnectRecoveryAttempts = 0
                        networkState.bindNetworkListener()
                        // if (isActivityConnected) launchSendingStatistics()
                        launchTrafficStatsUpdate()
                        updateSleepGuards()
                    }

                    DISCONNECTED -> {
                        networkState.unbindNetworkListener()
                        stopTrafficStatsUpdateJob()
                        // stopSendingStatistics()
                        updateSleepGuards()
                        if (disconnectRequested) {
                            cancelUnexpectedDisconnectRecovery()
                            unexpectedDisconnectRecoveryAttempts = 0
                            if (!isServiceBound) stopService()
                        } else if (hasSavedConfig()) {
                            scheduleUnexpectedDisconnectRecovery("Protocol disconnected unexpectedly")
                        } else if (!isServiceBound) {
                            stopService()
                        }
                    }

                    DISCONNECTING -> {
                        cancelPendingStopService()
                        cancelUnexpectedDisconnectRecovery()
                        networkState.unbindNetworkListener()
                        stopTrafficStatsUpdateJob()
                        // stopSendingStatistics()
                        updateSleepGuards()
                    }

                    RECONNECTING -> {
                        cancelPendingStopService()
                        cancelUnexpectedDisconnectRecovery()
                        stopTrafficStatsUpdateJob()
                        // stopSendingStatistics()
                        updateSleepGuards()
                    }

                    CONNECTING -> {
                        cancelPendingStopService()
                        cancelUnexpectedDisconnectRecovery()
                        updateSleepGuards()
                    }

                    UNKNOWN -> {
                        updateSleepGuards()
                    }
                }
            }
        }
    }

/*  @MainThread
    private fun launchSendingStatistics() {
        if (isServiceBound && isConnected) {
            statisticsSendingJob = mainScope.launch {
                while (true) {
                    clientMessenger.send {
                        ServiceEvent.STATISTICS_UPDATE.packToMessage {
                            putStatistics(protocol?.statistics ?: Statistics.EMPTY_STATISTICS)
                        }
                    }
                    delay(STATISTICS_SENDING_TIMEOUT)
                }
            }
        }
    }

    @MainThread
    private fun stopSendingStatistics() {
        statisticsSendingJob?.cancel()
    } */

    @MainThread
    private fun enableNotification() {
        serviceNotification.updateNotification(serverName, vpnProto?.label, currentStatusFor(protocolState.value))
        launchTrafficStatsUpdate()
    }

    @MainThread
    private fun disableNotification() {
        stopTrafficStatsUpdateJob()
    }

    @MainThread
    private fun launchTrafficStatsUpdate() {
        stopTrafficStatsUpdateJob()
        if (isConnected &&
            serviceNotification.isNotificationEnabled() &&
            getSystemService<PowerManager>()?.isInteractive != false
        ) {
            Log.v(TAG, "Launch traffic stats update")
            trafficStats.reset()
            startTrafficStatsUpdateJob()
        }
    }

    @MainThread
    private fun startTrafficStatsUpdateJob() {
        if (trafficStatsUpdateJob == null && trafficStats.isSupported()) {
            Log.d(TAG, "Start traffic stats update")
            trafficStatsUpdateJob = mainScope.launch {
                while (true) {
                    trafficStats.getSpeed().let { speed ->
                        if (isConnected) {
                            serviceNotification.updateSpeed(speed)
                        }
                    }
                    delay(TRAFFIC_STATS_UPDATE_TIMEOUT)
                }
            }
        }
    }

    @MainThread
    private fun stopTrafficStatsUpdateJob() {
        Log.d(TAG, "Stop traffic stats update")
        trafficStatsUpdateJob?.cancel()
        trafficStatsUpdateJob = null
    }

    @MainThread
    private fun connect(vpnConfig: String? = null) {
        disconnectRequested = false
        cancelPendingStopService()
        cancelUnexpectedDisconnectRecovery()
        if (vpnConfig == null) {
            connectToVpn(Prefs.load(PREFS_CONFIG_KEY))
        } else {
            Prefs.save(PREFS_CONFIG_KEY, vpnConfig)
            connectToVpn(vpnConfig)
        }
    }

    @MainThread
    private fun connectToVpn(vpnConfig: String) {
        if (isConnected || protocolState.value == CONNECTING) return

        Log.d(TAG, "Start VPN connection")

        val config = parseConfigToJson(vpnConfig)
        saveServerData(config)
        if (config == null) {
            onError("Invalid VPN config")
            protocolState.value = DISCONNECTED
            return
        }

        try {
            vpnProto = VpnProto.get(config.getString("protocol"))
        } catch (e: Exception) {
            onError("Invalid VPN config: ${e.message}")
            protocolState.value = DISCONNECTED
            return
        }

        disconnectRequested = false
        protocolState.value = CONNECTING
        currentStatus = currentStatusFor(CONNECTING)

        if (!checkPermission()) {
            protocolState.value = DISCONNECTED
            return
        }

        connectionJob = connectionScope.launch {
            disconnectionJob?.join()
            disconnectionJob = null

            vpnProto?.protocol?.let { protocol ->
                protocol.initialize(this@AmneziaVpnService, protocolState, ::onError, ::onProtocolStatusChanged)
                protocol.startVpn(config, Builder(), ::protect)
            }
        }
    }

    @MainThread
    private fun disconnect() {
        if (isUnknown || isDisconnected || protocolState.value == DISCONNECTING) return

        Log.d(TAG, "Stop VPN connection")

        disconnectRequested = true
        cancelUnexpectedDisconnectRecovery()
        unexpectedDisconnectRecoveryAttempts = 0
        protocolState.value = DISCONNECTING

        disconnectionJob = connectionScope.launch {
            connectionJob?.cancelAndJoin()
            connectionJob = null

            vpnProto?.protocol?.stopVpn()

            try {
                withTimeout(DISCONNECT_TIMEOUT) {
                    // waiting for disconnect state
                    protocolState.first { it == DISCONNECTED }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Disconnect timeout")
                stopService()
            }
        }
    }

    @MainThread
    private fun reconnect() {
        if (!isConnected) return

        Log.d(TAG, "Reconnect VPN")

        disconnectRequested = false
        cancelPendingStopService()
        cancelUnexpectedDisconnectRecovery()
        protocolState.value = RECONNECTING

        connectionJob = connectionScope.launch {
            vpnProto?.protocol?.reconnectVpn(Builder(), ::protect)
        }
    }

    /**
     * Utils methods
     */
    private fun onError(msg: String) {
        Log.e(TAG, msg)
        mainScope.launch {
            clientMessengers.send {
                ServiceEvent.ERROR.packToMessage {
                    putString(MSG_ERROR, msg)
                }
            }
        }
    }

    private fun onProtocolStatusChanged(status: Status) {
        currentStatus = Status.build {
            setState(protocolState.value)
            setMessage(status.message)
            setSteps(status.steps)
        }
        mainScope.launch {
            serviceNotification.updateNotification(serverName, vpnProto?.label, currentStatus)
            clientMessengers.send {
                ServiceEvent.STATUS_CHANGED.packToMessage {
                    putStatus(currentStatus)
                }
            }
        }
    }

    private fun currentStatusFor(state: org.amnezia.vpn.protocol.ProtocolState): Status {
        return if (currentStatus.steps.isEmpty()) {
            Status.build {
                setState(state)
                setMessage(getString(state))
            }
        } else if (state == DISCONNECTED || state == DISCONNECTING || state == UNKNOWN) {
            Status.build {
                setState(state)
                setMessage(getString(state))
            }
        } else {
            Status.build {
                setState(state)
                setMessage(currentStatus.message.ifBlank { getString(state) })
                setSteps(currentStatus.steps)
            }
        }
    }

    private fun parseConfigToJson(vpnConfig: String): JSONObject? =
        if (vpnConfig.isBlank()) {
            null
        } else {
            try {
                JSONObject(vpnConfig)
            } catch (e: JSONException) {
                onError("Invalid VPN config json format: ${e.message}")
                null
            }
        }

    private fun saveServerData(config: JSONObject?) {
        serverName = config?.opt("description") as String?
        serverIndex = config?.opt("serverIndex") as Int? ?: -1
        Log.d(TAG, "Save server data: ($serverIndex, $serverName)")
        Prefs.save(PREFS_SERVER_NAME, serverName)
        Prefs.save(PREFS_SERVER_INDEX, serverIndex)
    }

    private fun loadServerData() {
        serverName = Prefs.load<String>(PREFS_SERVER_NAME).ifBlank { null }
        if (serverName != null) serverIndex = Prefs.load(PREFS_SERVER_INDEX)
        Log.d(TAG, "Load server data: ($serverIndex, $serverName)")
    }

    private fun hasSavedConfig(): Boolean = Prefs.load<String>(PREFS_CONFIG_KEY).isNotBlank()

    @MainThread
    private fun cancelPendingStopService() {
        pendingStopServiceJob?.cancel()
        pendingStopServiceJob = null
    }

    @MainThread
    private fun cancelUnexpectedDisconnectRecovery() {
        unexpectedDisconnectRecoveryJob?.cancel()
        unexpectedDisconnectRecoveryJob = null
    }

    @MainThread
    private fun scheduleUnexpectedDisconnectRecovery(reason: String) {
        if (disconnectRequested || !hasSavedConfig()) {
            if (!isServiceBound) stopService()
            return
        }
        if (unexpectedDisconnectRecoveryAttempts >= UNEXPECTED_DISCONNECT_RECOVERY_MAX_ATTEMPTS) {
            Log.w(TAG, "Unexpected disconnect recovery exhausted after $unexpectedDisconnectRecoveryAttempts attempts")
            if (!isServiceBound) stopService()
            return
        }

        cancelUnexpectedDisconnectRecovery()
        updateSleepGuards()
        unexpectedDisconnectRecoveryJob = mainScope.launch {
            delay(UNEXPECTED_DISCONNECT_RECOVERY_DELAY_MS)
            if (disconnectRequested || protocolState.value != DISCONNECTED || !hasSavedConfig()) {
                return@launch
            }

            unexpectedDisconnectRecoveryAttempts += 1
            Log.w(
                TAG,
                "Unexpected disconnect recovery attempt " +
                    "$unexpectedDisconnectRecoveryAttempts/$UNEXPECTED_DISCONNECT_RECOVERY_MAX_ATTEMPTS: $reason"
            )
            connect()
        }
    }

    @MainThread
    private fun handleWakeOrResume(reason: String) {
        updateSleepGuards()
        when (protocolState.value) {
            CONNECTED -> vpnProto?.protocol?.requestConnectionCheck(reason)
            DISCONNECTED -> scheduleUnexpectedDisconnectRecovery("Wake check requested: $reason")
            else -> Unit
        }
    }

    private fun shouldHoldSleepGuards(): Boolean {
        if (disconnectRequested) return false

        return when (protocolState.value) {
            CONNECTING, CONNECTED, RECONNECTING -> true
            DISCONNECTED -> hasSavedConfig() && unexpectedDisconnectRecoveryAttempts < UNEXPECTED_DISCONNECT_RECOVERY_MAX_ATTEMPTS
            DISCONNECTING, UNKNOWN -> false
        }
    }

    @MainThread
    private fun updateSleepGuards() {
        val powerManager = getSystemService<PowerManager>() ?: return
        val shouldHold = !powerManager.isInteractive && shouldHoldSleepGuards()
        if (shouldHold) {
            acquireSleepGuards(powerManager)
        } else {
            releaseSleepGuards()
        }
    }

    private fun acquireSleepGuards(powerManager: PowerManager) {
        val wakeLock = sleepGuardWakeLock ?: powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SLEEP_GUARD_WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .also { sleepGuardWakeLock = it }

        if (!wakeLock.isHeld) {
            Log.d(TAG, "Acquire sleep guard wake lock")
            wakeLock.acquire()
        }

        val wifiManager = applicationContext.getSystemService<WifiManager>()
        val wifiLock = sleepGuardWifiLock ?: wifiManager
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, SLEEP_GUARD_WIFI_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { sleepGuardWifiLock = it }

        if (wifiLock != null && !wifiLock.isHeld) {
            Log.d(TAG, "Acquire sleep guard Wi-Fi lock")
            wifiLock.acquire()
        }
    }

    private fun releaseSleepGuards() {
        sleepGuardWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) {
                Log.d(TAG, "Release sleep guard wake lock")
                wakeLock.release()
            }
        }
        sleepGuardWifiLock?.let { wifiLock ->
            if (wifiLock.isHeld) {
                Log.d(TAG, "Release sleep guard Wi-Fi lock")
                wifiLock.release()
            }
        }
    }

    private fun checkPermission(): Boolean =
        if (prepare(applicationContext) != null) {
            Intent(this, VpnRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_PROTOCOL, vpnProto)
            }.also {
                startActivity(it)
            }
            false
        } else {
            true
        }

    companion object {
        fun isRunning(context: Context, processName: String): Boolean =
            context.getSystemService<ActivityManager>()!!.runningAppProcesses.any {
                it.processName == processName && it.importance <= IMPORTANCE_FOREGROUND_SERVICE
            }
    }
}
