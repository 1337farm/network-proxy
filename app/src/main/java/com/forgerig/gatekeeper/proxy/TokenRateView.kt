package com.forgerig.gatekeeper.proxy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Scrollable stacked bar chart of per-second output tokens.
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
 * - Stacked: [setSeries] renders one bar per second built from every
 *   named series (provider · model). Segment tones come from a stable
 *   hash of the series key, so a model keeps its colour across polls and
 *   while panning even though the host's list order follows the totals.
 *   Segments are separated by a 1px divider and the base segment is
 *   brightened, so a stack never reads as one solid block. A peak
 *   reference line is drawn at the tallest bar with its value parked in
 *   the gutter, and a legend in a band the bars are lifted clear of
 *   names the top [LEGEND_MAX] stacks plus an "+N more" note.
 *
 * Everything that is not drawing, touch or the frame animation is pure
 * companion math ([stackTotals], [peakOfTotals], [yFor], [bandY],
 * [peakLabelY], [seriesTotal], [legendOrder], [tailCount], [legendLines],
 * [colorIndexFor], [bandWindows], [mergeSeries], [frameMoved],
 * [updateShownFrame]) so the geometry is unit-testable on the JVM without
 * a device.
 */
class TokenRateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * One named contribution to a bar: the tokens/second [provider] served
     * for [model] in each of the [WINDOW_SECS] buckets (oldest-first).
     *
     * Declared here rather than in the metrics layer so the chart has no
     * dependency on the host's session data model — the host maps whatever
     * it holds onto this. [key] identifies the series for colour purposes
     * and only has to be stable for a given (provider, model) pair.
     */
    data class Series(
        val key: String,
        val provider: String,
        val model: String,
        val perSecond: LongArray
    ) {
        /** Value class, so two equal series stay equal. */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Series) return false
            return key == other.key &&
                provider == other.provider &&
                model == other.model &&
                perSecond.contentEquals(other.perSecond)
        }

        override fun hashCode(): Int {
            var h = key.hashCode()
            h = 31 * h + provider.hashCode()
            h = 31 * h + model.hashCode()
            h = 31 * h + perSecond.contentHashCode()
            return h
        }
    }

    companion object {
        const val WINDOW_SECS = 120
        const val MAX_BACK_SECS = 3600

        /** Stacks named in the legend, in stack order, before the tail folds. */
        const val LEGEND_MAX = 4

        /**
         * Stacks drawn as individual segments. Anything past this (after
         * same-key merging) is summed into the bottom tail band: the bar
         * height still equals the aggregate, but a long tail of models
         * cannot turn one bar into a hundred hairline stripes.
         */
        const val MAX_STACKS = 8

        /** Distinct series tones. */
        const val PALETTE_SIZE = 8

        /** Baseline nudge of a gutter label off the gridline it belongs to. */
        const val LABEL_BASELINE = 3f

        /** Two gutter labels closer than this (px) would touch. */
        const val LABEL_TOL = 10f

        /** Peak line this close to a gridline reads as *being* that line. */
        const val COINCIDE_TOL = 2f

        /**
         * Peak-label search scoring: sitting below the line costs half a
         * pixel of distance, so above wins a tie. [peakLabelY] adds a
         * penalty larger than the whole plot to any overlapping slot,
         * which is what makes "a free slot always wins" provable rather
         * than merely likely.
         */
        const val SIDE_COST = 0.5f

        /**
         * Frame change below which an ease is not worth restarting, as a
         * fraction of the plot height (0.0015 is about a tenth of a pixel
         * on a 66dp chart). Keeps a poll that only nudges a bar from
         * cancelling the previous ease over and over.
         */
        const val MOVE_EPS_FRAC = 0.0015f

        /** Key of the single unnamed series that [setSamples] wraps itself in. */
        const val LEGACY_KEY = "legacy:aggregate"

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

        // ---- stacked geometry ----

        /**
         * Per-second aggregate of [series]: bucket *i* is the sum of every
         * series' value there, so a stacked bar's height is the total.
         *
         * Deliberately un-clamped pure accumulation — the ceiling belongs
         * to [normalize]/[yFor] so the axis, not the data, owns it. Ragged
         * [Series.perSecond] arrays are tolerated: the width is the longest
         * one and a shorter series counts as zero past its end.
         */
        @JvmStatic
        fun stackTotals(series: List<Series>): LongArray {
            var n = 0
            for (s in series) if (s.perSecond.size > n) n = s.perSecond.size
            val out = LongArray(n)
            for (s in series) {
                val p = s.perSecond
                for (i in p.indices) out[i] += p[i]
            }
            return out
        }

        /** Axis peak of a stacked frame; never zero, so the scale can't divide by 0. */
        @JvmStatic
        fun peakOfTotals(totals: LongArray): Long {
            var m = 0L
            for (v in totals) if (v > m) m = v
            return if (m < 1L) 1L else m
        }

        /**
         * Screen y of a value: the bottom edge [base] minus [value]/[max] of
         * the plot [span] (the usable plot height in px, i.e. the draw
         * path's `plotSpan`). The single place the value scale becomes
         * pixels: the quartile gridlines, the bars and the peak reference
         * line all go through it, so the axis cannot disagree with the
         * bars. Clamped into the plot, so no value can draw outside it.
         */
        @JvmStatic
        fun yFor(value: Long, max: Long, base: Float, span: Float): Float {
            val m = if (max < 1L) 1L else max
            val h = span.coerceAtLeast(0f)
            val y = base - (value.toFloat() / m.toFloat()) * h
            val hi = base - h
            return if (y < hi) hi else if (y > base) base else y
        }

        /**
         * Baseline of the gutter label for quartile [q] (1-based), and the
         * single source of truth the gridlines are drawn from too.
         * [LABEL_BASELINE] nudges the text off its own line.
         */
        @JvmStatic
        fun bandY(q: Int, base: Float, span: Float): Float =
            base - (q / 4f) * span.coerceAtLeast(0f) + LABEL_BASELINE

        /** Would a label whose baseline is [y] sit on a quartile label? */
        @JvmStatic
        fun labelCollision(y: Float, base: Float, span: Float): Boolean {
            for (q in 1..3) {
                if (abs(bandY(q, base, span) - y) <= LABEL_TOL) return true
            }
            return false
        }

        /**
         * Should the peak line carry a number at all?
         *
         * No when the line lands *on* a quartile gridline: that line's own
         * gutter label already reads the very same value, so a second
         * number there is pure duplication. The tolerance is deliberately
         * tight ([COINCIDE_TOL]) — a line merely near a quartile still gets
         * its own number, moved out of the way rather than dropped, because
         * near-but-different values would otherwise be misread as equal.
         */
        @JvmStatic
        fun peakLabelShown(valueY: Float, base: Float, span: Float): Boolean {
            for (q in 1..3) {
                if (abs(bandY(q, base, span) - valueY) <= COINCIDE_TOL) return false
            }
            return true
        }

        /**
         * Baseline for the peak reference label: the position closest to
         * the line that does not sit on a gutter label. Above the line
         * wins over below at equal distance (a number above its line reads
         * most naturally, and the first peak seen is at the top of the
         * plot), and the line's own y is preferred over both, so a peak
         * boxed in by the quartile labels keeps its number right on the
         * line it marks — which is what the backing box is for. [topPad]
         * is the reserve kept below the plot top (one text ascent) so a
         * label can never reach the "peak 312/s" readout or the LIVE chip
         * in the header.
         *
         * A 1px search over the plot: tens of iterations, once per frame,
         * and it cannot return a slot that overlaps a label while a free
         * one exists.
         */
        @JvmStatic
        fun peakLabelY(valueY: Float, base: Float, span: Float, topPad: Float = 0f): Float {
            val floor = base - span.coerceAtLeast(0f) + topPad
            // Plot too short to hold the header band plus a label (a very
            // squashed view): nothing can be placed, so the caller keeps
            // the number on the line itself and clamps it to the view.
            if (floor >= base) return valueY
            val collideCost = (base - floor) + SIDE_COST + 1f
            var bestY = -1f
            var bestScore = Float.MAX_VALUE
            var y = ceil(floor)
            while (y <= base) {
                val d = abs(y - valueY)
                val score = when {
                    labelCollision(y, base, span) -> collideCost + d
                    y <= valueY -> d
                    else -> SIDE_COST + d
                }
                if (score < bestScore) {
                    bestScore = score
                    bestY = y
                }
                y += 1f
            }
            // No candidate at all (a NaN span, say): hand the line's own
            // position back and let the caller clamp it to the view.
            return if (bestY < 0f) valueY else bestY
        }

        /**
         * Tokens a series contributed over the window, negatives counted as
         * zero — the ranking key for the legend.
         */
        @JvmStatic
        fun seriesTotal(s: Series): Long {
            var sum = 0L
            for (v in s.perSecond) if (v > 0L) sum += v
            return sum
        }

        /**
         * Indices of the series drawn as their own stack and rendered in
         * the legend: biggest [seriesTotal] first, ties keeping input order
         * so the chart is stable frame to frame, at most [max] entries.
         * Everything past the cap is still drawn (it is folded into the
         * tail band) but loses its legend entry; a zero contributor is
         * neither drawn nor listed.
         */
        @JvmStatic
        fun legendOrder(series: List<Series>, max: Int = LEGEND_MAX): IntArray {
            val order = series.indices.sortedWith(
                compareByDescending<Int> { seriesTotal(series[it]) }.thenBy { it }
            )
            val out = IntArray(max.coerceAtLeast(0))
            var c = 0
            for (i in order) {
                if (c >= out.size) break
                if (seriesTotal(series[i]) > 0L) out[c++] = i
            }
            return if (c == out.size) out else out.copyOf(c)
        }

        /** How many contributing series are folded into the tail, not named. */
        @JvmStatic
        fun tailCount(series: List<Series>, max: Int = LEGEND_MAX): Int {
            val named = legendOrder(series, max).size
            var contributing = 0
            for (s in series) if (seriesTotal(s) > 0L) contributing++
            return (contributing - named).coerceAtLeast(0)
        }

        /** "provider · model", or just the model when the provider is unknown. */
        @JvmStatic
        fun legendLabel(s: Series): String {
            return if (s.provider.isBlank()) s.model else "${s.provider} · ${s.model}"
        }

        /**
         * Legend rows, bottom-left upwards: the capped stacks, then a
         * single "+N more" row for the folded tail. The caller stacks these
         * bottom-up; the cap is the only width bound, so a row can never
         * grow past the cap of entries and nothing is ever truncated.
         */
        @JvmStatic
        fun legendLines(series: List<Series>, max: Int = LEGEND_MAX): List<String> {
            val out = ArrayList<String>(max.coerceAtLeast(0) + 1)
            for (i in legendOrder(series, max)) out.add(legendLabel(series[i]))
            val tail = tailCount(series, max)
            if (tail > 0) out.add("+$tail more")
            return out
        }

        /**
         * Stable tone index in 0 until [PALETTE_SIZE] for [key].
         *
         * String.hashCode() is fixed by the JLS, so the same key lands on
         * the same tone on every device and every run — unlike an
         * index-in-list assignment, which reshuffles the whole chart every
         * time the totals reorder the host's series list. Empty keys get
         * 0 (the primary tone).
         */
        @JvmStatic
        fun colorIndexFor(key: String?): Int {
            if (key.isNullOrEmpty()) return 0
            val h = key.hashCode() and 0x7FFFFFFF // drop the sign bit
            return if (h == 0) 0 else h % PALETTE_SIZE
        }

        /**
         * Per-series [Series.perSecond] windows, each exactly [n] long:
         * contributions are aligned on the *trailing* edge (a short array
         * is the most recent N seconds, since the host may hand over only
         * as much history as it has) and a longer one is cropped at the
         * front. Pure so the alignment is unit-tested.
         */
        @JvmStatic
        fun bandWindows(series: List<Series>, n: Int): Array<LongArray> {
            if (n <= 0) return emptyArray()
            return series.map { s ->
                val len = s.perSecond.size
                when {
                    len >= n -> s.perSecond.copyOfRange(len - n, len)
                    else -> LongArray(n - len) + s.perSecond
                }
            }.toTypedArray()
        }

        /**
         * Merge series sharing a [Series.key] into one (tone and legend
         * name are per key, so two entries with one key would otherwise
         * draw twice in the same colour), then cap the stack to [max] by
         * total. The dropped overflow is summed into the last kept entry
         * rather than discarded, so the bar height still equals the
         * aggregate. A null list is treated as an empty frame.
         */
        @JvmStatic
        fun mergeSeries(series: List<Series>?, max: Int = MAX_STACKS): List<Series> {
            val src = series ?: emptyList()
            val acc = LinkedHashMap<String, Series>()
            for (s in src) {
                val prev = acc[s.key]
                if (prev == null) {
                    // copy: the merge below mutates the stored window
                    acc[s.key] = Series(s.key, s.provider, s.model, s.perSecond.copyOf())
                    continue
                }
                val a = prev.perSecond
                val b = s.perSecond
                if (b.size > a.size) {
                    val grown = LongArray(b.size)
                    System.arraycopy(a, 0, grown, b.size - a.size, a.size)
                    System.arraycopy(b, 0, grown, 0, b.size)
                    acc[s.key] = Series(prev.key, prev.provider, prev.model, grown)
                } else {
                    val off = a.size - b.size
                    for (i in b.indices) a[off + i] += b[i]
                }
            }
            val unique = ArrayList(acc.values)
            if (unique.size <= max) return unique

            val cap = max.coerceAtLeast(1)
            // Ranked directly rather than through legendOrder: that one is
            // about the *legend* and drops silent series, and here a list of
            // nothing but silent series still has to come back capped
            // instead of exploding on an empty ranking.
            val ranked = unique.indices.sortedWith(
                compareByDescending<Int> { seriesTotal(unique[it]) }.thenBy { it }
            )
            val kept = ranked.take(cap)
            val dropped = BooleanArray(unique.size) { it !in kept }
            // width of the widest series, kept or folded, so nothing is lost
            val n = unique.maxOf { it.perSecond.size }
            val bands = ArrayList<Series>(kept.size)
            for (j in kept.indices) {
                val s = unique[kept[j]]
                val per = LongArray(n)
                System.arraycopy(s.perSecond, 0, per, n - s.perSecond.size, s.perSecond.size)
                if (j == kept.size - 1) {
                    // last kept band absorbs the folded tail, so the sum
                    // over bands still equals the per-second aggregate
                    for (i in unique.indices) {
                        if (!dropped[i]) continue
                        val p = unique[i].perSecond
                        for (k in p.indices) per[n - p.size + k] += p[k]
                    }
                }
                bands.add(Series(s.key, s.provider, s.model, per))
            }
            return bands
        }

        /**
         * Did the shape move enough to earn a 350ms ease? Compared in both
         * directions and against [MOVE_EPS_FRAC], so a bar that shrinks
         * eases down exactly as one that grows eases up, and a poll that
         * only shaves a thousandth off the top does not restart the ease.
         */
        @JvmStatic
        fun frameMoved(fromFracs: FloatArray, toFracs: FloatArray): Boolean {
            if (fromFracs.size != toFracs.size) return true
            for (i in toFracs.indices) {
                if (abs(toFracs[i] - fromFracs[i]) > MOVE_EPS_FRAC) return true
            }
            return false
        }

        /**
         * Hot path of [setSeries]: publishes the *displayed* frame as
         * 0..1 heights of [lastMax]. The axis is committed by the caller
         * at the same points, never while an ease is in flight. Split out
         * of the View so it is unit-testable without a device.
         */
        @JvmStatic
        fun updateShownFrame(shown: ArrayList<Float>, next: FloatArray) {
            shown.clear()
            shown.addAll(next.asList())
        }
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

    /**
     * Stack source for panned windows. Install it together with
     * [sampleProvider] to chart named series; while it is null the
     * single-values path is used, so a host that has not migrated yet
     * still pans correctly.
     */
    var seriesProvider: ((offsetSec: Long, endMs: Long) -> List<Series>)? = null

    /**
     * Stacks of the *displayed* frame in paint order — first series at the
     * base of each bar, the folded tail last — with their windows already
     * aligned to the frame's width, plus the per-second aggregate those
     * bands add up to. Built once per frame, never per pixel: the draw loop
     * only divides a band by the total to get its share of the bar.
     */
    private var frameSeries: List<Series> = emptyList()
    private var frameBands: Array<LongArray> = emptyArray()
    private var frameTotals: LongArray = LongArray(0)

    /**
     * The displayed frame as 0..1 heights of [lastMax]. Pixels are derived
     * in the draw path, which is what keeps the quartile gridlines, the
     * bars and the peak line on one scale — and what makes the ease
     * visible: the segments are shares of these heights, not raw values.
     */
    private val shown = ArrayList<Float>(WINDOW_SECS)
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

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val seriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Distinct series tones: hue-spread so neighbouring stack segments are
     * easy to tell apart (a value ramp would make two of them read as one
     * segment), with index 0 kept on the running-green the chart already
     * used so a single unnamed series looks exactly as it did.
     */
    private val palette = IntArray(PALETTE_SIZE) { i ->
        if (i == 0) {
            resources.getColor(R.color.status_running, context.theme)
        } else {
            val hsv = FloatArray(3)
            hsv[0] = (i * 360f / (PALETTE_SIZE - 1) + 190f) % 360f
            hsv[1] = 0.60f
            hsv[2] = if (i % 2 == 0) 0.78f else 0.62f
            Color.HSVToColor(hsv)
        }
    }

    init {
        minimumHeight = (110 * resources.displayMetrics.density).toInt()
        isClickable = true
    }

    /**
     * New trailing samples (oldest-first, [WINDOW_SECS] long) for a single
     * unnamed series — the pre-stack path the host still calls. Delegates
     * to [setSeries]; when [seriesProvider] is installed the panned window
     * comes from it (one source of truth, no two writers), otherwise the
     * values are carried as one series and re-projected per pan, which
     * preserves the "the window wins" rule the provider used to enforce.
     */
    fun setSamples(values: List<Long>, snap: Boolean = false) {
        if (seriesProvider != null) {
            refreshWindow()
            return
        }
        setSeries(listOf(Series(LEGACY_KEY, "", "", values.toLongArray())), snap)
    }

    /**
     * New frame of stacked series, oldest bucket first; an empty list is a
     * legal frame and draws the empty grid. Series are stacked in list
     * order (first at the base of each bar).
     *
     * [snap] picks the frame straight in with no animator. Panning
     * ([refreshWindow]) passes true: a continuous drag re-queries on
     * every offset change, so a 350ms ease would be cancelled every few
     * ms, never complete, and leave the bars permanently mid-blend and
     * lagging the finger. The host poll passes false (the default) and
     * keeps the 350ms decelerate so bars settle into new data rather than
     * jumping on every 2s tick.
     */
    fun setSeries(series: List<Series>, snap: Boolean = false) {
        val merged = mergeSeries(series)
        val n = merged.maxOfOrNull { it.perSecond.size } ?: 0
        // The bands switch to the new frame immediately while the *totals*
        // below ease, which is what makes a changing mix read as a
        // recolouring of a stable shape rather than a jump.
        frameSeries = merged
        frameBands = bandWindows(merged, n)
        val totals = stackTotals(merged)
        frameTotals = totals
        val peak = peakOfTotals(totals)
        val next = FloatArray(n) { (totals[it].toFloat() / peak.toFloat()).coerceIn(0f, 1f) }
        val from = fitFrame(shown.toFloatArray(), next)
        animator?.cancel()
        // The displayed frame already IS the new frame when snapping or
        // detached, and when the shape is unchanged there is nothing to
        // tween (the same short-circuit setSamples had). In all three the
        // axis may commit now.
        if (snap || !isAttachedToWindow || !frameMoved(from, next)) {
            updateShownFrame(shown, next)
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
                updateShownFrame(shown, blend(from, next, a.animatedValue as Float))
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Only a completed ease promotes the new scale; a
                    // cancel means a newer frame superseded this one.
                    if (completed) {
                        updateShownFrame(shown, next)
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
        // One timestamp, used for the fetch *and* passed down, so the
        // frame cannot straddle a second boundary relative to the next
        // poll's frame.
        val endMs = windowEndMs(System.currentTimeMillis(), offsetSec)
        val sp = seriesProvider
        if (sp != null) {
            setSeries(sp(offsetSec, endMs), snap = true)
            invalidate()
            return
        }
        val p = sampleProvider ?: return
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

    /** The same hue as [palette], lifted so a stack's top edge pops. */
    private fun seriesColor(index: Int, lift: Boolean): Int {
        val c = palette[((index % PALETTE_SIZE) + PALETTE_SIZE) % PALETTE_SIZE]
        if (!lift) return c
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 0.75f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 1.18f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** Legend rows currently shown: the capped stacks plus the tail note. */
    private fun legendRows(): Int =
        legendOrder(frameSeries).size + if (tailCount(frameSeries) > 0) 1 else 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val res = resources
        val d = res.displayMetrics.density
        trackPaint.color = res.getColor(R.color.outline, context.theme)
        gridPaint.color = res.getColor(R.color.outline_variant, context.theme)
        labelPaint.color = res.getColor(R.color.hint_text, context.theme)
        labelPaint.textSize = 10f * d
        labelPaint.typeface = android.graphics.Typeface.MONOSPACE
        dividerPaint.color = res.getColor(R.color.outline, context.theme)
        peakPaint.color = res.getColor(R.color.hint_text, context.theme)
        peakTextPaint.color = peakPaint.color
        peakTextPaint.textSize = 10f * d
        peakTextPaint.typeface = android.graphics.Typeface.MONOSPACE

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val gutter = gutterPx()
        val plotW = (w - gutter).coerceAtLeast(1f)
        val labelTop = 12f * d
        val base = h - 2f
        // Bars stop above the legend, so the bottom of a stack — and the
        // divider on top of the tail band — is never hidden by its own
        // legend row.
        val legendH = legendRows() * 12f * d + 2f * d
        val plotBase = (base - legendH).coerceAtLeast(labelTop)
        val plotSpan = (plotBase - labelTop).coerceAtLeast(3f) - 2f
        val n = shown.size
        val nStack = frameSeries.size

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
            val by = bandY(q, plotBase, plotSpan)
            canvas.drawRect(0f, by - LABEL_BASELINE, plotW, by - LABEL_BASELINE + 1f, gridPaint)
            val label = StatsFormat.humanTokens((lastMax * (q / 4f)).toLong())
            canvas.drawText(label, plotW + 4f * d, by, labelPaint)
        }
        canvas.drawRect(0f, base, plotW, base + 2f, gridPaint)
        if (n == 0 || nStack == 0) {
            // Empty frame: axis, gutter values and the baseline only, so
            // "no data" looks like a chart at rest rather than a broken one.
            return
        }

        val gap = (2 * d).coerceAtLeast(1f)
        val slot = plotW / n
        val bw = (slot - gap).coerceAtLeast(1f)
        for (i in 0 until n) {
            val x0 = i * slot + gap / 2f
            val x1 = x0 + bw
            // The displayed fraction *is* the bar: the segment heights are
            // shares of it, so the 350ms ease moves the whole stack and the
            // axis, gridlines and reference line stay on one scale.
            val barPx = shown[i] * plotSpan
            if (barPx <= 0.4f) {
                canvas.drawRect(x0, plotBase - 2f, x1, plotBase, trackPaint)
                continue
            }
            val total = frameTotals[i]
            // Stack up from the baseline in list order: series A on the
            // floor, B on top of it, so the bar height is the aggregate.
            var cursor = plotBase
            for (k in 0 until nStack) {
                val share = if (total > 0L) frameBands[k][i].toFloat() / total else 0f
                val seg = share * barPx
                val top = cursor - seg
                if (seg >= 0.6f) {
                    seriesPaint.color = seriesColor(colorIndexFor(frameSeries[k].key), k == 0)
                    canvas.drawRect(x0, top, x1, cursor, seriesPaint)
                    // 1px divider on the segment's own top edge: no extra
                    // pixels of bar, and even a 0.5px band gets a visible
                    // edge against the segment below it.
                    if (k != nStack - 1) {
                        canvas.drawRect(x0, top, x1, top + 1f, dividerPaint)
                    }
                }
                cursor = top
            }
        }

        // Reference line at the tallest bar. Its value rides the *displayed*
        // frame (the max of the eased fractions against the committed
        // scale), so the line sits exactly on the bar the user is looking at
        // — mid-ease included — and never ahead of it.
        val peakValue = (peakFrac() * lastMax).toLong()
        val peakY = yFor(peakValue, lastMax, plotBase, plotSpan)
        canvas.drawRect(0f, peakY, plotW, peakY + 1f, peakPaint)
        if (peakLabelShown(peakY, plotBase, plotSpan)) {
            val valueText = StatsFormat.humanTokens(peakValue)
            val tw = peakTextPaint.measureText(valueText)
            val tx = (plotW + 4f * d).coerceAtMost(w - tw)
            // one text ascent below the plot top, so the number can never
            // reach the "peak 312/s" readout or the LIVE chip above it
            val ty = peakLabelY(peakY, plotBase, plotSpan, 8f * d)
                .coerceIn(labelTop, h)
            // Reserved gutter, so the backing box never covers a bar; it is
            // what keeps the number readable on top of a gridline label.
            canvas.drawRect(
                tx - 2f * d,
                ty - 8f * d,
                (tx + tw).coerceAtMost(w),
                ty + 3f * d,
                trackPaint
            )
            canvas.drawText(valueText, tx, ty, peakTextPaint)
        }

        drawLegend(canvas, base, d)
    }

    /**
     * Legend in the band the bars were lifted clear of: swatch + name, the
     * bottom row being the base of the stack (the biggest contributor, so
     * the rows read in the same order the segments stack) and "+N more"
     * above that. Swatch colours are the same hash the segments use.
     */
    private fun drawLegend(canvas: Canvas, base: Float, d: Float) {
        val order = legendOrder(frameSeries)
        val tail = tailCount(frameSeries)
        if (order.isEmpty() && tail == 0) return
        val rowH = 12f * d
        val bottom = base - 2f * d
        val rows = order.size + if (tail > 0) 1 else 0
        for (row in 0 until rows) {
            val y = bottom - row * rowH
            val x = 1f * d
            if (row < order.size) {
                val s = frameSeries[order[row]]
                seriesPaint.color = seriesColor(colorIndexFor(s.key), false)
                canvas.drawRect(x, y - 7f * d, x + 6f * d, y - 1f * d, seriesPaint)
                canvas.drawText(legendLabel(s), x + 9f * d, y, labelPaint)
            } else {
                canvas.drawText("+$tail more", x, y, labelPaint)
            }
        }
    }

    /** Height of the tallest displayed bar, 0..1 against [lastMax]. */
    private fun peakFrac(): Float {
        var m = 0f
        for (v in shown) if (v > m) m = v
        return m
    }
}
