package com.forgerig.gatekeeper.proxy

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

data class MetricsSnapshot(
    val sessions: List<SessionMetrics> = emptyList(),
    val requests: List<RequestMetrics> = emptyList(),
    val scenarioCounts: Map<String, Int> = emptyMap(),
    val retryCounts: Map<String, Int> = emptyMap(),
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("startTime", startTime)
        endTime?.let { json.put("endTime", it) }
        val sessionsArray = JSONArray()
        for (s in sessions) sessionsArray.put(s.toJson())
        json.put("sessions", sessionsArray)
        val requestsArray = JSONArray()
        for (r in requests) requestsArray.put(r.toJson())
        json.put("requests", requestsArray)
        json.put("scenarioCounts", JSONObject(scenarioCounts))
        json.put("retryCounts", JSONObject(retryCounts))
        return json.toString()
    }

    fun save(path: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(toJson())
    }

    companion object {
        fun load(path: String): MetricsSnapshot? {
            val file = File(path)
            if (!file.exists()) return null
            return try {
                val obj = JSONObject(file.readText())
                val sessions = mutableListOf<SessionMetrics>()
                val requests = mutableListOf<RequestMetrics>()
                val scenarioCounts = mutableMapOf<String, Int>()
                val retryCounts = mutableMapOf<String, Int>()

                val sessionsArray = obj.getJSONArray("sessions")
                for (i in 0 until sessionsArray.length()) {
                    sessions.add(SessionMetrics.fromJson(sessionsArray.getJSONObject(i)))
                }

                val requestsArray = obj.getJSONArray("requests")
                for (i in 0 until requestsArray.length()) {
                    requests.add(RequestMetrics.fromJson(requestsArray.getJSONObject(i)))
                }

                val scenarioObj = obj.getJSONObject("scenarioCounts")
                for (key in scenarioObj.keys()) {
                    scenarioCounts[key] = scenarioObj.getInt(key)
                }

                val retryObj = obj.getJSONObject("retryCounts")
                for (key in retryObj.keys()) {
                    retryCounts[key] = retryObj.getInt(key)
                }

                MetricsSnapshot(
                    sessions = sessions,
                    requests = requests,
                    scenarioCounts = scenarioCounts,
                    retryCounts = retryCounts,
                    startTime = obj.getLong("startTime"),
                    endTime = obj.optLong("endTime").takeIf { it > 0 }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class SessionMetrics(
    val sessionId: String,
    val url: String,
    val status: SessionStatus,
    val durationMs: Long,
    val bytesTransferred: Long,
    val resumeCount: Int,
    val scenarios: List<String>
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("sessionId", sessionId)
        json.put("url", url)
        json.put("status", status.name)
        json.put("durationMs", durationMs)
        json.put("bytesTransferred", bytesTransferred)
        json.put("resumeCount", resumeCount)
        val scenariosArray = JSONArray()
        for (s in scenarios) scenariosArray.put(s)
        json.put("scenarios", scenariosArray)
        return json
    }

    companion object {
        fun fromJson(obj: JSONObject): SessionMetrics {
            val scenarios = mutableListOf<String>()
            val scenariosArray = obj.getJSONArray("scenarios")
            for (i in 0 until scenariosArray.length()) {
                scenarios.add(scenariosArray.getString(i))
            }
            return SessionMetrics(
                sessionId = obj.getString("sessionId"),
                url = obj.getString("url"),
                status = SessionStatus.valueOf(obj.getString("status")),
                durationMs = obj.getLong("durationMs"),
                bytesTransferred = obj.getLong("bytesTransferred"),
                resumeCount = obj.getInt("resumeCount"),
                scenarios = scenarios
            )
        }
    }
}

data class RequestMetrics(
    val requestId: String,
    val sessionId: String,
    val url: String,
    val method: String,
    val durationMs: Long,
    val bytesTransferred: Long,
    val statusCode: Int,
    val scenario: String,
    val category: String = "unknown",
    /** Set when the record is finalized; drives retention pruning. */
    val atMs: Long = 0L
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("requestId", requestId)
        json.put("sessionId", sessionId)
        json.put("url", url)
        json.put("method", method)
        json.put("durationMs", durationMs)
        json.put("bytesTransferred", bytesTransferred)
        json.put("statusCode", statusCode)
        json.put("scenario", scenario)
        json.put("category", category)
        return json
    }

    companion object {
        fun fromJson(obj: JSONObject): RequestMetrics {
            return RequestMetrics(
                requestId = obj.getString("requestId"),
                sessionId = obj.getString("sessionId"),
                url = obj.getString("url"),
                method = obj.getString("method"),
                durationMs = obj.getLong("durationMs"),
                bytesTransferred = obj.getLong("bytesTransferred"),
                statusCode = obj.getInt("statusCode"),
                scenario = obj.getString("scenario"),
                category = obj.optString("category", "unknown")
            )
        }
    }
}

object ProxyMetrics {
    private val sessionStartTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val requestStartTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Last output total seen per request, so replays/resumes are not re-credited. */
    private val scenarioCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val retryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val sessionMetrics = java.util.concurrent.ConcurrentHashMap<String, SessionMetrics>()
    private val requestMetrics = java.util.concurrent.ConcurrentHashMap<String, RequestMetrics>()

    fun recordSessionStart(sessionId: String, url: String, config: ProxyConfig) {
        sessionStartTimes[sessionId] = System.currentTimeMillis()
        if (config.metricsEnabled) {
            sessionMetrics[sessionId] = SessionMetrics(
                sessionId = sessionId,
                url = url,
                status = SessionStatus.PENDING,
                durationMs = 0,
                bytesTransferred = 0,
                resumeCount = 0,
                scenarios = emptyList()
            )
        }
    }

    fun recordSessionEnd(sessionId: String, status: SessionStatus, bytesTransferred: Long, resumeCount: Int, scenarios: List<NetworkScenario>) {
        val start = sessionStartTimes.remove(sessionId) ?: System.currentTimeMillis()
        if (start > 0) {
            val metrics = SessionMetrics(
                sessionId = sessionId,
                url = sessionMetrics[sessionId]?.url ?: "",
                status = status,
                durationMs = System.currentTimeMillis() - start,
                bytesTransferred = bytesTransferred,
                resumeCount = resumeCount,
                scenarios = scenarios.map { it.name }
            )
            sessionMetrics[sessionId] = metrics
        }
    }

    fun recordRequestStart(
        sessionId: String,
        requestId: String,
        url: String,
        method: String,
        llmHosts: Set<String> = emptySet()
    ) {
        requestStartTimes[requestId] = System.currentTimeMillis()
        requestMetrics[requestId] = RequestMetrics(
            requestId = requestId,
            sessionId = sessionId,
            url = url,
            method = method,
            durationMs = 0,
            bytesTransferred = 0,
            statusCode = 0,
            scenario = NetworkScenario.UNKNOWN.name,
            category = categorizeUrl(url, llmHosts)
        )
    }

    private fun categorizeUrl(url: String): String = categorizeUrl(url, emptySet())

    /**
     * Host-aware categorization. When [llmHosts] is non-empty, hosts
     * outside the LLM allowlist are "passthrough" (opaque-tunneled,
     * never brokered) regardless of URL shape — this fixes e.g.
     * api.github.com, which the "github.com" file rule used to catch.
     * Empty [llmHosts] preserves legacy shape-only behavior.
     */
    fun categorizeUrl(url: String, llmHosts: Set<String>): String {
        val lowerUrl = url.lowercase()
        if (llmHosts.isNotEmpty()) {
            val host = LlmPolicy.extractHost(url)
            if (host.isNotEmpty() && !LlmPolicy.matchesAny(host, llmHosts)) {
                return "passthrough"
            }
        }
        // File downloads: large payloads, binaries, archives, media
        val filePatterns = listOf(
            ".apk", ".exe", ".dmg", ".deb", ".rpm", ".zip", ".tar", ".gz",
            ".bz2", ".xz", ".7z", ".rar", ".iso", ".img", ".bin",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tiff", ".webp",
            ".mp3", ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv",
            ".wav", ".ogg", ".flac", ".aac",
            ".css", ".js", ".ts", ".jsx", ".tsx", ".html", ".htm",
            ".json", ".xml", ".yaml", ".yml", ".txt", ".md", ".csv",
            ".wasm", ".map", ".woff", ".woff2", ".ttf", ".eot", ".svg",
            "github.com", "download", "asset", "release", "artifact"
        )
        // API endpoints: service APIs, AI models, auth, config
        val apiPatterns = listOf(
            "api.", "/api/", "integrate.", "models.", "opencode.ai",
            "graphql", "oauth", "auth", "token", "key", "v1/", "v2/", "v3/",
            "anthropic", "openai", "googleapis", "cloudflare", "fastly",
            "search", "parallel", "vector", "embedding", "completion"
        )
        // API shape wins over file shape: an API-shaped URL serving bytes
        // is still API traffic from the proxy's perspective (e.g. the
        // "github.com" file rule must not catch "api.github.com").
        if (apiPatterns.any { lowerUrl.contains(it) }) return "api"
        if (filePatterns.any { lowerUrl.contains(it) }) return "file"
        return "other"
    }

    /**
     * Close out requests that never reached a terminal record (exceptions,
     * early returns after start). No-op when already ended, so every
     * started request ends exactly once. The `remove` is the atomic
     * claim: no monitor, so connection teardown never queues behind
     * addBytes' lock on the hot relay path.
     */
    fun recordRequestEndIfOpen(sessionId: String, requestId: String) {
        if (requestStartTimes.remove(requestId) == null) return // already ended
        recordRequestEnd(sessionId, requestId, 0, 0, NetworkScenario.UNKNOWN)
    }

    fun recordRequestEnd(sessionId: String, requestId: String, statusCode: Int, bytesTransferred: Long, scenario: NetworkScenario) {
        val start = requestStartTimes.remove(requestId) ?: System.currentTimeMillis()
        val metrics = RequestMetrics(
            requestId = requestId,
            sessionId = sessionId,
            url = requestMetrics[requestId]?.url ?: "",
            method = requestMetrics[requestId]?.method ?: "",
            durationMs = System.currentTimeMillis() - start,
            bytesTransferred = bytesTransferred,
            statusCode = statusCode,
            scenario = scenario.name,
            category = requestMetrics[requestId]?.category ?: categorizeUrl(requestMetrics[requestId]?.url ?: ""),
            atMs = System.currentTimeMillis()
        )
        requestMetrics[requestId] = metrics
        incrementScenario(scenario)
    }

    fun recordRetry(sessionId: String, requestId: String, attempt: Int, scenario: NetworkScenario, delayMs: Long) {
        retryCounts.merge(scenario.name, 1) { a, b -> a + b }
        sessionMetrics[sessionId]?.let {
            sessionMetrics[sessionId] = it.copy(
                resumeCount = it.resumeCount + 1,
                scenarios = it.scenarios + scenario.name
            )
        }
    }

    /**
     * Finalize a CONNECT tunnel record when the tunnel actually closes.
     * Tunnels can't use recordRequestEnd at handshake time (bytes=0,
     * handshake-only duration) — that produced exports full of 0-byte
     * 200s. This rewrites the record with lifetime bytes + duration.
     */
    fun recordTunnelEnd(requestId: String, bytesTransferred: Long, scenario: NetworkScenario) {
        val start = requestStartTimes.remove(requestId) ?: System.currentTimeMillis()
        val prev = requestMetrics[requestId]
        requestMetrics[requestId] = RequestMetrics(
            requestId = requestId,
            sessionId = prev?.sessionId ?: "",
            url = prev?.url ?: "",
            method = prev?.method ?: "CONNECT",
            durationMs = System.currentTimeMillis() - start,
            bytesTransferred = bytesTransferred,
            statusCode = if (prev?.statusCode == 0) 200 else prev?.statusCode ?: 200,
            scenario = scenario.name,
            category = prev?.category ?: categorizeUrl(prev?.url ?: ""),
            atMs = System.currentTimeMillis()
        )
        incrementScenario(scenario)
    }

    fun incrementScenario(scenario: NetworkScenario) {
        // Atomic on a ConcurrentHashMap: get()+put() loses increments when
        // the 32 relay threads retry at once, which is exactly what the
        // scenario tallies are supposed to measure.
        scenarioCounts.merge(scenario.name, 1) { a, b -> a + b }
    }

    fun incrementRetryType(retryType: RetryType) {
        retryCounts.merge(retryType.name, 1) { a, b -> a + b }
    }

    fun snapshot(): MetricsSnapshot {
        pruneRequestMetrics()
        return MetricsSnapshot(
            sessions = sessionMetrics.values.toList(),
            requests = requestMetrics.values.toList(),
            scenarioCounts = scenarioCounts.toMap(),
            retryCounts = retryCounts.toMap(),
            startTime = sessionStartTimes.values.minOrNull() ?: System.currentTimeMillis(),
            endTime = null
        )
    }

    /**
     * requestMetrics is written once per HTTP request AND per CONNECT
     * tunnel and was only ever emptied by clear() — a long-lived proxy
     * grows it without bound while the 1 Hz UI poll copies the whole map
     * on the main thread. Keep the most recent [MAX_RETAINED_REQUESTS],
     * dropping to [PRUNE_TARGET] so this amortizes instead of re-sorting
     * on every poll once we are over the cap.
     */
    private fun pruneRequestMetrics() {
        if (requestMetrics.size <= MAX_RETAINED_REQUESTS) return
        val keep = requestMetrics.entries
            .sortedByDescending { it.value.atMs }
            .take(PRUNE_TARGET)
            .map { it.key }
            .toSet()
        requestMetrics.keys.retainAll(keep)
    }

    fun clear() {
        sessionStartTimes.clear()
        requestStartTimes.clear()
        scenarioCounts.clear()
        retryCounts.clear()
        sessionMetrics.clear()
        requestMetrics.clear()
    }

    // ---- Human-readable event log (logcat + in-app feed) ----
    const val TAG = "NetworkProxy"
    private const val MAX_EVENTS = 100

    /** Retention cap for finalized request/tunnel records. */
    private const val MAX_RETAINED_REQUESTS = 2000
    private const val PRUNE_TARGET = 1500
    private val events = ConcurrentLinkedQueue<Pair<Long, String>>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Log sink, defaulting to logcat. Swappable in JVM unit tests where
     * android.util.Log is stubbed out.
     */
    var logSink: (level: Int, msg: String, e: Throwable?) -> Unit = { level, msg, e ->
        when (level) {
            android.util.Log.WARN -> android.util.Log.w(TAG, msg)
            android.util.Log.ERROR -> if (e != null) android.util.Log.e(TAG, msg, e) else android.util.Log.e(TAG, msg)
            else -> android.util.Log.i(TAG, msg)
        }
    }

    /** Timestamped line, kept in a capped ring buffer and mirrored to logcat. */
    fun event(msg: String) {
        val now = System.currentTimeMillis()
        events.add(now to msg)
        while (events.size > MAX_EVENTS) events.poll()
        logSink(android.util.Log.INFO, msg, null)
    }

    fun eventWarning(msg: String) {
        val now = System.currentTimeMillis()
        events.add(now to "WARN: $msg")
        while (events.size > MAX_EVENTS) events.poll()
        logSink(android.util.Log.WARN, msg, null)
    }

    fun eventError(msg: String, e: Throwable? = null) {
        val now = System.currentTimeMillis()
        events.add(now to "ERROR: $msg")
        while (events.size > MAX_EVENTS) events.poll()
        logSink(android.util.Log.ERROR, msg, e)
    }

    /** Newest-first formatted lines for the in-app feed. */
    fun recentEvents(limit: Int = 15): List<String> =
        events.toList().takeLast(limit).reversed()
            .map { (ts, msg) -> "${timeFmt.format(Date(ts))}  $msg" }

    fun clearEvents() {
        events.clear()
    }

    // ---- Traffic tallies: bytes per host (always visible, even inside
    // CONNECT tunnels) + tokens (only when bodies are readable, i.e.
    // plain-HTTP JSON/SSE carrying usage blocks; HTTPS tunnels are opaque).
    data class HostTally(var upBytes: Long = 0, var downBytes: Long = 0)
    private val hostTallies = ConcurrentHashMap<String, HostTally>()

    // Token counters. Anthropic: input_tokens / output_tokens /
    // cache_read_input_tokens / cache_creation_input_tokens.
    // OpenAI: prompt_tokens / completion_tokens (+ cached_tokens detail).
    @Volatile var inputTokens: Long = 0
        private set
    @Volatile var outputTokens: Long = 0
        private set
    @Volatile var cacheReadTokens: Long = 0
        private set
    @Volatile var cacheWriteTokens: Long = 0
        private set

    @Synchronized
    fun addBytes(host: String, up: Long, down: Long) {
        if (host.isBlank()) return
        val t = hostTallies.getOrPut(host) { HostTally() }
        t.upBytes += up
        t.downBytes += down
    }

    @Synchronized
    fun addTokens(
        input: Long, output: Long, cacheRead: Long, cacheWrite: Long,
        host: String = "", model: String = ""
    ) {
        inputTokens += input
        outputTokens += output
        cacheReadTokens += cacheRead
        cacheWriteTokens += cacheWrite
        if (host.isNotBlank()) {
            val t = tokenTallies.getOrPut(tallyKey(host, model)) { TokenTally() }
            t.inTokens += input
            t.outTokens += output
            t.cacheRead += cacheRead
            t.cacheWrite += cacheWrite
        }
        if (output > 0) {
            val now = System.currentTimeMillis()
            if (firstOutputMs == 0L) firstOutputMs = now
            // NOTE: no arrival-bucketing here — usage totals land in the
            // final chunk and would spike. Rate credit flows exclusively
            // through sampleOutputSpread (active-span attribution).
        }
    }

    // ---- Output tokens/sec, from the SAME spread buckets the chart
    // draws — the number and the chart cannot disagree. ----
    private const val TPS_WINDOW_MS = 30_000L
    /** Set on the first counted output token; session average hangs off it. */
    @Volatile private var firstOutputMs: Long = 0L

    /** Output tokens per second over the trailing window (default 30s). */
    @Synchronized
    fun outputTokensPerSecond(windowMs: Long = TPS_WINDOW_MS): Double {
        if (windowMs <= 0) return 0.0
        val n = (windowMs / 1000).toInt().coerceIn(1, RATE_HISTORY_SECS)
        return rateHistory(n).sum().toDouble() / n
    }

    /**
     * Session-average output tok/s since the first counted output token.
     * Unlike the trailing window, this never reads 0 while output exists —
     * if trailing is 0 but avg climbs, the stream is idle, not broken.
     */
    @Synchronized
    fun outputTokensAvg(): Double {
        val start = firstOutputMs
        if (start == 0L || outputTokens <= 0) return 0.0
        val elapsedMs = (System.currentTimeMillis() - start).coerceAtLeast(1L)
        return outputTokens.toDouble() / (elapsedMs / 1000.0)
    }

    // ---- Per-second output samples for the rate bar chart ----
    /** Ring of (second-epoch → output tokens); capped, oldest evicted. */
    /**
     * Output tokens per wall-clock second, keyed by second so a bucket can be
     * merged no matter what order writes arrive in. This was an
     * insertion-ordered ArrayDeque that only merged at the tail: spreading a
     * response over its span appends older seconds *after* newer ones, which
     * created duplicate slots and made the 3600 cap a cap on entries rather
     * than seconds (so real history was evicted by smeared duplicates).
     */
    private val rateBuckets = java.util.TreeMap<Long, Long>()
    const val RATE_CHART_SECS = 60
    /** Full retention behind the scrollable chart (1h, ~60KB). */
    const val RATE_HISTORY_SECS = 3600
    /** Default visible window of the scrollable chart. */
    const val RATE_WINDOW_SECS = 120

    /** Window used when a response's generation span is unknown. */
    const val MIN_SPAN_SECS = 4

    /**
     * Ceiling on how far one response's tokens may be smeared. Without it a
     * long keep-alive tunnel (requestId start = tunnel start) spreads each
     * response across the whole tunnel, appending thousands of buckets and
     * evicting every other stream's real data.
     */
    const val MAX_SPREAD_SECS = 60

    // ---- Per-(provider, model) breakdown of the SAME rate ring ----
    //
    // The chart draws one stack of bars per model+provider. Each series is
    // fed from the identical spread plan that feeds the aggregate (one code
    // path, creditOutput), so
    //   sum(series.total) == rateHistory(n, at).sum()
    // is an identity of the implementation, not a reconciliation pass, and
    // a stacked bar can never be taller than the aggregate line. Nothing
    // here re-derives usage numbers: the count credited is the very same
    // already-delta'd output delta that credits the token tallies (see
    // recordUsage), so the per-key replay/boundary semantics of
    // StreamingUsage carry over untouched.
    //
    // COST. The aggregate is ONE TreeMap and rateHistory does a lookup per
    // second; a series read instead does one O(log S) probe per series plus
    // one O(log S) step per non-empty second in the window (S = seconds
    // retained in that series, <= RATE_HISTORY_SECS), and allocates nothing
    // beyond the LongArray per live series the caller asked for — the whole
    // retention window is never rebuilt, and an idle series costs a single
    // failed probe. Worst case that is MAX_RATE_SERIES x RATE_WINDOW_SECS
    // longs (64 x 120 = 7.7k, ~61KB) per call, i.e. bounded and small
    // against the FloatArray churn the chart already does per frame, and the
    // typical case is a handful of live models. The read is on the UI's hot
    // path twice over: once per 1-2 Hz poll AND once per pan frame (the
    // chart re-queries its window on every drag), so it stays a single
    // monitor acquisition, a TreeMap cursor walk, and one sort of <= 64
    // elements. Writes cost one extra TreeMap update per plan slot
    // (<= MAX_SPREAD_SECS per response).
    //
    // LOCKING. Every mutation AND the read take the SAME monitor the
    // aggregate already uses (the object's @Synchronized), so no new lock
    // is introduced and 32 relay threads see no added contention: one
    // recordUsage still means exactly one monitor acquisition, with the
    // plan computed once and applied to both rings inside it.

    /**
     * Hard cap on tracked series. A long-running proxy can observe an
     * unbounded number of models, which is the same class of leak
     * [pruneRequestMetrics] already fixes for request records: past the cap
     * the least-recently-written series is dropped whole (its ring with it)
     * rather than being allowed to grow. An evicted model reappears — and
     * restarts from an empty window — on its next response.
     *
     * This is the ONE documented way the sum identity can lapse: the
     * aggregate ring never forgets a credited second, so the tokens of an
     * evicted model stay in [rateHistory] while its series is gone, and
     * `sum(rateSeries(...).total)` then falls short of the aggregate by
     * exactly the evicted series' in-window totals (bounded by the cap, not
     * by traffic). That is deliberate — a chart that showed a synthetic
     * "(dropped)" stack would mislabel another model's tokens — and it only
     * happens past 64 distinct live (provider, model) pairs, which no real
     * setup reaches. Raise the cap if yours does.
     */
    const val MAX_RATE_SERIES = 64

    /** Provider id used by call sites that cannot attribute one. See recordUsage. */
    const val UNATTRIBUTED_PROVIDER = ""

    private class SeriesRing {
        val buckets = java.util.TreeMap<Long, Long>()
    }

    /**
     * Access-ordered, so [MAX_RATE_SERIES] evicts the least recently written
     * series (a read counts as a use: a model still on screen is not the one
     * dropped). Guarded by the object's monitor, like the aggregate ring.
     */
    private val rateSeriesRings = object : java.util.LinkedHashMap<String, SeriesRing>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeriesRing>) =
            size > MAX_RATE_SERIES
    }

    /**
     * Composite series key. Case-folded like [tallyKey] so one model cannot
     * split across two series on a casing difference, and therefore so
     * "same model" means the same thing here as in the token table.
     */
    fun seriesKey(provider: String, model: String): String =
        provider.trim().lowercase() + "\u0001" + model.trim().lowercase()

    private fun splitSeriesKey(key: String): Pair<String, String> {
        val i = key.indexOf('\u0001')
        return if (i < 0) key to "" else key.substring(0, i) to key.substring(i + 1)
    }

    private fun ringFor(key: String): SeriesRing =
        rateSeriesRings.getOrPut(key) { SeriesRing() }

    /**
     * Add to one series ring under the SAME [RATE_HISTORY_SECS] retention
     * and oldest-first eviction as the aggregate, so a series can never hold
     * a second the aggregate has already dropped — the sum identity survives
     * retention, not just steady state.
     */
    private fun addToSeriesBucket(ring: SeriesRing, sec: Long, count: Long) {
        if (count <= 0) return
        val b = ring.buckets
        b[sec] = (b[sec] ?: 0L) + count
        while (b.size > RATE_HISTORY_SECS) {
            val oldest = b.firstKey() ?: break
            b.remove(oldest)
        }
    }

    /** Fold output tokens into the current second-bucket (test seam: [atMs]). */
    @Synchronized
    fun sampleOutput(count: Long, atMs: Long = System.currentTimeMillis(), seriesKey: String? = null) {
        if (count <= 0) return
        val sec = atMs / 1000
        addToBucket(sec, count)
        seriesKey?.let { addToSeriesBucket(ringFor(it), sec, count) }
    }

    /**
     * Fold output tokens in when the generation span is unknown, smearing
     * them over [MIN_SPAN_SECS] rather than one second.
     *
     * A non-streaming (or gzip, opaque-to-the-live-scanner) response reports
     * its whole completion count in one report at close. Crediting that to a
     * single second reads as "4000 tok/s" for work that actually took
     * seconds — the spikes that kept showing up. Totals are unchanged; only
     * the distribution is honest.
     */
    @Synchronized
    fun sampleOutputUnknownSpan(count: Long, atMs: Long = System.currentTimeMillis(), seriesKey: String? = null) {
        creditOutput(seriesKey, null, count, atMs)
    }

    private fun addToBucket(sec: Long, count: Long) {
        if (count <= 0) return
        rateBuckets[sec] = (rateBuckets[sec] ?: 0L) + count
        while (rateBuckets.size > RATE_HISTORY_SECS) {
            val oldest = rateBuckets.firstKey() ?: break
            rateBuckets.remove(oldest)
        }
    }

    /**
     * The one credit path behind both the aggregate ring and every series
     * ring: compute the spread plan ONCE and apply it, second by second, to
     * both. [seriesKey] null = aggregate only (the pre-existing
     * aggregate-only samplers). [requestId] null = unknown span, i.e. the
     * [MIN_SPAN_SECS] smear.
     *
     * Because both rings are written from the same `base + i` seconds, the
     * MAX_SPREAD_SECS clamp, the MIN_SPAN_SECS floor and the even-split
     * remainder are identical on both sides by construction.
     */
    private fun creditOutput(seriesKey: String?, requestId: String?, count: Long, atMs: Long) {
        if (count <= 0) return
        val startMs = if (requestId != null) (requestStartTimes[requestId] ?: 0L) else 0L
        val spanSecs = if (startMs > 0) ((atMs - startMs) / 1000).toInt() else 0
        val plan = spreadPlan(count, smoothedSpanSecs(spanSecs))
        val endSec = atMs / 1000
        val base = endSec - plan.size + 1
        val ring = seriesKey?.let { ringFor(it) }
        plan.forEachIndexed { i, c ->
            addToBucket(base + i, c)
            if (ring != null) addToSeriesBucket(ring, base + i, c)
        }
    }

    /**
     * Credit output tokens across the request's ACTIVE seconds (first to
     * last) instead of the arrival second. Usage totals arrive in the
     * final chunk, so arrival-bucketing fabricates spikes (2k tokens
     * after a 40s think reads as 2000 tok/s instead of ~49 tok/s).
     * Even split, remainder to the last bucket. Falls back to a
     * [MIN_SPAN_SECS] smear when the request start is unknown.
     */
    @Synchronized
    fun sampleOutputSpread(
        requestId: String,
        count: Long,
        atMs: Long = System.currentTimeMillis(),
        seriesKey: String? = null
    ) {
        // Cache-read/cache-write never reach here: recordUsage passes
        // found[1] (output_tokens) alone.
        //
        // De-duplication of a provider's re-reported usage blocks does NOT
        // happen here. A monotonic per-requestId watermark was tried and
        // removed: requestId is minted per client CONNECTION, so every
        // response on a keep-alive tunnel was diffed against the previous
        // one and real output was silently dropped. The dedupe now lives in
        // StreamingUsage, whose lifetime is exactly one response stream, and
        // every report reaching this method is already a delta to credit.
        creditOutput(seriesKey, requestId, count, atMs)
    }

    /**
     * Effective width, in seconds, of the smear one response's output credit
     * is spread across: the larger of the measured span and [MIN_SPAN_SECS].
     *
     * A completion that took 1-3s used to be spread over just those 1-3
     * seconds, so it read as its full count in one bucket (2000 tokens ->
     * "2000 tok/s") even though generation took seconds. The obvious fix — a
     * hard tok/s ceiling — was rejected: it would clamp a genuinely fast
     * model and silently under-report real rates, and the ceiling would have
     * to be picked arbitrarily. Instead this bounds the response's
     * CONCENTRATION: density can never exceed count/MIN_SPAN_SECS for a short
     * response, while the total is unchanged and a long response is still
     * attributed over its true span, so a high-but-real rate (many tokens AND
     * a long span) keeps its actual tok/s. A 0/unknown span keeps the
     * existing [MIN_SPAN_SECS] unknown-span behaviour.
     */
    fun smoothedSpanSecs(spanSecs: Int): Int =
        if (spanSecs <= 0) MIN_SPAN_SECS else maxOf(spanSecs, MIN_SPAN_SECS)

    /**
     * Pure spread plan (oldest-first per-second shares): even split, the
     * remainder spread one-per-bucket from the newest end.
     */
    fun spreadPlan(count: Long, spanSecs: Int, capped: Int = MAX_SPREAD_SECS): List<Long> {
        if (count <= 0) return emptyList()
        val n = spanSecs.coerceIn(1, capped)
        val base = count / n
        val rem = (count % n).toInt()
        return List(n) { i -> base + if (i >= n - rem) 1 else 0 }
    }

    /**
     * Single choke point for usage accounting: credits the token tallies
     * (split by host AND model) AND the rate sampler together, so the
     * displays can never drift apart. [found] is the scanner quad
     * (input, output, cacheRead, cacheWrite) — already reduced to the
     * tokens to CREDIT by [StreamingUsage] under its per-key semantics
     * (the output slot a cumulative reporter's increase, the one-shot slots
     * a first-sighting full value), so this credits the quad exactly as
     * given. [requestId] null = tally only (opaque paths with no
     * request context).
     *
     * ## PROVIDER ATTRIBUTION FOR [rateSeries]
     *
     * This overload CANNOT attribute a provider and says so by crediting
     * [UNATTRIBUTED_PROVIDER] (""), which is the same convention
     * [tallyKey] already uses for a blank model. Every current call site is
     * in that bucket, and none of them is a place where a provider id
     * exists:
     *
     *  - `ProxyService`:610 — brokered upstream request. The leg's provider
     *    is known there only as a `ProviderBroker` store/entry, which is
     *    context-backed (`ProviderBroker.store(this)`) and read back on
     *    other lines of the same handler; this accounting site has no
     *    reference to it.
     *
     * Resolving a provider id is therefore a `ProxyService` change (it owns
     * the store handle): thread the matched `Provider.id` in from that site
     * and call the 5-argument overload below. Until then the chart's
     * provider axis shows one "" group per model — grouped, but not
     * provider-split. This file deliberately does NOT infer a provider from
     * the host: the same model can be served by several configured
     * providers, so a host-derived id would be a guess, and a guess that
     * silently looks like attribution in the UI is worse than an honestly
     * empty group.
     */
    @Synchronized
    fun recordUsage(host: String, model: String, requestId: String?, found: LongArray) {
        recordUsage(host, model, requestId, found, UNATTRIBUTED_PROVIDER)
    }

    /**
     * As [recordUsage] above, but attributes the rate credit to
     * (providerId, model) as well. [providerId] is the configured
     * provider's `Provider.id` ([UNATTRIBUTED_PROVIDER] when genuinely
     * unknown — pass it explicitly rather than defaulting, so an
     * unattributed call site stays visible in review).
     *
     * One monitor acquisition: the plan is computed once by [creditOutput]
     * and applied to the aggregate ring and the series ring together, which
     * is what makes sum(series) == rateHistory(...).sum() hold. The count
     * credited is [found]'s already-delta'd output slot, never re-derived,
     * so a replayed frame or a [StreamingUsage.beginResponse] boundary
     * flush credits identically here and to the aggregate.
     */
    @Synchronized
    fun recordUsage(
        host: String,
        model: String,
        requestId: String?,
        found: LongArray,
        providerId: String
    ) {
        require(found.size == 4) { "usage quad must be (in, out, cacheR, cacheW)" }
        addTokens(found[0], found[1], found[2], found[3], host, model)
        if (requestId != null) {
            creditOutput(seriesKey(providerId, model), requestId, found[1], System.currentTimeMillis())
        }
    }

    @Synchronized
    fun rateHistory(nSecs: Int = RATE_CHART_SECS, atMs: Long = System.currentTimeMillis()): List<Long> {
        val n = nSecs.coerceIn(1, RATE_HISTORY_SECS)
        val nowSec = atMs / 1000
        return List(n) { i -> rateBuckets[nowSec - n + 1 + i] ?: 0L }
    }

    /**
     * One (provider, model) output-rate series, as a slice of the aggregate
     * ring rather than a resample of it. [perSecond][i] is the second
     * `atMs/1000 - perSecond.size + 1 + i`, i.e. oldest first and index-
     * aligned with [rateHistory] of the same size, so a chart can stack
     * these and the stack's column i sums to the aggregate's column i.
     * [total] is the in-window sum of [perSecond] (a series idle for the
     * whole window is still reported, with zeros, so its legend/colour
     * survives a lull).
     */
    data class RateSeries(
        val provider: String,
        val model: String,
        val perSecond: LongArray,
        val total: Long
    ) {
        /**
         * Stable identity of this series, for a caller that needs one (the
         * chart hashes it to pick a segment colour that survives reordering
         * and re-polling). Derived, not stored: it is exactly the ring key.
         */
        val key: String get() = seriesKey(provider, model)

        /** Value equality, so two reads of the same series compare equal. */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RateSeries) return false
            return provider == other.provider && model == other.model &&
                total == other.total && perSecond.contentEquals(other.perSecond)
        }

        override fun hashCode(): Int {
            var h = provider.hashCode()
            h = 31 * h + model.hashCode()
            h = 31 * h + total.hashCode()
            h = 31 * h + perSecond.contentHashCode()
            return h
        }
    }

    private val RATE_SERIES_ORDER: Comparator<RateSeries> =
        compareByDescending<RateSeries> { it.total }
            .thenBy { it.provider }
            .thenBy { it.model }

    /**
     * Per-(provider, model) output tok/s over the trailing [nSecs] seconds
     * ending at [atMs], heaviest first, ties broken by provider then model.
     * The total ordering makes the result deterministic across polls, so
     * the UI can keep a stable stacking order and a stable colour per
     * series.
     *
     * Summed over every series this equals `rateHistory(nSecs, atMs).sum()`
     * — see the section note above for why that is structural, and
     * [MAX_RATE_SERIES] for the single documented exception (a series evicted
     * by the cap). Deliberately NOT truncated to a top-N here: a folded list
     * would stop summing to the aggregate on every poll, not just under
     * pathological model churn. Callers that draw a bounded number of stacks
     * fold afterwards.
     *
     * Costs one LongArray(nSecs) per live series and nothing else: the
     * in-window seconds are walked with a cursor into the ring rather than
     * by rebuilding the retention window, and a series idle for the whole
     * window costs a single failed probe.
     */
    @Synchronized
    fun rateSeries(
        nSecs: Int = RATE_WINDOW_SECS,
        atMs: Long = System.currentTimeMillis()
    ): List<RateSeries> {
        val n = nSecs.coerceIn(1, RATE_HISTORY_SECS)
        val nowSec = atMs / 1000
        val firstSec = nowSec - n + 1
        val out = ArrayList<RateSeries>(rateSeriesRings.size)
        for ((key, ring) in rateSeriesRings) {
            val perSecond = LongArray(n)
            var total = 0L
            val buckets = ring.buckets
            var cursor = buckets.ceilingEntry(firstSec)
            while (cursor != null && cursor.key <= nowSec) {
                val i = (cursor.key - firstSec).toInt()
                perSecond[i] = cursor.value
                total += cursor.value
                cursor = buckets.higherEntry(cursor.key)
            }
            val (provider, model) = splitSeriesKey(key)
            out.add(RateSeries(provider, model, perSecond, total))
        }
        out.sortWith(RATE_SERIES_ORDER)
        return out
    }

    fun hostSummary(top: Int = 5): List<Triple<String, Long, Long>> =
        hostTallies.entries.sortedByDescending { it.value.upBytes + it.value.downBytes }
            .take(top).map { Triple(it.key, it.value.upBytes, it.value.downBytes) }

    // ---- Per-(host, model) token tallies (feeds the token table;
    // globals stay the totals row). Model "" = unattributed (tunnels).
    data class TokenTally(
        var inTokens: Long = 0,
        var outTokens: Long = 0,
        var cacheRead: Long = 0,
        var cacheWrite: Long = 0
    ) {
        fun total(): Long = inTokens + outTokens
    }

    private val tokenTallies = ConcurrentHashMap<String, TokenTally>()

    /** Composite key; split back with [splitTallyKey]. */
    fun tallyKey(host: String, model: String): String =
        host.lowercase() + "\u0001" + model.lowercase()

    private fun splitTallyKey(key: String): Pair<String, String> {
        val i = key.indexOf('\u0001')
        return if (i < 0) key to "" else key.substring(0, i) to key.substring(i + 1)
    }

    data class TokenRow(
        val host: String,
        val model: String,
        val inTokens: Long,
        val outTokens: Long,
        val cacheRead: Long,
        val cacheWrite: Long
    )

    fun tokenSummary(top: Int = 5): List<TokenRow> =
        tokenTallies.entries.sortedByDescending { it.value.total() }
            .take(top)
            .map { (k, t) ->
                val (h, m) = splitTallyKey(k)
                TokenRow(h, m, t.inTokens, t.outTokens, t.cacheRead, t.cacheWrite)
            }

    /**
     * Keep at most [maxPerHost] rows for any one host, preserving the
     * incoming (total-sorted) order. Pure (unit-tested).
     */
    fun capPerHost(rows: List<TokenRow>, maxPerHost: Int): List<TokenRow> {
        if (maxPerHost <= 0) return emptyList()
        val seen = java.util.HashMap<String, Int>()
        val out = ArrayList<TokenRow>(rows.size)
        for (r in rows) {
            val n = seen.getOrDefault(r.host, 0)
            if (n >= maxPerHost) continue
            seen[r.host] = n + 1
            out.add(r)
        }
        return out
    }

    fun resetTallies() {
        hostTallies.clear()
        inputTokens = 0
        outputTokens = 0
        cacheReadTokens = 0
        cacheWriteTokens = 0
        tokenTallies.clear()
        rateBuckets.clear()
        // Series rings too: a reset that left them behind would re-create
        // output the aggregate has forgotten and break the sum identity.
        rateSeriesRings.clear()
        firstOutputMs = 0L
    }

    // usage-block scanner: finds Anthropic + OpenAI token fields in a
    // buffered body (plain JSON or SSE stream chunk). Returns
    // (input, output, cacheRead, cacheWrite).

    /** "No value credited yet" marker for StreamingUsage's per-key trackers. */
    private const val USAGE_UNSET = -1L
    /** Quad slot whose provider semantics are a per-response running total. */
    private const val SLOT_OUTPUT = 1
    private val tokNum = { key: String, body: String ->
        Regex(""""$key"\s*:\s*(\d+)""").findAll(body).map { it.groupValues[1].toLong() }.sum()
    }


    fun scanUsage(body: String): LongArray {
        val anthIn = tokNum("input_tokens", body)
        val anthOut = tokNum("output_tokens", body)
        val cacheRead = tokNum("cache_read_input_tokens", body)
        // Anthropic reports writes as cache_creation_input_tokens; the
        // OpenAI-compatible gateways most clients actually talk to report
        // them as prompt_tokens_details.cache_write_tokens. Without the
        // second spelling the cacheW column is structurally always 0.
        // These are two names for ONE number, not two components, so take
        // the larger: a gateway that echoed both must not be counted twice.
        val cacheWrite = maxOf(
            tokNum("cache_creation_input_tokens", body),
            tokNum("cache_write_tokens", body)
        )
        val oaiIn = tokNum("prompt_tokens", body)
        val oaiOut = tokNum("completion_tokens", body)
        // prompt_tokens INCLUDES cached tokens on OpenAI; keep raw sums.
        val oaiCached = tokNum("cached_tokens", body)
        return longArrayOf(anthIn + oaiIn, anthOut + oaiOut, cacheRead + oaiCached, cacheWrite)
    }


}