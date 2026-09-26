package com.forgerig.gatekeeper.proxy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Scrollable vertical bar chart of per-second output tokens.
 *
 * - Window: [WINDOW_SECS] buckets ending [offsetSec] seconds ago
 *   (0 = live edge). Drag horizontally to pan up to an hour back;
 *   tap to snap back to live. A LIVE / −MmSs chip shows the position.
 * - Buttery motion: bar heights ease toward new data (350ms
 *   decelerate) instead of jumping on every 2s poll. Pan frames are
 *   snapped (no animator) so a continuous drag tracks the finger
 *   instead of restarting the ease on every pixel. While a pan gesture
 *   is active ([isInteracting]), the host must not push new frames —
 *   they would snap the bars out from under the finger.
 * - Readable labels: values live in a reserved right gutter that bars
 *   never enter; faint quartile gridlines + peak readout on top.
 */
class TokenRateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val WINDOW_SECS = 120
        const val MAX_BACK_SECS = 3600

        /**
         * Pan mapping, pure so the direction is unit-tested: dragging right
         * moves the window *back* in time (the content follows the finger),
         * dragging left returns toward live. Clamped so the window always
         * fits inside the retained hour.
         */
        @JvmStatic
        fun panOffset(downOffset: Long, dxPx: Float, pxPerSec: Float): Long {
            val pps = if (pxPerSec < 1f) 1f else pxPerSec
            val maxBack = (MAX_BACK_SECS - WINDOW_SECS).toLong()
            return (downOffset + (dxPx / pps).toLong()).coerceIn(0L, maxBack)
        }

        /**
         * Should this touch be treated as the start of a pan? Past the
         * [slopPx] dead zone *and* horizontally dominant.
         *
         * The direction test is the important half: a vertical swipe
         * (|dy| >= |dx|) never becomes a pan, so the chart never claims
         * the gesture and an enclosing ScrollView stays free to scroll
         * the page instead of the drag being silently eaten. Pure so the
         * decision is unit-testable without a device.
         */
        @JvmStatic
        fun beginPan(dx: Float, dy: Float, slopPx: Float): Boolean {
            val adx = if (dx < 0) -dx else dx
            val ady = if (dy < 0) -dy else dy
            return adx > slopPx && adx > ady
        }

        /**
         * The single wall-clock instant a frame is anchored to: the
         * window's last bucket ends here. One frame is computed from one
         * timestamp — the drag frame and the poll that follows it can no
         * longer straddle a second boundary and show two different windows.
         */
        @JvmStatic
        fun windowEndMs(nowMs: Long, offsetSec: Long): Long = nowMs - offsetSec * 1000

        /** Axis peak for [values]; never zero, so the scale cannot divide by 0. */
        @JvmStatic
        fun peakOf(values: List<Long>): Long = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)

        /**
         * Values to 0..1 heights against [peak]. This *is* the frame the
         * view shows, so a snapped frame is exactly the incoming values.
         */
        @JvmStatic
        fun normalize(values: List<Long>, peak: Long): FloatArray {
            val max = peak.toFloat()
            return FloatArray(values.size) { i -> (values[i].toFloat() / max).coerceIn(0f, 1f) }
        }

        /**
         * The old frame resized to [next]'s length so a shape change can
         * be eased rather than popping: overlapping tail kept, any new
         * buckets growing in from zero.
         */
        @JvmStatic
        fun fitFrame(shown: FloatArray, next: FloatArray): FloatArray = when {
            shown.size == next.size -> shown.copyOf()
            shown.size > next.size -> shown.takeLast(next.size).toFloatArray()
            else -> FloatArray(next.size - shown.size) { 0f } + shown
        }

        /** Eased frame at [t] in 0..1 between [from] and [to]. */
        @JvmStatic
        fun blend(from: FloatArray, to: FloatArray, t: Float): FloatArray =
            FloatArray(to.size) { i -> from[i] + (to[i] - from[i]) * t }
    }

    /** Seconds behind live; 0 follows the edge. Survives data refreshes. */
    var offsetSec: Long = 0L
        private set

    /** True between touch-down and release: host should not push new frames. */
    var isInteracting: Boolean = false
        private set

    /**
     * Pulls the window ending at [endMs] for [offsetSec] seconds back.
     * The view calls this itself whenever the offset changes, so panning
     * redraws with the data for the window you are actually looking at
     * instead of the stale frame the host last pushed (which left the
     * chart frozen mid-drag and only caught up on the next 2s poll after
     * release).
     *
     * [endMs] is passed in rather than recomputed here so a frame and its
     * axis labels are derived from one timestamp.
     */
    var sampleProvider: ((offsetSec: Long, endMs: Long) -> List<Long>)? = null

    private var shown: FloatArray = FloatArray(0) // eased 0..1 heights
    private var animator: ValueAnimator? = null
    /** Absolute scale of the displayed frame (tokens/s at full height). */
    private var lastMax: Long = 1L
    /**
     * Scale the in-flight ease is heading for. Held out of [lastMax]
     * until the ease lands so the peak readout and quartile labels
     * travel *with* the bars instead of jumping to the new window while
     * the bars are still mid-blend.
     */
    private var pendingMax: Long = 1L

    private var downX = 0f
    private var downY = 0f
    private var downOffset = 0L
    private var downMs = 0L
    private var dragging = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        minimumHeight = (110 * resources.displayMetrics.density).toInt()
        isClickable = true
    }

    /**
     * New trailing samples (oldest-first, [WINDOW_SECS] long).
     *
     * [snap] picks the frame straight in with no animator. Panning
     * ([refreshWindow]) passes true: a continuous drag re-queries on
     * every offset change, so a 350ms ease would be cancelled every few
     * ms, never complete, and leave the bars permanently mid-blend and
     * lagging the finger. The host poll passes false (the default) and
     * keeps the 350ms decelerate so bars settle into new data rather than
     * jumping on every 2s tick.
     */
    fun setSamples(values: List<Long>, snap: Boolean = false) {
        val peak = peakOf(values)
        val next = normalize(values, peak)
        val from = fitFrame(shown, next)
        animator?.cancel()
        val shapeChanged = !from.contentEquals(next)
        // No animator is scheduled when snapping, when detached, or when
        // the shape is unchanged — so in all three the displayed frame
        // already *is* the new frame and the axis may commit now.
        if (snap || !isAttachedToWindow || !shapeChanged) {
            shown = next
            lastMax = peak
            pendingMax = peak
            invalidate()
            return
        }
        pendingMax = peak
        var completed = false
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                shown = blend(from, next, a.animatedValue as Float)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Only a completed ease promotes the new scale; a
                    // cancel means a newer frame superseded this one.
                    if (completed) {
                        shown = next
                        lastMax = pendingMax
                        invalidate()
                    }
                }

                override fun onAnimationCancel(a: android.animation.Animator) {
                    completed = false
                }
            })
            // Before start(): start() dispatches on a later frame, so the
            // flag is already observable by the time any callback runs.
            completed = true
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Re-query the current window and redraw. Called on touch-down, on
     * every offset change (drag, snap-back) and on gesture end, so the
     * graph is never showing data from the wrong time range — and the
     * first frame of a gesture is never the one the last poll left behind.
     */
    private fun refreshWindow() {
        val p = sampleProvider ?: return
        // One timestamp, used for the fetch *and* passed down, so the
        // frame cannot straddle a second boundary relative to the next
        // poll's frame.
        val endMs = windowEndMs(System.currentTimeMillis(), offsetSec)
        setSamples(p(offsetSec, endMs), snap = true)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downOffset = offsetSec
                downMs = System.currentTimeMillis()
                dragging = false
                isInteracting = true
                // Anchor the gesture on the window it actually started
                // on, not on whatever the last poll pushed.
                refreshWindow()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && beginPan(dx, dy, 8 * resources.displayMetrics.density)) {
                    dragging = true
                    // Claim the gesture only now that it is a horizontal
                    // pan. Disallowing on DOWN would starve an enclosing
                    // ScrollView of vertical drags over the chart.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    val pxPerSec = ((width - gutterPx()) / WINDOW_SECS.toFloat())
                        .coerceAtLeast(1f)
                    val next = panOffset(downOffset, dx, pxPerSec)
                    if (next != offsetSec) {
                        offsetSec = next
                        // Immediate: fetch the window now rather than waiting
                        // for the next poll, so the bars under the finger are
                        // the ones being panned to.
                        refreshWindow()
                    }
                }
                return true
            }
            // Every gesture end (including multi-touch pointer lifts and
            // cancel) releases the frame gate — otherwise a second finger
            // lifting first would strand isInteracting=true and freeze
            // chart updates. Only a clean single-tap snaps back to live.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                // Hand the gesture back so the parent can scroll again
                // (and so a ScrollView that stole a vertical drag sees a
                // consistent disallow flag on the next one).
                parent?.requestDisallowInterceptTouchEvent(false)
                val tap = !dragging && System.currentTimeMillis() - downMs < 300 &&
                    event.actionMasked == MotionEvent.ACTION_UP
                dragging = false
                isInteracting = false
                if (tap) {
                    offsetSec = 0 // snap back to live
                    invalidate()
                }
                // Settle on real data for wherever the gesture ended.
                refreshWindow()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun gutterPx(): Float {
        labelPaint.textSize = 10f * resources.displayMetrics.density
        labelPaint.typeface = android.graphics.Typeface.MONOSPACE
        return labelPaint.measureText("0000/s") + 8 * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = resources
        val d = res.displayMetrics.density
        barPaint.color = res.getColor(R.color.status_running, context.theme)
        trackPaint.color = res.getColor(R.color.outline, context.theme)
        gridPaint.color = res.getColor(R.color.outline_variant, context.theme)
        labelPaint.color = res.getColor(R.color.hint_text, context.theme)
        labelPaint.textSize = 10f * d
        labelPaint.typeface = android.graphics.Typeface.MONOSPACE
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val gutter = gutterPx()
        val plotW = (w - gutter).coerceAtLeast(1f)
        val labelTop = 12f * d
        val base = h - 2f
        val plotH = (base - labelTop).coerceAtLeast(1f)
        val n = shown.size
        // peak readout (top-left, above the plot so bars never touch it)
        canvas.drawText(
            "peak ${StatsFormat.humanTokens(lastMax)}/s",
            0f, 10f * d, labelPaint
        )
        // live/back chip (top-right, inside the gutter column)
        val chip = if (offsetSec <= 0) "LIVE" else "−${offsetSec / 60}m${offsetSec % 60}s"
        val cw = labelPaint.measureText(chip)
        canvas.drawText(chip, (w - cw).coerceAtLeast(0f), 10f * d, labelPaint)
        // quartile gridlines + values parked in the gutter
        for (q in 1..3) {
            val frac = q / 4f
            val y = base - frac * (plotH - 2f)
            canvas.drawRect(0f, y, plotW, y + 1f, gridPaint)
            val label = StatsFormat.humanTokens((lastMax * frac).toLong())
            canvas.drawText(label, plotW + 4f * d, y + 3f * d, labelPaint)
        }
        if (n == 0) {
            canvas.drawRect(0f, base, plotW, base + 2f, trackPaint)
            return
        }
        val gap = (2 * d).coerceAtLeast(1f)
        val slot = plotW / n
        val bw = (slot - gap).coerceAtLeast(1f)
        canvas.drawRect(0f, base, plotW, base + 2f, gridPaint)
        for (i in shown.indices) {
            val bh = shown[i] * (plotH - 2f)
            val x0 = i * slot + gap / 2f
            if (bh <= 0f) {
                canvas.drawRect(x0, base - 2f, x0 + bw, base, trackPaint)
            } else {
                canvas.drawRect(x0, base - bh, x0 + bw, base, barPaint)
            }
        }
    }
}
