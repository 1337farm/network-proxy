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
     * Usable IPv4 literals, deduped and sorted numerically on all four
     * octets (a string compare would order 10.0.0.10 before 10.0.0.9).
     * Loopback and link-local (169.254.x) are dropped: neither is
     * reachable from another device. Pure w.r.t. [interfaces] so it is
     * unit-tested.
     */
    fun fromInterfaces(interfaces: List<String>): List<String> =
        interfaces.asSequence()
            .map { it.trim() }
            .filter { validIpv4(it) }
            .filterNot { it.startsWith("127.") || it.startsWith("169.254.") }
            .distinct()
            .sortedWith(OCTET_ORDER)
            .toList()

    /**
     * Four octets, or a total-function failure marker.
     *
     * [primary] is public and its comparator must never throw: a malformed
     * literal reaching it from anywhere but [list] would otherwise blow up
     * with NumberFormatException/IndexOutOfBounds on the service's
     * notification tick. Missing or non-numeric parts become [BAD_OCTET],
     * so junk sorts predictably (before every real address) instead of
     * crashing; out-of-range digits (e.g. "300") keep their numeric value —
     * this parses, it does not validate ([validIpv4] does that).
     */
    private fun octets(ip: String): IntArray {
        val parts = ip.split('.')
        return IntArray(4) { i -> parts.getOrNull(i)?.trim()?.toIntOrNull() ?: BAD_OCTET }
    }

    private const val BAD_OCTET = -1

    /**
     * The single address most worth showing: a private home/LAN range
     * first, because that is what another device on the network has to be
     * pointed at (VPN/Tailscale addresses sort lower numerically and are
     * rarely the one you want to type). Pure (unit-tested).
     *
     * Only IPv4 literals are candidates; anything else is ignored, and null
     * comes back when the list holds no usable address. Total — never throws.
     */
    fun primary(ips: List<String>): String? {
        val o = ips.asSequence()
            .map { it.trim() }
            .filter { validIpv4(it) }
            .distinct()
            .sortedWith(OCTET_ORDER)
            .toList()
        if (o.isEmpty()) return null
        return o.firstOrNull { it.startsWith("192.168.") }
            ?: o.firstOrNull { it.startsWith("10.") }
            ?: o.firstOrNull { it.startsWith("172.") && it.split('.').getOrNull(1)?.toIntOrNull() in 16..31 }
            ?: o.first()
    }

    /**
     * Numeric order on all four octets (a string compare would order
     * 10.0.0.10 before 10.0.0.9), with a lexical tie-break so the ordering
     * is total even for two spellings of the same address. Total: never
     * throws on malformed input (see [octets]).
     */
    private val OCTET_ORDER = Comparator<String> { a, b ->
        val x = octets(a)
        val y = octets(b)
        for (i in 0 until 4) {
            val c = x[i].compareTo(y[i])
            if (c != 0) return@Comparator c
        }
        a.compareTo(b)
    }

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
