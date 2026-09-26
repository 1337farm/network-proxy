package com.forgerig.gatekeeper.proxy

import com.forgerig.gatekeeper.proxy.TokenRateView.Series
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the stacked rate chart's geometry.
 *
 * TokenRateView extends android.view.View and the module has no
 * Robolectric, so the class is never instantiated here: every assertion is
 * on the pure companion surface that the draw path itself calls — stack
 * accumulation, the reference-line height, the peak-label placement rule,
 * the legend cap/order, band alignment and merge, and the deterministic
 * colour index.
 *
 * Density-free by construction: the label rules take plain pixel numbers,
 * so the tests use an abstract plot (baseline 100, span 100, 8px of header
 * reserve) instead of pretending to know a device's metrics.
 *
 * Still device-only (see the fix report): the actual Canvas output
 * (divider pixels, palette rendering, the measured gutter and the backing
 * box), the ValueAnimator ease and its cancel/commit interleaving, the
 * touch pipeline and parent intercept, and whether the LIVE chip and a
 * full-height peak label graze each other in the gutter. Those need an
 * instrumentation test or a screenshot; faking an animator or a Canvas
 * here would assert nothing.
 */
class StackedChartGeometryTest {

    /** baseline, plot span and header reserve of the abstract plot. */
    private val base = 100f
    private val span = 100f
    private val topPad = 8f

    private fun s(key: String, vararg v: Long) =
        Series(key, key.substringBefore(':'), "m", longArrayOf(*v))

    private fun sOf(provider: String, model: String, key: String, vararg v: Long) =
        Series(key, provider, model, longArrayOf(*v))

    // ---- stack accumulation: bar height is the aggregate ----

    @Test
    fun totalsPerSecondAreTheSumOfContributions() {
        val a = s("p1:gpt", 10, 20, 30)
        val b = s("p2:claude", 1, 2, 3)
        val c = s("p3:llama", 100, 0, 5)
        assertArrayEquals(
            longArrayOf(111, 22, 38),
            TokenRateView.stackTotals(listOf(a, b, c)),
            0L
        )
    }

    @Test
    fun anEmptyFrameTotalsToNothingAndStillHasAUsableScale() {
        val totals = TokenRateView.stackTotals(emptyList())
        assertEquals(0, totals.size)
        // An all-zero frame must still produce a scale: divide-by-zero here
        // would be a NaN height, i.e. a bar that draws nowhere.
        assertEquals(1L, TokenRateView.peakOfTotals(totals))
        assertEquals(1L, TokenRateView.peakOfTotals(longArrayOf(0, 0, 0)))
    }

    @Test
    fun stackWidthIsTheLongestSeriesAndRaggedOnesCountAsZero() {
        // A host that has only just started has a short history: it must
        // neither shift the window nor throw.
        val long = s("p1:gpt", 1, 1, 1, 1)
        val short = s("p2:claude", 5)
        assertArrayEquals(longArrayOf(1, 1, 1, 6), TokenRateView.stackTotals(listOf(long, short)), 0L)
        assertArrayEquals(longArrayOf(5, 0, 0, 1), TokenRateView.stackTotals(listOf(short, long)), 0L)
    }

    @Test
    fun peakOfTotalsIsTheTallestBarAndNeverZero() {
        assertEquals(312L, TokenRateView.peakOfTotals(longArrayOf(4, 312, 90)))
        assertEquals(1L, TokenRateView.peakOfTotals(longArrayOf()))
        assertEquals(9L, TokenRateView.peakOfTotals(longArrayOf(0, 9, 0)))
    }

    @Test
    fun theAggregateCannotExceedTheScale() {
        // The ceiling belongs to the axis, not the data: with the peak as
        // the scale every bucket normalises to <= 1, however much the
        // stack adds up to.
        val series = listOf(s("p1:gpt", 200, 0), s("p2:claude", 100, 0), s("p3:llama", 12, 0))
        val totals = TokenRateView.stackTotals(series)
        val peak = TokenRateView.peakOfTotals(totals)
        assertEquals(312L, peak)
        for (i in totals.indices) {
            val frac = totals[i].toFloat() / peak.toFloat()
            assertTrue("bucket $i normalised above the scale", frac <= 1f)
        }
        // and yFor keeps even a mis-scaled value inside the plot
        assertTrue(TokenRateView.yFor(totals[0], peak, base, span) >= base - span)
    }

