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
 *   decelerate) instead of jumping on every 2s poll. While a pan
 *   gesture is active ([isInteracting]), the host must not push new
 *   frames — they would snap the bars out from under the finger.
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
    }

    /** Seconds behind live; 0 follows the edge. Survives data refreshes. */
    var offsetSec: Long = 0L
        private set

    /** True between touch-down and release: host should not push new frames. */
    var isInteracting: Boolean = false
        private set

    private var shown: FloatArray = FloatArray(0) // eased 0..1 heights
    private var target: FloatArray = FloatArray(0)
    private var animator: ValueAnimator? = null
    /** Absolute scale of the current frame (tokens/s at full height). */
    private var lastMax: Long = 1L

    private var downX = 0f
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
     * New trailing samples (oldest-first, [WINDOW_SECS] long). Heights
     * ease from current to new over 350ms instead of snapping.
     */
    fun setSamples(values: List<Long>) {
        lastMax = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val max = lastMax.toFloat()
        val next = FloatArray(values.size) { i -> (values[i].toFloat() / max).coerceIn(0f, 1f) }
        val from = when {
            shown.size == next.size -> shown.copyOf()
            shown.size > next.size -> shown.takeLast(next.size).toFloatArray()
            else -> FloatArray(next.size - shown.size) { 0f } + shown
        }
        target = next
        animator?.cancel()
        if (from.contentEquals(next) || !isAttachedToWindow) {
            shown = next
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                shown = FloatArray(next.size) { i -> from[i] + (next[i] - from[i]) * t }
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downOffset = offsetSec
                downMs = System.currentTimeMillis()
                dragging = false
                isInteracting = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!dragging && kotlin.math.abs(dx) > 8 * resources.displayMetrics.density) {
                    dragging = true
                }
                if (dragging) {
                    val pxPerSec = (width - gutterPx()) / WINDOW_SECS.toFloat()
                    offsetSec = (downOffset - (dx / pxPerSec).toLong())
                        .coerceIn(0, MAX_BACK_SECS - WINDOW_SECS.toLong())
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val tap = !dragging && System.currentTimeMillis() - downMs < 300
                dragging = false
                isInteracting = false
                if (tap && event.action == MotionEvent.ACTION_UP) {
                    offsetSec = 0 // snap back to live
                    invalidate()
                }
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
