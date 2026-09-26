package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cumulative usage reporting (Anthropic-style SSE re-reports
 * `output_tokens` as a running total in every `message_delta` frame).
 * Crediting each frame verbatim summed 12+40+150 = 202 for a 150-token
 * completion — a ~35% over-report that grew with frame count and read as
 * multi-thousand tok/s. These tests pin the delta behaviour of
 * [ProxyMetrics.StreamingUsage] through the real accounting path
 * ([ProxyMetrics.recordUsage]), so the tally and the rate are checked as the
 * relay actually produces them, and pin the two failure modes that must never
 * come back: a keep-alive tunnel dropping real tokens, and a short
 * completion reading as one huge one-second spike.
 */
class CumulativeUsageTest {

    private val host = "api.anthropic.com"
    private val model = "claude-sonnet-4"

    /** Anthropic message_start: input-side usage is reported exactly once. */
    private fun startFrame(input: Int, cacheRead: Int = 0, cacheWrite: Int = 0): String =
        "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"," +
            "\"usage\":{\"input_tokens\":$input,\"cache_read_input_tokens\":$cacheRead," +
            "\"cache_creation_input_tokens\":$cacheWrite}}}\n\n"

    /** Anthropic message_delta: output_tokens is a RUNNING TOTAL. */
    private fun deltaFrame(runningTotal: Int): String =
        "event: message_delta\ndata: {\"type\":\"message_delta\"," +
            "\"usage\":{\"output_tokens\":$runningTotal}}\n\n"

    /**
     * Drive one scanner the way relayTap does — per-chunk feed, then flush at
     * stream end — crediting every quad it returns through recordUsage (tally
     * AND rate, exactly like the relay). Returns the scanner's own total so a
     * test can assert per-frame deltas as well as the end state.
     */
    private fun drive(id: String, vararg chunks: String): LongArray {
        val u = ProxyMetrics.StreamingUsage()
        val total = LongArray(4)
        fun credit(q: LongArray) {
            for (k in 0..3) total[k] += q[k]
            if (q[0] + q[1] + q[2] + q[3] > 0) {
                ProxyMetrics.recordUsage(host, model, id, q)
            }
        }
        for (c in chunks) {
            val b = c.toByteArray(Charsets.UTF_8)
            credit(u.feed(b, b.size))
        }
        credit(u.flush())
        return total
    }

    private fun rateSum(id: String): Long =
        ProxyMetrics.rateHistory(ProxyMetrics.RATE_HISTORY_SECS, System.currentTimeMillis()).sum()

    private fun newTunnel(id: String) {
        ProxyMetrics.recordRequestStart("s", id, "https://$host/v1/messages", "POST")
    }

    @Test
    fun cumulativeOutputSequenceSumsToTheFinalTotalNotTheSumOfFrames() {
        ProxyMetrics.resetTallies()
        val id = "cumulative-1"
        newTunnel(id)
        val total = drive(
            id,
            startFrame(1200),
            deltaFrame(12),
            deltaFrame(40),
            deltaFrame(150)
        )
        // Per-frame deltas, not the re-reported totals.
        assertEquals("input credited once", 1200L, total[0])
        assertEquals("12 + 28 + 110", 150L, total[1])
        // Tally: exactly the completion, not 202.
        assertEquals(1200L, ProxyMetrics.inputTokens)
        assertEquals("tally over-reported cumulative frames", 150L, ProxyMetrics.outputTokens)
        // Rate: same number. A spread only moves buckets inside the retention
        // window, so the 3600s total must agree with the tally exactly.
        assertEquals("rate over-reported cumulative frames", 150L, rateSum(id))
    }

    @Test
    fun manyFramesOfTheSameCumulativeTotalStillSumToTheTotal() {
        // The over-report compounded with frame count: a long completion with
        // many message_delta frames is the worst case for the old code.
        ProxyMetrics.resetTallies()
        val id = "cumulative-2"
        newTunnel(id)
        val chunks = ArrayList<String>()
        chunks += startFrame(900)
        val runningTotals = listOf(1, 5, 9, 30, 31, 44, 44, 200, 201, 250)
        for (running in runningTotals) chunks += deltaFrame(running)
        val got = drive(id, *chunks.toTypedArray())
        val expected = runningTotals.last().toLong()
        assertEquals(expected, got[1])
        assertEquals(expected, ProxyMetrics.outputTokens)
        assertEquals(expected, rateSum(id))
    }

    @Test
    fun newResponseStartingLowerThanThePreviousIsCreditedInFull() {
        // The regression the per-requestId watermark caused: a keep-alive
        // tunnel carries many responses, and the second one starts its running
        // total from zero — BELOW where the previous response finished. It
        // must be credited in full, never diffed against the old total.
        ProxyMetrics.resetTallies()
        val id = "keepalive-1"
        newTunnel(id)
        val total = drive(
            id,
            // Response 1: 150-token completion.
            startFrame(1200),
            deltaFrame(12),
            deltaFrame(150),
            // Response 2 on the SAME connection: 12, then 40.
            startFrame(1300),
            deltaFrame(12),
            deltaFrame(40)
        )
        assertEquals("both responses credited", 190L, total[1])
        assertEquals(190L, ProxyMetrics.outputTokens)
        assertEquals("real tokens dropped on keep-alive", 190L, rateSum(id))
    }

