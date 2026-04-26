package org.amnezia.vpn.protocol.xray

import android.content.Context
import android.net.VpnService.Builder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import go.Seq
import org.amnezia.vpn.protocol.BadConfigException
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState.CONNECTED
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.Statistics
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.protocol.xray.libXray.DialerController
import org.amnezia.vpn.protocol.xray.libXray.LibXray
import org.amnezia.vpn.protocol.xray.libXray.Logger
import org.amnezia.vpn.protocol.xray.libXray.Tun2SocksConfig
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.net.InetNetwork
import org.amnezia.vpn.util.net.ip
import org.amnezia.vpn.util.net.parseInetAddress
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "Xray"
private const val LIBXRAY_TAG = "libXray"
private const val SOCKS_WAIT_TIMEOUT_MS = 4_000L
private const val SOCKS_WAIT_RETRY_DELAY_MS = 50L
private const val SOCKS_WAIT_CONNECT_TIMEOUT_MS = 200
private const val APP_SPLIT_TUNNEL_INCLUDE = 1

private fun findSocksInboundIndex(inbounds: JSONArray): Int {
    for (i in 0 until inbounds.length()) {
        val o = inbounds.optJSONObject(i) ?: continue
        if (o.optString("protocol").equals("socks", ignoreCase = true)) {
            return i
        }
    }
    return -1
}

private fun acquireFreeLocalPort(): Int {
    try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { return it.localPort }
    } catch (e: Exception) {
        throw VpnStartException(
            "Failed to acquire free TCP port on 127.0.0.1 for SOCKS inbound: ${e.message}"
        )
    }
}

