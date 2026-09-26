package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Usage accounting for cumulative AND one-shot providers.
 *
 * Two different reporting styles meet in one stream, and treating them the
 * same is wrong in opposite directions:
 *
 *  - Anthropic-style SSE re-reports `output_tokens` as a RUNNING TOTAL in
 *    every `message_delta` frame. Crediting each frame verbatim summed
 *    12+40+150 = 202 for a 150-token completion — a ~35% over-report that
 *    grew with frame count and read as multi-thousand tok/s.
 *  - `input_tokens` / `cache_read_input_tokens` / `cache_creation_input_tokens`
 *    are ONE-SHOT per response. Diffing them as if they were running totals
 *    under-counted on keep-alive connections, where one scanner sees many
 *    responses and a growing conversation's input (100, then 350) was
 *    credited as 100 + 250.
 *
 * These tests drive [ProxyMetrics.StreamingUsage] directly with real UTF-8
 * bytes through the real accounting path ([ProxyMetrics.recordUsage]), so
 * the tally and the rate are checked as the relay actually produces them,
 * and they pin the failure modes that must never come back: a keep-alive
 * tunnel dropping real tokens, an over-reported running total, a short
 * completion reading as one huge one-second spike, and the [beginResponse]
 * boundary hook disagreeing with the automatic fallback.
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

    /** One feed of [text] to a scanner; returns just the credit quad. */
    private fun feedOne(u: ProxyMetrics.StreamingUsage, text: String): LongArray {
        val b = text.toByteArray(Charsets.UTF_8)
        return u.feed(b, b.size)
    }

    /** The provider's authoritative usage schema, verbatim. */
    private val schemaJson = """
        {
          "type": "object",
          "properties": {
            "input_tokens":  { "type": "integer", "description": "The number of tokens in the input prompt." },
            "output_tokens": { "type": "integer", "description": "The number of tokens generated in the assistant response." },
            "cache_creation_input_tokens": { "type": ["integer","null"], "description": "Tokens written to the cache for prompt caching setup (if applicable)." },
            "cache_read_input_tokens":     { "type": ["integer","null"], "description": "Tokens retrieved directly from the prompt cache (if applicable)." }
          },
          "required": ["input_tokens", "output_tokens"]
        }
    """.trimIndent()

    /**
     * A realistic single usage object carrying the schema's four fields.
     * The schema itself is the contract; the scanner never sees a "type"
     * wrapper, so the pinned payload is the `properties` payload.
     */
    private fun schemaUsageBody(
        input: Int = 1200, output: Int = 150,
        cacheRead: Int = 9000, cacheWrite: Int = 500
    ): String = """{"type":"message_delta","usage":{"input_tokens":$input,""" +
        """"output_tokens":$output,"cache_creation_input_tokens":$cacheWrite,""" +
        """"cache_read_input_tokens":$cacheRead}}"""

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

    // ---- per-key semantics: the one-shot keys are NOT running totals ----
    //
    // `input_tokens` counts only the UNCACHED part of the prompt, so across a
    // growing conversation it grows (100, then 350). Diffing it as if it were
    // Anthropic's running output total credits 100 + 250 = 350 and silently
    // drops 250 real input tokens. These tests drive the scanner directly with
    // real UTF-8 bytes and pin the fix.

    /**
     * Run [chunks] on ONE scanner, crediting through [recordUsage] like the
     * relay does. [boundaryBefore] lists the chunk indexes that begin a new
     * response, i.e. where the relay owner would call
     * [ProxyMetrics.StreamingUsage.beginResponse]. Returns the scanner total.
     */
    private fun driveScanner(
        id: String,
        chunks: List<String>,
        boundaryBefore: Set<Int> = emptySet()
    ): LongArray {
        val u = ProxyMetrics.StreamingUsage()
        val total = LongArray(4)
        fun credit(q: LongArray) {
            for (k in 0..3) total[k] += q[k]
            if (q[0] + q[1] + q[2] + q[3] > 0) {
                ProxyMetrics.recordUsage(host, model, id, q)
            }
        }
        chunks.forEachIndexed { i, c ->
            if (i in boundaryBefore) credit(u.beginResponse())
            credit(feedOne(u, c))
        }
        credit(u.flush())
        return total
    }

    @Test
    fun theProviderSchemaMapsToTheQuadWithCacheWriteOnTheRightSlot() {
        // Pinned against the provider's own schema: cache_creation is the
        // cache WRITE and cache_read is the cache READ. Swapping them would
        // still sum to the same grand total everywhere the UI shows one
        // number, so only a slot-level assertion catches it.
        ProxyMetrics.resetTallies()
        val u = ProxyMetrics.StreamingUsage()
        val q = feedOne(u, schemaUsageBody())
        assertArrayEquals(
            "(input, output, cacheRead, cacheWrite)",
            longArrayOf(1200, 150, 9000, 500), q
        )
        // The schema text itself must not be mistaken for a usage report.
        val u2 = ProxyMetrics.StreamingUsage()
        assertArrayEquals(
            "the schema document is not a usage block",
            LongArray(4), feedOne(u2, schemaJson)
        )
    }

    @Test
    fun schemaFieldNamesAreAllScannedAndTheRequiredPairIsNeverOptional() {
        // Only input_tokens/output_tokens are required; the two cache fields
        // are ["integer","null"], so a body carrying just the required pair
        // must credit exactly that and leave the cache slots at zero.
        ProxyMetrics.resetTallies()
        val u = ProxyMetrics.StreamingUsage()
        val q = feedOne(u, """{"usage":{"input_tokens":1200,"output_tokens":150}}""")
        assertArrayEquals(longArrayOf(1200, 150, 0, 0), q)
        // And a body carrying only the cache pair (a null-valued usage block
        // reporting no prompt) must credit the cache slots, not the input.
        val u2 = ProxyMetrics.StreamingUsage()
        val q2 = feedOne(u2, """{"usage":{"cache_read_input_tokens":9000,"cache_creation_input_tokens":500}}""")
        assertArrayEquals(longArrayOf(0, 0, 9000, 500), q2)
    }

    @Test
    fun aGrowingOneShotInputOnOneKeepAliveScannerIsSummedNotDiffed() {
        // THE REGRESSION. One scanner, one keep-alive connection, two
        // responses, `input_tokens` growing 100 -> 350. One-shot semantics
        // require 100 + 350 = 450; the old diff-everything rule gave
        // 100 + (350 - 100) = 350 and lost 250 input tokens.
        ProxyMetrics.resetTallies()
        val id = "keepalive-oneshot"
        newTunnel(id)
        val total = driveScanner(
            id,
            listOf(
                startFrame(100),
                deltaFrame(12),
                deltaFrame(150),
                // Response 2 on the SAME connection: bigger prompt.
                startFrame(350),
                deltaFrame(12),
                deltaFrame(40)
            )
        )
        assertEquals("100 + 350, not 100 + 250", 450L, total[0])
        assertEquals(450L, ProxyMetrics.inputTokens)
        assertEquals("150 + 40 = 190 output", 190L, total[1])
        // Input never reaches the rate sampler; only output does.
        assertEquals(190L, rateSum(id))
    }

    @Test
    fun theExplicitBeginResponseHookAgreesWithTheAutomaticFallback() {
        // Same stream, same expected totals, but the boundary is DECLARED at
        // index 3 instead of inferred. Proves the hook and the fallback are
        // two routes to one behaviour, so calling the hook can never change
        // the numbers.
        ProxyMetrics.resetTallies()
        val id = "keepalive-hooked"
        newTunnel(id)
        val total = driveScanner(
            id,
            listOf(
                startFrame(100),
                deltaFrame(12),
                deltaFrame(150),
                startFrame(350),
                deltaFrame(12),
                deltaFrame(40)
            ),
            boundaryBefore = setOf(3)
        )
        assertEquals(450L, total[0])
        assertEquals(190L, total[1])
        assertEquals(450L, ProxyMetrics.inputTokens)
        assertEquals(190L, rateSum(id))
    }

    @Test
    fun aRepeatedOneShotValueIsOneResponseButTheSameValueInALaterResponseCountsAgain() {
        // The one case the automatic fallback alone cannot decide: two
        // responses reporting the SAME input_tokens. The fallback reads the
        // repeat as the same response (correct — it is genuinely
        // indistinguishable in-band) and the explicit hook is what counts it
        // twice. Both halves are pinned here.
        ProxyMetrics.resetTallies()
        val id = "same-value"
        newTunnel(id)
        val u = ProxyMetrics.StreamingUsage()
        // Re-sent usage object inside ONE response: credited once.
        val a = feedOne(u, startFrame(100))
        ProxyMetrics.recordUsage(host, model, id, a)
        val b = feedOne(u, startFrame(100))
        ProxyMetrics.recordUsage(host, model, id, b)
        val c = feedOne(u, startFrame(100))
        ProxyMetrics.recordUsage(host, model, id, c)
        assertEquals(100L, a[0])
        assertEquals("identical re-send is not a new response", 0L, b[0])
        assertEquals(0L, c[0])
        assertEquals(100L, ProxyMetrics.inputTokens)
        // Next response, declared: same value, counted again.
        assertArrayEquals("boundary flushes nothing pending", LongArray(4), u.beginResponse())
        val d = feedOne(u, startFrame(100))
        ProxyMetrics.recordUsage(host, model, id, d)
        assertEquals("same value, later response", 100L, d[0])
        assertEquals(200L, ProxyMetrics.inputTokens)
    }

    @Test
    fun oneShotCacheFieldsAreSummedAcrossKeepAliveResponsesAndReachTheTallyOnly() {
        // Cache read/write are one-shot per response exactly like input, so a
        // second response with a LARGER cache_read must be credited in full
        // rather than diffed — and neither may ever reach the rate.
        ProxyMetrics.resetTallies()
        val id = "keepalive-cache"
        newTunnel(id)
        val total = driveScanner(
            id,
            listOf(
                startFrame(100, cacheRead = 9000, cacheWrite = 500),
                deltaFrame(150),
                startFrame(350, cacheRead = 40000, cacheWrite = 1200),
                deltaFrame(40)
            )
        )
        assertEquals("9000 + 40000, not 9000 + 31000", 49000L, total[2])
        assertEquals("500 + 1200, not 500 + 700", 1700L, total[3])
        assertEquals(450L, total[0])
        assertEquals(190L, total[1])
        assertEquals(49000L, ProxyMetrics.cacheReadTokens)
        assertEquals(1700L, ProxyMetrics.cacheWriteTokens)
        // Rate sees output alone: 190, never 190 + 49000 + 1700.
        assertEquals(190L, rateSum(id))
    }

    @Test
    fun beginResponseMidStreamNeitherLosesNorDuplicatesOutput() {
        // The output running total is 40 when the boundary lands. Crediting
        // 150 after a reset must still be exactly one 150, neither lost nor
        // double-credited against the 40 already counted.
        ProxyMetrics.resetTallies()
        val id = "midstream"
        newTunnel(id)
        val u = ProxyMetrics.StreamingUsage()
        val total = LongArray(4)
        fun credit(q: LongArray) {
            for (k in 0..3) total[k] += q[k]
            if (q[0] + q[1] + q[2] + q[3] > 0) {
                ProxyMetrics.recordUsage(host, model, id, q)
            }
        }
        credit(feedOne(u, deltaFrame(12)))
        credit(feedOne(u, deltaFrame(40)))
        assertEquals("40 before the boundary", 40L, total[1])
        credit(u.beginResponse())
        assertEquals("the boundary itself credits nothing", 40L, total[1])
        // The running total restarted, so 150 is a NEW response's total and
        // is credited in full — the reset watermark is what encodes that.
        credit(feedOne(u, deltaFrame(150)))
        assertEquals("new response credited in full", 190L, total[1])
        credit(feedOne(u, deltaFrame(150)))
        assertEquals("repeat inside the new response credits 0", 190L, total[1])
        credit(u.flush())
        assertEquals(190L, total[1])
        assertEquals(190L, ProxyMetrics.outputTokens)
        assertEquals(190L, rateSum(id))
    }

    @Test
    fun beginResponseCreditsAPendingTrailingDigitRunExactlyOnceBeforeResetting() {
        // A response cut mid-number still has to be counted. beginResponse
        // flushes the deferred match against the OLD trackers (so it is the
        // increase, 150 - 40 = 110) and then resets — the partial number is
        // never both flushed here and completed by the next feed.
        ProxyMetrics.resetTallies()
        val id = "pending-boundary"
        newTunnel(id)
        val u = ProxyMetrics.StreamingUsage()
        ProxyMetrics.recordUsage(host, model, id, feedOne(u, deltaFrame(40)))
        val cut = "event: message_delta\ndata: {\"usage\":{\"output_tokens\":150"
        val cb = cut.toByteArray(Charsets.UTF_8)
        assertEquals("deferred at the edge", 0L, u.feed(cb, cb.size)[1])
        val boundary = u.beginResponse()
        assertEquals("flushes the pending run as a delta", 110L, boundary[1])
        ProxyMetrics.recordUsage(host, model, id, boundary)
        assertArrayEquals("pending tail consumed exactly once", LongArray(4), u.beginResponse())
        // The next response is counted from scratch and the stale 150 is not
        // re-credited by whatever bytes completed the old number.
        val next = feedOne(u, deltaFrame(12))
        ProxyMetrics.recordUsage(host, model, id, next)
        assertEquals(12L, next[1])
        assertEquals("40 + 110 + 12", 162L, ProxyMetrics.outputTokens)
    }

    @Test
    fun beginResponseBeforeTheFirstResponseIsANoOp() {
        // The relay calls it on every response head, including the first.
        ProxyMetrics.resetTallies()
        val u = ProxyMetrics.StreamingUsage()
        assertArrayEquals(LongArray(4), u.beginResponse())
        assertArrayEquals(LongArray(4), u.beginResponse())
        val q = feedOne(u, startFrame(1200) + deltaFrame(150))
        assertArrayEquals(longArrayOf(1200, 150, 0, 0), q)
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