    @Test
    fun exactlyRepeatedUsageBlockCreditsNothingExtra() {
        // A duplicated frame (or a replayed block) repeats the same value:
        // that must credit 0, not another full count.
        ProxyMetrics.resetTallies()
        val id = "repeat-1"
        newTunnel(id)
        val block = "data: {\"usage\":{\"output_tokens\":40}}\n\n"
        val total = drive(
            id,
            startFrame(1200),
            block,
            block, // duplicate frame: same running total
            deltaFrame(150),
            // Full usage object re-sent at the end of the stream, repeating
            // the final total: a replay credits nothing extra.
            "data: {\"usage\":{\"output_tokens\":150}}\n\n"
        )
        assertEquals("40 + 0 + 110 + 0", 150L, total[1])
        assertEquals(150L, ProxyMetrics.outputTokens)
        assertEquals(150L, rateSum(id))
    }

    @Test
    fun cacheTokensAreTalliedOnceAndNeverReachTheRate() {
        ProxyMetrics.resetTallies()
        val id = "cache-1"
        newTunnel(id)
        val total = drive(
            id,
            startFrame(1200, cacheRead = 9000, cacheWrite = 500),
            deltaFrame(12),
            deltaFrame(150),
            // Providers re-send the full usage object; the cache fields repeat
            // their values and must be counted once.
            "data: {\"usage\":{\"output_tokens\":150,\"cache_read_input_tokens\":9000," +
                "\"cache_creation_input_tokens\":500}}\n\n"
        )
        assertEquals(9000L, total[2])
        assertEquals(500L, total[3])
        assertEquals(9000L, ProxyMetrics.cacheReadTokens)
        assertEquals(500L, ProxyMetrics.cacheWriteTokens)
        assertEquals(150L, ProxyMetrics.outputTokens)
        // Rate sees output only: 150, not 9650.
        assertEquals(150L, rateSum(id))
    }

    @Test
    fun deferredTrailingDigitRunIsCreditedExactlyOnceAtFlush() {
        // A value whose digits are the last bytes of a chunk (here the stream
        // is cut mid-object) is deferred; flush() completes it. Credited
        // once, as the delta from the previous running total.
        ProxyMetrics.resetTallies()
        val id = "deferred-1"
        newTunnel(id)
        val u = ProxyMetrics.StreamingUsage()
        val total = LongArray(4)
        fun feed(text: String) {
            val b = text.toByteArray(Charsets.UTF_8)
            val q = u.feed(b, b.size)
            for (k in 0..3) total[k] += q[k]
            // Account every frame as the service does, not just the tail.
            ProxyMetrics.recordUsage(host, model, id, q)
        }
        feed(deltaFrame(12))
        feed(deltaFrame(40))
        // Digits stop at the buffer edge with no closing brace: unknowable
        // completeness, so nothing is credited by this feed.
        val cut = "event: message_delta\ndata: {\"usage\":{\"output_tokens\":150"
        val cb = cut.toByteArray(Charsets.UTF_8)
        val cutQuad = u.feed(cb, cb.size)
        assertEquals("trailing digit run must be deferred", 0L, cutQuad[1])
        val tail = u.flush()
        assertEquals("flush credits 150 - 40", 110L, tail[1])
        ProxyMetrics.recordUsage(host, model, id, tail)
        total[1] += tail[1]
        assertEquals(12L + 28L + 110L, total[1])
        assertEquals(150L, ProxyMetrics.outputTokens)
        assertEquals(150L, rateSum(id))
    }

    @Test
    fun flushTwiceDoesNotDoubleCredit() {
        ProxyMetrics.resetTallies()
        val id = "flush-1"
        newTunnel(id)
        val u = ProxyMetrics.StreamingUsage()
        // Digits are the last bytes of the stream: nothing is creditable until
        // flush, and flush must then credit it exactly once.
        val b = "event: message_delta\ndata: {\"usage\":{\"output_tokens\":150"
            .toByteArray(Charsets.UTF_8)
        assertEquals(0L, u.feed(b, b.size)[1])
        val first = u.flush()
        val second = u.flush()
        assertEquals(150L, first[1])
        assertArrayEquals("second flush must be a no-op", LongArray(4), second)
        ProxyMetrics.recordUsage(host, model, id, first)
        assertEquals(150L, ProxyMetrics.outputTokens)
        assertEquals(150L, rateSum(id))
    }

