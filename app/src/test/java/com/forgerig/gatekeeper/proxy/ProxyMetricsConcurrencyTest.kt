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
    fun replayedUsageBlockDoesNotAddTokens() {
        ProxyMetrics.resetTallies()
        val id = "resume-1"
        ProxyMetrics.recordRequestStart("s", id, "https://api.openai.com/v1/chat/completions", "POST")
        val t = System.currentTimeMillis()
        ProxyMetrics.sampleOutputSpread(id, 500, t)
        val afterFirst = ProxyMetrics.rateHistory(60, t).sum()
        // Same block again (client resumed the session).
        ProxyMetrics.sampleOutputSpread(id, 500, t)
        assertEquals(afterFirst, ProxyMetrics.rateHistory(60, t).sum())
        // A cumulative provider reporting 900 total credits only the new 400.
        ProxyMetrics.sampleOutputSpread(id, 900, t)
        assertEquals(afterFirst + 400, ProxyMetrics.rateHistory(60, t).sum())
    }
}
