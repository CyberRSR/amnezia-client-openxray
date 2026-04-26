package org.amnezia.vpn.protocol.xray

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log as AndroidLog
import java.io.Closeable
import java.io.EOFException
import java.io.FileDescriptor
import java.io.InterruptedIOException
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_Status
import org.amnezia.vpn.protocol.BadConfigException
import org.amnezia.vpn.protocol.ProtocolState
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.protocol.openvpn.OpenVpn
import org.amnezia.vpn.protocol.openvpn.OpenVpnClient
import org.amnezia.vpn.protocol.openvpn.OpenVpnConfig
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.net.getLocalNetworks
import org.json.JSONObject

private const val USERSACE_TAG = "OpenVpnUserspace"
private const val OPENVPN_USERSACE_CONNECT_TIMEOUT_MS = 45_000L
private const val TCP_CONNECT_TIMEOUT_MS = 15_000L
private const val TCP_ACK_TIMEOUT_MS = 4_000L
private const val TCP_MSS = 1120
private const val TCP_ADVERTISED_WINDOW = 0xffff
private const val TCP_SEND_WINDOW_BYTES = 32 * TCP_MSS
private const val TCP_SEND_RETRIES = 4
private const val TCP_RECEIVE_QUEUE_CHUNKS = 65_536
private const val TCP_RECEIVE_BUFFER_BYTES = 16 * 1024 * 1024
private const val TCP_OUT_OF_ORDER_BUFFER_BYTES = 2 * 1024 * 1024
private const val TCP_GAP_ACK_INTERVAL_MS = 250L
private const val TCP_GAP_STALL_LOG_INTERVAL_MS = 2_000L
private const val TCP_GAP_STALL_CLOSE_MS = 45_000L
private const val TCP_PRESSURE_GAP_STALL_CLOSE_MS = 15_000L
private const val TCP_RECEIVE_WINDOW_LOG_INTERVAL_MS = 2_000L
private const val TCP_HALF_CLOSED_IDLE_TIMEOUT_MS = 15_000L
private const val TCP_PRESSURE_HALF_CLOSED_IDLE_TIMEOUT_MS = 5_000L
private const val TCP_IDLE_TIMEOUT_MS = 30 * 60_000L
private const val TCP_PRESSURE_IDLE_TIMEOUT_MS = 5 * 60_000L
private const val TCP_PRESSURE_CONNECTIONS = 64
private const val TCP_IDLE_SWEEP_INTERVAL_MS = 2_000L
private const val SOCKS_BACKLOG = 256
private const val MAX_ACTIVE_SOCKS_CONNECTIONS = 64
private const val MAX_ACTIVE_SOCKS_CONNECTIONS_PER_TARGET = 64
private const val SOCKS_CONNECT_QUEUE_TIMEOUT_MS = 45_000L
private const val SOCKS_SLOT_QUEUE_TIMEOUT_MS = 45_000L
private const val LOCAL_SOCKS_HANDSHAKE_TIMEOUT_MS = 15_000
private const val LOCAL_SOCKS_HALF_CLOSE_DRAIN_MS = 500L
private const val SOCKS_PIPE_BUFFER_BYTES = 64 * 1024
private const val PACKET_SOCKET_BUFFER_BYTES = 8 * 1024 * 1024
private const val LOCAL_SOCKS_SOCKET_BUFFER_BYTES = 2 * 1024 * 1024
private const val OPENVPN_TRANSPORT_SOCKET_BUFFER_BYTES = 4 * 1024 * 1024
private const val SLOW_VIRTUAL_TCP_CONNECT_MS = 500L
private const val SLOW_VIRTUAL_TCP_ACK_MS = 2_000L

private val UDP_PROTO_REGEX = Regex(
    """(?im)^\s*(proto\s+udp[46]?|remote\s+\S+\s+\d+\s+udp[46]?)\b"""
)

private fun traceUserspace(message: String) {
    AndroidLog.d(USERSACE_TAG, message)
}

internal data class OpenVpnUnderlaySettings(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
)

internal class OpenVpnUserspaceTunnel : OpenVpn() {
    @Volatile
    private var router: UserspaceTcpRouter? = null
    private var socksServer: OpenVpnSocksServer? = null
    private var userspaceClient: UserspaceOpenVpnClient? = null
    private val connectWorkerLock = Object()
    private val connectTasks = LinkedBlockingQueue<ConnectTask>()
    private val connectGeneration = AtomicInteger()
    @Volatile
    private var connectThread: Thread? = null
    @Volatile
    private var activeConnectGeneration = 0
    @Volatile
    private var stoppedConnectGeneration = 0
    @Volatile
    private var openVpnConnected = false
    @Volatile
    private var lastTunnelLocalAddress: String? = null
    private val underlayUser = "amnezia"
    private val underlayPass = Random.nextBytes(18).joinToString("") { "%02x".format(it) }

    private data class ConnectTask(
        val client: UserspaceOpenVpnClient,
        val generation: Int
    )

    suspend fun startUserspace(config: JSONObject, protect: (Int) -> Boolean): OpenVpnUnderlaySettings {
        val openVpnConfigJson = createOpenVpnConfig(config)
        val rawConfig = openVpnConfigJson.getJSONObject("openvpn_config_data").getString("config")
        if (UDP_PROTO_REGEX.containsMatchIn(rawConfig)) {
            throw BadConfigException("OXray currently supports only OpenVPN proto tcp")
        }

        val connected = CompletableDeferred<Unit>()
        val configBuilder = OpenVpnConfig.Builder().apply {
            setMtu(OXRAY_CHAIN_MTU)
        }
        openVpnConnected = false

        val client = UserspaceOpenVpnClient(
            configBuilder = configBuilder,
            state = state,
            getLocalNetworks = { ipv6 -> getLocalNetworks(context, ipv6) },
            protect = protect,
            onError = onError,
            onConnectionState = { isConnected -> openVpnConnected = isConnected },
            onConnected = { if (!connected.isCompleted) connected.complete(Unit) },
            establishUserspace = { establishUserspaceInterface() }
        )
        userspaceClient = client
        openVpnClient = client

        val parsedConfig = parseConfig(openVpnConfigJson)
        val eval = client.eval_config(parsedConfig)
        if (eval.error) {
            throw BadConfigException("OpenVPN config parse error: ${eval.message}")
        }

        startConnectWorker(client)

        withTimeout(OPENVPN_USERSACE_CONNECT_TIMEOUT_MS) {
            connected.await()
        }

        val activeRouter = router
            ?: throw VpnStartException("OpenVPN userspace packet router was not established")
        val server = OpenVpnSocksServer(
            routerProvider = { router?.takeUnless { it.isClosed } },
            isOpenVpnReady = { openVpnConnected },
            username = underlayUser,
            password = underlayPass
        )
        socksServer = server
        val port = server.start()
        Log.i(USERSACE_TAG, "OpenVPN userspace SOCKS backend listening on 127.0.0.1:$port")
        return OpenVpnUnderlaySettings("127.0.0.1", port, underlayUser, underlayPass)
    }

    override fun stopVpn() {
        val generation = activeConnectGeneration
        if (generation != 0) {
            stoppedConnectGeneration = generation
        }
        connectTasks.clear()
        openVpnConnected = false
        socksServer?.close()
        socksServer = null
        router?.close()
        router = null
        userspaceClient = null
        super.stopVpn()
    }

    private fun startConnectWorker(client: UserspaceOpenVpnClient) {
        val generation = connectGeneration.incrementAndGet()
        activeConnectGeneration = generation
        stoppedConnectGeneration = 0
        connectTasks.clear()
        ensureConnectThread()
        connectTasks.offer(ConnectTask(client, generation))
    }