    // ---- yFor: the reference-line height ----

    @Test
    fun yForMapsTheScaleOntoThePlot() {
        assertEquals(0f, TokenRateView.yFor(1000, 1000, base, span), 1e-4f) // top
        assertEquals(100f, TokenRateView.yFor(0, 1000, base, span), 1e-4f) // baseline
        assertEquals(50f, TokenRateView.yFor(500, 1000, base, span), 1e-4f)
    }

    @Test
    fun yForStaysInsideThePlotForOutOfRangeValues() {
        // Over the scale (a stacked total racing the committed axis) and
        // nonsense below it both clamp rather than draw outside the plot.
        assertEquals(0f, TokenRateView.yFor(2000, 1000, base, span), 1e-4f)
        assertEquals(100f, TokenRateView.yFor(-20, 1000, base, span), 1e-4f)
        // A zero scale must not divide by zero into -Inf/NaN.
        val y = TokenRateView.yFor(0, 0, base, span)
        assertFalse("y must stay finite", y.isNaN() || y.isInfinite())
        assertEquals(100f, y, 1e-4f)
    }

    @Test
    fun theReferenceLineLandsOnTheTallestBarsTop() {
        // The point of the line: its y is the y of the top of the tallest
        // bar, both derived from the one committed scale (lastMax), so the
        // axis cannot drift away from the bars it is labelling.
        val lastMax = 1000L
        val line = TokenRateView.yFor(312, lastMax, base, span)
        // what the draw path paints for that bar: fraction of the span
        assertEquals(base - (312f / lastMax.toFloat()) * span, line, 1e-4f)
        // a smaller value is a lower line, a larger one a higher line
        assertTrue(line < TokenRateView.yFor(156, lastMax, base, span))
        assertTrue(line > TokenRateView.yFor(624, lastMax, base, span))
        // the axis maximum is the top of the plot
        assertEquals(base - span, TokenRateView.yFor(lastMax, lastMax, base, span), 1e-4f)
    }

    // ---- peak label: duplication and collision rules ----

    @Test
    fun gutterLabelBandsAreTheQuartiles() {
        // baseline 100, span 100 -> the 25/50/75% gridlines, each label
        // nudged off its own line.
        assertEquals(78f, TokenRateView.bandY(1, base, span), 1e-4f)
        assertEquals(53f, TokenRateView.bandY(2, base, span), 1e-4f)
        assertEquals(28f, TokenRateView.bandY(3, base, span), 1e-4f)
    }

    @Test
    fun peakLabelIsDroppedOnlyWhenItWouldDuplicateAQuartile() {
        // On a gridline: that line's own gutter label already reads the
        // same number, so nothing is drawn on top of it.
        assertFalse(TokenRateView.peakLabelShown(28f, base, span))
        assertFalse(TokenRateView.peakLabelShown(53f, base, span))
        assertFalse(TokenRateView.peakLabelShown(78f, base, span))
        // Near, but not on, one: kept, because the values differ and
        // dropping it would misreport the peak.
        assertTrue(TokenRateView.peakLabelShown(37f, base, span))
        assertTrue(TokenRateView.peakLabelShown(0f, base, span))
    }

    @Test
    fun peakLabelTakesTheNearestFreeSlot() {
        // A peak at the very top of the plot: the header reserve is the
        // only row available above, so the number sits just under the top
        // rather than up in the "peak 312/s" readout.
        assertEquals(8f, TokenRateView.peakLabelY(0f, base, span, topPad), 1e-4f)
        // Row 37 is 9px off the 25% label, so it is not free; the search
        // moves to the nearest row that is (2px lower).
        assertEquals(39f, TokenRateView.peakLabelY(37f, base, span, topPad), 1e-4f)
        // Room above wins: 70 moves up to 67 rather than down to 74.
        assertEquals(67f, TokenRateView.peakLabelY(70f, base, span, topPad), 1e-4f)
        // Nothing in the way: the number stays on its own line.
        assertEquals(95f, TokenRateView.peakLabelY(95f, base, span, topPad), 1e-4f)
    }

