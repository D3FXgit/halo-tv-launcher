package app.halo.launcher.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** Finding the TV's address in the home network, for the small pages a phone opens via QR code. */
internal object Lan {
    /**
     * The TV's private IPv4 address on Ethernet or Wi-Fi. Never a VPN tunnel (a VPN app on the TV is
     * often the "active" network, and a phone can't reach its address) and never a public address.
     */
    @Suppress("DEPRECATION") // allNetworks: the replacement (NetworkCallback) is asynchronous
    fun address(context: Context): Inet4Address? {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
            cm.allNetworks.mapNotNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val rank = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                    else -> return@mapNotNull null
                }
                val ip = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress }
                    ?: return@mapNotNull null
                rank to ip
            }.minByOrNull { it.first }?.second
        }.getOrNull()?.let { return it }
        // Fallback: the Ethernet / Wi-Fi interfaces by name (tun*, ppp*, rmnet* … are left out).
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && (it.name.startsWith("eth") || it.name.startsWith("wlan")) }
                .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
        }.getOrNull()
    }

    /** True while a VPN app is on: it may block phones in the home network from reaching the TV. */
    @Suppress("DEPRECATION")
    fun vpnActive(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)
}
