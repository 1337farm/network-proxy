package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the rate chart's drag logic.
 *
 * Everything asserted here is the pure companion surface of
 * [TokenRateView]: the pan-start gesture decision, the single-timestamp
 * window anchor, and the frame math that [TokenRateView.setSamples]
 * runs. TokenRateView itself extends android.view.View and the module
 * has no Robolectric, so the touch pipeline, the ValueAnimator and the
 * parent-intercept plumbing are *not* reachable from a JVM unit test —
 * those need an instrumentation test on a device (see notes in the fix
 * report); there is no honest way to fake an animator here.
 *
 * panOffset is deliberately not re-tested here: it is already covered by
 * the RateChartPanTest class that lives in BackupFormatTest.kt.
 */
class RateChartDragTest {

    // ---- beginPan: gesture start decision ----

    @Test
    fun moveInsideSlopDoesNotStartPan() {
        assertFalse(TokenRateView.beginPan(4f, 0f, 8f))
        assertFalse(TokenRateView.beginPan(8f, 0f, 8f)) // exactly slop: not past it
    }

    @Test
    fun horizontalMovePastSlopStartsPan() {
        assertTrue(TokenRateView.beginPan(9f, 0f, 8f))
        assertTrue(TokenRateView.beginPan(9f, 2f, 8f))
        assertTrue(TokenRateView.beginPan(-9f, -2f, 8f)) // both axes flipped
    }

    @Test
    fun verticalDragNeverBecomesAPan() {
        // The whole point of the direction test: an enclosing ScrollView
        // must stay free to scroll a vertical swipe over the chart.
        assertFalse(TokenRateView.beginPan(10f, 30f, 8f))
        assertFalse(TokenRateView.beginPan(9f, 9f, 8f)) // 45 degrees: not horizontal enough
        assertFalse(TokenRateView.beginPan(0f, 200f, 8f))
    }

    // ---- windowEndMs: one timestamp per frame ----

    @Test
    fun windowEndIsNowMinusOffset() {
        assertEquals(1_000_000L, TokenRateView.windowEndMs(1_000_000L, 0L))
        assertEquals(999_000L, TokenRateView.windowEndMs(1_000_000L, 1L))
        assertEquals(640_000L, TokenRateView.windowEndMs(1_000_000L, 360L))
    }

    @Test
    fun windowEndIsStableForAGivenNowAndOffset() {
        // The mismatch this closes: a drag frame and the next poll frame
        // were each recomputing nowMs independently and could straddle a
        // second boundary. One nowMs in, one endMs out.
        val now = 1_700_000_123_456L
        assertEquals(TokenRateView.windowEndMs(now, 42L), TokenRateView.windowEndMs(now, 42L))
        assertEquals(now - 42_000L, TokenRateView.windowEndMs(now, 42L))
    }

    // ---- frame math: what setSamples actually shows ----

    @Test
    fun peakIsNeverZero() {
        assertEquals(1L, TokenRateView.peakOf(emptyList()))
        assertEquals(1L, TokenRateView.peakOf(listOf(0L, 0L)))
        assertEquals(7L, TokenRateView.peakOf(listOf(0L, 7L, 3L)))
    }

    @Test
    fun snappedFrameEqualsTheIncomingValues() {
        // snap = true assigns normalize(...) straight to `shown` with no
        // animator in the path, so this is the dragged frame verbatim.
        val values = listOf(0L, 25L, 50L, 100L)
        val frame = TokenRateView.normalize(values, TokenRateView.peakOf(values))
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.5f, 1f), frame, 1e-6f)
    }

    @Test
    fun normalizeClampsToUnitHeight() {
        val frame = TokenRateView.normalize(listOf(-5L, 0L, 9L, 99L), 10L)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.9f, 1f), frame, 1e-6f)
    }

    @Test
    fun fitFrameAlignsShapeChanges() {
        val three = floatArrayOf(1f, 2f, 3f)
        val five = floatArrayOf(0f, 0f, 1f, 2f, 3f)
        // grows: new buckets ease in from zero at the front
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 2f, 3f), TokenRateView.fitFrame(three, five), 1e-6f)
        // shrinks: keep the trailing overlap
        assertArrayEquals(floatArrayOf(2f, 3f), TokenRateView.fitFrame(three, floatArrayOf(9f, 9f)), 1e-6f)
        // same size: a copy, so the ease can tween without aliasing `shown`
        val same = TokenRateView.fitFrame(three, floatArrayOf(4f, 5f, 6f))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), same, 1e-6f)
        three[0] = 99f
        assertEquals(1f, same[0], 1e-6f) // aliasing would have shown 99 here
    }

    @Test
    fun blendIsExactAtBothEndsOfTheEase() {
        val from = floatArrayOf(0f, 1f)
        val to = floatArrayOf(1f, 0f)
        assertArrayEquals(from, TokenRateView.blend(from, to, 0f), 1e-6f)
        assertArrayEquals(to, TokenRateView.blend(from, to, 1f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), TokenRateView.blend(from, to, 0.5f), 1e-6f)
    }

    @Test
    fun identicalShapeNeedsNoEase() {
        // The `shapeChanged` half of setSamples' no-animator branch: when
        // the eased frame already equals the target there is nothing to
        // tween, so the scale commits immediately (as it also does for
        // snap and for a detached view).
        val next = TokenRateView.normalize(listOf(0L, 50L, 100L), 100L)
        assertTrue(TokenRateView.fitFrame(next, next).contentEquals(next))
    }
}