    @Test
    fun peakLabelNeverOverlapsALabelAndStaysInThePlot() {
        // The invariant behind the rule, swept over every line height and
        // several plot shapes (a tall one, the abstract one, a squat one
        // and a pathologically short one).
        val shapes = listOf(
            Triple(300f, 258f, 26f), // phone-sized
            Triple(base, span, topPad),
            Triple(100f, 60f, 8f), // squat
            Triple(60f, 20f, 8f), // shorter than the labels are wide
            Triple(60f, 20f, 40f) // header reserve swallows the plot
        )
        var labelled = 0
        for ((b, sp, tp) in shapes) {
            val floor = b - sp + tp
            for (step in 0..400) {
                val valueY = b * step / 400f
                if (!TokenRateView.peakLabelShown(valueY, b, sp)) continue
                val y = TokenRateView.peakLabelY(valueY, b, sp, tp)
                if (floor >= b) {
                    // no room at all: the helper must not throw and must
                    // hand the line's own position back
                    assertEquals(valueY, y, 1e-4f)
                    continue
                }
                assertTrue("label $y below the floor $floor (line $valueY)", y >= floor)
                assertTrue("label $y below the baseline $b", y <= b)
                // A collision is only acceptable when the plot has no
                // collision-free row left at all.
                val anyFree = (Math.ceil(floor).toInt()..b.toInt()).any {
                    !TokenRateView.labelCollision(it.toFloat(), b, sp)
                }
                if (anyFree) {
                    assertFalse(
                        "label $y overlaps a gutter label (line $valueY, b=$b span=$sp)",
                        TokenRateView.labelCollision(y, b, sp)
                    )
                }
                labelled++
            }
        }
        assertTrue("expected most heights to be labelled", labelled > 1000)
    }

    @Test
    fun labelCollisionIsSymmetricAndToleranceBounded() {
        val band = TokenRateView.bandY(3, base, span) // 28
        assertTrue(TokenRateView.labelCollision(band, base, span))
        assertTrue(TokenRateView.labelCollision(band + 5f, base, span))
        assertTrue(TokenRateView.labelCollision(band - 5f, base, span))
        assertFalse(TokenRateView.labelCollision(band + 11f, base, span))
        assertFalse(TokenRateView.labelCollision(band - 11f, base, span))
        // a zero-height plot collapses all three labels onto one row just
        // below the baseline
        assertTrue(TokenRateView.labelCollision(base + 3f, base, 0f))
    }

    // ---- legend: cap, order, naming ----

    @Test
    fun legendIsCappedAndRankedByTotal() {
        val series = listOf(
            s("p1:a", 1),      // total 1
            s("p2:b", 10, 10), // total 20 -> first
            s("p3:c", 5),
            s("p4:d", 4),
            s("p5:e", 3),
            s("p6:f", 2)
        )
        assertEquals(TokenRateView.LEGEND_MAX, TokenRateView.legendOrder(series).size)
        assertEquals(listOf(1, 2, 3, 4), TokenRateView.legendOrder(series).toList())
        // 6 contributors, 4 named: the note says how many were folded
        assertEquals(2, TokenRateView.tailCount(series))
    }

    @Test
    fun legendOrderIsStableForTiedTotals() {
        // Ties keep input order, so the chart cannot reshuffle between two
        // polls that report the same numbers.
        val series = listOf(s("p1:a", 5), s("p2:b", 5), s("p3:c", 5))
        assertEquals(listOf(0, 1, 2), TokenRateView.legendOrder(series).toList())
        assertEquals(listOf(0, 1, 2), TokenRateView.legendOrder(series).toList())
    }

    @Test
    fun silentSeriesAreNeitherNamedNorDrawn() {
        val series = listOf(s("p1:a", 0, 0), s("p2:b", 7), s("p3:c", 0))
        assertEquals(listOf(1), TokenRateView.legendOrder(series).toList())
        assertEquals(0, TokenRateView.tailCount(series))
        assertEquals(0L, TokenRateView.seriesTotal(series[0]))
        assertEquals(7L, TokenRateView.seriesTotal(series[1]))
    }

