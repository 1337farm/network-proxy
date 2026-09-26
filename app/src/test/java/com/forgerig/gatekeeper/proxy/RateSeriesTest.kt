package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The chart stacks one bar per (provider, model). For the stack to be worth
 * drawing, its columns must ADD UP to the aggregate line the same dashboard
 * already shows, so these tests pin the identity
 *
 *     sum(rateSeries(n, at).total) == rateHistory(n, at).sum()
 *
 * column by column, in every shape the credit path can take (known span,
 * short span, unknown span, out-of-order writes, retention pressure, the
 * series cap) and under concurrency. A per-series implementation that
 * re-derived, re-bucketed or re-smoothed on its own would drift here; one
 * fed from the same spread plan (creditOutput) cannot.
 *
 * Reads are always anchored at a timestamp taken AFTER the writes they
 * assert on, so a second boundary crossing mid-test cannot make a whole
 * retention window miss the credit it just made.
 */
class RateSeriesTest {
    /** Whole retention window: a spread lands a few seconds either side. */
    private val full = ProxyMetrics.RATE_HISTORY_SECS

    /** A fixed, second-aligned instant, for tests that pin exact columns. */
    private val t0 = 1_700_000_000_000L

    private fun seriesTotal(nSecs: Int, atMs: Long): Long =
        ProxyMetrics.rateSeries(nSecs, atMs).sumOf { it.total }

    private fun assertInvariant(nSecs: Int, atMs: Long, what: String) {
        assertEquals(
            "$what: series must sum to the aggregate",
            ProxyMetrics.rateHistory(nSecs, atMs).sum(),
            seriesTotal(nSecs, atMs)
        )
    }

    private fun key(provider: String, model: String) = ProxyMetrics.seriesKey(provider, model)

    /**
     * Models credited by a test that are no longer tracked, read at the same
     * instant the assertions use. Only the cap can produce one.
     */
    private fun evicted(credited: List<String>, atMs: Long): List<String> {
        val live = ProxyMetrics.rateSeries(full, atMs).map { it.model }.toSet()
        return credited.filterNot { it in live }
    }

    private fun startRequest(id: String) {
        ProxyMetrics.recordRequestStart("s", id, "https://api.anthropic.com/v1/messages", "POST")
    }

    private fun credit(model: String, out: Long, provider: String = "p1", requestId: String? = "r") {
        if (requestId != null) startRequest(requestId)
        ProxyMetrics.recordUsage("h", model, requestId, longArrayOf(0, out, 0, 0), provider)
    }

    // ---- the identity ----

    @Test
    fun singleSeriesSumsToTheAggregate() {
        ProxyMetrics.resetTallies()
        credit("sonnet", 400)
        val at = System.currentTimeMillis()

        val series = ProxyMetrics.rateSeries(full, at)
        assertEquals("one model is one series", 1, series.size)
        assertEquals("p1", series[0].provider)
        assertEquals("sonnet", series[0].model)
        assertEquals(400L, series[0].total)
        assertEquals(400L, ProxyMetrics.rateHistory(full, at).sum())
        assertInvariant(full, at, "single series")
    }

    @Test
    fun manySeriesSumToTheAggregate() {
        ProxyMetrics.resetTallies()
        for (i in 1..12) {
            credit("model-$i", i * 100L, provider = "prov-${i % 3}", requestId = "many-$i")
        }
        val at = System.currentTimeMillis()
        assertEquals(12, ProxyMetrics.rateSeries(full, at).size)
        assertEquals(7800L, seriesTotal(full, at)) // 100 + 200 + ... + 1200
        assertInvariant(full, at, "twelve series")
    }

