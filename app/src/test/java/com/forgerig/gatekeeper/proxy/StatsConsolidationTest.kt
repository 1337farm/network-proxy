package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the statistics consolidation: the TOTAL-vs-single-row
 * dedupe rule, the derived `reuse %` formula that replaced the deleted
 * "Cache efficiency" section, per-host grouping (full hostname once per
 * host, models beneath), and Group 1's rate heading.
 *
 * The rendering itself (TableLayout rows, TokenRateView, session list) is
 * Android-only and cannot be unit-tested on the JVM — only these decisions
 * are pinned here, deliberately.
 */
class StatsConsolidationTest {

    private fun row(
        host: String, model: String,
        inTok: Long = 0, outTok: Long = 0, cacheR: Long = 0, cacheW: Long = 0
    ) = ProxyMetrics.TokenRow(host, model, inTok, outTok, cacheR, cacheW)

    // ---- TOTAL dedupe -------------------------------------------------

    @Test
    fun totalIsShownOnlyWhenMoreThanOneRow() {
        // The bug this kills: one data row + a TOTAL row repeating it.
        assertFalse(StatsConsolidation.shouldShowTotal(0))
        assertFalse(StatsConsolidation.shouldShowTotal(1))
        assertTrue(StatsConsolidation.shouldShowTotal(2))
        assertTrue(StatsConsolidation.shouldShowTotal(7))
    }

    @Test
    fun singleRowNeedsNoTotalEvenWithManyModels() {
        // One host, one model: the heading plus the row IS the whole truth.
        val groups = StatsConsolidation.groupByHost(listOf(row("opencode.ai", "space-bunny-free", 10, 5)))
        assertEquals(1, groups.size)
        assertFalse(StatsConsolidation.shouldShowTotal(groups.sumOf { it.rows.size }))
    }

    // ---- reuse % formula ----------------------------------------------

    @Test
    fun reuseIsCacheReadOverInputPlusCacheRead() {
        // 485 / (515 + 485) — the 48.5% the old cache section showed.
        assertEquals(0.485, StatsConsolidation.reuseRatio(515, 485), 1e-9)
        assertEquals("48.5%", StatsConsolidation.reusePct(515, 485))
        // Fully cached turn: 100%, which the old clamped cacheRead/input
        // ratio could never express.
        assertEquals(1.0, StatsConsolidation.reuseRatio(0, 900), 1e-9)
        assertEquals("100.0%", StatsConsolidation.reusePct(0, 900))
        // Nothing cached.
        assertEquals(0.0, StatsConsolidation.reuseRatio(900, 0), 1e-9)
        assertEquals("0.0%", StatsConsolidation.reusePct(900, 0))
        // No prompt at all: 0, not a division by zero.
        assertEquals(0.0, StatsConsolidation.reuseRatio(0, 0), 1e-9)
        assertEquals("0.0%", StatsConsolidation.reusePct(0, 0))
        // Reuse can never exceed the prompt it is a share of.
        assertTrue(StatsConsolidation.reuseRatio(10, 10_000) <= 1.0)
        // Negative tallies (a reset race) read as 0 rather than nonsense.
        assertEquals(0.0, StatsConsolidation.reuseRatio(-5, -5), 1e-9)
    }

    // ---- host grouping ------------------------------------------------

    @Test
    fun hostIsStatedOnceAndModelsNestBeneathIt() {
        val groups = StatsConsolidation.groupByHost(
            listOf(
                row("opencode.ai", "space-bunny-free", 100, 10, 90),
                row("api.anthropic.com", "claude", 5, 1, 0),
                row("opencode.ai", "other-model", 7, 2, 3)
            )
        )
        // First-seen host order, all models of a host under one heading.
        assertEquals(2, groups.size)
        assertEquals("opencode.ai", groups[0].host)
        assertEquals(listOf("space-bunny-free", "other-model"), groups[0].rows.map { it.model })
        assertEquals("api.anthropic.com", groups[1].host)
        assertEquals(listOf("claude"), groups[1].rows.map { it.model })
        // Every input row survives grouping exactly once.
        assertEquals(3, groups.sumOf { it.rows.size })
        // Hostnames are never touched: grouping must not shorten them
        // (the table used to ellipsize them to `opencode.ai…`).
        val longHost = "very-long-subdomain.that-used-to-be-truncated.example.ai"
        assertEquals(longHost, StatsConsolidation.groupByHost(listOf(row(longHost, "m"))).single().host)
    }

    @Test
    fun groupByHostIsEmptySafe() {
        assertTrue(StatsConsolidation.groupByHost(emptyList()).isEmpty())
    }

    @Test
    fun perHostCapStillProtectsTheTableFromOneFloodingHost() {
        // The rule the deleted cache section enforced: three models of one
        // provider must not push every other host out of the table.
        val rows = ProxyMetrics.capPerHost(
            (1..4).map { row("flood.ai", "m$it", (100 - it).toLong()) } + listOf(row("other.ai", "m", 1L)),
            3
        )
        assertEquals(4, rows.size)
        assertEquals(3, rows.count { it.host == "flood.ai" })
        assertEquals(1, rows.count { it.host == "other.ai" })
        // … and the surviving rows are exactly what Group 2 renders.
        val groups = StatsConsolidation.groupByHost(rows)
        assertEquals(2, groups.size)
        assertTrue(StatsConsolidation.shouldShowTotal(rows.size))
    }

    // ---- Group 1 heading ----------------------------------------------

    @Test
    fun rateHeadingCarriesRateAverageAndPeak() {
        val h = StatsConsolidation.rateHeading(93.0, 229.7, 214L, 120)
        assertEquals("Output rate · 93 tok/s · avg 229.7 · peak 214 (trailing 120s)", h)
        // Idle stream: zeros, never blank or "-".
        assertEquals("Output rate · 0 tok/s · avg 0.0 · peak 0 (trailing 120s)",
            StatsConsolidation.rateHeading(0.0, 0.0, 0L, 120))
    }
}