    @Test
    fun legendCapIsConfigurableAndBounded() {
        val series = (1..6).map { s("p$it:m", it.toLong()) }
        assertEquals(2, TokenRateView.legendOrder(series, max = 2).size)
        assertEquals(6, TokenRateView.legendOrder(series, max = 9).size)
        assertEquals(0, TokenRateView.legendOrder(series, max = 0).size)
        assertEquals(6, TokenRateView.tailCount(series, max = 0))
    }

    @Test
    fun legendLabelNamesProviderAndModel() {
        assertEquals(
            "anthropic · claude-4",
            TokenRateView.legendLabel(sOf("anthropic", "claude-4", "anthropic:claude-4", 1))
        )
        // unknown provider: the model alone, no dangling separator
        assertEquals(
            "local-7b",
            TokenRateView.legendLabel(Series("k", "  ", "local-7b", longArrayOf(1)))
        )
        assertEquals(
            "local-7b",
            TokenRateView.legendLabel(Series("k", "", "local-7b", longArrayOf(1)))
        )
    }

    @Test
    fun legendLinesEndWithTheFoldedTailNote() {
        val series = (1..6).map { s("p$it:m", it.toLong()) }
        val lines = TokenRateView.legendLines(series)
        assertEquals(TokenRateView.LEGEND_MAX + 1, lines.size) // 4 names + note
        assertEquals("+2 more", lines.last())
        assertEquals(listOf("p6 · m", "p5 · m", "p4 · m", "p3 · m"), lines.take(4))
        // nothing to fold: no note at all
        val small = listOf(s("p1:a", 3), s("p2:b", 2))
        assertEquals(listOf("p1 · m", "p2 · m"), TokenRateView.legendLines(small))
        assertEquals(emptyList<String>(), TokenRateView.legendLines(emptyList()))
    }

    @Test
    fun legendIsBoundedInCountWhateverTheSeriesCount() {
        // 40 live series: still 4 rows plus the note. The bound is the cap,
        // never the list length.
        val many = (1..40).map { s("p$it:m", it.toLong()) }
        assertEquals(TokenRateView.LEGEND_MAX + 1, TokenRateView.legendLines(many).size)
        // and the loudest series keep the top rows
        assertEquals(listOf(39, 38, 37, 36), TokenRateView.legendOrder(many).toList())
    }

    // ---- colour: deterministic per key ----

    @Test
    fun colourIndexIsStableForAKey() {
        val key = "anthropic:claude-4"
        val first = TokenRateView.colorIndexFor(key)
        assertEquals(first, TokenRateView.colorIndexFor(key))
        assertEquals(first, TokenRateView.colorIndexFor(String(key.toCharArray())))
        // and independent of where the series sits in the list, which is
        // the whole point: order follows the totals, colour may not.
        val a = listOf(s("anthropic:claude-4", 1), s("openai:gpt", 2))
        val b = listOf(s("openai:gpt", 2), s("anthropic:claude-4", 1))
        assertEquals(
            TokenRateView.colorIndexFor(a[0].key),
            TokenRateView.colorIndexFor(b[1].key)
        )
    }

    @Test
    fun colourIndexIsInRangeAndAnEmptyKeyIsToneZero() {
        val keys = listOf(
            "a", "b", "anthropic:claude-4", "openai:gpt-5", "", "x".repeat(64),
            "üñïçø∂é", "12", "12.0", TokenRateView.LEGACY_KEY
        )
        for (k in keys) {
            val i = TokenRateView.colorIndexFor(k)
            assertTrue("index $i out of range for '$k'", i in 0 until TokenRateView.PALETTE_SIZE)
        }
        assertEquals(0, TokenRateView.colorIndexFor(""))
        assertEquals(0, TokenRateView.colorIndexFor(null))
    }

