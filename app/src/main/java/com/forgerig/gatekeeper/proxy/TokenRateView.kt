package com.forgerig.gatekeeper.proxy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Vertical bar chart of per-second output tokens (trailing window).
 * Zero dependencies: bars drawn on canvas, scaled to the visible max
 * (minimum scale 1 so a lone token still reads). Faint gridlines at
 * quartiles + a peak label make values readable; idle seconds render
 * as track ticks, so gaps in the stream are visible, not collapsed.
 */
class TokenRateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var samples: List<Long> = emptyList()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
    }

    init {
        // Theme colors resolved lazily in onDraw (context fully attached).
        minimumHeight = (96 * resources.displayMetrics.density).toInt()
    }

    fun setSamples(values: List<Long>) {
        samples = values.takeLast(ProxyMetrics.RATE_CHART_SECS)
        invalidate()
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
        // reserve a label gutter at the top for the peak readout
        val gutter = 14f * d
        val plotH = (h - gutter).coerceAtLeast(1f)
        val base = h - 2f
        val n = samples.size
        val max = (samples.maxOrNull() ?: 0L).coerceAtLeast(1L)
        // peak label (top-left) + faint quartile gridlines with values
        canvas.drawText("peak ${StatsFormat.humanTokens(max)}/s", 0f, 10f * d, labelPaint)
        for (q in 1..3) {
            val frac = q / 4f
            val y = base - frac * (plotH - 2f)
            canvas.drawRect(0f, y, w, y + 1f, gridPaint)
            val v = (max * frac).toLong()
            val label = StatsFormat.humanTokens(v)
            val lw = labelPaint.measureText(label)
            canvas.drawText(label, (w - lw).coerceAtLeast(0f), y - 2f * d, labelPaint)
        }
        if (n == 0) {
            canvas.drawRect(0f, base, w, h, trackPaint)
            return
        }
        val gap = (2 * d).coerceAtLeast(1f)
        val slot = w / n
        val bw = (slot - gap).coerceAtLeast(1f)
        // baseline
        canvas.drawRect(0f, base, w, h, gridPaint)
        for (i in samples.indices) {
            val frac = samples[i].toFloat() / max.toFloat()
            val bh = frac * (plotH - 2f)
            val x0 = i * slot + gap / 2f
            if (bh <= 0f) {
                // idle second: short track tick instead of nothing
                canvas.drawRect(x0, base - 2f, x0 + bw, base, trackPaint)
            } else {
                canvas.drawRect(x0, base - bh, x0 + bw, base, barPaint)
            }
        }
    }
}