    @Test
    fun mixedSmoothingPathsStillSumToTheAggregate() {
        ProxyMetrics.resetTallies()
        val t = System.currentTimeMillis()

        // 1. unknown span: a requestId with no start -> MIN_SPAN_SECS smear.
        ProxyMetrics.recordUsage("h", "no-start", "never-started", longArrayOf(0, 500, 0, 0), "p1")
        // 2. tally-only (requestId null) credits tallies but no rate at all,
        //    so it must not appear in the series either.
        ProxyMetrics.recordUsage("h", "tally-only", null, longArrayOf(0, 777, 0, 0), "p1")
        // 3. a live request whose span is under a second -> MIN_SPAN floor.
        credit("short-span", 120, provider = "p2", requestId = "short")
        // 4./5. the close-of-tunnel (encoded body) and live-tail paths, which
        //        credit a deferred final delta through the unknown-span smear.
        ProxyMetrics.sampleOutputUnknownSpan(900, t, key("p3", "encoded"))
        ProxyMetrics.sampleOutputUnknownSpan(80, t, key("p3", "encoded"))
        // 6. zero and negative credits are no-ops on both sides.
        ProxyMetrics.recordUsage("h", "noise", "short", longArrayOf(0, 0, 0, 0), "p3")
        ProxyMetrics.recordUsage("h", "noise", "short", longArrayOf(0, -5, 0, 0), "p3")
        val at = System.currentTimeMillis()

        assertEquals(
            "unknown-span + short-span + encoded, and nothing else",
            setOf("no-start", "short-span", "encoded"),
            ProxyMetrics.rateSeries(full, at).map { it.model }.toSet()
        )
        // Tally-only usage still credits the token table, and still no rate
        // at all: it must not appear in the series (see the set above).
        assertEquals(777L, tokenOut("tally-only"))
        assertEquals(1600L, seriesTotal(full, at)) // 500 + 120 + 900 + 80
        assertInvariant(full, at, "mixed smoothing paths")
    }

    @Test
    fun stackedColumnsLineUpWithTheAggregateColumnByColumn() {
        ProxyMetrics.resetTallies()
        val t = System.currentTimeMillis()
        credit("a", 1000, requestId = "col-a")
        credit("b", 40, requestId = "col-b")
        credit("c", 7, requestId = "col-c")
        val at = System.currentTimeMillis()

        val n = 64
        val hist = ProxyMetrics.rateHistory(n, at)
        val series = ProxyMetrics.rateSeries(n, at)
        for (i in 0 until n) {
            assertEquals("column $i", hist[i], series.sumOf { it.perSecond[i] })
            for (s in series) {
                assertTrue(
                    "series ${s.key} exceeds the aggregate in column $i",
                    s.perSecond[i] <= hist[i]
                )
            }
        }
        // 1000 is spread over MIN_SPAN_SECS by both sides, so even the
        // busiest single series is far below its raw count.
        assertTrue("aggregate peak ${hist.max()}", hist.max() < 1000L)
    }

    @Test
    fun outOfOrderWritesMergeInBothRings() {
        ProxyMetrics.resetTallies()
        val now = t0 / 1000
        val k = key("p", "m")
        // The same second written before and after a spread of older
        // seconds: one bucket on both sides, not two slots.
        ProxyMetrics.sampleOutput(10, now * 1000, k)
        ProxyMetrics.sampleOutputUnknownSpan(20, (now - 5) * 1000, k)
        ProxyMetrics.sampleOutput(7, now * 1000, k)

        val s = ProxyMetrics.rateSeries(60, now * 1000).single()
        assertEquals(17L, s.perSecond.last())
        assertEquals(37L, s.total)
        assertInvariant(60, now * 1000, "out-of-order writes")
    }

    // ---- attribution ----

    @Test
    fun twoModelsOnOneProviderSplitTheRate() {
        ProxyMetrics.resetTallies()
        credit("sonnet", 300, requestId = "attr-1")
        credit("haiku", 100, requestId = "attr-2")
        val at = System.currentTimeMillis()

        val series = ProxyMetrics.rateSeries(full, at)
        val byModel = series.associateBy { it.model }
        assertEquals(setOf("sonnet", "haiku"), byModel.keys)
        assertEquals(300L, byModel.getValue("sonnet").total)
        assertEquals(100L, byModel.getValue("haiku").total)
        // Heaviest first, so the chart's bottom stack is the busy model.
        assertEquals(listOf("sonnet", "haiku"), series.map { it.model })
        assertEquals("p1", byModel.getValue("haiku").provider)
        assertInvariant(full, at, "two models")
    }