    @Test
    fun differentKeysGetDifferentTones() {
        // With 8 tones and 8 distinct keys all 8 must differ: a hash that
        // collapsed neighbours would make adjacent stack segments look
        // like one segment, which is the bug the palette exists to avoid.
        val tones = (1..8).map { TokenRateView.colorIndexFor("p$it:m") }.toSet()
        assertEquals(TokenRateView.PALETTE_SIZE, tones.size)
        // The unnamed aggregate of the legacy path must not impersonate a
        // host series key of the same model name.
        assertTrue(
            TokenRateView.colorIndexFor(TokenRateView.LEGACY_KEY) !=
                TokenRateView.colorIndexFor("m")
        )
    }

    // ---- band alignment and same-key merge ----

    @Test
    fun bandWindowsAlignOnTheTrailingEdge() {
        val bands = TokenRateView.bandWindows(
            listOf(
                Series("k", "p", "m", longArrayOf(1, 2, 3, 4)),
                Series("k2", "p", "m", longArrayOf(9))
            ),
            n = 4
        )
        assertEquals(2, bands.size)
        assertArrayEquals(longArrayOf(1, 2, 3, 4), bands[0], 0L)
        // a short history is the most recent N seconds, left-padded with
        // zeros — never right-shifted, which would put a new model's
        // first tokens at the wrong second
        assertArrayEquals(longArrayOf(0, 0, 0, 9), bands[1], 0L)
    }

    @Test
    fun bandWindowsCropALongerHistoryAtTheFront() {
        val bands = TokenRateView.bandWindows(
            listOf(Series("k", "p", "m", longArrayOf(1, 2, 3, 4, 5, 6))),
            n = 4
        )
        assertArrayEquals(longArrayOf(3, 4, 5, 6), bands[0], 0L)
        assertEquals(0, TokenRateView.bandWindows(emptyList(), n = 0).size)
    }

    @Test
    fun sameKeySeriesMergeAndKeepTheirTotal() {
        val merged = TokenRateView.mergeSeries(
            listOf(
                Series("p1:m", "p1", "m", longArrayOf(10, 20)),
                Series("p1:m", "p1", "m", longArrayOf(1, 2))
            )
        )
        assertEquals(1, merged.size)
        assertArrayEquals(longArrayOf(11, 22), merged[0].perSecond, 0L)
    }

    @Test
    fun mergeAlignsUnevenSameKeyWindowsOnTheTrailingEdge() {
        val merged = TokenRateView.mergeSeries(
            listOf(
                Series("p1:m", "p1", "m", longArrayOf(5)),
                Series("p1:m", "p1", "m", longArrayOf(1, 2, 3))
            )
        )
        assertEquals(1, merged.size)
        // the short one is the most recent second: 3 + 5 at the tail
        assertArrayEquals(longArrayOf(1, 2, 8), merged[0].perSecond, 0L)
    }

    @Test
    fun mergeCapsStacksWithoutLosingTheAggregate() {
        val many = (1..12).map { Series("p$it:m", "p$it", "m", longArrayOf(it.toLong(), 0)) }
        val merged = TokenRateView.mergeSeries(many, max = 4)
        assertEquals(4, merged.size)
        // the loudest four are kept, in total order
        assertEquals(listOf("p12", "p11", "p10", "p9"), merged.map { it.key })
        // and the dropped tail is folded into the last band, not lost: the
        // per-second totals are identical to the un-capped input
        assertArrayEquals(TokenRateView.stackTotals(many), TokenRateView.stackTotals(merged), 0L)
        // bucket 0: 12+11+10+9 = 42 kept plus 1..8 = 36 folded in
        assertEquals(78L, TokenRateView.stackTotals(merged)[0])
    }

    @Test
    fun anEmptyFrameMergesToNothing() {
        assertEquals(0, TokenRateView.mergeSeries(null).size)
        assertEquals(0, TokenRateView.mergeSeries(emptyList()).size)
    }

    @Test
    fun moreSilentSeriesThanTheStackCapIsStillACappedFrame() {
        // A host that registers many models before any of them has served a
        // token: the cap must not fall over an all-zero ranking, and the
        // frame has to stay a legal (empty) one.
        val silent = (1..TokenRateView.MAX_STACKS + 4).map {
            Series("p$it:m", "p$it", "m", longArrayOf(0, 0))
        }
        val merged = TokenRateView.mergeSeries(silent)
        assertEquals(TokenRateView.MAX_STACKS, merged.size)
        assertArrayEquals(longArrayOf(0L, 0L), TokenRateView.stackTotals(merged), 0L)
        assertEquals(1L, TokenRateView.peakOfTotals(TokenRateView.stackTotals(merged)))
        // nothing is named, so the legend is empty and the chart draws the
        // empty grid
        assertEquals(0, TokenRateView.legendOrder(merged).size)
        assertEquals(emptyList<String>(), TokenRateView.legendLines(merged))
    }

