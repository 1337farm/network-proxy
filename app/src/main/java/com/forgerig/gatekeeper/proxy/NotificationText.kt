package com.forgerig.gatekeeper.proxy

/**
 * Notification body composition. Pure so the exact strings are unit-tested
 * instead of eyeballed on a device (the previous version shipped a
 * three-line wall of text that nobody wanted to read).
 *
 * Contract (status first, addresses second):
 *  - [Body.collapsed] is *only* the status line — uptime and tok/s. It is
 *    the one-line `setContentText` row, so it stays short and never
 *    carries an address.
 *  - [Body.expanded] is that same status line, then one address per line.
 *  - The status line is built once and shared by both strings, so the
 *    collapsed row can never disagree with the expanded body.
 */
object NotificationText {
    data class Body(
        /** What the collapsed shade row shows: the status line only. */
        val collapsed: String,
        /** What the expanded BigText shows: the status line, then one address per line. */
        val expanded: String,
    )

    /**
     * Running-proxy body.
     *
     * The status line is `up <uptime> · <rate>` when an uptime is known and
     * degrades to `<rate>` alone when it is not — no dangling separator, no
     * empty row, ever. [uptime] is caller-formatted and treated as opaque;
     * null/blank means "not known yet" (the service has not ticked yet).
     *
     * The rate formatting is unchanged from before (`tpsLabel`), and the
     * `+N more` affordance is gone from both strings: it only ever existed to
     * compress the address list into the single-line row, and that row is now
     * status-only. The expanded body lists every address anyway, so a count
     * there would be pure redundancy, and leaving it in the row would break
     * the status-only contract.
     */
    fun running(ips: List<String>, port: Int, tps: Double = 0.0, uptime: String? = null): Body {
        val status = statusLine(tps, uptime)
        if (ips.isEmpty()) {
            // No address was enumerated, so the status line is all the
            // collapsed row can honestly show. The bind-address fallback is
            // kept in the *expanded* body only: it still earns its place
            // there, because without it there is no port anywhere in the
            // notification and the user has no way to point a client at the
            // proxy — the one job this notification has. It stays out of the
            // collapsed row, which is status-only by contract.
            return Body(status, "$status\nlistening on 0.0.0.0:$port")
        }
        val addresses = ips.joinToString("\n") { "$it:$port" }
        return Body(status, "$status\n$addresses")
    }

    /** Stopped / error body: just the caller's message. */
    fun plain(message: String): Body = Body(message, message)

    /**
     * The one status line, shared by [collapsed] and [expanded]. Built here
     * so the two strings are byte-identical on this line by construction.
     */
    private fun statusLine(tps: Double, uptime: String?): String {
        val rate = "${tpsLabel(tps)} tok/s"
        return if (uptime.isNullOrBlank()) rate else "up $uptime · $rate"
    }

    private fun tpsLabel(tps: Double): String = when {
        tps <= 0.0 -> "0.0"
        tps < 10 -> String.format(java.util.Locale.US, "%.1f", tps)
        else -> String.format(java.util.Locale.US, "%.0f", tps)
    }
}
