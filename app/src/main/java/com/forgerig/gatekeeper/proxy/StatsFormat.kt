package com.forgerig.gatekeeper.proxy

import java.util.Locale

/**
 * Compact number rendering for the statistics card's real table widget
 * (TableLayout handles column alignment; these just shorten magnitudes).
 *
 * Pure functions (no Android deps) so rendering stays unit-tested.
 */
object StatsFormat {
    /** Compact token count: 999, 1.5K, 168K, 1.54M, 2.10B. */
    fun humanTokens(n: Long): String {
        if (n <= 0) return "0"
        return when {
            n < 1000 -> n.toString()
            n < 10_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
            n < 1_000_000 -> String.format(Locale.US, "%.0fK", n / 1000.0)
            n < 10_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
            n < 1_000_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
            else -> String.format(Locale.US, "%.2fB", n / 1_000_000_000.0)
        }
    }
}
