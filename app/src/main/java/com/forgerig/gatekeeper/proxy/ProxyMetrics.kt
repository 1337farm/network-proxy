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
    val category: String = "unknown"
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
            category = requestMetrics[requestId]?.category ?: categorizeUrl(requestMetrics[requestId]?.url ?: "")
        )
        requestMetrics[requestId] = metrics
        incrementScenario(scenario)
    }

    fun recordRetry(sessionId: String, requestId: String, attempt: Int, scenario: NetworkScenario, delayMs: Long) {
        retryCounts[scenario.name] = retryCounts.getOrDefault(scenario.name, 0) + 1
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
            category = prev?.category ?: categorizeUrl(prev?.url ?: "")
        )
        incrementScenario(scenario)
    }

    fun incrementScenario(scenario: NetworkScenario) {
        scenarioCounts[scenario.name] = scenarioCounts.getOrDefault(scenario.name, 0) + 1
    }

    fun incrementRetryType(retryType: RetryType) {
        retryCounts[retryType.name] = retryCounts.getOrDefault(retryType.name, 0) + 1
    }

    fun snapshot(): MetricsSnapshot {
        return MetricsSnapshot(
            sessions = sessionMetrics.values.toList(),
            requests = requestMetrics.values.toList(),
            scenarioCounts = scenarioCounts.toMap(),
            retryCounts = retryCounts.toMap(),
            startTime = sessionStartTimes.values.minOrNull() ?: System.currentTimeMillis(),
            endTime = null
        )
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
    fun addTokens(input: Long, output: Long, cacheRead: Long, cacheWrite: Long, host: String = "") {
        inputTokens += input
        outputTokens += output
        cacheReadTokens += cacheRead
        cacheWriteTokens += cacheWrite
        if (host.isNotBlank()) {
            val t = tokenTallies.getOrPut(host) { TokenTally() }
            t.inTokens += input
            t.outTokens += output
            t.cacheRead += cacheRead
            t.cacheWrite += cacheWrite
        }
        if (output > 0) {
            val now = System.currentTimeMillis()
            if (firstOutputMs == 0L) firstOutputMs = now
            tokenEventTimes.add(now to output)
            pruneTokenEvents(now)
            sampleOutput(output, now)
        }
    }

    // ---- Output tokens/sec (trailing window over completion tokens) ----
    private val tokenEventTimes = ConcurrentLinkedQueue<Pair<Long, Long>>()
    private const val TPS_WINDOW_MS = 30_000L
    /** Set on the first counted output token; session average hangs off it. */
    @Volatile private var firstOutputMs: Long = 0L

    private fun pruneTokenEvents(now: Long, windowMs: Long = TPS_WINDOW_MS) {
        while (true) {
            val head = tokenEventTimes.peek() ?: break
            if (now - head.first <= windowMs) break
            tokenEventTimes.poll()
        }
    }

    /** Output tokens per second over the trailing window (default 30s). */
    @Synchronized
    fun outputTokensPerSecond(windowMs: Long = TPS_WINDOW_MS): Double {
        if (windowMs <= 0) return 0.0
        val now = System.currentTimeMillis()
        pruneTokenEvents(now, windowMs)
        var sum = 0L
        for ((_, n) in tokenEventTimes) sum += n
        return sum.toDouble() / (windowMs / 1000.0)
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
    private val rateBuckets = java.util.ArrayDeque<Pair<Long, Long>>()
    const val RATE_CHART_SECS = 60

    /** Fold output tokens into the current second-bucket (test seam: [atMs]). */
    @Synchronized
    fun sampleOutput(count: Long, atMs: Long = System.currentTimeMillis()) {
        if (count <= 0) return
        val sec = atMs / 1000
        val last = rateBuckets.peekLast()
        if (last != null && last.first == sec) {
            rateBuckets.removeLast()
            rateBuckets.addLast(sec to last.second + count)
        } else {
            rateBuckets.addLast(sec to count)
        }
        while (rateBuckets.size > RATE_CHART_SECS) rateBuckets.removeFirst()
    }

    /**
     * Last [nSecs] per-second output counts, oldest-first, zero-filled for
     * idle seconds (trailing). Pure shape: list size always == nSecs.
     */
    @Synchronized
    fun rateHistory(nSecs: Int = RATE_CHART_SECS, atMs: Long = System.currentTimeMillis()): List<Long> {
        val n = nSecs.coerceIn(1, RATE_CHART_SECS)
        val nowSec = atMs / 1000
        val map = HashMap<Long, Long>(rateBuckets.size * 2)
        for ((s, c) in rateBuckets) map[s] = (map[s] ?: 0L) + c
        return List(n) { i -> map[nowSec - n + 1 + i] ?: 0L }
    }

    fun hostSummary(top: Int = 5): List<Triple<String, Long, Long>> =
        hostTallies.entries.sortedByDescending { it.value.upBytes + it.value.downBytes }
            .take(top).map { Triple(it.key, it.value.upBytes, it.value.downBytes) }

    // ---- Per-host token tallies (feeds the token table; globals above
    // stay the totals row). Host is passed by every addTokens call site.
    data class TokenTally(
        var inTokens: Long = 0,
        var outTokens: Long = 0,
        var cacheRead: Long = 0,
        var cacheWrite: Long = 0
    ) {
        fun total(): Long = inTokens + outTokens
    }

    private val tokenTallies = ConcurrentHashMap<String, TokenTally>()

    data class TokenRow(
        val host: String,
        val inTokens: Long,
        val outTokens: Long,
        val cacheRead: Long,
        val cacheWrite: Long
    )

    fun tokenSummary(top: Int = 5): List<TokenRow> =
        tokenTallies.entries.sortedByDescending { it.value.total() }
            .take(top)
            .map { (h, t) -> TokenRow(h, t.inTokens, t.outTokens, t.cacheRead, t.cacheWrite) }

    fun resetTallies() {
        hostTallies.clear()
        inputTokens = 0
        outputTokens = 0
        cacheReadTokens = 0
        cacheWriteTokens = 0
        tokenTallies.clear()
        tokenEventTimes.clear()
        rateBuckets.clear()
        firstOutputMs = 0L
    }

    // usage-block scanner: finds Anthropic + OpenAI token fields in a
    // buffered body (plain JSON or SSE stream chunk). Returns
    // (input, output, cacheRead, cacheWrite).
    private val tokNum = { key: String, body: String ->
        Regex(""""$key"\s*:\s*(\d+)""").findAll(body).map { it.groupValues[1].toLong() }.sum()
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

        /** Count a deferred trailing match at stream end. Idempotent. */
        fun flush(): LongArray {
            val out = LongArray(4)
            pendingText?.let { addMatch(out, it) }
            pendingText = null
            return out
        }

        private fun addMatch(out: LongArray, matchText: String) {
            val m = keyValue.find(matchText) ?: return
            val value = m.groupValues[2].toLongOrNull() ?: return
            when (m.groupValues[1]) {
                "input_tokens", "prompt_tokens" -> out[0] += value
                "output_tokens", "completion_tokens" -> out[1] += value
                "cache_read_input_tokens", "cached_tokens" -> out[2] += value
                "cache_creation_input_tokens" -> out[3] += value
            }
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