    @Test
    fun sameModelOnTwoProvidersIsTwoSeries() {
        ProxyMetrics.resetTallies()
        credit("sonnet", 250, provider = "anthropic", requestId = "p-a")
        credit("sonnet", 150, provider = "gateway", requestId = "p-b")
        val at = System.currentTimeMillis()

        val series = ProxyMetrics.rateSeries(full, at)
        assertEquals(2, series.size)
        assertEquals(listOf("anthropic", "gateway"), series.map { it.provider })
        assertTrue("both series keep the model", series.all { it.model == "sonnet" })
        assertEquals(400L, series.sumOf { it.total })
        assertInvariant(full, at, "same model, two providers")
    }

    @Test
    fun perSecondValuesLandInTheRightColumns() {
        ProxyMetrics.resetTallies()
        // sampleOutput folds into exactly one second, so individual columns
        // can be pinned instead of a smeared plan.
        ProxyMetrics.sampleOutput(11, t0, key("p", "a"))
        ProxyMetrics.sampleOutput(22, t0 - 1000, key("p", "a"))
        ProxyMetrics.sampleOutput(33, t0 - 2000, key("p", "b"))

        val n = 8
        val s = ProxyMetrics.rateSeries(n, t0).associateBy { "${it.provider}/${it.model}" }
        assertEquals(33L, s.getValue("p/b").perSecond[n - 3])
        assertEquals(22L, s.getValue("p/a").perSecond[n - 2])
        assertEquals(11L, s.getValue("p/a").perSecond[n - 1])
        assertEquals(33L, s.getValue("p/b").total)
        assertEquals(33L, s.getValue("p/a").perSecond.sum())
        assertInvariant(n, t0, "column alignment")
    }

    @Test
    fun theFourArgumentOverloadFilesUnderTheEmptyProvider() {
        ProxyMetrics.resetTallies()
        startRequest("unattributed")
        // Every ProxyService call site uses this form today: no provider id
        // exists there, and it is grouped honestly as "" rather than guessed.
        ProxyMetrics.recordUsage("h", "m", "unattributed", longArrayOf(0, 42, 0, 0))
        val at = System.currentTimeMillis()

        val s = ProxyMetrics.rateSeries(full, at).single()
        assertEquals(ProxyMetrics.UNATTRIBUTED_PROVIDER, s.provider)
        assertEquals("", s.provider)
        assertEquals("m", s.model)
        assertEquals(42L, s.total)
        assertInvariant(full, at, "unattributed provider")
    }

    @Test
    fun providerAndModelAreCaseFoldedLikeTheTokenTable() {
        ProxyMetrics.resetTallies()
        startRequest("case-1")
        ProxyMetrics.recordUsage("h", "Sonnet-4", "case-1", longArrayOf(0, 10, 0, 0), "Anthropic")
        startRequest("case-2")
        ProxyMetrics.recordUsage("h", "sonnet-4", "case-2", longArrayOf(0, 20, 0, 0), "anthropic")
        val at = System.currentTimeMillis()

        val s = ProxyMetrics.rateSeries(full, at)
        assertEquals("a casing difference must not split a series", 1, s.size)
        assertEquals(30L, s[0].total)
        assertEquals("sonnet-4", s[0].model)
        assertEquals("anthropic", s[0].provider)
        assertInvariant(full, at, "case folding")
    }

    // ---- determinism ----

    @Test
    fun orderingIsStableAcrossCalls() {
        ProxyMetrics.resetTallies()
        val models = listOf("zeta", "alpha", "mu", "beta", "gamma", "delta")
        models.forEachIndexed { i, m -> credit(m, 100, requestId = "det-$i") }
        val at = System.currentTimeMillis()

        // Equal totals everywhere, so the order is decided by the key alone.
        val first = ProxyMetrics.rateSeries(full, at)
        repeat(5) {
            val again = ProxyMetrics.rateSeries(full, at)
            assertEquals("order must not drift", first.map { it.key }, again.map { it.key })
            assertEquals(first, again)
        }
        assertEquals(models.sorted(), first.map { it.model })
        // Stable colour key, and a value-equal snapshot (LongArray included).
        assertTrue(first.all { it.key == key(it.provider, it.model) })
    }