    @Test
    fun theFramePipelineTheViewRunsAddsUpEndToEnd() {
        // merge -> align -> total -> scale, exactly as setSeries does.
        val input = listOf(
            Series("p1:gpt", "openai", "gpt", longArrayOf(40, 0, 10)),
            Series("p2:claude", "anthropic", "claude", longArrayOf(0, 20, 5))
        )
        val merged = TokenRateView.mergeSeries(input)
        val bands = TokenRateView.bandWindows(merged, 3)
        assertEquals(2, bands.size)
        val totals = TokenRateView.stackTotals(merged)
        assertArrayEquals(longArrayOf(40, 20, 15), totals, 0L)
        assertEquals(40L, TokenRateView.peakOfTotals(totals))
        // every band is the frame width, so the draw loop can index [i]
        for (band in bands) assertEquals(3, band.size)
    }

    // ---- frame publication: what the view actually shows ----

    @Test
    fun aSnappedFrameIsExactlyTheIncomingTotals() {
        val totals = longArrayOf(0, 25, 50, 100)
        val peak = TokenRateView.peakOfTotals(totals)
        val next = FloatArray(totals.size) { totals[it].toFloat() / peak.toFloat() }
        val shown = ArrayList<Float>()
        TokenRateView.updateShownFrame(shown, next)
        assertEquals(4, shown.size)
        // snap = true: the displayed frame *is* the incoming frame, so the
        // reference line (max fraction x the committed scale) is on the bar
        assertEquals(1f, shown[3], 1e-6f)
        assertEquals(0.5f, shown[2], 1e-6f)
        assertEquals(0f, shown[0], 1e-6f)
    }

    @Test
    fun publishingAFrameReplacesTheOldOne() {
        val shown = ArrayList<Float>()
        TokenRateView.updateShownFrame(shown, floatArrayOf(1f, 1f))
        TokenRateView.updateShownFrame(shown, floatArrayOf(0.5f))
        assertEquals(1, shown.size) // no residue from the previous frame
        assertEquals(0.5f, shown[0], 1e-6f)
    }

    @Test
    fun anUnchangedShapeNeedsNoEase() {
        val totals = longArrayOf(0, 50, 100)
        val peak = TokenRateView.peakOfTotals(totals)
        val next = FloatArray(3) { totals[it].toFloat() / peak.toFloat() }
        val shown = ArrayList<Float>()
        TokenRateView.updateShownFrame(shown, next)
        // next poll, same numbers, same size: nothing to tween, so the axis
        // may commit immediately
        assertFalse(TokenRateView.frameMoved(shown.toFloatArray(), next))
        // a real change does need the ease
        assertTrue(TokenRateView.frameMoved(shown.toFloatArray(), floatArrayOf(1f, 1f, 1f)))
        // and so does a change of shape (a panned window of another length)
        assertTrue(TokenRateView.frameMoved(shown.toFloatArray(), floatArrayOf(1f)))
    }

    @Test
    fun growthAndShrinkBothEaseButAnInvisibleNudgeDoesNot() {
        val from = floatArrayOf(0.5f, 1f)
        // a visible change either way animates: a shrinking bar has to ease
        // down exactly as a growing one eases up
        assertTrue(TokenRateView.frameMoved(from, floatArrayOf(0.5f, 0.8f)))
        assertTrue(TokenRateView.frameMoved(from, floatArrayOf(0.5f, 1f - 0.3f)))
        // a nudge far below a tenth of a pixel does not: a poll that only
        // shaves the frame must not restart the ease over and over
        assertFalse(TokenRateView.frameMoved(from, floatArrayOf(0.5f, 1f + 0.0005f)))
        assertFalse(TokenRateView.frameMoved(from, from))
    }
}
