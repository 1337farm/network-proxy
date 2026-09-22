package com.forgerig.gatekeeper.proxy

import java.util.Locale

/**
 * Production rendering for the statistics card (monospace views).
 *
 * Pure functions (no Android deps) so the layout is unit-tested: columns
 * stay aligned because every numeric cell is humanized to a FIXED width
 * (raw 7-digit counts overflowed %7d and broke the table).
 */
object StatsFormat {
    private const val HOST_W = 13
    private const val NUM_W = 6

    /** Compact token count, always exactly [NUM_W] wide (right-aligned). */
    fun humanTokens(n: Long): String {
        val s = when {
            n < 0 -> "0"
            n < 1000 -> n.toString()
            n < 10_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
            n < 1_000_000 -> String.format(Locale.US, "%.0fK", n / 1000.0)
            n < 10_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
            else -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        }
        return if (s.length >= NUM_W) s.take(NUM_W) else s.padStart(NUM_W)
    }

    data class Totals(val inTokens: Long, val outTokens: Long, val cacheRead: Long, val cacheWrite: Long)

    /**
     * Fixed-width token table. The throughput line stands alone so the
     * TOTAL row never wraps (the old trailing "@ x tok/s" broke alignment).
     */
    fun tokenTable(
        rows: List<ProxyMetrics.TokenRow>,
        totals: Totals,
        tokPerSec: Double
    ): String {
        val tps = String.format(Locale.US, "%.1f", tokPerSec)
        if (rows.isEmpty()) return "Tokens: in 0 / out 0 @ $tps tok/s"
        val sb = StringBuilder()
        sb.append(
            String.format(
                Locale.US, "%-${HOST_W}s %${NUM_W}s %${NUM_W}s %${NUM_W}s %${NUM_W}s",
                "host", "in", "out", "cacheR", "cacheW"
            )
        )
        for (r in rows) {
            sb.append("\n").append(
                String.format(
                    Locale.US, "%-${HOST_W}s %s %s %s %s",
                    r.host.take(HOST_W), humanTokens(r.inTokens),
                    humanTokens(r.outTokens), humanTokens(r.cacheRead),
                    humanTokens(r.cacheWrite)
                )
            )
        }
        sb.append("\n").append(
            String.format(
                Locale.US, "%-${HOST_W}s %s %s %s %s",
                "TOTAL", humanTokens(totals.inTokens),
                humanTokens(totals.outTokens), humanTokens(totals.cacheRead),
                humanTokens(totals.cacheWrite)
            )
        )
        sb.append("\n@ ").append(tps).append(" tok/s")
        return sb.toString()
    }
}