    @Test
    fun heavierSeriesSortFirstAndTiesBreakByKey() {
        ProxyMetrics.resetTallies()
        listOf("m1" to 10L, "m2" to 300L, "m3" to 10L, "m4" to 50L).forEach { (m, out) ->
            credit(m, out, requestId = "sort-$m")
        }
        val at = System.currentTimeMillis()
        assertEquals(
            listOf("m2", "m4", "m1", "m3"),
            ProxyMetrics.rateSeries(full, at).map { it.model }
        )
    }

    // ---- retention and the cap ----

    @Test
    fun aSeriesOutsideTheWindowContributesNothing() {
        ProxyMetrics.resetTallies()
        ProxyMetrics.sampleOutput(500, t0, key("p", "old"))
        ProxyMetrics.sampleOutput(60, t0 + 60_000, key("p", "new"))

        // Window ending 30s after the first credit: only it is in range.
        val mid = ProxyMetrics.rateSeries(60, t0 + 30_000)
        assertEquals(500L, mid.single { it.model == "old" }.total)
        assertEquals(
            "a series past the window contributes nothing",
            0L, mid.single { it.model == "new" }.total
        )
        assertEquals(500L, ProxyMetrics.rateHistory(60, t0 + 30_000).sum())
        assertInvariant(60, t0 + 30_000, "partly panned window")

        // A window that ends before ANY credit: nothing in range anywhere.
        val early = ProxyMetrics.rateSeries(60, t0 - 60_000)
        assertTrue(early.all { it.total == 0L })
        assertEquals(0L, ProxyMetrics.rateHistory(60, t0 - 60_000).sum())
        assertInvariant(60, t0 - 60_000, "window before any traffic")
    }

    @Test
    fun seriesAndAggregateAgeOutTogetherUnderRetentionPressure() {
        ProxyMetrics.resetTallies()
        val now = t0 / 1000
        val k = key("p", "m")
        // More seconds than RATE_HISTORY_SECS, so the oldest are evicted
        // from both rings; a series must never keep a second the aggregate
        // has already dropped.
        for (s in 0 until ProxyMetrics.RATE_HISTORY_SECS + 200) {
            ProxyMetrics.sampleOutput(1, (now - s) * 1000, k)
        }
        val series = ProxyMetrics.rateSeries(full, now * 1000).single()
        assertEquals(
            "only the retained seconds",
            ProxyMetrics.RATE_HISTORY_SECS.toLong(), series.total
        )
        assertEquals(1L, series.perSecond.last())
        assertInvariant(full, now * 1000, "retention pressure")
    }

    @Test
    fun seriesCountIsCappedAndEvictsTheLeastRecentlyWritten() {
        ProxyMetrics.resetTallies()
        val n = ProxyMetrics.MAX_RATE_SERIES + 10
        for (i in 0 until n) credit("model-$i", 10, requestId = "cap-$i")
        val at = System.currentTimeMillis()

        val series = ProxyMetrics.rateSeries(full, at)
        assertEquals("the cap must hold", ProxyMetrics.MAX_RATE_SERIES, series.size)
        val models = series.map { it.model }.toSet()
        assertTrue("evicted the oldest, kept the newest", "model-${n - 1}" in models)
        assertTrue("LRU victim should be gone", "model-0" !in models)
        assertTrue("no early victims left", (0 until 10).none { "model-$it" in models })
        // The one documented lapse of the sum identity: the aggregate never
        // forgets a credited second, so each evicted model leaves its 10
        // tokens behind. Exactly the victims' totals, nothing else.
        val gone = evicted((0 until n).map { "model-$it" }, at)
        assertEquals(10, gone.size)
        val aggregate = ProxyMetrics.rateHistory(full, at).sum()
        assertEquals(n * 10L, aggregate)
        assertEquals(aggregate - gone.size * 10L, seriesTotal(full, at))
    }

