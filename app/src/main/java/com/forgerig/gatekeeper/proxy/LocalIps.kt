package com.forgerig.gatekeeper.proxy

import java.net.NetworkInterface
import java.util.Collections

/**
 * Local IPv4 addresses for the notification body — the whole point of the
 * "proxy is running" notification is knowing which host:port to point
 * clients at, so we list every routable address, not 0.0.0.0.
 */
object LocalIps {
    /**
     * Usable IPv4 literals, deduped and sorted numerically. Loopback and
     * link-local (169.254.x) are dropped: neither is reachable from another
     * device. Pure w.r.t. [interfaces] so it is unit-tested.
     */
    fun fromInterfaces(interfaces: List<String>): List<String> =
        interfaces.asSequence()
            .map { it.trim() }
            .filter { validIpv4(it) }
            .filterNot { it.startsWith("127.") || it.startsWith("169.254.") }
            .distinct()
            .sortedWith(compareBy({ it.split('.').first().toInt() }, { it }))
            .toList()

    private fun validIpv4(s: String): Boolean {
        val m = IPV4.matchEntire(s) ?: return false
        return (1..4).all { i -> m.groupValues[i].toInt() in 0..255 }
    }

    /** Enumerate host IPv4 literals via [NetworkInterface]. */
    fun list(): List<String> = fromInterfaces(
        try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .flatMap { ni ->
                    Collections.list(ni.inetAddresses).asSequence()
                        .mapNotNull { it.hostAddress }
                }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    )

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
}