    private fun ensureConnectThread() {
        if (connectThread?.isAlive == true) {
            return
        }
        synchronized(connectWorkerLock) {
            if (connectThread?.isAlive == true) {
                return
            }
            connectThread = Thread({ runConnectWorker() }, "OpenVpnUserspaceConnect").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runConnectWorker() {
        Log.i(USERSACE_TAG, "OpenVPN userspace connect worker started")
        while (true) {
            val task = try {
                connectTasks.take()
            } catch (_: InterruptedException) {
                continue
            }
            runConnectTask(task)
        }
    }

    private fun runConnectTask(task: ConnectTask) {
        val status: ClientAPI_Status = try {
            Log.i(USERSACE_TAG, "OpenVPN userspace connect() starting generation=${task.generation}")
            task.client.connect()
        } catch (e: Throwable) {
            if (!isExpectedConnectReturn(task.generation)) {
                state.value = DISCONNECTED
                onError("OpenVPN userspace connect() exception: ${e.message ?: e}")
            }
            return
        }

        val expectedReturn = isExpectedConnectReturn(task.generation)
        Log.w(
            USERSACE_TAG,
            "OpenVPN userspace connect() returned generation=${task.generation} " +
                "status=${status.status} error=${status.error} expectedReturn=$expectedReturn: ${status.message}"
        )
        if (status.error && !expectedReturn) {
            state.value = DISCONNECTED
            onError("OpenVPN userspace connect() error: ${status.status}: ${status.message}")
        } else if (!expectedReturn) {
            state.value = DISCONNECTED
        }
    }

    private fun isExpectedConnectReturn(generation: Int): Boolean =
        generation != activeConnectGeneration || generation == stoppedConnectGeneration

    private fun createOpenVpnConfig(config: JSONObject): JSONObject =
        JSONObject(config.toString()).apply {
            put("protocol", "OPENVPN")
            put("killSwitchOption", false)
        }

    private fun establishUserspaceInterface(): Int {
        val currentLocalAddress = userspaceClient?.tunnelLocalAddress
        val localAddress = currentLocalAddress
            ?: lastTunnelLocalAddress
            ?: throw VpnStartException("OpenVPN did not provide a tunnel local address")
        if (currentLocalAddress == null) {
            Log.w(
                USERSACE_TAG,
                "OpenVPN did not repeat tunnel local address; reusing $localAddress for userspace router"
            )
        }
        lastTunnelLocalAddress = localAddress
        val localIpv4 = InetAddress.getByName(localAddress) as? Inet4Address
            ?: throw VpnStartException("OpenVPN userspace tunnel requires an IPv4 local address")

        val openVpnFd = FileDescriptor()
        val stackFd = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, openVpnFd, stackFd)
        tunePacketSocketBuffers(openVpnFd, stackFd)
        val openVpnPfd = ParcelFileDescriptor.dup(openVpnFd)
        Os.close(openVpnFd)

        val nextRouter = UserspaceTcpRouter(stackFd, localIpv4).also { it.start() }
        val previousRouter = router
        router = nextRouter
        previousRouter?.close()
        Log.i(USERSACE_TAG, "Created userspace packet pair for OpenVPN at $localAddress")
        return openVpnPfd.detachFd()
    }

    private fun tunePacketSocketBuffers(openVpnFd: FileDescriptor, stackFd: FileDescriptor) {
        listOf(openVpnFd, stackFd).forEach { fd ->
            runCatching {
                Os.setsockoptInt(
                    fd,
                    OsConstants.SOL_SOCKET,
                    OsConstants.SO_RCVBUF,
                    PACKET_SOCKET_BUFFER_BYTES
                )
            }
            runCatching {
                Os.setsockoptInt(
                    fd,
                    OsConstants.SOL_SOCKET,
                    OsConstants.SO_SNDBUF,
                    PACKET_SOCKET_BUFFER_BYTES
                )
            }
        }
    }
}

private class UserspaceOpenVpnClient(
    configBuilder: OpenVpnConfig.Builder,
    state: kotlinx.coroutines.flow.MutableStateFlow<ProtocolState>,
    getLocalNetworks: (Boolean) -> List<org.amnezia.vpn.util.net.InetNetwork>,
    protect: (Int) -> Boolean,
    onError: (String) -> Unit,
    private val onConnectionState: (Boolean) -> Unit,
    private val onConnected: () -> Unit,
    private val establishUserspace: () -> Int
) : OpenVpnClient(
    configBuilder = configBuilder,
    state = state,
    getLocalNetworks = getLocalNetworks,
    establish = { establishUserspace() },
    protect = protect,
    onError = onError
) {
    override fun tun_builder_establish(): Int {
        Log.d(USERSACE_TAG, "tun_builder_establish userspace fd")
        return establishUserspace()
    }

    override fun socket_protect(socket: Int, remote: String, ipv6: Boolean): Boolean {
        val tuned = tuneTransportSocket(socket)
        val protected = super.socket_protect(socket, remote, ipv6)
        Log.i(
            USERSACE_TAG,
            "Protect OpenVPN transport socket fd=$socket remote=$remote ipv6=$ipv6 " +
                "protected=$protected tuned=$tuned"
        )
        return protected
    }

    override fun event(event: ClientAPI_Event) {
        super.event(event)
        when (event.name) {
            "CONNECTED" -> {
                onConnectionState(true)
                onConnected()
            }
            "RECONNECTING", "DISCONNECTED", "RESOLVE", "WAIT", "PAUSE", "EXITING" -> {
                onConnectionState(false)
            }
        }
    }

    private fun tuneTransportSocket(socket: Int): Boolean {
        return runCatching {
            ParcelFileDescriptor.fromFd(socket).use { pfd ->
                Os.setsockoptInt(
                    pfd.fileDescriptor,
                    OsConstants.IPPROTO_TCP,
                    OsConstants.TCP_NODELAY,
                    1
                )
                Os.setsockoptInt(
                    pfd.fileDescriptor,
                    OsConstants.SOL_SOCKET,
                    OsConstants.SO_RCVBUF,
                    OPENVPN_TRANSPORT_SOCKET_BUFFER_BYTES
                )
                Os.setsockoptInt(
                    pfd.fileDescriptor,
                    OsConstants.SOL_SOCKET,
                    OsConstants.SO_SNDBUF,
                    OPENVPN_TRANSPORT_SOCKET_BUFFER_BYTES
                )
            }
            true
        }.onFailure { error ->
            Log.w(
                USERSACE_TAG,
                "Failed to tune OpenVPN transport socket fd=$socket: ${error.message ?: error}"
            )
        }.getOrDefault(false)
    }
}

private class OpenVpnSocksServer(
    private val routerProvider: () -> UserspaceTcpRouter?,
    private val isOpenVpnReady: () -> Boolean,
    private val username: String,
    private val password: String
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnections = Semaphore(MAX_ACTIVE_SOCKS_CONNECTIONS, true)
    private val targetConnections = ConcurrentHashMap<SocksTarget, Semaphore>()
    private var serverSocket: ServerSocket? = null

    fun start(): Int {
        val server = ServerSocket(0, SOCKS_BACKLOG, InetAddress.getByName("127.0.0.1"))
        runCatching { server.receiveBufferSize = LOCAL_SOCKS_SOCKET_BUFFER_BYTES }
        serverSocket = server
        scope.launch {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                scope.launch {
                    try {
                        handleClient(socket)
                    } catch (error: Exception) {
                        if (error !is EOFException && error !is IOException) {
                            Log.w(USERSACE_TAG, "SOCKS client failed: ${error.message ?: error}")
                        }
                    }
                }
            }
        }
        return server.localPort
    }

