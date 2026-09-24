package com.forgerig.gatekeeper.proxy

import java.util.Locale

/**
 * Compact number rendering for the statistics card widgets
 * (TableLayout + chart handle layout; these just shorten magnitudes).
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

    /**
     * Cache-read ratio in [0,1]: share of input tokens served from cache.
     * Zero-input → 0 (no bar, not 100%).
     */
    fun cacheRatio(cacheRead: Long, input: Long): Double {
        if (input <= 0 || cacheRead <= 0) return 0.0
        return (cacheRead.toDouble() / input.toDouble()).coerceIn(0.0, 1.0)
    }

    /** "96.5%" style label for [cacheRatio]. */
    fun cachePct(cacheRead: Long, input: Long): String =
        String.format(Locale.US, "%.1f%%", 100.0 * cacheRatio(cacheRead, input))
}
