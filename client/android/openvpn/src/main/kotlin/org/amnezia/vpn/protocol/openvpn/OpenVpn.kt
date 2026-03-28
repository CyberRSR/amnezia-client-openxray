package org.amnezia.vpn.protocol.openvpn

import android.net.VpnService.Builder
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.openvpn.ovpn3.ClientAPI_Config
import org.amnezia.vpn.protocol.BadConfigException
import org.amnezia.vpn.protocol.Protocol
import org.amnezia.vpn.protocol.ProtocolState.DISCONNECTED
import org.amnezia.vpn.protocol.Statistics
import org.amnezia.vpn.protocol.VpnStartException
import org.amnezia.vpn.util.LibraryLoader.loadSharedLibrary
import org.amnezia.vpn.util.Log
import org.amnezia.vpn.util.net.InetNetwork
import org.amnezia.vpn.util.net.getLocalNetworks
import org.amnezia.vpn.util.net.parseInetAddress
import org.json.JSONObject

private const val TAG = "OpenVpn"
private const val LEGACY_TLS_CERT_PROFILE = "legacy-default"
private const val LEGACY_COMPRESSION_MODE = "yes"
private const val LEGACY_MIN_RSA_KEY_SIZE = 2048
private val INLINE_STATIC_KEY_TAGS = listOf("tls-auth", "tls-crypt", "tls-crypt-v2", "secret")
private val LEGACY_COMPRESSION_REGEX = Regex(
    "^\\s*comp-lzo\\b",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
private val LEGACY_CBC_CIPHER_REGEX = Regex(
    "^\\s*cipher\\s+.*CBC\\b",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
private val DATA_CIPHERS_REGEX = Regex(
    "^\\s*data-ciphers\\b",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
)
private val INLINE_CERTIFICATE_REGEX = Regex(
    "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

open class OpenVpn : Protocol() {

    protected var openVpnClient: OpenVpnClient? = null
    protected lateinit var scope: CoroutineScope

    open val tunnelServerAddress: String?
        get() = openVpnClient?.tunnelServerAddress

    open val tunnelLocalAddress: String?
        get() = openVpnClient?.tunnelLocalAddress

    open val tunnelDnsServers: List<String>
        get() = openVpnClient?.tunnelDnsServers.orEmpty()

    override val statistics: Statistics
        get() {
            openVpnClient?.let { client ->
                val stats = client.transport_stats()
                return Statistics.build {
                    setRxBytes(stats.bytesIn)
                    setTxBytes(stats.bytesOut)
                }
            }
            return Statistics.EMPTY_STATISTICS
        }

    override fun internalInit() {
        if (!isInitialized) {
            loadSharedLibrary(context, "ovpn3")
            loadSharedLibrary(context, "ovpnutil")
        }
        if (this::scope.isInitialized) {
            scope.cancel()
        }
        scope = CoroutineScope(Dispatchers.IO)
    }

    override suspend fun startVpn(config: JSONObject, vpnBuilder: Builder, protect: (Int) -> Boolean) {
        val configBuilder = createConfigBuilder(config)

        openVpnClient = createOpenVpnClient(configBuilder, vpnBuilder, protect)

        try {
            openVpnClient?.let { client ->
                val openVpnConfig = parseConfig(config)
                val evalConfig = client.eval_config(openVpnConfig)
                if (evalConfig.error) {
                    throw BadConfigException("OpenVPN config parse error: ${evalConfig.message}")
                }

                configureOpenVpn(configBuilder, config)

                scope.launch {
                    val status = client.connect()
                    if (status.error) {
                        state.value = DISCONNECTED
                        onError("OpenVpn connect() error: ${status.status}: ${status.message}")
                    }
                }
            }
        } catch (e: Exception) {
            openVpnClient = null
            throw e
        }
    }

    override fun stopVpn() {
        openVpnClient?.stop()
        openVpnClient = null
    }

    override fun reconnectVpn(vpnBuilder: Builder, protect: (Int) -> Boolean) {
        openVpnClient?.let {
            it.establish = makeEstablish(vpnBuilder)
            it.reconnect(0)
        }
    }

    protected open fun createConfigBuilder(config: JSONObject): OpenVpnConfig.Builder = OpenVpnConfig.Builder()

    protected open fun createOpenVpnClient(
        configBuilder: OpenVpnConfig.Builder,
        vpnBuilder: Builder,
        protect: (Int) -> Boolean
    ): OpenVpnClient = OpenVpnClient(
        configBuilder = configBuilder,
        state = state,
        getLocalNetworks = { ipv6 -> getLocalNetworks(context, ipv6) },
        establish = makeEstablish(vpnBuilder),
        protect = protect,
        onError = onError
    )

    protected open fun configureOpenVpn(configBuilder: OpenVpnConfig.Builder, config: JSONObject) {
        // Desktop OpenVPN can work with only the raw .ovpn file, so keep Android tolerant
        // to imported profiles where metadata may be incomplete.
        config.optString("hostName")
            .takeIf { it.isNotBlank() }
            ?.let { remoteServer ->
                val remoteServerAddress = InetNetwork(parseInetAddress(remoteServer))
                configBuilder.excludeRoute(remoteServerAddress)
            }

        configPluggableTransport(configBuilder, config)
        configBuilder.configSplitTunneling(config)
        configBuilder.configAppSplitTunneling(config)
    }

    protected open fun parseConfig(config: JSONObject): ClientAPI_Config {
        val openVpnConfig = ClientAPI_Config()
        val rawConfig = config.getJSONObject("openvpn_config_data").getString("config")
        val transformedRawConfig = transformRawConfig(config, rawConfig)
        val sanitizedConfig = sanitizeConfig(transformedRawConfig)
        val compatibilityOverrides = detectCompatibilityOverrides(sanitizedConfig)
        if (sanitizedConfig != transformedRawConfig) {
            Log.i(TAG, "Sanitized OpenVPN inline config before eval_config")
        }
        openVpnConfig.content = sanitizedConfig
        compatibilityOverrides.compressionMode?.let {
            Log.w(TAG, "Enabling legacy OpenVPN compression mode for imported profile")
            openVpnConfig.compressionMode = it
        }
        compatibilityOverrides.tlsCertProfileOverride?.let {
            Log.w(TAG, "Enabling legacy TLS certificate profile for imported profile")
            openVpnConfig.tlsCertProfileOverride = it
        }
        if (compatibilityOverrides.enableLegacyAlgorithms) {
            Log.w(TAG, "Enabling legacy OpenVPN algorithms for imported profile")
            openVpnConfig.enableLegacyAlgorithms = true
        }
        if (compatibilityOverrides.enableNonPreferredDcAlgorithms) {
            Log.w(TAG, "Enabling non-preferred data channel algorithms for imported profile")
            openVpnConfig.enableNonPreferredDCAlgorithms = true
        }
        return openVpnConfig
    }

    protected open fun transformRawConfig(config: JSONObject, rawConfig: String): String = rawConfig

    private fun detectCompatibilityOverrides(config: String): CompatibilityOverrides {
        val hasLegacyCompression = LEGACY_COMPRESSION_REGEX.containsMatchIn(config)
        val hasLegacyTlsProfile = hasWeakInlineRsaCertificate(config)
        val hasLegacyCipher = LEGACY_CBC_CIPHER_REGEX.containsMatchIn(config) && !DATA_CIPHERS_REGEX.containsMatchIn(config)

        return CompatibilityOverrides(
            compressionMode = if (hasLegacyCompression) LEGACY_COMPRESSION_MODE else null,
            tlsCertProfileOverride = if (hasLegacyTlsProfile) LEGACY_TLS_CERT_PROFILE else null,
            enableLegacyAlgorithms = hasLegacyTlsProfile,
            enableNonPreferredDcAlgorithms = hasLegacyCipher
        )
    }

    private fun hasWeakInlineRsaCertificate(config: String): Boolean {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        return INLINE_CERTIFICATE_REGEX.findAll(config).any { match ->
            val pem = "-----BEGIN CERTIFICATE-----${match.groupValues[1]}-----END CERTIFICATE-----"
            try {
                val certificate = certificateFactory.generateCertificate(
                    ByteArrayInputStream(pem.toByteArray())
                ) as? X509Certificate ?: return@any false
                val publicKey = certificate.publicKey as? RSAPublicKey ?: return@any false
                publicKey.modulus.bitLength() < LEGACY_MIN_RSA_KEY_SIZE
            } catch (e: Exception) {
                Log.w(TAG, "Failed to inspect inline certificate: ${e.message}")
                false
            }
        }
    }

    private fun sanitizeConfig(config: String): String {
        var normalizedConfig = config
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        for (tag in INLINE_STATIC_KEY_TAGS) {
            normalizedConfig = sanitizeInlineStaticKeyBlock(normalizedConfig, tag)
        }

        return normalizedConfig
    }

    private fun sanitizeInlineStaticKeyBlock(config: String, tag: String): String {
        val pattern = Regex(
            "<$tag>\\s*(.*?)\\s*</$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        return pattern.replace(config) { match ->
            val sanitizedBody = match.groupValues[1]
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .joinToString("\n")

            if (sanitizedBody.isEmpty()) {
                match.value
            } else {
                "<$tag>\n$sanitizedBody\n</$tag>"
            }
        }
    }

    private data class CompatibilityOverrides(
        val compressionMode: String? = null,
        val tlsCertProfileOverride: String? = null,
        val enableLegacyAlgorithms: Boolean = false,
        val enableNonPreferredDcAlgorithms: Boolean = false
    )

    protected open fun configPluggableTransport(configBuilder: OpenVpnConfig.Builder, config: JSONObject) {}

    protected fun makeEstablish(vpnBuilder: Builder): (OpenVpnConfig.Builder) -> Int = { configBuilder ->
        val openVpnConfig = configBuilder.build()
        buildVpnInterface(openVpnConfig, vpnBuilder)

        vpnBuilder.establish().use { tunFd ->
            if (tunFd == null) {
                throw VpnStartException("Create VPN interface: permission not granted or revoked")
            }
            return@use tunFd.detachFd()
        }
    }
}
