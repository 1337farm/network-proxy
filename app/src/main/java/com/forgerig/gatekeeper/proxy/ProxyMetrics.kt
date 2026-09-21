package com.forgerig.gatekeeper.proxy

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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

    fun recordRequestStart(sessionId: String, requestId: String, url: String, method: String) {
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
            category = categorizeUrl(url)
        )
    }

    private fun categorizeUrl(url: String): String {
        val lowerUrl = url.lowercase()
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
        if (filePatterns.any { lowerUrl.contains(it) }) return "file"
        if (apiPatterns.any { lowerUrl.contains(it) }) return "api"
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

<<<<<<< Updated upstream
    fun recordRetry(sessionId: String, requestId: String, attempt: Int, scenario: NetworkScenario, delayMs: Long) {
        retryCounts[scenario.name] = retryCounts.getOrDefault(scenario.name, 0) + 1
=======
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

    fun recordRetry(sessionId: String, requestId: String, attempt: Int, scenario: NetworkScenario, delayMs: Long) {        retryCounts[scenario.name] = retryCounts.getOrDefault(scenario.name, 0) + 1
>>>>>>> Stashed changes
        sessionMetrics[sessionId]?.let {
            sessionMetrics[sessionId] = it.copy(
                resumeCount = it.resumeCount + 1,
                scenarios = it.scenarios + scenario.name
            )
        }
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
    fun addTokens(input: Long, output: Long, cacheRead: Long, cacheWrite: Long) {
        inputTokens += input
        outputTokens += output
        cacheReadTokens += cacheRead
        cacheWriteTokens += cacheWrite
    }

    fun hostSummary(top: Int = 5): List<Triple<String, Long, Long>> =
        hostTallies.entries.sortedByDescending { it.value.upBytes + it.value.downBytes }
            .take(top).map { Triple(it.key, it.value.upBytes, it.value.downBytes) }

    fun resetTallies() {
        hostTallies.clear()
        inputTokens = 0
        outputTokens = 0
        cacheReadTokens = 0
        cacheWriteTokens = 0
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
}