    @Test
    fun anEvictedSeriesComesBackOnItsNextResponse() {
        ProxyMetrics.resetTallies()
        for (i in 0..ProxyMetrics.MAX_RATE_SERIES) {
            credit("model-$i", 10, requestId = "churn-$i")
        }
        val before = System.currentTimeMillis()
        assertEquals(ProxyMetrics.MAX_RATE_SERIES, ProxyMetrics.rateSeries(full, before).size)
        assertTrue(
            "model-0 was the LRU victim",
            ProxyMetrics.rateSeries(full, before).none { it.model == "model-0" }
        )

        credit("model-0", 7, requestId = "churn-again")
        val after = System.currentTimeMillis()
        val series = ProxyMetrics.rateSeries(full, after)
        assertEquals(ProxyMetrics.MAX_RATE_SERIES, series.size)
        assertEquals(
            "the re-credited delta only, not its lost history",
            7L, series.single { it.model == "model-0" }.total
        )
        // 64 live series, one of which (the revived model-0) holds only what
        // it re-earned; the aggregate still remembers everything credited.
        assertEquals((ProxyMetrics.MAX_RATE_SERIES - 1) * 10L + 7L, seriesTotal(full, after))
        val aggregate = ProxyMetrics.rateHistory(full, after).sum()
        assertEquals((ProxyMetrics.MAX_RATE_SERIES + 1) * 10L + 7L, aggregate)
        // The documented cost of the cap: two eviction events (model-0, then
        // the next LRU) each dropped a full 10 tokens of retained history.
        assertEquals(20L, aggregate - seriesTotal(full, after))
    }

    // ---- smoothing parity ----

    @Test
    fun aShortSpanIsSmearedForTheSeriesExactlyAsForTheAggregate() {
        ProxyMetrics.resetTallies()
        credit("m", 800, requestId = "smear")
        val at = System.currentTimeMillis()

        val plan = ProxyMetrics.spreadPlan(800, ProxyMetrics.MIN_SPAN_SECS)
        val s = ProxyMetrics.rateSeries(ProxyMetrics.MIN_SPAN_SECS * 2, at).single()
        assertEquals("series uses the MIN_SPAN_SECS floor", plan, s.perSecond.takeLast(plan.size).toList())
        assertTrue("no credit before the plan", s.perSecond.dropLast(plan.size).all { it == 0L })
        assertEquals(800L, s.total)
        // The point of the smoothing: a chart can never draw a taller bar
        // than the aggregate line, and never a spike the total doesn't back.
        assertEquals(800L / ProxyMetrics.MIN_SPAN_SECS, s.perSecond.max())
        assertEquals(
            "series peak must not exceed the aggregate peak",
            s.perSecond.max(), ProxyMetrics.rateHistory(full, at).max()
        )
    }

    @Test
    fun aLongSpanIsClampedToMaxSpreadSecsOnBothSides() {
        ProxyMetrics.resetTallies()
        // A keep-alive tunnel reports a huge span; both sides must clamp to
        // MAX_SPREAD_SECS instead of smearing across the whole tunnel.
        startRequest("tunnel")
        val startMs = System.currentTimeMillis()
        val atMs = startMs + 600_000
        val k = key("p", "m")
        ProxyMetrics.sampleOutputSpread("tunnel", 3000, atMs, k)

        val s = ProxyMetrics.rateSeries(full, atMs).single()
        assertEquals("clamped to MAX_SPREAD_SECS", ProxyMetrics.MAX_SPREAD_SECS, s.perSecond.count { it > 0 })
        assertTrue("evenly spread after the clamp", s.perSecond.filter { it > 0 }.all { it == 50L })
        assertTrue(
            "nothing credited outside the clamp",
            s.perSecond.dropLast(ProxyMetrics.MAX_SPREAD_SECS).all { it == 0L }
        )
        assertEquals(3000L, s.total)
        assertEquals(3000L / ProxyMetrics.MAX_SPREAD_SECS, s.perSecond.max())
        assertInvariant(full, atMs, "clamped long span")
    }

