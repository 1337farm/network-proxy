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


    /** Fold output tokens into the current second-bucket (test seam: [atMs]). */
    @Synchronized
    fun sampleOutput(count: Long, atMs: Long = System.currentTimeMillis()) {
        if (count <= 0) return
        addToBucket(atMs / 1000, count)
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
    fun sampleOutputUnknownSpan(count: Long, atMs: Long = System.currentTimeMillis()) {
        if (count <= 0) return
        val plan = spreadPlan(count, MIN_SPAN_SECS)
        val endSec = atMs / 1000
        val base = endSec - plan.size + 1
        plan.forEachIndexed { i, c -> addToBucket(base + i, c) }
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
     * Credit output tokens across the request's ACTIVE seconds (first to
     * last) instead of the arrival second. Usage totals arrive in the
     * final chunk, so arrival-bucketing fabricates spikes (2k tokens
     * after a 40s think reads as 2000 tok/s instead of ~49 tok/s).
     * Even split, remainder to the last bucket. Falls back to a
     * [MIN_SPAN_SECS] smear when the request start is unknown.
     */
    @Synchronized
    fun sampleOutputSpread(requestId: String, count: Long, atMs: Long = System.currentTimeMillis()) {
        if (count <= 0) return
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
        val startMs = requestStartTimes[requestId] ?: 0L
        val spanSecs = if (startMs > 0) ((atMs - startMs) / 1000).toInt() else 0
        // Bound work and ring pressure; density stays honest for real spans.
        val plan = spreadPlan(count, smoothedSpanSecs(spanSecs))
        val endSec = atMs / 1000
        val base = endSec - plan.size + 1
        plan.forEachIndexed { i, c -> addToBucket(base + i, c) }
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
     */
    @Synchronized
    fun recordUsage(host: String, model: String, requestId: String?, found: LongArray) {
        require(found.size == 4) { "usage quad must be (in, out, cacheR, cacheW)" }
        addTokens(found[0], found[1], found[2], found[3], host, model)
        if (requestId != null) {
            sampleOutputSpread(requestId, found[1])
        }
    }
    @Synchronized
    fun rateHistory(nSecs: Int = RATE_CHART_SECS, atMs: Long = System.currentTimeMillis()): List<Long> {
        val n = nSecs.coerceIn(1, RATE_HISTORY_SECS)
        val nowSec = atMs / 1000
        return List(n) { i -> rateBuckets[nowSec - n + 1 + i] ?: 0L }
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

    private val modelName = Regex(""""model"\s*:\s*"([^"\\]{1,80})"""")

    /**
     * Best-effort model id from request head bytes (both families use a
     * top-level "model" field, usually early in the body). "" when absent.
     * Feeds per-model tallies for MITM tunnels, whose bodies otherwise
     * stay opaque to the proxy.
     */
    fun sniffModel(head: ByteArray, len: Int = head.size): String {
        if (len <= 0) return ""
        return try {
            val text = head.copyOf(len.coerceAtMost(head.size)).toString(Charsets.UTF_8)
            modelName.find(text)?.groupValues?.getOrNull(1)?.trim() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun scanUsage(body: String): LongArray {
        val anthIn = tokNum("input_tokens", body)
        val anthOut = tokNum("output_tokens", body)
        val cacheRead = tokNum("cache_read_input_tokens", body)
        val cacheWrite = tokNum("cache_creation_input_tokens", body)
        val oaiIn = tokNum("prompt_tokens", body)
        val oaiOut = tokNum("completion_tokens", body)
        // prompt_tokens INCLUDES cached tokens on OpenAI; keep raw sums.
        val oaiCached = tokNum("cached_tokens", body)
        return longArrayOf(anthIn + oaiIn, anthOut + oaiOut, cacheRead + oaiCached, cacheWrite)
    }

    /**
     * Incremental usage scanner for live MITM downstream bytes.
     *
     * The old code scanned the tap once at tunnel close — but upstream
     * keep-alive means close can lag the response by minutes (or never
     * happen while the client reuses the connection), so the UI sat at
     * zero. This counts usage fields per chunk as they stream, keeping a
     * short carry so a field split across a chunk boundary is counted
     * exactly once (matches fully inside the carry region were already
     * counted by an earlier feed and are skipped).
     *
     * Digit runs need care: `\d+` also matches a PREFIX of a number still
     * arriving (`"prompt_tokens":78` of `7843`), which would then count
     * twice. A match ending at the very end of the buffer is therefore
     * deferred (remembered as [pendingText]) and resolved on the next
     * feed — or via [flush] at stream end.
     *
     * ## PER-KEY SEMANTICS (the providers do not agree, so neither do we)
     *
     * | quad slot | keys                                  | semantics   |
     * |-----------|---------------------------------------|-------------|
     * | 0 input   | `input_tokens`, `prompt_tokens`       | ONE-SHOT    |
     * | 1 output  | `output_tokens`, `completion_tokens`  | CUMULATIVE  |
     * | 2 cacheR  | `cache_read_input_tokens`, `cached_tokens` | ONE-SHOT |
     * | 3 cacheW  | `cache_creation_input_tokens`         | ONE-SHOT    |
     *
     * **CUMULATIVE (output):** Anthropic-style SSE re-reports
     * `usage.output_tokens` as a RUNNING TOTAL in every `message_delta`
     * frame (12, then 40, then 150 for a 150-token completion), so crediting
     * each frame verbatim summed to 202 and the rate read ~35% hot — worse
     * with every extra frame, which is the 2k+ tok/s spike the user saw.
     * Rule: value >= last -> credit the increase; an exactly repeated value
     * credits 0 (replay / duplicate frame); value < last -> the running
     * total restarted, i.e. a NEW response on this connection, credit in
     * full.
     *
     * **ONE-SHOT (input / cache read / cache write):** the provider sends
     * these ONCE per response, so there is nothing to diff. Crediting the
     * full value on the first sighting of a response and IGNORING repeats
     * within that same response is the only rule that is right. Diffing
     * them was simply wrong: on a keep-alive connection one scanner spans
     * many responses, so a second response reporting a LARGER
     * `input_tokens` (a growing conversation: 100, then 350) was credited
     * as only the 250 difference and input tokens under-counted by 250.
     * Note `input_tokens` counts only the UNCACHED part of the prompt, so
     * it is not even monotonic across a conversation — no difference rule
     * can be right for it, which is exactly why it is one-shot.
     *
     * ## RESPONSE BOUNDARIES
     *
     * Authoritative, cheap and explicit: [beginResponse].
     *
     * Automatic fallback, so behaviour stays correct when nobody calls it
     * ([addMatch]):
     *  - cumulative: the decrease rule above (a restarted total).
     *  - one-shot: an exactly REPEATED value is the same response (a
     *    re-sent usage object) and credits 0; a CHANGED value is taken as
     *    a new response and is credited in full. This is the rule that
     *    turns a growing `input_tokens` into 100 + 350 = 450.
     *
     * State is per SCANNER INSTANCE. It is deliberately NOT keyed on
     * requestId: that id is minted per client CONNECTION, so a
     * per-requestId watermark diffed every response on a keep-alive tunnel
     * against the previous one and swallowed real tokens (the regression
     * this design replaces).
     *
     * Providers that report a single one-shot usage block (OpenAI) are
     * unaffected: their only match per response is a first sighting and is
     * credited in full.
     *
     * ## RELAY HOOK — REQUIRED IN `ProxyService` (not owned by this file)
     *
     * The explicit boundary is what makes this exact, and it lives in the
     * MITM downstream relay. Signature:
     *
     * ```
     * fun ProxyMetrics.StreamingUsage.beginResponse(): LongArray
     * ```
     *
     * It returns the credits it flushed (see its doc) and the caller must
     * push them through `ProxyMetrics.recordUsage(host, model, requestId,
     * …)` like any other quad. Add this inside
     * `private fun relayTap(input, output, counter, tap, tapCap, liveUsage,
     * usageHost, requestId, modelRef, sniff)` — ProxyService.kt:905 —
     * in the downstream copy loop, immediately BEFORE the existing
     * `liveUsage.feed(buf, n)` call at ProxyService.kt:936-937, on the
     * branch that detects a fresh downstream HTTP response (an
     * `HTTP/1.x ` status line at the head of the chunk):
     *
     * ```kotlin
     * if (liveUsage != null) {
     *     if (isResponseHead(buf, n)) {           // NEW: keep-alive boundary
     *         val b = liveUsage.beginResponse()
     *         if (b[0] + b[1] + b[2] + b[3] > 0) {
     *             ProxyMetrics.recordUsage(usageHost, modelRef?.get() ?: "", requestId, b)
     *         }
     *     }
     *     val found = liveUsage.feed(buf, n)
     *     …
     * }
     * ```
     *
     * Calling it before the first response of a connection is a harmless
     * no-op (the trackers start unset). Skipping it entirely still gives
     * the correct 100 + 350 = 450 via the automatic fallback; the hook only
     * removes the remaining ambiguity (a one-shot value that is identical
     * across two consecutive responses is counted once, not twice).
     */
    class StreamingUsage {
        // Carry is decoded CHARS (not bytes) so every index below is exact;
        // a multi-byte char split across a chunk boundary degrades to
        // U+FFFD noise on both sides, which never sits inside an ASCII
        // `"key":digits` span and so can't break matching.
        private var carryText = ""
        private val carryMax = 160
        private var pendingText: String? = null
        private val patterns = listOf(
            "input_tokens", "output_tokens",
            "cache_read_input_tokens", "cache_creation_input_tokens",
            "prompt_tokens", "completion_tokens", "cached_tokens"
        ).map { key -> key to Regex(""""$key"\s*:\s*(\d+)(?!\d)""") }
        private val keyValue = Regex(""""(\w+)"\s*:\s*(\d+)$""")

        /**
         * Running-total watermark for the CUMULATIVE slot (output), for the
         * response stream this scanner is watching. [USAGE_UNSET] means
         * "nothing credited yet" — a first sighting is credited in full.
         */
        private var outputWatermark = USAGE_UNSET

        /**
         * Per ONE-SHOT slot (input, cache read, cache write): the value
         * already credited for the CURRENT response, and whether one has
         * been credited at all. A one-shot field is sent once per response,
         * so a sighting while [oneShotCredited] is set is a re-sent usage
         * object — credited 0 when identical, or taken as the start of a
         * new response when it differs.
         */
        private val oneShotCredited = BooleanArray(4)
        private val oneShotValue = LongArray(4)

        fun feed(chunk: ByteArray, len: Int): LongArray {
            val out = LongArray(4)
            if (len <= 0) return out
            val text = carryText + chunk.copyOf(len).toString(Charsets.UTF_8)
            val carryChars = carryText.length
            // Resolve the previous deferred match, which ends exactly where
            // the new bytes begin. If the digit run continued, the extended
            // match below counts it; otherwise it was complete — count now.
            pendingText?.let { p ->
                val at = carryChars - p.length
                if (at >= 0 && text.regionMatches(at, p, 0, p.length) &&
                    at + p.length == carryChars
                ) {
                    if (!text[carryChars].isDigit()) addMatch(out, p)
                } else {
                    // Misaligned (shouldn't happen): locate the deferred
                    // span in already-seen bytes and count it iff its run
                    // provably ended there.
                    val found = text.lastIndexOf(p, (carryChars - 1).coerceAtLeast(0))
                    if (found >= 0 && found + p.length <= carryChars &&
                        (found + p.length >= text.length || !text[found + p.length].isDigit())
                    ) addMatch(out, p)
                }
                pendingText = null
            }
            for ((_, re) in patterns) {
                for (m in re.findAll(text)) {
                    if (m.range.last == text.length - 1) {
                        // Ends at the buffer edge: possibly a partial digit
                        // run — defer to the next feed / flush.
                        pendingText = m.value
                    } else if (m.range.last >= carryChars) {
                        addMatch(out, m.value)
                    }
                }
            }
            carryText = text.takeLast(minOf(carryMax, text.length))
            return out
        }

        /**
         * Declare that a NEW response has started on this connection, so the
         * one-shot keys are credited in full again and the output running
         * total restarts.
         *
         * This is the explicit, authoritative alternative to guessing a
         * boundary from the byte stream; the class doc gives the exact call
         * site and signature the `ProxyService` relay owner must add inside
         * `relayTap`. Cheap and allocation-free apart from the returned quad,
         * and a no-op when called before the first response.
         *
         * Any match deferred by [feed] (a trailing digit run at a chunk edge)
         * belongs to the response that is ENDING, so it is credited HERE
         * against the old trackers before they are cleared — credited
         * exactly once, and as a delta of the finished response's running
         * total rather than as a fresh full value. The caller must push the
         * returned quad through `ProxyMetrics.recordUsage` like any other.
         *
         * [carryText] is deliberately left intact: it is a byte-window
         * artefact, not accounting state, and dropping it could lose a value
         * whose key was split across a chunk edge.
         */
        fun beginResponse(): LongArray {
            val out = LongArray(4)
            pendingText?.let { addMatch(out, it) }
            pendingText = null
            outputWatermark = USAGE_UNSET
            oneShotCredited.fill(false)
            oneShotValue.fill(0L)
            return out
        }

        /**
         * Credit a deferred trailing match and close the response boundary.
         *
         * Idempotent: the pending match is consumed (nulled) by the call that
         * credits it, and a match already credited by [feed] never reaches
         * here, so a second [flush] returns zeros. After crediting, the
         * per-key trackers are cleared because everything seen so far belongs
         * to the response that just ended — anything this scanner matches
         * next is credited from scratch rather than diffed against a total
         * that is no longer running. Same reset as [beginResponse], which
         * makes flush a valid (end-of-stream) response boundary too.
         */
        fun flush(): LongArray {
            val out = LongArray(4)
            pendingText?.let { addMatch(out, it) }
            pendingText = null
            outputWatermark = USAGE_UNSET
            oneShotCredited.fill(false)
            oneShotValue.fill(0L)
            return out
        }

        /**
         * Credit one matched `"key":digits` span under the per-key semantics
         * documented on the class: the output slot is a running total and is
         * credited as the increase since its watermark; the input and cache
         * slots are one-shot per response, credited in full on their first
         * sighting of a response and 0 on an identical repeat.
         */
        private fun addMatch(out: LongArray, matchText: String) {
            val m = keyValue.find(matchText) ?: return
            val value = m.groupValues[2].toLongOrNull() ?: return
            val slot = when (m.groupValues[1]) {
                "input_tokens", "prompt_tokens" -> 0
                "output_tokens", "completion_tokens" -> 1
                "cache_read_input_tokens", "cached_tokens" -> 2
                "cache_creation_input_tokens" -> 3
                else -> return
            }
            if (slot == SLOT_OUTPUT) {
                // CUMULATIVE. No prior value, or the running total restarted
                // (new response on this connection): credit in full.
                // Otherwise credit the increase, which is 0 for an exact
                // repeat (replayed frame).
                val prev = outputWatermark
                val delta = if (prev == USAGE_UNSET || value < prev) value else value - prev
                outputWatermark = value
                out[slot] += delta
                return
            }
            // ONE-SHOT. Never diffed: a repeat inside the same response
            // credits 0, and a CHANGED value is the automatic new-response
            // signal, so a growing input_tokens (100 then 350) sums to 450
            // rather than being diffed down to 250.
            if (oneShotCredited[slot] && value == oneShotValue[slot]) return
            oneShotCredited[slot] = true
            oneShotValue[slot] = value
            out[slot] += value
        }
    }

    /**
     * Close-of-tunnel fallback for the MITM tap. Live [StreamingUsage]
     * already counted every plaintext byte as it streamed, so a plaintext
     * tap returns zeros here (recounting would double). Encoded bodies
     * (gzip/deflate — opaque to the live scanner) are de-chunked,
     * inflated and scanned instead.
     */
    fun scanTapBytesForClose(raw: ByteArray): LongArray {
        if (raw.isEmpty()) return LongArray(4)
        val headEnd = indexOfHeaderEnd(raw)
        val headLen = if (headEnd in 1..32768) headEnd else minOf(raw.size, 32768)
        val head = raw.copyOf(headLen).toString(Charsets.UTF_8)
        val enc = Regex("""(?i)content-encoding\s*:\s*([^\r\n]+)""")
            .find(head)?.groupValues?.get(1) ?: ""
        val gzipped = enc.contains("gzip", ignoreCase = true)
        val deflated = enc.contains("deflate", ignoreCase = true)
        if (!gzipped && !deflated) return LongArray(4)
        val body = if (headEnd > 0) raw.copyOfRange(headEnd, raw.size) else raw
        val plain = tryDecodeBody(body, gzipped) ?: return LongArray(4)
        return scanUsage(plain)
    }

    private fun indexOfHeaderEnd(raw: ByteArray): Int {
        var i = 0
        while (i + 3 < raw.size) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
            ) return i + 4
            i++
        }
        return -1
    }

    private fun tryDecodeBody(body: ByteArray, gzipped: Boolean): String? {
        // Bodies are usually chunk-framed; strip framing first (best effort).
        val framed = tryDechunk(body) ?: body
        return try {
            val stream = if (gzipped) GZIPInputStream(framed.inputStream())
            else InflaterInputStream(framed.inputStream())
            stream.readBytes().toString(Charsets.UTF_8).takeIf { it.contains("{") }
        } catch (_: Exception) {
            null
        }
    }

    /** Best-effort HTTP/1.1 chunked decoder. Null when not chunk-framed. */
    fun tryDechunk(body: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        var i = 0
        var chunks = 0
        fun readLine(): String? {
            var j = i
            while (j + 1 < body.size &&
                !(body[j] == '\r'.code.toByte() && body[j + 1] == '\n'.code.toByte())
            ) j++
            if (j + 1 >= body.size) return null
            val line = body.copyOfRange(i, j).toString(Charsets.UTF_8)
            i = j + 2
            return line
        }
        while (i < body.size) {
            val line = readLine() ?: break
            val size = line.trim().substringBefore(";").toLongOrNull(16)
                ?: return if (chunks > 0) out.toByteArray() else null
            if (size == 0L) {
                // Skip trailers, then continue (pipelined responses); the
                // next head line won't parse as hex and ends decoding.
                while (true) {
                    val t = readLine() ?: break
                    if (t.isEmpty()) break
                }
                continue
            }
            if (size > 64 * 1024 * 1024) return null
            if (i + size + 2 > body.size) return if (chunks > 0) out.toByteArray() else null
            out.write(body, i, size.toInt())
            i += size.toInt() + 2 // skip data + trailing CRLF
            chunks++
        }
        return if (chunks > 0) out.toByteArray() else null
    }
}