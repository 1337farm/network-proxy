package com.forgerig.gatekeeper.proxy

/**
 * Notification body composition. Pure so the exact strings are unit-tested
 * instead of eyeballed on a device (the previous version shipped a
 * three-line wall of text that nobody wanted to read).
 */
object NotificationText {
    data class Body(
        /** What the collapsed shade row shows: one address, or +N more. */
        val collapsed: String,
        /** What the expanded BigText shows: every address, one per line. */
        val expanded: String,
    )

    /**
     * Running-proxy body. The collapsed row is the LAN address plus the
     * live tok/s (the two things you actually need); everything else —
     * the other interfaces and the uptime — is in the expanded text.
     */
    fun running(ips: List<String>, port: Int, tps: Double = 0.0, uptime: String? = null): Body {
        val rate = "${tpsLabel(tps)} tok/s"
        if (ips.isEmpty()) {
            val t = "listening on 0.0.0.0:$port · $rate"
            return Body(t, listOf(t, uptimeLine(uptime)).filter { it.isNotEmpty() }.joinToString("\n"))
        }
        val head = LocalIps.primary(ips) ?: ips.first()
        val collapsed = buildString {
            append(head).append(':').append(port)
            append(" · ").append(rate)
            if (ips.size > 1) append("  +").append(ips.size - 1).append(" more")
        }
        val expanded = buildString {
            append(ips.joinToString("\n") { "$it:$port" })
            append("\n").append(uptimeLine(uptime))
            if (uptime != null) append(" · ").append(rate)
        }
        return Body(collapsed, expanded)
    }

    /** Stopped / error body: just the caller's message. */
    fun plain(message: String): Body = Body(message, message)

    private fun uptimeLine(uptime: String?): String =
        if (uptime.isNullOrBlank()) "" else "up $uptime"

    private fun tpsLabel(tps: Double): String = when {
        tps <= 0.0 -> "0.0"
        tps < 10 -> String.format(java.util.Locale.US, "%.1f", tps)
        else -> String.format(java.util.Locale.US, "%.0f", tps)
    }
}
