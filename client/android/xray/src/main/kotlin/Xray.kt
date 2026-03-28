package org.amnezia.vpn.protocol.xray

import android.content.Context
import android.net.VpnService.Builder
import java.io.File
import java.io.IOException
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
import org.json.JSONObject

private const val TAG = "Xray"
private const val LIBXRAY_TAG = "libXray"

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
            config.getString("hostName").let { hostName ->
                excludeRoute(InetNetwork(parseInetAddress(hostName)))
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

            val socksConfig = xrayJsonConfig.getJSONArray("inbounds")[0] as JSONObject
            socksConfig.getInt("port").let { setSocksPort(it) }

            configSplitTunneling(config)
            configAppSplitTunneling(config)
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
            Log.d(TAG, "Run tun2Socks")
            runTun2Socks(config, tunFd.detachFd())

            Log.d(TAG, "Run XRay")
            Log.i(TAG, "xray ${LibXray.xrayVersion()}")
            val assetsPath = context.getDir("assets", Context.MODE_PRIVATE).absolutePath
            LibXray.initXray(assetsPath)
            val geoDir = File(assetsPath, "geo").absolutePath
            val configPath = File(context.cacheDir, "config.json")
            Log.v(TAG, "xray.location.asset: $geoDir")
            Log.v(TAG, "config: $configPath")
            try {
                configPath.writeText(configJson)
            } catch (e: IOException) {
                LibXray.stopTun2Socks()
                throw VpnStartException("Failed to write xray config: ${e.message}")
            }
            LibXray.runXray(geoDir, configPath.absolutePath, config.maxMemory).isNotNullOrBlank { err ->
                LibXray.stopTun2Socks()
                throw VpnStartException("Failed to start xray: $err")
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

    private fun runTun2Socks(config: XrayConfig, fd: Int) {
        val tun2SocksConfig = Tun2SocksConfig().apply {
            mtu = config.mtu.toLong()
            proxy = "socks5://127.0.0.1:${config.socksPort}"
            device = "fd://$fd"
            logLevel = "warn"
        }
        LibXray.startTun2Socks(tun2SocksConfig, fd.toLong()).isNotNullOrBlank { err ->
            throw VpnStartException("Failed to start tun2socks: $err")
        }
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