    override fun close() {
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.tcpNoDelay = true
            client.soTimeout = LOCAL_SOCKS_HANDSHAKE_TIMEOUT_MS
            runCatching { client.receiveBufferSize = LOCAL_SOCKS_SOCKET_BUFFER_BYTES }
            runCatching { client.sendBufferSize = LOCAL_SOCKS_SOCKET_BUFFER_BYTES }
            val input = client.getInputStream()
            val output = client.getOutputStream()
            negotiate(input, output)
            authenticate(input, output)
            val target = readConnectRequest(input, output)
            val slot = acquireConnectionSlot(target)
            if (slot == null) {
                Log.w(
                    USERSACE_TAG,
                    "SOCKS CONNECT ${target.host}:${target.port} rejected: userspace underlay is saturated"
                )
                sendConnectFailure(output, 0x01)
                return
            }
            var activeRouter: UserspaceTcpRouter? = null
            try {
                val selectedRouter = awaitRouter(target)
                if (selectedRouter == null) {
                    Log.w(
                        USERSACE_TAG,
                        "SOCKS CONNECT ${target.host}:${target.port} rejected: OpenVPN userspace router is not ready"
                    )
                    sendConnectFailure(output, 0x01)
                    return
                }
                activeRouter = selectedRouter
                traceUserspace(
                    "SOCKS CONNECT ${target.host}:${target.port} via OpenVPN userspace " +
                        connectionCountsForLog(target) + " " +
                        "virtual=${selectedRouter.activeConnectionCount()}"
                )
                val connection = selectedRouter.connect(target.host, target.port)
                sendConnectReply(output)
                client.soTimeout = 0
                connection.use { virtual ->
                    val upstream = scope.launch {
                        pipeClientToVirtual(input, virtual, target)
                    }
                    val downstream = scope.launch {
                        pipeVirtualToClient(virtual.input, output, virtual, target)
                    }
                    while (upstream.isActive && downstream.isActive && !client.isClosed && !virtual.closed) {
                        delay(100)
                    }
                    if (!upstream.isActive && downstream.isActive && !client.isClosed && !virtual.closed) {
                        val drainDeadline = System.currentTimeMillis() + LOCAL_SOCKS_HALF_CLOSE_DRAIN_MS
                        while (downstream.isActive && !client.isClosed && !virtual.closed &&
                            System.currentTimeMillis() < drainDeadline
                        ) {
                            delay(50)
                        }
                    }
                    if (downstream.isActive && !client.isClosed && !virtual.closed) {
                        virtual.close()
                    }
                    upstream.cancel()
                    downstream.cancel()
                }
            } finally {
                slot.release()
                traceUserspace(
                    "SOCKS CONNECT ${target.host}:${target.port} closed " +
                        connectionCountsForLog(target) + " " +
                        "virtual=${activeRouter?.activeConnectionCount() ?: routerProvider()?.activeConnectionCount() ?: 0}"
                )
            }
        }
    }

    private fun awaitRouter(target: SocksTarget): UserspaceTcpRouter? {
        val deadline = System.currentTimeMillis() + SOCKS_CONNECT_QUEUE_TIMEOUT_MS
        var loggedWait = false
        while (System.currentTimeMillis() < deadline) {
            val router = routerProvider()
            if (router != null && !router.isClosed && isOpenVpnReady()) {
                return router
            }
            if (!loggedWait) {
                loggedWait = true
                traceUserspace(
                    "SOCKS CONNECT ${target.host}:${target.port} waiting for OpenVPN userspace router"
                )
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun acquireConnectionSlot(target: SocksTarget): ConnectionSlot? {
        val startedAt = System.currentTimeMillis()
        val targetSemaphore = targetConnections.computeIfAbsent(target) {
            Semaphore(MAX_ACTIVE_SOCKS_CONNECTIONS_PER_TARGET, true)
        }
        val acquiredTotal = try {
            activeConnections.tryAcquire(SOCKS_SLOT_QUEUE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquiredTotal) {
            return null
        }

        val remainingMs = (SOCKS_SLOT_QUEUE_TIMEOUT_MS - (System.currentTimeMillis() - startedAt))
            .coerceAtLeast(0L)
        val acquiredTarget = try {
            targetSemaphore.tryAcquire(remainingMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquiredTarget) {
            activeConnections.release()
            return null
        }

        val waitedMs = System.currentTimeMillis() - startedAt
        if (waitedMs > 250) {
            traceUserspace(
                "SOCKS CONNECT ${target.host}:${target.port} waited ${waitedMs}ms for OpenVPN userspace slot " +
                    connectionCountsForLog(target)
            )
        }
        return ConnectionSlot(activeConnections, targetSemaphore)
    }

    private fun activeConnectionCount(): Int =
        MAX_ACTIVE_SOCKS_CONNECTIONS - activeConnections.availablePermits()

    private fun targetActiveConnectionCount(target: SocksTarget): Int =
        MAX_ACTIVE_SOCKS_CONNECTIONS_PER_TARGET -
            (targetConnections[target]?.availablePermits() ?: MAX_ACTIVE_SOCKS_CONNECTIONS_PER_TARGET)

    private fun connectionCountsForLog(target: SocksTarget): String =
        "active=${activeConnectionCount()}/$MAX_ACTIVE_SOCKS_CONNECTIONS " +
            "targetActive=${targetActiveConnectionCount(target)}/$MAX_ACTIVE_SOCKS_CONNECTIONS_PER_TARGET"

    private fun negotiate(input: InputStream, output: OutputStream) {
        if (input.read() != 0x05) {
            throw EOFException("Invalid SOCKS version")
        }
        val methodCount = input.read()
        if (methodCount <= 0) {
            throw EOFException("SOCKS method list is empty")
        }
        val methods = ByteArray(methodCount)
        readFully(input, methods)
        if (!methods.any { (it.toInt() and 0xff) == 0x02 }) {
            output.write(byteArrayOf(0x05, 0xff.toByte()))
            output.flush()
            throw EOFException("SOCKS client did not offer username/password auth")
        }
        output.write(byteArrayOf(0x05, 0x02))
        output.flush()
    }

    private fun authenticate(input: InputStream, output: OutputStream) {
        if (input.read() != 0x01) {
            throw EOFException("Invalid SOCKS auth version")
        }
        val userLength = input.read()
        if (userLength < 0) throw EOFException("Missing SOCKS username length")
        val userBytes = ByteArray(userLength)
        readFully(input, userBytes)
        val passLength = input.read()
        if (passLength < 0) throw EOFException("Missing SOCKS password length")
        val passBytes = ByteArray(passLength)
        readFully(input, passBytes)
        val ok = userBytes.toString(Charsets.UTF_8) == username &&
            passBytes.toString(Charsets.UTF_8) == password
        output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
        output.flush()
        if (!ok) {
            throw EOFException("SOCKS authentication failed")
        }
    }

    private fun readConnectRequest(input: InputStream, output: OutputStream): SocksTarget {
        if (input.read() != 0x05 || input.read() != 0x01) {
            throw EOFException("Only SOCKS5 CONNECT is supported")
        }
        input.read() // RSV
        val atyp = input.read()
        val host = when (atyp) {
            0x01 -> ByteArray(4).also { readFully(input, it) }
                .joinToString(".") { (it.toInt() and 0xff).toString() }
            0x03 -> {
                val length = input.read()
                ByteArray(length).also { readFully(input, it) }.toString(Charsets.UTF_8)
            }
            else -> {
                output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                throw EOFException("Unsupported SOCKS ATYP=$atyp")
            }
        }
        val portBytes = ByteArray(2)
        readFully(input, portBytes)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        return SocksTarget(host, port)
    }

    private fun sendConnectReply(output: OutputStream) {
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
        output.flush()
    }

    private fun sendConnectFailure(output: OutputStream, reason: Int) {
        output.write(byteArrayOf(0x05, reason.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun pipeClientToVirtual(
        input: InputStream,
        virtual: VirtualTcpConnection,
        target: SocksTarget
    ) {
        val buffer = ByteArray(SOCKS_PIPE_BUFFER_BYTES)
        try {
            while (!virtual.closed) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    virtual.write(buffer, 0, read)
                }
            }
        } catch (_: SocketTimeoutException) {
            Log.i(USERSACE_TAG, "SOCKS client idle timeout for ${target.host}:${target.port}")
        } catch (_: Exception) {
            // The SOCKS side may close first; this is a normal TCP teardown path.
        } finally {
            virtual.shutdownOutput()
        }
    }

    private fun pipeVirtualToClient(
        input: InputStream,
        output: OutputStream,
        virtual: VirtualTcpConnection,
        target: SocksTarget
    ) {
        val buffer = ByteArray(SOCKS_PIPE_BUFFER_BYTES)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    output.write(buffer, 0, read)
                    output.flush()
                    virtual.onInboundPayloadDelivered(read)
                }
            }
        } catch (_: Exception) {
            // The client may close the SOCKS connection while the virtual TCP pipe is draining.
        } finally {
            virtual.close()
        }
    }
}

private data class SocksTarget(val host: String, val port: Int)

private data class ConnectionSlot(
    private val activeConnections: Semaphore,
    private val targetConnections: Semaphore
) {
    fun release() {
        targetConnections.release()
        activeConnections.release()
    }
}

private data class TcpAckSnapshot(
    val seq: Long,
    val ack: Long,
    val window: Int,
    val tcpOptions: ByteArray = ByteArray(0)
)

private class UserspaceTcpRouter(
    private val packetFd: FileDescriptor,
    private val localAddress: Inet4Address
) : Closeable {
    private val localAddressInt = ipv4ToInt(localAddress.address)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<Int, VirtualTcpConnection>()
    private val packetWriteLock = Object()
    private val nextPort = AtomicInteger(30_000 + Random.nextInt(10_000))
    private val nextIpId = AtomicInteger(Random.nextInt(0xffff))
    private var packetReaderThread: Thread? = null
    @Volatile
    private var closed = false
    internal val isClosed: Boolean
        get() = closed

    fun start() {
        packetReaderThread = Thread({ readPackets() }, "OpenVpnUserspacePackets").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        scope.launch { retransmitGapAcks() }
        scope.launch { closeIdleConnections() }
    }

    fun connect(host: String, port: Int): VirtualTcpConnection {
        if (closed) {
            throw EOFException("OpenVPN userspace router is closed")
        }
        val address = InetAddress.getByName(host) as? Inet4Address
            ?: throw VpnStartException("OpenVPN userspace backend supports only IPv4 targets: $host")
        val localPort = allocatePort()
        val connection = VirtualTcpConnection(
            router = this,
            localAddress = localAddressInt,
            remoteAddress = ipv4ToInt(address.address),
            localPort = localPort,
            remotePort = port
        )
        connections[localPort] = connection
        try {
            connection.connect()
            return connection
        } catch (e: Exception) {
            connections.remove(localPort)
            connection.close()
            throw e
        }
    }

    internal fun sendTcp(
        localAddress: Int,
        remoteAddress: Int,
        localPort: Int,
        remotePort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
        window: Int = TCP_ADVERTISED_WINDOW,
        tcpOptions: ByteArray = tcpOptionsFor(flags)
    ) {
        if (closed) {
            return
        }
        val packet = buildIpv4TcpPacket(
            srcIp = localAddress,
            dstIp = remoteAddress,
            srcPort = localPort,
            dstPort = remotePort,
            seq = seq,
            ack = ack,
            flags = flags,
            ipId = nextIpId.getAndIncrement() and 0xffff,
            payload = payload,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            window = window.coerceIn(0, TCP_ADVERTISED_WINDOW),
            tcpOptions = tcpOptions
        )
        try {
            synchronized(packetWriteLock) {
                if (!closed) {
                    Os.write(packetFd, packet, 0, packet.size)
                }
            }
        } catch (e: Exception) {
            if (!closed) {
                closeAfterPacketFdError(e)
            }
            throw e
        }
    }

    internal fun remove(localPort: Int) {
        connections.remove(localPort)
    }

    internal fun activeConnectionCount(): Int = connections.size

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        packetReaderThread?.interrupt()
        scope.cancel()
        connections.values.forEach { it.close() }
        connections.clear()
        runCatching { Os.close(packetFd) }
    }

    private fun allocatePort(): Int {
        while (true) {
            val port = nextPort.getAndIncrement()
            val normalized = if (port > 60_000) {
                nextPort.set(30_000)
                30_000
            } else {
                port
            }
            if (!connections.containsKey(normalized)) {
                return normalized
            }
        }
    }

    private fun readPackets() {
        val buffer = ByteArray(32 * 1024)
        var readError: Exception? = null
        while (true) {
            val read = try {
                Os.read(packetFd, buffer, 0, buffer.size)
            } catch (e: Exception) {
                readError = e
                break
            }
            if (read <= 0) continue
            val packet = parseIpv4TcpPacket(buffer, read) ?: continue
            val connection = connections[packet.dstPort] ?: continue
            try {
                connection.onPacket(packet)
            } catch (e: Exception) {
                Log.w(
                    USERSACE_TAG,
                    "Closing virtual TCP ${packet.dstPort} after packet handling error: ${e.message ?: e}"
                )
                connection.close()
            }
        }
        if (!closed) {
            readError?.let { error ->
                Log.w(
                    USERSACE_TAG,
                    "OpenVPN userspace packet reader stopped: ${error.message ?: error}"
                )
            }
            close()
        }
    }

    private fun closeAfterPacketFdError(error: Exception) {
        if (!closed) {
            Log.w(
                USERSACE_TAG,
                "Closing OpenVPN userspace router after packet fd write error: ${error.message ?: error}"
            )
            close()
        }
    }

    private suspend fun closeIdleConnections() {
        while (!closed) {
            delay(TCP_IDLE_SWEEP_INTERVAL_MS)
            val now = System.currentTimeMillis()
            val underPressure = connections.size >= TCP_PRESSURE_CONNECTIONS
            connections.values.forEach { connection ->
                val closeReason = connection.idleCloseReason(now, underPressure)
                if (closeReason != null) {
                    Log.i(USERSACE_TAG, "Closing $closeReason virtual TCP ${connection.describe()}")
                    connection.close()
                    return@forEach
                }

                val gapCloseReason = connection.gapCloseReason(now, underPressure)
                if (gapCloseReason != null) {
                    Log.w(USERSACE_TAG, "Closing $gapCloseReason virtual TCP ${connection.describe()}")
                    connection.close()
                }
            }
        }
    }

    private suspend fun retransmitGapAcks() {
        while (!closed) {
            delay(TCP_GAP_ACK_INTERVAL_MS)
            val now = System.currentTimeMillis()
            connections.values.forEach { connection ->
                try {
                    connection.retransmitGapAckIfNeeded(now)
                } catch (e: Exception) {
                    Log.w(
                        USERSACE_TAG,
                        "Closing virtual TCP ${connection.describe()} after gap ACK error: ${e.message ?: e}"
                    )
                    connection.close()
                }
            }
        }
    }
}

private class VirtualTcpConnection(
    private val router: UserspaceTcpRouter,
    private val localAddress: Int,
    private val remoteAddress: Int,
    private val localPort: Int,
    private val remotePort: Int
) : Closeable {
    private val lock = Object()
    private val sendLock = Object()
    private val receivedInput = TcpPayloadInputStream(TCP_RECEIVE_QUEUE_CHUNKS)
    private val pendingInbound = TreeMap<Long, ByteArray>()
    private var sendSeq = Random.nextInt().toLong() and 0xffffffffL
    private var ackedSeq = sendSeq
    private var remoteSeq = 0L
    private var pendingInboundBytes = 0
    private var outOfOrderBufferedPackets = 0
    private var gapStartedAt = 0L
    private var gapAck = 0L
    private var lastGapAckAt = 0L
    private var lastGapLogAt = 0L
    private var receiveBufferedBytes = 0
    private var receiveWindowBlocked = false
    private var lastReceiveWindowFullLogAt = 0L
    private var established = false
    @Volatile
    private var outputShutdown = false
    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
        private set
    @Volatile
    private var outputShutdownAt = 0L
    @Volatile
    private var lastInboundPayloadAt = lastActivityAt
    @Volatile
    var closed: Boolean = false
        private set

    val input: InputStream = receivedInput

    fun describe(): String = "$localPort -> ${intToIpv4(remoteAddress)}:$remotePort"

    fun idleCloseReason(now: Long, underPressure: Boolean): String? {
        if (closed) return null
        val halfClosedTimeout = if (underPressure) {
            TCP_PRESSURE_HALF_CLOSED_IDLE_TIMEOUT_MS
        } else {
            TCP_HALF_CLOSED_IDLE_TIMEOUT_MS
        }
        val idleTimeout = if (underPressure) TCP_PRESSURE_IDLE_TIMEOUT_MS else TCP_IDLE_TIMEOUT_MS
        val lastHalfClosedDataAt = maxOf(outputShutdownAt, lastInboundPayloadAt)
        return when {
            outputShutdown && now - lastHalfClosedDataAt > halfClosedTimeout ->
                if (underPressure) "half-closed idle under pressure" else "half-closed idle"
            now - lastActivityAt > idleTimeout ->
                if (underPressure) "idle under pressure" else "idle"
            else -> null
        }
    }

    fun gapCloseReason(now: Long, underPressure: Boolean): String? =
        synchronized(lock) {
            if (closed || pendingInbound.isEmpty()) {
                clearGapTrackingLocked()
                return@synchronized null
            }

            if (gapStartedAt == 0L || gapAck != remoteSeq) {
                gapStartedAt = now
                gapAck = remoteSeq
                return@synchronized null
            }

            val timeout = if (underPressure) {
                TCP_PRESSURE_GAP_STALL_CLOSE_MS
            } else {
                TCP_GAP_STALL_CLOSE_MS
            }
            if (now - gapStartedAt > timeout) {
                if (underPressure) "stalled gap under pressure" else "stalled gap"
            } else {
                null
            }
        }

    fun connect() {
        val startedAt = System.currentTimeMillis()
        touch()
        val synSeq = synchronized(lock) {
            val seq = sendSeq
            sendSeq = addSeq(sendSeq, 1)
            seq
        }
        router.sendTcp(
            localAddress,
            remoteAddress,
            localPort,
            remotePort,
            synSeq,
            0,
            TCP_SYN,
            tcpOptions = tcpSynOptions()
        )
        if (!waitUntil(TCP_CONNECT_TIMEOUT_MS) { established }) {
            throw EOFException("Timed out waiting for TCP SYN-ACK from ${intToIpv4(remoteAddress)}:$remotePort")
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        val message = "Virtual TCP connected $localPort -> ${intToIpv4(remoteAddress)}:$remotePort in ${elapsedMs}ms"
        if (elapsedMs >= SLOW_VIRTUAL_TCP_CONNECT_MS) {
            Log.w(USERSACE_TAG, message)
        } else {
            traceUserspace(message)
        }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        synchronized(sendLock) {
            var written = 0
            while (written < length && !closed && !outputShutdown) {
                touch()
                val windowStartSeq = synchronized(lock) { sendSeq }
                val windowLength = min(TCP_SEND_WINDOW_BYTES, length - written)
                var sent = 0
                while (sent < windowLength && !closed) {
                    val chunk = min(TCP_MSS, windowLength - sent)
                    sendNewDataSegment(data, offset + written + sent, chunk)
                    sent += chunk
                }

                if (sent <= 0) {
                    break
                }

                val targetAck = synchronized(lock) { sendSeq }
                var ackWaitStartedAt = System.currentTimeMillis()
                var acknowledged = waitForAck(targetAck, TCP_ACK_TIMEOUT_MS)
                logSlowAckIfNeeded(targetAck, System.currentTimeMillis() - ackWaitStartedAt, sent)
                var retry = 0
                while (!acknowledged && retry < TCP_SEND_RETRIES && !closed) {
                    val ackedBytes = synchronized(lock) {
                        seqDistance(ackedSeq, windowStartSeq).coerceIn(0, sent)
                    }
                    Log.w(
                        USERSACE_TAG,
                        "Retransmitting virtual TCP $localPort -> ${intToIpv4(remoteAddress)}:$remotePort, " +
                            "acked=$ackedBytes/$sent"
                    )
                    var resend = ackedBytes
                    while (resend < sent && !closed) {
                        val chunk = min(TCP_MSS, sent - resend)
                        sendDataSegmentAt(addSeq(windowStartSeq, resend), data, offset + written + resend, chunk)
                        resend += chunk
                    }
                    retry += 1
                    ackWaitStartedAt = System.currentTimeMillis()
                    acknowledged = waitForAck(targetAck, TCP_ACK_TIMEOUT_MS)
                    logSlowAckIfNeeded(targetAck, System.currentTimeMillis() - ackWaitStartedAt, sent)
                }

                if (!acknowledged) {
                    if (!closed) {
                        Log.w(
                            USERSACE_TAG,
                            "Timed out waiting for virtual TCP ACK $localPort -> ${intToIpv4(remoteAddress)}:$remotePort"
                        )
                    }
                    throw EOFException("Timed out waiting for TCP ACK from ${intToIpv4(remoteAddress)}:$remotePort")
                }
                written += sent
            }
        }
    }

    fun shutdownOutput() {
        var shouldSendFin = false
        synchronized(lock) {
            if (!closed && !outputShutdown && established) {
                outputShutdown = true
                outputShutdownAt = System.currentTimeMillis()
                shouldSendFin = true
            }
            lock.notifyAll()
        }
        if (shouldSendFin) {
            synchronized(sendLock) {
                val snapshot = reserveFinSnapshot()
                runCatching {
                    router.sendTcp(
                        localAddress,
                        remoteAddress,
                        localPort,
                        remotePort,
                        snapshot.seq,
                        snapshot.ack,
                        TCP_ACK or TCP_FIN,
                        window = snapshot.window,
                        tcpOptions = snapshot.tcpOptions
                    )
                }
            }
        }
    }

    fun onPacket(packet: TcpPacket) {
        touch()
        synchronized(lock) {
            if (closed) {
                return
            }

            if ((packet.flags and TCP_RST) != 0) {
                closeLocked()
                lock.notifyAll()
                return
            }

            if ((packet.flags and TCP_ACK) != 0 && isSeqAfterOrEqual(packet.ack, ackedSeq)) {
                ackedSeq = packet.ack
                lock.notifyAll()
            }

            if (!established && (packet.flags and TCP_SYN) != 0 && (packet.flags and TCP_ACK) != 0) {
                remoteSeq = (packet.seq + 1) and 0xffffffffL
                established = true
                ackedSeq = packet.ack
                sendAckLocked()
                lock.notifyAll()
                return
            }

            if (packet.payloadLength > 0) {
                if (!handleInboundPayload(packet)) {
                    lock.notifyAll()
                    return
                }
                sendAckLocked()
            }

            if ((packet.flags and TCP_FIN) != 0) {
                val expectedFinSeq = addSeq(packet.seq, packet.payloadLength)
                if (expectedFinSeq != remoteSeq) {
                    sendAckLocked()
                    return
                }
                remoteSeq = addSeq(remoteSeq, 1)
                sendAckLocked()
                closeLocked()
                lock.notifyAll()
            }
        }
    }

    fun onInboundPayloadDelivered(length: Int) {
        if (length <= 0) return
        var shouldUpdateWindow = false
        synchronized(lock) {
            receiveBufferedBytes = (receiveBufferedBytes - length).coerceAtLeast(0)
            if (!closed && receiveWindowBlocked && availableReceiveWindowBytesLocked() >= TCP_MSS) {
                receiveWindowBlocked = false
                shouldUpdateWindow = true
            }
        }
        if (shouldUpdateWindow) {
            sendAck()
        }
    }

    fun retransmitGapAckIfNeeded(now: Long) {
        synchronized(lock) {
            if (closed || pendingInbound.isEmpty() || now - lastGapAckAt < TCP_GAP_ACK_INTERVAL_MS) {
                return
            }
            lastGapAckAt = now
            val firstSeq = pendingInbound.firstKey()
            if (now - lastGapLogAt >= TCP_GAP_STALL_LOG_INTERVAL_MS) {
                lastGapLogAt = now
                traceUserspace(
                    "Retransmitting duplicate ACK for virtual TCP gap $localPort -> " +
                        "${intToIpv4(remoteAddress)}:$remotePort ack=$remoteSeq firstPending=$firstSeq " +
                        "gap=${seqDistance(firstSeq, remoteSeq)} pending=$pendingInboundBytes bytes"
                )
            }
            sendAckLocked()
        }
    }

    override fun close() {
        var shouldSendFin = false
        synchronized(lock) {
            if (!closed && !outputShutdown) {
                shouldSendFin = established
                outputShutdown = true
                outputShutdownAt = System.currentTimeMillis()
            }
            closeLocked()
            lock.notifyAll()
        }
        if (shouldSendFin) {
            synchronized(sendLock) {
                val snapshot = reserveFinSnapshot()
                runCatching {
                    router.sendTcp(
                        localAddress,
                        remoteAddress,
                        localPort,
                        remotePort,
                        snapshot.seq,
                        snapshot.ack,
                        TCP_ACK or TCP_FIN,
                        window = snapshot.window,
                        tcpOptions = snapshot.tcpOptions
                    )
                }
            }
        }
    }

    private fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    private fun waitForAck(target: Long, timeoutMs: Long): Boolean =
        waitUntil(timeoutMs) { isSeqAfterOrEqual(ackedSeq, target) }

    private fun logSlowAckIfNeeded(targetAck: Long, elapsedMs: Long, sentBytes: Int) {
        val snapshot = synchronized(lock) { TcpAckSnapshot(sendSeq, ackedSeq, advertisedWindowLocked()) }
        if (elapsedMs >= SLOW_VIRTUAL_TCP_ACK_MS && !closed) {
            Log.w(
                USERSACE_TAG,
                "Slow virtual TCP ACK $localPort -> ${intToIpv4(remoteAddress)}:$remotePort " +
                    "waited=${elapsedMs}ms bytes=$sentBytes target=$targetAck acked=${snapshot.ack}"
            )
        }
    }

    private fun handleInboundPayload(packet: TcpPacket): Boolean {
        if (packet.seq == remoteSeq) {
            if (!deliverInboundPayload(packet.payloadBuffer, packet.payloadOffset, packet.payloadLength)) {
                return false
            }
            return drainPendingInbound()
        }

        if (isSeqBefore(packet.seq, remoteSeq)) {
            val alreadyDelivered = seqDistance(remoteSeq, packet.seq)
            if (alreadyDelivered >= packet.payloadLength) {
                return true
            }
            if (!deliverInboundPayload(
                    packet.payloadBuffer,
                    packet.payloadOffset + alreadyDelivered,
                    packet.payloadLength - alreadyDelivered
                )
            ) {
                return false
            }
            return drainPendingInbound()
        }

        bufferOutOfOrderPayload(packet.seq, packet.payloadBuffer, packet.payloadOffset, packet.payloadLength)
        return true
    }

    private fun drainPendingInbound(): Boolean {
        while (!closed) {
            val entry = pendingInbound.firstEntry()
            if (entry == null) {
                clearGapTrackingLocked()
                return true
            }
            val seq = entry.key
            if (seq != remoteSeq && !isSeqBefore(seq, remoteSeq)) {
                return true
            }

            val payload = pendingInbound.remove(seq) ?: return true
            pendingInboundBytes = (pendingInboundBytes - payload.size).coerceAtLeast(0)
            val alreadyDelivered = if (isSeqBefore(seq, remoteSeq)) {
                seqDistance(remoteSeq, seq)
            } else {
                0
            }
            if (alreadyDelivered < payload.size &&
                !deliverInboundPayload(payload, alreadyDelivered, payload.size - alreadyDelivered)
            ) {
                pendingInbound[seq] = payload
                pendingInboundBytes += payload.size
                return false
            }
        }
        clearGapTrackingLocked()
        return false
    }

    private fun deliverInboundPayload(payload: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0) {
            return true
        }
        if (receiveBufferedBytes + length > TCP_RECEIVE_BUFFER_BYTES) {
            val now = System.currentTimeMillis()
            receiveWindowBlocked = true
            if (now - lastReceiveWindowFullLogAt >= TCP_RECEIVE_WINDOW_LOG_INTERVAL_MS) {
                lastReceiveWindowFullLogAt = now
                Log.w(
                    USERSACE_TAG,
                    "Receive window full for virtual TCP $localPort -> ${intToIpv4(remoteAddress)}:$remotePort " +
                        "buffered=$receiveBufferedBytes bytes"
                )
            }
            sendAckLocked(window = 0)
            return false
        }
        val chunk = payload.copyOfRange(offset, offset + length)
        if (!receivedInput.enqueue(chunk)) {
            Log.w(
                USERSACE_TAG,
                "Receive queue full for virtual TCP $localPort -> ${intToIpv4(remoteAddress)}:$remotePort"
            )
            receiveWindowBlocked = true
            sendAckLocked(window = 0)
            return false
        }
        receiveBufferedBytes += length
        lastInboundPayloadAt = System.currentTimeMillis()
        remoteSeq = addSeq(remoteSeq, length)
        return true
    }

    private fun bufferOutOfOrderPayload(seq: Long, payload: ByteArray, offset: Int, length: Int) {
        if (length <= 0) {
            return
        }
        if (pendingInbound.containsKey(seq)) {
            return
        }
        if (pendingInboundBytes + length > TCP_OUT_OF_ORDER_BUFFER_BYTES) {
            Log.w(
                USERSACE_TAG,
                "Dropping out-of-order virtual TCP payload $localPort -> ${intToIpv4(remoteAddress)}:$remotePort " +
                    "pending=$pendingInboundBytes bytes"
            )
            return
        }
        pendingInbound[seq] = payload.copyOfRange(offset, offset + length)
        pendingInboundBytes += length
        if (gapStartedAt == 0L || gapAck != remoteSeq) {
            gapStartedAt = System.currentTimeMillis()
            gapAck = remoteSeq
        }
        outOfOrderBufferedPackets += 1
        if (outOfOrderBufferedPackets == 1 || outOfOrderBufferedPackets % 500 == 0) {
            traceUserspace(
                "Buffered out-of-order virtual TCP payload $localPort -> ${intToIpv4(remoteAddress)}:$remotePort " +
                    "packets=$outOfOrderBufferedPackets gap=${seqDistance(seq, remoteSeq)} " +
                    "pending=$pendingInboundBytes bytes"
            )
        }
    }

    private fun sendNewDataSegment(
        data: ByteArray,
        dataOffset: Int,
        length: Int
    ) {
        try {
            synchronized(lock) {
                val seq = sendSeq
                val snapshot = TcpAckSnapshot(seq, remoteSeq, advertisedWindowLocked(), sackOptionsLocked())
                sendSeq = addSeq(sendSeq, length)
                router.sendTcp(
                    localAddress,
                    remoteAddress,
                    localPort,
                    remotePort,
                    snapshot.seq,
                    snapshot.ack,
                    TCP_ACK or TCP_PSH,
                    data,
                    dataOffset,
                    length,
                    window = snapshot.window,
                    tcpOptions = snapshot.tcpOptions
                )
            }
        } catch (e: Exception) {
            synchronized(lock) {
                closeLocked()
                lock.notifyAll()
            }
            throw e
        }
    }

    private fun sendDataSegmentAt(
        seq: Long,
        data: ByteArray,
        dataOffset: Int,
        length: Int
    ) {
        val snapshot = synchronized(lock) {
            TcpAckSnapshot(seq, remoteSeq, advertisedWindowLocked(), sackOptionsLocked())
        }
        try {
            router.sendTcp(
                localAddress,
                remoteAddress,
                localPort,
                remotePort,
                snapshot.seq,
                snapshot.ack,
                TCP_ACK or TCP_PSH,
                data,
                dataOffset,
                length,
                window = snapshot.window,
                tcpOptions = snapshot.tcpOptions
            )
        } catch (e: Exception) {
            synchronized(lock) {
                closeLocked()
                lock.notifyAll()
            }
            throw e
        }
    }

    private fun sendAck() {
        val snapshot = synchronized(lock) {
            TcpAckSnapshot(sendSeq, remoteSeq, advertisedWindowLocked(), sackOptionsLocked())
        }
        router.sendTcp(
            localAddress,
            remoteAddress,
            localPort,
            remotePort,
            snapshot.seq,
            snapshot.ack,
            TCP_ACK,
            window = snapshot.window,
            tcpOptions = snapshot.tcpOptions
        )
    }

    private fun sendAckLocked(window: Int = advertisedWindowLocked()) {
        router.sendTcp(
            localAddress,
            remoteAddress,
            localPort,
            remotePort,
            sendSeq,
            remoteSeq,
            TCP_ACK,
            window = window,
            tcpOptions = sackOptionsLocked()
        )
    }

    private fun receiveWindowSnapshot(): Int =
        synchronized(lock) { advertisedWindowLocked() }

    private fun reserveFinSnapshot(): TcpAckSnapshot =
        synchronized(lock) {
            val snapshot = TcpAckSnapshot(sendSeq, remoteSeq, advertisedWindowLocked(), sackOptionsLocked())
            sendSeq = addSeq(sendSeq, 1)
            snapshot
        }

    private fun sackOptionsLocked(maxBlocks: Int = 4): ByteArray {
        if (pendingInbound.isEmpty()) {
            return ByteArray(0)
        }

        val blocks = ArrayList<Pair<Long, Long>>(maxBlocks)
        var blockStart: Long? = null
        var blockEnd = 0L
        for ((seq, payload) in pendingInbound) {
            val payloadEnd = addSeq(seq, payload.size)
            if (blockStart == null) {
                blockStart = seq
                blockEnd = payloadEnd
                continue
            }
            if (seq == blockEnd) {
                blockEnd = payloadEnd
                continue
            }
            blocks += blockStart to blockEnd
            if (blocks.size >= maxBlocks) {
                break
            }
            blockStart = seq
            blockEnd = payloadEnd
        }
        blockStart?.let {
            if (blocks.size < maxBlocks) {
                blocks += it to blockEnd
            }
        }
        if (blocks.isEmpty()) {
            return ByteArray(0)
        }

        val sackLength = 2 + blocks.size * 8
        val paddedLength = ((sackLength + 3) / 4) * 4
        val options = ByteArray(paddedLength) { TCP_OPTION_NOP.toByte() }
        options[0] = TCP_OPTION_SACK.toByte()
        options[1] = sackLength.toByte()
        var offset = 2
        blocks.forEach { (left, right) ->
            putU32(options, offset, left.toInt())
            putU32(options, offset + 4, right.toInt())
            offset += 8
        }
        return options
    }

    private fun availableReceiveWindowBytesLocked(): Int =
        (TCP_RECEIVE_BUFFER_BYTES - receiveBufferedBytes - pendingInboundBytes).coerceAtLeast(0)

    private fun advertisedWindowLocked(): Int =
        availableReceiveWindowBytesLocked().coerceIn(0, TCP_ADVERTISED_WINDOW)

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!condition() && !closed) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    return false
                }
                lock.wait(min(remaining, 250L))
            }
            return condition()
        }
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        outputShutdown = true
        router.remove(localPort)
        pendingInbound.clear()
        pendingInboundBytes = 0
        clearGapTrackingLocked()
        receiveBufferedBytes = 0
        receiveWindowBlocked = false
        receivedInput.close()
    }

    private fun clearGapTrackingLocked() {
        gapStartedAt = 0L
        gapAck = 0L
        lastGapAckAt = 0L
        lastGapLogAt = 0L
    }
}

