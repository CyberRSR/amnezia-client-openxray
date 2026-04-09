package org.amnezia.vpn.util.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

fun getLocalNetworks(context: Context, ipv6: Boolean): List<InetNetwork> {
    val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    connectivityManager.activeNetwork?.let { network ->
        val netCapabilities = connectivityManager.getNetworkCapabilities(network)
        val linkProperties = connectivityManager.getLinkProperties(network)
        if (linkProperties == null ||
            netCapabilities == null ||
            netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        ) return emptyList()

        val addresses = mutableListOf<InetNetwork>()

        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if ((!ipv6 && address is Inet4Address) || (ipv6 && address is Inet6Address)) {
                addresses += InetNetwork(address, linkAddress.prefixLength)
            }
        }
        return addresses
    }
    return emptyList()
}

fun describeActiveNetwork(context: Context): String {
    val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    val activeNetwork = connectivityManager.activeNetwork ?: return "activeNetwork=null"
    val netCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
    val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
    val transports = buildList {
        if (netCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
        if (netCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
        if (netCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
        if (netCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        if (netCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("BLUETOOTH")
    }.ifEmpty { listOf("UNKNOWN") }
    val capabilities = buildList {
        if (netCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) add("INTERNET")
        if (netCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) add("VALIDATED")
        if (netCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) add("NOT_METERED")
        if (netCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == true) add("NOT_ROAMING")
    }.ifEmpty { listOf("NONE") }
    val linkAddresses = linkProperties?.linkAddresses
        ?.joinToString(prefix = "[", postfix = "]") { "${it.address.ip}/${it.prefixLength}" }
        ?: "[]"
    val dnsServers = linkProperties?.dnsServers
        ?.joinToString(prefix = "[", postfix = "]") { it.ip }
        ?: "[]"
    val routes = linkProperties?.routes
        ?.joinToString(prefix = "[", postfix = "]") { route ->
            val destination = route.destination?.toString() ?: "default"
            val gateway = route.gateway?.ip ?: "-"
            val iface = route.`interface` ?: "-"
            "$destination->$gateway@$iface"
        }
        ?: "[]"
    val interfaceName = linkProperties?.interfaceName ?: "?"

    return "activeNetwork=$activeNetwork transports=${transports.joinToString("+")} " +
        "capabilities=${capabilities.joinToString(",")} iface=$interfaceName " +
        "dns=$dnsServers addrs=$linkAddresses routes=$routes"
}

fun activeTransportKey(context: Context): String {
    val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    val activeNetwork = connectivityManager.activeNetwork ?: return "UNKNOWN"
    val netCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return "UNKNOWN"

    return when {
        netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
        netCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "UNKNOWN"
    }
}

fun parseInetAddress(address: String): InetAddress = InetAddress.getByName(address)

private val parseNumericAddressCompat: (String) -> InetAddress =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        InetAddresses::parseNumericAddress
    } else {
        try {
            val m = InetAddress::class.java.getMethod("parseNumericAddress", String::class.java)
            fun(address: String): InetAddress {
                try {
                    return m.invoke(null, address) as InetAddress
                } catch (e: InvocationTargetException) {
                    throw e.cause ?: e
                }
            }
        } catch (_: NoSuchMethodException) {
            fun(address: String): InetAddress {
                return InetAddress.getByName(address)
            }
        }
    }

internal fun convertIpv6ToCanonicalForm(ipv6: String): String = ipv6
    .replace("((?:(?:^|:)0+\\b){2,}):?(?!\\S*\\b\\1:0+\\b)(\\S*)".toRegex(), "::$2")

val InetAddress.ip: String
    get() = if (this is Inet4Address) {
        hostAddress!!
    } else {
        convertIpv6ToCanonicalForm(hostAddress!!)
    }
