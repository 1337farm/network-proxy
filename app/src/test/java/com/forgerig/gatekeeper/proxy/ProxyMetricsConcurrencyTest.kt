package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The scenario/retry tallies are read-modify-write on a ConcurrentHashMap.
 * With the proxy's 32 relay threads retrying at once, a non-atomic
 * get()+put() silently loses increments — these tests pin the atomicity.
 */
class ProxyMetricsConcurrencyTest {
    private fun runConcurrently(threads: Int, perThread: Int, block: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { block(t) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test
    fun scenarioCounterLosesNoIncrements() {
        ProxyMetrics.clear()
        val threads = 16
        val per = 400
        runConcurrently(threads, per) { ProxyMetrics.incrementScenario(NetworkScenario.RETRYABLE_HTTP) }
        val snap = ProxyMetrics.snapshot()
        assertEquals(threads * per, snap.scenarioCounts[NetworkScenario.RETRYABLE_HTTP.name])
    }

    @Test
    fun retryTypeCounterLosesNoIncrements() {
        ProxyMetrics.clear()
        val threads = 16
        val per = 400
        runConcurrently(threads, per) { ProxyMetrics.incrementRetryType(RetryType.HTTP_RETRY) }
        val snap = ProxyMetrics.snapshot()
        assertEquals(threads * per, snap.retryCounts[RetryType.HTTP_RETRY.name])
    }

    @Test
    fun scenarioCounterIsExactUnderMixedScenarios() {
        ProxyMetrics.clear()
        val threads = 8
        val per = 300
        val scenarios = NetworkScenario.entries
        runConcurrently(threads, per) { i -> ProxyMetrics.incrementScenario(scenarios[i % scenarios.size]) }
        val counts = ProxyMetrics.snapshot().scenarioCounts
        assertEquals(threads * per, counts.values.sum())
    }
}

/**
 * A request that dies before any terminal record (exception path in
 * handleClient) must still be closed out exactly once.
 */
/** Chart rows are per (host, model) but must not let one host fill the view. */
class CapPerHostTest {
    private fun row(host: String, model: String) =
        ProxyMetrics.TokenRow(host, model, 100, 10, 0, 0)

    @Test
    fun keepsAtMostNPerHostInOrder() {
        val rows = listOf(
            row("a", "a1"), row("a", "a2"), row("a", "a3"),
            row("b", "b1"), row("a", "a4"), row("c", "c1"), row("b", "b2")
        )
        val out = ProxyMetrics.capPerHost(rows, 2)
        assertEquals(listOf("a1", "a2", "b1", "c1", "b2"), out.map { it.model })
    }

    @Test
    fun zeroCapDropsEverything() {
        assertTrue(ProxyMetrics.capPerHost(listOf(row("a", "m")), 0).isEmpty())
    }

    @Test
    fun generousCapIsATransparentPassThrough() {
        val rows = listOf(row("a", "a1"), row("b", "b1"))
        assertEquals(rows, ProxyMetrics.capPerHost(rows, 5))
    }
}

class RecordRequestEndIfOpenTest {
    @Test
    fun closesAnAbandonedRequestExactlyOnce() {
        ProxyMetrics.clear()
        val id = "abandoned-1"
        ProxyMetrics.recordRequestStart("s1", id, "https://api.openai.com/v1/chat/completions", "POST")
        ProxyMetrics.recordRequestEndIfOpen("s1", id)
        val first = ProxyMetrics.snapshot().requests.filter { it.requestId == id }
        assertEquals(1, first.size)
        assertEquals(0, first[0].statusCode)

        // A second teardown (e.g. both finally blocks) must not double count.
        ProxyMetrics.recordRequestEndIfOpen("s1", id)
        assertEquals(1, ProxyMetrics.snapshot().requests.count { it.requestId == id })
    }

    @Test
    fun doesNotDisturbRequestsThatAlreadyEnded() {
        ProxyMetrics.clear()
        val id = "finished-1"
        ProxyMetrics.recordRequestStart("s2", id, "https://api.openai.com/v1/chat/completions", "POST")
        ProxyMetrics.recordRequestEnd("s2", id, 200, 1234, NetworkScenario.SUCCESS)
        val before = ProxyMetrics.snapshot().requests.first { it.requestId == id }
        ProxyMetrics.recordRequestEndIfOpen("s2", id)
        val after = ProxyMetrics.snapshot().requests.first { it.requestId == id }
        assertEquals(200, after.statusCode)
        assertEquals(before.bytesTransferred, after.bytesTransferred)
    }

    @Test
    fun unknownIdIsANoOp() {
        ProxyMetrics.clear()
        ProxyMetrics.recordRequestEndIfOpen("s3", "never-started")
        assertTrue(ProxyMetrics.snapshot().requests.none { it.requestId == "never-started" })
    }
}

/**
 * tok/s spikes: a non-streaming/gzip response reports its whole completion
 * count at once, which used to land in a single one-second bucket and read
 * as thousands of tok/s.
 */
class RateSpikeTest {
    private fun peakPerSecond(hist: List<Long>): Long = hist.maxOrNull() ?: 0

    @Test
    fun unknownSpanSpreadsInsteadOfDumpingOneSecond() {
        ProxyMetrics.resetTallies()
        val now = System.currentTimeMillis()
        // No requestStartTimes entry -> unknown span, the close-time path.
        ProxyMetrics.sampleOutputUnknownSpan(4000, now)
        val hist = ProxyMetrics.rateHistory(60, now)
        assertTrue("no tokens recorded", hist.sum() > 0)
        // 4000 in one second read as "4000 tok/s"; spread over MIN_SPAN_SECS
        // the peak is a fraction of that while the total is unchanged.
        assertEquals(4000L, hist.sum())
        assertTrue(
            "still spiky: peak ${hist.max()} tok/s",
            hist.max() <= 4000L / ProxyMetrics.MIN_SPAN_SECS && hist.max() < 4000L
        )
        // Spread over a window, not one bucket.
        assertTrue("not spread", hist.count { it > 0 } >= 2)
    }

    @Test
    fun repeatedUnknownSpanReportsDoNotCompound() {
        ProxyMetrics.resetTallies()
        val now = System.currentTimeMillis()
        repeat(5) { ProxyMetrics.sampleOutputUnknownSpan(1000, now) }
        // Five 1000-token reports over the same window stay spread out
        // rather than stacking into a single multi-thousand bucket.
        val hist = ProxyMetrics.rateHistory(60, now)
        assertEquals(5000L, hist.sum())
        assertTrue("compounded into one second: ${peakPerSecond(hist)}", peakPerSecond(hist) <= 2000L)
    }

    @Test
    fun eachReportIsCreditedInFull() {
        // Deliberate change of behaviour: a monotonic per-requestId watermark
        // was removed because requestId is per-connection, so it dropped real
        // output on keep-alive tunnels. Every report is credited verbatim.
        ProxyMetrics.resetTallies()
        val id = "conn-3"
        ProxyMetrics.recordRequestStart("s", id, "https://api.openai.com/v1/chat/completions", "POST")
        val t = System.currentTimeMillis()
        ProxyMetrics.sampleOutputSpread(id, 500, t)
        ProxyMetrics.sampleOutputSpread(id, 200, t)
        assertEquals(700L, ProxyMetrics.rateHistory(3600, t).sum())
    }
}

/**
 * Regressions found in review of the tok/s work.
 *
 * The resume-replay guard compared each response's output against the
 * previous one *for the same requestId* — but requestId is minted per client
 * CONNECTION, so on a keep-alive tunnel the second response looked like a
 * replay and its tokens were dropped (tok/s read 0 while streaming).
 */
class KeepAliveTokenTest {
    @Test
    fun everyResponseOnOneConnectionIsCredited() {
        ProxyMetrics.resetTallies()
        val tunnel = "conn-1"
        ProxyMetrics.recordRequestStart("s", tunnel, "https://api.anthropic.com/v1/messages", "POST")
        val t = System.currentTimeMillis()
        // Three unrelated completions multiplexed over one keep-alive tunnel,
        // each reporting its own output_tokens; the second is LOWER than the
        // first, which a monotonic watermark would have swallowed.
        ProxyMetrics.recordUsage("h", "m", tunnel, longArrayOf(10, 500, 0, 0))
        ProxyMetrics.recordUsage("h", "m", tunnel, longArrayOf(10, 200, 0, 0))
        ProxyMetrics.recordUsage("h", "m", tunnel, longArrayOf(10, 800, 0, 0))
        assertEquals("tally lost tokens", 1500L, ProxyMetrics.outputTokens)
        // Full retention window: a spread can land a few seconds either side
        // of `t`, so a 60s slice is not a safe place to assert the total.
        assertEquals("rate lost tokens", 1500L, ProxyMetrics.rateHistory(3600, t).sum())
    }

    @Test
    fun cacheTokensNeverReachTheRate() {
        ProxyMetrics.resetTallies()
        val id = "conn-2"
        ProxyMetrics.recordRequestStart("s", id, "https://api.anthropic.com/v1/messages", "POST")
        val t = System.currentTimeMillis()
        ProxyMetrics.recordUsage("h", "m", id, longArrayOf(0, 40, 9000, 5000))
        assertEquals(40L, ProxyMetrics.rateHistory(3600, t).sum())
    }
}

/**
 * The rate ring used to be an insertion-ordered deque that only merged at
 * the tail, so spreading a response over its span (older seconds written
 * after newer ones) created duplicate slots and evicted real history.
 */
class RateRingIntegrityTest {
    @Test
    fun outOfOrderSecondsMergeIntoOneBucket() {
        ProxyMetrics.resetTallies()
        val now = System.currentTimeMillis() / 1000
        // Simulate the same second being written before and after a spread of
        // older seconds: the totals must merge, not occupy two slots.
        ProxyMetrics.sampleOutput(10, now * 1000)
        ProxyMetrics.sampleOutputUnknownSpan(20, (now - 5) * 1000)
        ProxyMetrics.sampleOutput(7, now * 1000)
        val hist = ProxyMetrics.rateHistory(60, now * 1000)
        assertEquals("same second split across slots", 17L, hist.last())
        assertEquals(37L, hist.sum())
    }

    @Test
    fun spreadIsCappedSoOneResponseCannotEvictRealHistory() {
        ProxyMetrics.resetTallies()
        val now = System.currentTimeMillis() / 1000
        // A 3-hour "tunnel" span must not append thousands of buckets.
        val plan = ProxyMetrics.spreadPlan(3000, 10_000)
        assertTrue("plan too long: ${plan.size}", plan.size <= ProxyMetrics.MAX_SPREAD_SECS)
        assertEquals(3000L, plan.sum())
    }

    @Test
    fun retentionStillCoversAFullHourUnderSpreads() {
        ProxyMetrics.resetTallies()
        val now = System.currentTimeMillis() / 1000
        // Fill an hour of seconds, then spread another response across the
        // window; the earlier seconds must survive.
        for (s in 0 until 3500) {
            ProxyMetrics.sampleOutput(1, (now - s) * 1000)
        }
        ProxyMetrics.sampleOutputUnknownSpan(500, now * 1000)
        val hist = ProxyMetrics.rateHistory(3600, now * 1000)
        assertTrue("history lost: ${hist.count { it > 0 }} buckets", hist.count { it > 0 } > 3000)
    }
}
