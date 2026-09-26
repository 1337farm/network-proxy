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
    fun spreadPlanIsEvenRemainderNewestEnd() {
        // the user's case: 2k tokens after a 40s think → ~49/s, not a spike
        val plan = ProxyMetrics.spreadPlan(2000, 41)
        assertEquals(41, plan.size)
        assertEquals(2000L, plan.sum())
        assertEquals(32, plan.count { it == 49L })
        assertEquals(9, plan.count { it == 48L })
        assertEquals(listOf(3L, 3L, 4L), ProxyMetrics.spreadPlan(10, 3))
        assertEquals(listOf(7L), ProxyMetrics.spreadPlan(7, 0))
        assertEquals(emptyList<Long>(), ProxyMetrics.spreadPlan(0, 10))
        assertEquals(ProxyMetrics.RATE_HISTORY_SECS, ProxyMetrics.spreadPlan(600, 9999).size)
    }

    @Test
    fun recordUsageCreditsTallyAndSamplerTogether() {
        ProxyMetrics.resetTallies()
        // Unknown request → unknown-span fallback, which now spreads the
        // total over MIN_SPAN_SECS instead of dumping it into one second
        // (a whole non-streaming response used to read as N tok/s).
        ProxyMetrics.recordUsage("h", "", "nope-missing", longArrayOf(100, 7, 0, 0))
        assertEquals(100L, ProxyMetrics.inputTokens)
        assertEquals(7L, ProxyMetrics.outputTokens)
        assertEquals(7L, ProxyMetrics.rateHistory(ProxyMetrics.MIN_SPAN_SECS).sum())
        // null request → tally only, sampler untouched
        ProxyMetrics.recordUsage("h", "", null, longArrayOf(10, 5, 0, 0))
        assertEquals(110L, ProxyMetrics.inputTokens)
        assertEquals(12L, ProxyMetrics.outputTokens)
        assertEquals(7L, ProxyMetrics.rateHistory(ProxyMetrics.MIN_SPAN_SECS).sum())
    }

    @Test
    fun humanAgeBuckets() {
        val now = 1_700_000_000_000L
        assertEquals("0s", StatsFormat.humanAge(now, now))
        assertEquals("45s", StatsFormat.humanAge(now - 45_000, now))
        assertEquals("3m", StatsFormat.humanAge(now - 200_000, now))
        assertEquals("2h", StatsFormat.humanAge(now - 7_500_000, now))
        assertEquals("5d", StatsFormat.humanAge(now - 500_000_000, now))
        assertEquals("0s", StatsFormat.humanAge(now + 10_000, now))
    }

    @Test
    fun samplerRetainsAnHour() {
        ProxyMetrics.resetTallies()
        val t0 = 1_700_000_000_000L
        ProxyMetrics.sampleOutput(5, t0)
        ProxyMetrics.sampleOutput(9, t0 + 3_599_000L) // ~1h later
        assertEquals(9L, ProxyMetrics.rateHistory(60, t0 + 3_599_000L).sum())
        val hour = ProxyMetrics.rateHistory(3600, t0 + 3_599_000L)
        assertEquals(3600, hour.size)
        assertEquals(14L, hour.sum())
        ProxyMetrics.resetTallies()
    }

    @Test
    fun samplerEvictsPastOneHour() {
        ProxyMetrics.resetTallies()
        val t0 = 1_700_000_000_000L
        // 3601 distinct seconds with distinct values: oldest must fall off.
        for (i in 0..3600) {
            ProxyMetrics.sampleOutput((i + 1).toLong(), t0 + i * 1000L)
        }
        val hour = ProxyMetrics.rateHistory(3600, t0 + 3600_000L)
        assertEquals(3600, hour.size)
        assertEquals(2L, hour[0]) // value 1 (at t0) evicted
        assertEquals(3601L, hour[3599])
        ProxyMetrics.resetTallies()
    }

    @Test
    fun sniffModelFindsTopLevelId() {
        assertEquals(
            "claude-opus-5",
            ProxyMetrics.sniffModel("""{"model":"claude-opus-5","messages":[]}""".toByteArray())
        )
        assertEquals(
            "openrouter/anthropic/claude-opus-5:free",
            ProxyMetrics.sniffModel("{\"model\" : \"openrouter/anthropic/claude-opus-5:free\"}".toByteArray())
        )
        assertEquals("", ProxyMetrics.sniffModel("""{"foo":1}""".toByteArray()))
        assertEquals("", ProxyMetrics.sniffModel(ByteArray(0)))
    }

    @Test
    fun talliesSplitByHostAndModel() {
        ProxyMetrics.resetTallies()
        ProxyMetrics.addTokens(100, 10, 50, 0, "openrouter.ai", "model-a")
        ProxyMetrics.addTokens(200, 20, 0, 0, "openrouter.ai", "model-b")
        ProxyMetrics.addTokens(300, 30, 0, 0, "openrouter.ai", "")
        val rows = ProxyMetrics.tokenSummary(5)
        assertEquals(3, rows.size)
        val byModel = rows.associateBy { it.model }
        assertEquals(100L, byModel["model-a"]!!.inTokens)
        assertEquals(200L, byModel["model-b"]!!.inTokens)
        assertEquals("", byModel[""]!!.model)
        assertEquals("openrouter.ai", byModel[""]!!.host)
        assertEquals(600L, ProxyMetrics.inputTokens)
        ProxyMetrics.resetTallies()
    }

    @Test
    fun sessionTitlesAndEvents() {
        SessionTracker.clearAll()
        val body = """{"model":"m","messages":[
            {"role":"system","content":"sys"},
            {"role":"user","content":[{"type":"text","text":"  Fix   my json please and then some extra words here"}]},
            {"role":"assistant","content":"ok"}]}""".toByteArray()
        assertEquals("Fix my json please and then some extra w…", SessionTracker.titleOf(body))
        assertEquals("", SessionTracker.titleOf(null))
        assertEquals("", SessionTracker.titleOf("""{"x":1}""".toByteArray()))
        SessionTracker.note("s1", "zen", "key-a", "m", "h", 1000, "Hello")
        SessionTracker.noteEvent("s1", "HTTP 429 on 'key-a' → rolling")
        val snap = SessionTracker.snapshot()
        assertEquals("Hello", snap[0].title)
        assertEquals("HTTP 429 on 'key-a' → rolling", snap[0].lastEvent)
        assertTrue(snap[0].lastEventMs > 0)
        SessionTracker.noteEvent("missing", "noop") // no crash
        SessionTracker.clearAll()
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