    // ---- concurrency ----

    @Test
    fun concurrentRecordUsageAndReadsHoldTheIdentity() {
        ProxyMetrics.resetTallies()
        val writers = 8
        val readers = 2
        val perWriter = 300
        val models = listOf("m-a", "m-b", "m-c", "m-d")
        val providers = listOf("p1", "p2")
        val pool = Executors.newFixedThreadPool(writers + readers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers + readers)
        val failure = AtomicReference<Throwable?>(null)

        // Readers hammer rateSeries() for the whole run, interleaved with
        // the writers rather than after them.
        repeat(readers) {
            pool.submit {
                try {
                    start.await()
                    repeat(2000) {
                        for (series in ProxyMetrics.rateSeries(full, System.currentTimeMillis())) {
                            // A snapshot must be internally consistent: every
                            // series is a partition of the aggregate, so no
                            // negative bucket and total == sum(perSecond).
                            check(series.perSecond.all { v -> v >= 0 }) { "negative bucket in ${series.key}" }
                            check(series.total == series.perSecond.sum()) { "total mismatch for ${series.key}" }
                        }
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }
        repeat(writers) { w ->
            pool.submit {
                try {
                    start.await()
                    val id = "conc-$w"
                    startRequest(id)
                    repeat(perWriter) { i ->
                        ProxyMetrics.recordUsage(
                            "h", models[i % models.size], id,
                            longArrayOf(1, 3L, 0, 0), providers[(i / models.size) % providers.size]
                        )
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        failure.get()?.let { throw it }

        val at = System.currentTimeMillis()
        val expected = writers * perWriter * 3L
        assertEquals("no credit lost", expected, ProxyMetrics.rateHistory(full, at).sum())
        assertEquals("tallies and rate agree", expected, ProxyMetrics.outputTokens)
        assertEquals(expected, seriesTotal(full, at))
        assertInvariant(full, at, "concurrent record + read")
        // Every credit landed exactly once: 4 models x 2 providers, all live.
        assertEquals(8, ProxyMetrics.rateSeries(full, at).size)
    }

    @Test
    fun concurrentWritesPastTheCapNeverThrowOrLoseTheIdentity() {
        ProxyMetrics.resetTallies()
        val threads = 6
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)
        repeat(threads) { w ->
            pool.submit {
                try {
                    start.await()
                    repeat(perThread) { i ->
                        // A distinct model every few iterations: far more
                        // live series than the cap, all through one requestId.
                        ProxyMetrics.recordUsage(
                            "h", "model-$w-${i % 130}", "over-$w",
                            longArrayOf(0, 2, 0, 0), "p"
                        )
                        if (i % 25 == 0) ProxyMetrics.rateSeries(60, System.currentTimeMillis())
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue("workers did not finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        failure.get()?.let { throw it }

        val at = System.currentTimeMillis()
        val series = ProxyMetrics.rateSeries(full, at)
        assertTrue("cap held under churn: ${series.size}", series.size <= ProxyMetrics.MAX_RATE_SERIES)
        assertTrue("churn produced no series at all", series.isNotEmpty())
        // Every snapshot is internally consistent, and the series side never
        // over-reports the aggregate even while models are being evicted.
        for (s in series) {
            assertTrue("${s.key} has a negative bucket", s.perSecond.all { it >= 0 })
            assertEquals("${s.key} total", s.perSecond.sum(), s.total)
        }
        val aggregate = ProxyMetrics.rateHistory(full, at).sum()
        assertTrue("series exceeded the aggregate", seriesTotal(full, at) <= aggregate)
        assertEquals(threads * perThread * 2L, aggregate)
    }

    /** Per-model output total from the token table (not the rate path). */
    private fun tokenOut(model: String): Long =
        ProxyMetrics.tokenSummary(20).firstOrNull { it.model == model }?.outTokens ?: 0L
}