private class TcpPayloadInputStream(
    capacityChunks: Int
) : InputStream() {
    private val queue = LinkedBlockingQueue<ByteArray>(capacityChunks)
    @Volatile
    private var closed = false
    private var current: ByteArray? = null
    private var currentOffset = 0

    fun enqueue(payload: ByteArray): Boolean {
        if (closed || payload.isEmpty()) {
            return !closed
        }
        return queue.offer(payload)
    }

    override fun read(): Int {
        val single = ByteArray(1)
        val read = read(single, 0, 1)
        return if (read < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var total = 0
        while (true) {
            val chunk = current
            if (chunk != null && currentOffset < chunk.size) {
                val copied = min(length - total, chunk.size - currentOffset)
                System.arraycopy(chunk, currentOffset, buffer, offset + total, copied)
                currentOffset += copied
                total += copied
                if (currentOffset >= chunk.size) {
                    current = null
                    currentOffset = 0
                }
                if (total >= length) {
                    return total
                }
                continue
            }

            if (closed && queue.isEmpty()) {
                return if (total > 0) total else -1
            }

            val next = try {
                if (total > 0) queue.poll() else queue.poll(250, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while reading virtual TCP payload")
            }
            if (next != null) {
                current = next
                currentOffset = 0
                continue
            }
            if (total > 0) {
                return total
            }
        }
    }

    override fun close() {
        closed = true
    }
}

private data class TcpPacket(
    val srcIp: Int,
    val dstIp: Int,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val payloadBuffer: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int
)

private const val TCP_FIN = 0x01
private const val TCP_SYN = 0x02
private const val TCP_RST = 0x04
private const val TCP_PSH = 0x08
private const val TCP_ACK = 0x10
private const val TCP_OPTION_NOP = 0x01
private const val TCP_OPTION_MSS = 0x02
private const val TCP_OPTION_SACK_PERMITTED = 0x04
private const val TCP_OPTION_SACK = 0x05

private fun parseIpv4TcpPacket(buffer: ByteArray, length: Int): TcpPacket? {
    if (length < 40) return null
    val version = (buffer[0].toInt() ushr 4) and 0x0f
    val ihl = (buffer[0].toInt() and 0x0f) * 4
    if (version != 4 || ihl < 20 || length < ihl + 20) return null
    if ((buffer[9].toInt() and 0xff) != 6) return null
    val totalLength = u16(buffer, 2).coerceAtMost(length)
    val srcIp = u32(buffer, 12)
    val dstIp = u32(buffer, 16)
    val srcPort = u16(buffer, ihl)
    val dstPort = u16(buffer, ihl + 2)
    val seq = u32(buffer, ihl + 4).toLong() and 0xffffffffL
    val ack = u32(buffer, ihl + 8).toLong() and 0xffffffffL
    val tcpHeaderLength = ((buffer[ihl + 12].toInt() ushr 4) and 0x0f) * 4
    if (tcpHeaderLength < 20 || totalLength < ihl + tcpHeaderLength) return null
    val flags = buffer[ihl + 13].toInt() and 0xff
    val payloadOffset = ihl + tcpHeaderLength
    val payloadLength = totalLength - payloadOffset
    return TcpPacket(srcIp, dstIp, srcPort, dstPort, seq, ack, flags, buffer, payloadOffset, payloadLength)
}

private fun buildIpv4TcpPacket(
    srcIp: Int,
    dstIp: Int,
    srcPort: Int,
    dstPort: Int,
    seq: Long,
    ack: Long,
    flags: Int,
    ipId: Int,
    payload: ByteArray,
    payloadOffset: Int,
    payloadLength: Int,
    window: Int,
    tcpOptions: ByteArray = tcpOptionsFor(flags)
): ByteArray {
    val ipHeaderLength = 20
    val tcpHeaderLength = 20 + tcpOptions.size
    val totalLength = ipHeaderLength + tcpHeaderLength + payloadLength
    val packet = ByteArray(totalLength)
    packet[0] = 0x45
    packet[1] = 0
    putU16(packet, 2, totalLength)
    putU16(packet, 4, ipId)
    putU16(packet, 6, 0x4000)
    packet[8] = 64
    packet[9] = 6
    putU32(packet, 12, srcIp)
    putU32(packet, 16, dstIp)
    putU16(packet, 10, checksum(packet, 0, ipHeaderLength))

    val tcp = ipHeaderLength
    putU16(packet, tcp, srcPort)
    putU16(packet, tcp + 2, dstPort)
    putU32(packet, tcp + 4, seq.toInt())
    putU32(packet, tcp + 8, ack.toInt())
    packet[tcp + 12] = ((tcpHeaderLength / 4) shl 4).toByte()
    packet[tcp + 13] = flags.toByte()
    putU16(packet, tcp + 14, window)
    if (tcpOptions.isNotEmpty()) {
        System.arraycopy(tcpOptions, 0, packet, tcp + 20, tcpOptions.size)
    }
    if (payloadLength > 0) {
        System.arraycopy(payload, payloadOffset, packet, tcp + tcpHeaderLength, payloadLength)
    }
    putU16(packet, tcp + 16, tcpChecksum(packet, tcp, tcpHeaderLength + payloadLength, srcIp, dstIp))
    return packet
}

private fun tcpOptionsFor(flags: Int): ByteArray {
    if ((flags and TCP_SYN) == 0) {
        return ByteArray(0)
    }
    return tcpSynOptions()
}

private fun tcpSynOptions(): ByteArray =
    byteArrayOf(
        TCP_OPTION_MSS.toByte(),
        0x04,
        ((TCP_MSS ushr 8) and 0xff).toByte(),
        (TCP_MSS and 0xff).toByte(),
        TCP_OPTION_SACK_PERMITTED.toByte(),
        0x02,
        TCP_OPTION_NOP.toByte(),
        TCP_OPTION_NOP.toByte()
    )

private fun tcpChecksum(packet: ByteArray, offset: Int, length: Int, srcIp: Int, dstIp: Int): Int {
    val pseudo = ByteArray(12 + length)
    putU32(pseudo, 0, srcIp)
    putU32(pseudo, 4, dstIp)
    pseudo[8] = 0
    pseudo[9] = 6
    putU16(pseudo, 10, length)
    System.arraycopy(packet, offset, pseudo, 12, length)
    return checksum(pseudo, 0, pseudo.size)
}

private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
    var sum = 0
    var index = offset
    val end = offset + length
    while (index + 1 < end) {
        sum += ((buffer[index].toInt() and 0xff) shl 8) or (buffer[index + 1].toInt() and 0xff)
        index += 2
    }
    if (index < end) {
        sum += (buffer[index].toInt() and 0xff) shl 8
    }
    while ((sum ushr 16) != 0) {
        sum = (sum and 0xffff) + (sum ushr 16)
    }
    return sum.inv() and 0xffff
}

private fun u16(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)

private fun u32(buffer: ByteArray, offset: Int): Int =
    ((buffer[offset].toInt() and 0xff) shl 24) or
        ((buffer[offset + 1].toInt() and 0xff) shl 16) or
        ((buffer[offset + 2].toInt() and 0xff) shl 8) or
        (buffer[offset + 3].toInt() and 0xff)

private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value ushr 8) and 0xff).toByte()
    buffer[offset + 1] = (value and 0xff).toByte()
}