private fun waitForLocalSocksReady(port: Int) {
    val deadline = System.currentTimeMillis() + SOCKS_WAIT_TIMEOUT_MS
    var lastError = "listener not ready"

    while (System.currentTimeMillis() < deadline) {
        try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress("127.0.0.1", port),
                    SOCKS_WAIT_CONNECT_TIMEOUT_MS
                )
            }
            return
        } catch (e: Exception) {
            lastError = "${e::class.java.simpleName}: ${e.message}"
            try {
                Thread.sleep(SOCKS_WAIT_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    throw VpnStartException(
        "Timed out waiting for Xray SOCKS inbound on 127.0.0.1:$port: $lastError"
    )
}

private fun extractRemoteHost(xrayConfig: JSONObject): String? =
    xrayConfig.optJSONArray("outbounds")
        ?.optJSONObject(0)
        ?.optJSONObject("settings")
        ?.optJSONArray("vnext")
        ?.optJSONObject(0)
        ?.optString("address")
        ?.takeIf { it.isNotBlank() }

private fun configureCoreDns(xrayJsonConfig: JSONObject, config: JSONObject) {
    val remoteHost = extractRemoteHost(xrayJsonConfig)
    val remoteIp = remoteHost?.let { runCatching { parseInetAddress(it).ip }.getOrNull() }

    val preferredDns = linkedSetOf<String>()
    config.optJSONArray("dnsServers")?.let { dnsArray ->
        for (index in 0 until dnsArray.length()) {
            dnsArray.optString(index)
                .takeIf { it.isNotBlank() }
                ?.let(preferredDns::add)
        }
    }
    config.optString("dns1")
        .takeIf { it.isNotBlank() }
        ?.let(preferredDns::add)
    config.optString("dns2")
        .takeIf { it.isNotBlank() }
        ?.let(preferredDns::add)

    val filteredDns = preferredDns.filterNot { dns ->
        val dnsIp = runCatching { parseInetAddress(dns).ip }.getOrNull()
        (!remoteHost.isNullOrBlank() && dns.equals(remoteHost, ignoreCase = true)) ||
            (!dnsIp.isNullOrBlank() && !remoteIp.isNullOrBlank() && dnsIp == remoteIp)
    }
    if (filteredDns.isEmpty()) {
        return
    }

    val tunnelFirstDns = filteredDns.sortedWith(
        compareBy<String> { !it.startsWith("10.") && !it.startsWith("192.168.") && !it.startsWith("172.") }
            .thenBy { it }
    )

    val dnsObject = xrayJsonConfig.optJSONObject("dns") ?: JSONObject()
    val servers = JSONArray()
    tunnelFirstDns.forEach { dns ->
        servers.put("tcp://$dns")
    }
    dnsObject.put("servers", servers)
    dnsObject.put("queryStrategy", "UseIPv4")
    xrayJsonConfig.put("dns", dnsObject)
    Log.i(TAG, "Configured Xray core DNS servers: $tunnelFirstDns")
}

open class Xray : Protocol() {

    private var isRunning: Boolean = false
    override val statistics: Statistics = Statistics.EMPTY_STATISTICS

    override fun internalInit() {
        Seq.setContext(context)
        if (!isInitialized) {
            LibXray.initLogger(object : Logger {
                override fun warning(s: String) = Log.w(LIBXRAY_TAG, s)

                override fun error(s: String) = Log.e(LIBXRAY_TAG, s)

                override fun write(msg: ByteArray): Long {
                    Log.w(LIBXRAY_TAG, String(msg))
                    return msg.size.toLong()
                }
            }).isNotNullOrBlank { err ->
                Log.w(TAG, "Failed to initialize logger: $err")
            }
        }
    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        if (isRunning) {
            Log.w(TAG, "XRay already running")
            return
        }

        val xrayJsonConfig = config.optJSONObject("xray_config_data")
            ?: config.optJSONObject("ssxray_config_data")
            ?: throw BadConfigException("config_data not found")

        // Inject SOCKS5 auth before starting xray. Re-uses existing credentials if present.
        ensureInboundAuth(xrayJsonConfig)
        configureCoreDns(xrayJsonConfig, config)

        val xrayConfig = parseConfig(config, xrayJsonConfig)

        (xrayJsonConfig.optJSONObject("log") ?: JSONObject().also { xrayJsonConfig.put("log", it) })
            .put("loglevel", "warning")
            .put("access", "none") // disable access log

        var xrayJsonConfigString = xrayJsonConfig.toString()
        config.getString("hostName").let { hostName ->
            val ipAddress = parseInetAddress(hostName).ip
            if (hostName != ipAddress) {
                xrayJsonConfigString = xrayJsonConfigString.replace(hostName, ipAddress)
            }
        }

        start(xrayConfig, xrayJsonConfigString, vpnBuilder, protect)
        state.value = CONNECTED
        isRunning = true
    }

    private fun parseConfig(config: JSONObject, xrayJsonConfig: JSONObject): XrayConfig {
        val dnsServers = resolveDnsServers(config)
        Log.i(TAG, "Xray interface DNS order: ${dnsServers.ifEmpty { listOf("<none>") }}")

        return XrayConfig.build {
            addAddress(XrayConfig.DEFAULT_IPV4_ADDRESS)

            for (dnsServer in dnsServers) {
                val parsedDnsServer = parseInetAddress(dnsServer)
                addDnsServer(parsedDnsServer)
                // Route DNS explicitly through the VPN interface so mobile policy routing
                // does not have to rely solely on the default route.
                addRoute(InetNetwork(parsedDnsServer))
            }

            addRoute(InetNetwork("0.0.0.0", 0))
            addRoute(InetNetwork("2000::0", 3))
            if (!config.optBoolean("oxrayOpenVpnUnderlay", false)) {
                config.getString("hostName").let { hostName ->
                    excludeRoute(InetNetwork(parseInetAddress(hostName)))
                }
            }

            config.optJSONArray("excludedAddresses")?.let { excludedAddresses ->
                for (index in 0 until excludedAddresses.length()) {
                    excludedAddresses.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let { excludeRoute(InetNetwork(parseInetAddress(it))) }
                }
            }

            config.optString("mtu").let {
                if (it.isNotBlank()) setMtu(it.toInt())
            }

            val inbounds = xrayJsonConfig.getJSONArray("inbounds")
            val socksIdx = findSocksInboundIndex(inbounds)
            if (socksIdx < 0) {
                throw BadConfigException("socks inbound not found")
            }
            val socksConfig = inbounds.getJSONObject(socksIdx)
            socksConfig.getInt("port").let { setSocksPort(it) }

            val socksSettings = socksConfig.optJSONObject("settings")
            val accounts = socksSettings?.optJSONArray("accounts")
            if (accounts != null && accounts.length() > 0) {
                val account = accounts.getJSONObject(0)
                setSocksUser(account.optString("user"))
                setSocksPass(account.optString("pass"))
            }

            if (config.optBoolean("oxrayOpenVpnUnderlay", false)) {
                configAppSplitTunneling(config)
                excludeSelfFromOxrayVpn(config)
                Log.i(TAG, "OXray OpenVPN underlay uses full-tunnel routing with app split tunneling")
            } else {
                configSplitTunneling(config)
                configAppSplitTunneling(config)
            }
        }
    }

    private fun resolveDnsServers(config: JSONObject): List<String> {
        val dnsServers = linkedSetOf<String>()

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

    private fun start(config: XrayConfig, configJson: String, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        buildVpnInterface(config, vpnBuilder)

        if (!shouldProtectDialerSockets()) {
            Log.i(TAG, "Xray dialer sockets are left unprotected for chained upstream routing")
        }
        val controller = DialerController { socket ->
            if (shouldProtectDialerSockets()) {
                protect(socket.toInt())
            } else {
                true
            }
        }
        controller.also {
            LibXray.registerDialerController(it).isNotNullOrBlank { err ->
                throw VpnStartException("Failed to register dialer controller: $err")
            }
            LibXray.registerListenerController(it).isNotNullOrBlank { err ->
                throw VpnStartException("Failed to register listener controller: $err")
            }
        }

        vpnBuilder.establish().use { tunFd ->
            if (tunFd == null) {
                throw VpnStartException("Create VPN interface: permission not granted or revoked")
            }
            val assetsPath = context.getDir("assets", Context.MODE_PRIVATE).absolutePath
            LibXray.initXray(assetsPath)
            val geoDir = File(assetsPath, "geo").absolutePath
            val configPath = File(context.cacheDir, "config.json")
            Log.v(TAG, "xray.location.asset: $geoDir")
            Log.v(TAG, "config: $configPath")
            var xrayStarted = false
            try {
                configPath.writeText(configJson)
            } catch (e: IOException) {
                throw VpnStartException("Failed to write xray config: ${e.message}")
            }

            try {
                Log.d(TAG, "Run XRay")
                Log.i(TAG, "xray ${LibXray.xrayVersion()}")
                LibXray.runXray(geoDir, configPath.absolutePath, config.maxMemory).isNotNullOrBlank { err ->
                    throw VpnStartException("Failed to start xray: $err")
                }
                xrayStarted = true

                waitForLocalSocksReady(config.socksPort)
                Log.i(TAG, "Xray SOCKS inbound is ready on 127.0.0.1:${config.socksPort}")

                Log.d(TAG, "Run tun2Socks")
                runTun2Socks(config, tunFd.detachFd())
            } catch (e: Exception) {
                if (xrayStarted) {
                    LibXray.stopXray().isNotNullOrBlank { err ->
                        Log.w(TAG, "Failed to stop Xray after startup error: $err")
                    }
                }
                throw e
            }
        }
    }

    override fun stopVpn() {
        LibXray.stopXray().isNotNullOrBlank { err ->
            Log.e(TAG, "Failed to stop XRay: $err")
        }
        LibXray.stopTun2Socks().isNotNullOrBlank { err ->
            Log.e(TAG, "Failed to stop tun2Socks: $err")
        }

        isRunning = false
        state.value = DISCONNECTED
    }

    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        state.value = CONNECTED
    }

    protected open fun shouldProtectDialerSockets(): Boolean = true

    private fun XrayConfig.Builder.excludeSelfFromOxrayVpn(config: JSONObject) {
        if (config.optInt("appSplitTunnelType") == APP_SPLIT_TUNNEL_INCLUDE) {
            Log.i(TAG, "OXray OpenVPN underlay keeps app include split-tunnel policy")
            return
        }

        excludeApplication(context.packageName)
        Log.i(TAG, "OXray OpenVPN underlay excludes own package from Android VPN: ${context.packageName}")
    }

    private fun runTun2Socks(config: XrayConfig, fd: Int) {
        val proxyUrl = "socks5://${config.socksUser}:${config.socksPass}@127.0.0.1:${config.socksPort}"
        val tun2SocksConfig = Tun2SocksConfig().apply {
            mtu = config.mtu.toLong()
            proxy = proxyUrl
            device = "fd://$fd"
            logLevel = "warn"
        }
        LibXray.startTun2Socks(tun2SocksConfig, fd.toLong()).isNotNullOrBlank { err ->
            throw VpnStartException("Failed to start tun2socks: $err")
        }
    }

    // Ensures SOCKS5 auth is present on the socks inbound settings.
    // Re-uses existing credentials if already configured; otherwise generates random ones.
    private fun ensureInboundAuth(xrayConfig: JSONObject) {
        val inbounds = xrayConfig.optJSONArray("inbounds") ?: return
        val socksIdx = findSocksInboundIndex(inbounds)
        if (socksIdx < 0) return

        val inbound = inbounds.getJSONObject(socksIdx)
        inbound.put("port", acquireFreeLocalPort())
        val settings = inbound.optJSONObject("settings") ?: JSONObject().also { inbound.put("settings", it) }
        settings.put("udp", true)
        val accounts = settings.optJSONArray("accounts")
        if (accounts != null && accounts.length() > 0) {
            val account = accounts.getJSONObject(0)
            if (account.optString("user").isNotEmpty() && account.optString("pass").isNotEmpty()) {
                // Ensure auth mode is enforced even for imported configs that had accounts
                // but auth: "noauth" (or no auth field).
                settings.put("auth", "password")
                inbound.put("settings", settings)
                inbounds.put(socksIdx, inbound)
                return
            }
        }

        val user = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val pass = UUID.randomUUID().toString().replace("-", "")
        settings.put("auth", "password")
        settings.put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", pass)))
        inbound.put("settings", settings)
        inbounds.put(socksIdx, inbound)
    }

    companion object {
        val instance: Xray by lazy { Xray() }
    }
}

private fun String?.isNotNullOrBlank(block: (String) -> Unit) {
    if (!this.isNullOrBlank()) {
        block(this)
    }
}
