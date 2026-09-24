package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-runnable coverage for the stats table + health restart decision. */
class StatsHealthTest {

    @Test
    fun humanTokensCompact() {
        assertEquals("0", StatsFormat.humanTokens(0))
        assertEquals("0", StatsFormat.humanTokens(-5))
        assertEquals("999", StatsFormat.humanTokens(999))
        assertEquals("1.5K", StatsFormat.humanTokens(1539))
        assertEquals("168K", StatsFormat.humanTokens(168366))
        assertEquals("1.54M", StatsFormat.humanTokens(1539293))
        assertEquals("1.27M", StatsFormat.humanTokens(1269685))
        assertEquals("2.10B", StatsFormat.humanTokens(2_100_000_000L))
    }

    @Test
    fun sessionAvgClimbsWithOutput() {
        ProxyMetrics.resetTallies()
        assertEquals(0.0, ProxyMetrics.outputTokensAvg(), 1e-9)
        ProxyMetrics.addTokens(100, 50, 0, 0, "h")
        assertTrue(ProxyMetrics.outputTokensAvg() > 0.0)
        ProxyMetrics.resetTallies()
        assertEquals(0.0, ProxyMetrics.outputTokensAvg(), 1e-9)
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