    @Test
    fun flushClearsTheTrackersSoTheNextResponseIsNotDiffed() {
        // flush() is a response boundary: what follows is credited from
        // scratch, even when it is larger than what the finished response
        // reported.
        ProxyMetrics.resetTallies()
        val u = ProxyMetrics.StreamingUsage()
        val a = "data: {\"usage\":{\"output_tokens\":150}}".toByteArray(Charsets.UTF_8)
        assertEquals(150L, u.feed(a, a.size)[1])
        assertEquals(0L, u.flush()[1])
        val b = "data: {\"usage\":{\"output_tokens\":900}}".toByteArray(Charsets.UTF_8)
        assertEquals("new response credited in full", 900L, u.feed(b, b.size)[1])
    }

    @Test
    fun oneShotOpenAiUsageBlockIsStillCountedInFull() {
        // OpenAI reports a single usage object at the end of the stream; it
        // must not be treated as a continuation of anything.
        ProxyMetrics.resetTallies()
        val id = "openai-1"
        newTunnel(id)
        val body = ("data: {\"id\":\"chatcmpl-1\",\"usage\":{\"prompt_tokens\":7843," +
            "\"completion_tokens\":14,\"total_tokens\":7857}}\ndata: [DONE]\n")
        val total = drive(id, body)
        assertArrayEquals(longArrayOf(7843, 14, 0, 0), total)
        assertEquals(7843L, ProxyMetrics.inputTokens)
        assertEquals(14L, ProxyMetrics.outputTokens)
        assertEquals(14L, rateSum(id))
    }

    @Test
    fun chunksSplitEverywhereInACumulativeStreamAgreeWithWholeFeeds() {
        // The delta bookkeeping must not depend on where the chunk boundaries
        // fall — including inside a key name and inside a digit run.
        ProxyMetrics.resetTallies()
        val stream = startFrame(1200) + deltaFrame(12) + deltaFrame(40) + deltaFrame(150)
        val bytes = stream.toByteArray(Charsets.UTF_8)
        // Split INSIDE the final digit run: the deferred partial run must not
        // be credited alongside the completed one.
        val cut = (stream.indexOf("\"output_tokens\":150") + "\"output_tokens\":15".length)
        for (split in listOf(1, 7, 23, cut, cut + 1, bytes.size - 1)) {
            ProxyMetrics.resetTallies()
            val u = ProxyMetrics.StreamingUsage()
            val total = LongArray(4)
            val feed = { from: Int, to: Int ->
                val n = to - from
                val q = u.feed(bytes.copyOfRange(from, to), n)
                for (k in 0..3) total[k] += q[k]
            }
            feed(0, split)
            feed(split, bytes.size)
            for (k in 0..3) total[k] += u.flush()[k]
            assertArrayEquals("split=$split", longArrayOf(1200, 150, 0, 0), total)
        }
    }

    // ---- short-span smoothing: a 1-3s completion must not read as one bucket ----

    @Test
    fun shortKnownSpanIsSmearedInsteadOfDumpedIntoOneSecond() {
        ProxyMetrics.resetTallies()
        val id = "span-1"
        newTunnel(id)
        // Real elapsed generation span of ~1s: before the fix, 2000 tokens in
        // that span landed entirely in one bucket and read as 2000 tok/s.
        Thread.sleep(1_100)
        val atMs = System.currentTimeMillis()
        ProxyMetrics.sampleOutputSpread(id, 2_000, atMs)
        val hist = ProxyMetrics.rateHistory(60, atMs)
        val peak = hist.maxOrNull() ?: 0L
        assertEquals("totals must be untouched", 2_000L, hist.sum())
        assertTrue("spiky: $peak tok/s", peak < 2_000L)
        assertTrue("not spread: ${hist.count { it > 0 }} buckets", hist.count { it > 0 } >= 2)
    }

    @Test
    fun smoothingWidensShortSpansWithoutCappingGenuineRates() {
        // Widening is what bounds density; there is deliberately no tok/s
        // ceiling, so a long, genuinely fast response keeps its real rate.
        assertEquals("unknown span keeps the unknown-span smear",
            ProxyMetrics.MIN_SPAN_SECS.toLong(), ProxyMetrics.smoothedSpanSecs(0).toLong())
        assertEquals("short span widened to the floor",
            ProxyMetrics.MIN_SPAN_SECS.toLong(), ProxyMetrics.smoothedSpanSecs(2).toLong())
        assertEquals("measured span wins when longer",
            37L, ProxyMetrics.smoothedSpanSecs(37).toLong())
        assertEquals("a long tunnel span is not pre-clamped here",
            10_000L, ProxyMetrics.smoothedSpanSecs(10_000).toLong())
        // 20k tokens over a real 20s span is a true 1000 tok/s, and stays one.
        val plan = ProxyMetrics.spreadPlan(20_000, ProxyMetrics.smoothedSpanSecs(20))
        assertEquals(20, plan.size)
        assertEquals(20_000L, plan.sum())
        assertEquals(1_000L, plan.maxOrNull() ?: 0L)
    }
}
