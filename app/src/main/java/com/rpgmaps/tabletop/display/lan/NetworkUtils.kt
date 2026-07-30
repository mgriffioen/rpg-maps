package com.rpgmaps.tabletop.display.lan

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * IPv4 addresses this device can be reached on from the local network,
     * best candidate first.
     *
     * Interface *names* are the only reliable signal here: `WifiManager` only
     * ever reports the Wi-Fi address, which is wrong when the tablet is on
     * Ethernet through a dock, and link-local 169.254.x addresses show up on
     * virtual interfaces that no TV can route to.
     */
    fun localAddresses(): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                // Skip tunnels and the tethering-side interfaces.
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy")) continue

                val rank = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("rndis") || name.startsWith("usb") -> 2
                    else -> 3
                }

                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val host = addr.hostAddress ?: continue
                    found += rank to host
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    fun primaryAddress(): String? = localAddresses().firstOrNull()
}
