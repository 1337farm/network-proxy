package com.forgerig.gatekeeper.proxy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * Live tok/s readout for the status bar, drawn as the notification's small
 * icon so it sits next to wifi/cellular.
 *
 * A small icon is rendered by the system as an alpha mask (colour is
 * ignored), so white text on a transparent bitmap is the correct way to get
 * readable glyphs. The bitmap is tiny and re-posted only when the value
 * actually changes, so this stays cheap.
 */
object StatusBarIcon {
    /** Compact label for [tps]: "0", "12", "1.2k". Pure (unit-tested). */
    fun label(tps: Double): String = when {
        tps <= 0.0 -> "0"
        tps < 10 -> String.format(java.util.Locale.US, "%.1f", tps)
        tps < 100 -> String.format(java.util.Locale.US, "%.0f", tps)
        tps < 1000 -> String.format(java.util.Locale.US, "%.0f", tps)
        else -> String.format(java.util.Locale.US, "%.1fk", tps / 1000.0)
    }

    fun render(label: String, sizePx: Int): IconCompat {
        val size = sizePx.coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            // Leave room for descenders/q descender so "1.2k" is not clipped.
            textSize = size * 0.68f
        }
        val fm = paint.fontMetrics
        val baseline = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, size / 2f, baseline, paint)
        return IconCompat.createWithBitmap(bmp)
    }
}
