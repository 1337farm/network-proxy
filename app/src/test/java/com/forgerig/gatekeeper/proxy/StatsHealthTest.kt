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
    fun cacheRatioAndPct() {
        assertEquals(0.0, StatsFormat.cacheRatio(0, 0), 1e-9)
        assertEquals(0.0, StatsFormat.cacheRatio(0, 100), 1e-9)
        assertEquals(0.0, StatsFormat.cacheRatio(50, 0), 1e-9)
        assertEquals(0.965, StatsFormat.cacheRatio(965, 1000), 1e-9)
        assertEquals(1.0, StatsFormat.cacheRatio(2000, 1000), 1e-9) // clamped
        assertEquals("96.5%", StatsFormat.cachePct(965, 1000))
        assertEquals("0.0%", StatsFormat.cachePct(0, 0))
    }

    @Test
    fun rateHistoryBucketsAndZeroFills() {
        ProxyMetrics.resetTallies()
        val t0 = 1_700_000_000_000L // fixed epoch ms for determinism
        ProxyMetrics.sampleOutput(10, t0)
        ProxyMetrics.sampleOutput(5, t0 + 200) // same second → merged
        ProxyMetrics.sampleOutput(7, t0 + 3_000) // +3s
        val hist = ProxyMetrics.rateHistory(5, t0 + 4_000)
        assertEquals(listOf(15L, 0L, 0L, 7L, 0L), hist)
        // window cap: oldest evicted past RATE_CHART_SECS
        for (i in 0..(ProxyMetrics.RATE_CHART_SECS + 5)) {
            ProxyMetrics.sampleOutput(1, t0 + i * 1000L)
        }
        val capped = ProxyMetrics.rateHistory(ProxyMetrics.RATE_CHART_SECS, t0 + 10_000_000L)
        assertEquals(ProxyMetrics.RATE_CHART_SECS, capped.size)
        ProxyMetrics.resetTallies()
        assertEquals(
            List(ProxyMetrics.RATE_CHART_SECS) { 0L },
            ProxyMetrics.rateHistory()
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
