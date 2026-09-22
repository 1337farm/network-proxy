package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-runnable coverage for the stats table + health restart decision. */
class StatsHealthTest {

    @Test
    fun humanTokensFixedWidth() {
        assertEquals("     0", StatsFormat.humanTokens(0))
        assertEquals("   999", StatsFormat.humanTokens(999))
        assertEquals("  1.5K", StatsFormat.humanTokens(1539))
        assertEquals("  168K", StatsFormat.humanTokens(168366))
        assertEquals(" 1.54M", StatsFormat.humanTokens(1539293))
        assertEquals(" 1.27M", StatsFormat.humanTokens(1269685))
        // every cell exactly 6 wide
        for (n in listOf(0L, 5, 999, 1000, 15393, 168366, 1539293, 99999999L)) {
            assertEquals("width for $n", 6, StatsFormat.humanTokens(n).length)
        }
    }

    @Test
    fun tokenTableColumnsAlign() {
        val rows = listOf(
            ProxyMetrics.TokenRow("opencode.ai", 1539293, 2033, 1269685, 0),
            ProxyMetrics.TokenRow("openrouter.ai", 168366, 1902, 33792, 0)
        )
        val out = StatsFormat.tokenTable(
            rows, StatsFormat.Totals(1707659, 3935, 1303477, 0), 11.5
        )
        val lines = out.lines()
        assertEquals(5, lines.size) // header + 2 rows + TOTAL + tok/s line
        // all table rows same visual width (tok/s stands alone, never wraps TOTAL)
        val widths = lines.take(4).map { it.length }.toSet()
        assertEquals("columns drifted: $lines", 1, widths.size)
        assertTrue(lines[3].startsWith("TOTAL"))
        assertEquals("@ 11.5 tok/s", lines[4])
        assertTrue(lines[1].contains("1.54M"))
    }

    @Test
    fun emptyTableKeepsLegacyLine() {
        assertEquals(
            "Tokens: in 0 / out 0 @ 0.0 tok/s",
            StatsFormat.tokenTable(emptyList(), StatsFormat.Totals(0, 0, 0, 0), 0.0)
        )
    }

    @Test
    fun healthRestartDecision() {
        assertFalse(ProxyService.healthNeedsRestart(0))
        assertFalse(ProxyService.healthNeedsRestart(2))
        assertTrue(ProxyService.healthNeedsRestart(3))
        assertTrue(ProxyService.healthNeedsRestart(9))
        assertTrue(ProxyService.healthNeedsRestart(1, maxFails = 1))
    }
}
