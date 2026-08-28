package com.kindleidle.host.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The addresses the Kindle can actually reach, matching lanAddresses() in
 * server/index.js.
 *
 * Interfaces are filtered rather than listed: a phone is usually holding a
 * mobile address and a handful of tunnel interfaces at the same time, and
 * printing those next to the Wi-Fi one would be inviting the user to type an
 * address the Kindle cannot route to.
 */
object Lan {

    fun addresses(): List<String> {
        val out = ArrayList<String>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces() ?: return out
        } catch (e: Exception) {
            return out
        }

        for (nif in interfaces) {
            val usable = try {
                nif.isUp && !nif.isLoopback
            } catch (e: Exception) {
                false
            }
            if (!usable) continue
            if (isMobileOrTunnel(nif.name)) continue

            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                addr.hostAddress?.let { out.add(it) }
            }
        }
        // A Wi-Fi address first: it is the one the Kindle is on.
        return out.sortedBy { if (it.startsWith("192.168.") || it.startsWith("10.")) 0 else 1 }
    }

    /**
     * `rmnet`/`ccmni` are the carrier data interfaces and `tun`/`ppp` are
     * VPNs. Neither carries traffic from a Kindle sitting on the same Wi-Fi.
     */
    private fun isMobileOrTunnel(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
            n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("clat")
    }
}