private fun putU32(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value ushr 24) and 0xff).toByte()
    buffer[offset + 1] = ((value ushr 16) and 0xff).toByte()
    buffer[offset + 2] = ((value ushr 8) and 0xff).toByte()
    buffer[offset + 3] = (value and 0xff).toByte()
}

private fun ipv4ToInt(bytes: ByteArray): Int =
    ((bytes[0].toInt() and 0xff) shl 24) or
        ((bytes[1].toInt() and 0xff) shl 16) or
        ((bytes[2].toInt() and 0xff) shl 8) or
        (bytes[3].toInt() and 0xff)

private fun intToIpv4(value: Int): String =
    listOf(
        (value ushr 24) and 0xff,
        (value ushr 16) and 0xff,
        (value ushr 8) and 0xff,
        value and 0xff
    ).joinToString(".")

private fun addSeq(seq: Long, delta: Int): Long =
    (seq + delta) and 0xffffffffL

private fun seqDistance(a: Long, b: Long): Int {
    val distance = (a - b) and 0xffffffffL
    return if (distance < 0x80000000L) distance.toInt() else 0
}

private fun isSeqAfterOrEqual(a: Long, b: Long): Boolean =
    ((a - b) and 0xffffffffL) < 0x80000000L

private fun isSeqBefore(a: Long, b: Long): Boolean =
    ((a - b) and 0xffffffffL) >= 0x80000000L

private fun readFully(input: InputStream, buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val read = input.read(buffer, offset, buffer.size - offset)
        if (read < 0) {
            throw EOFException("Unexpected EOF")
        }
        offset += read
    }
}
