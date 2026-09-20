package com.forgerig.gatekeeper.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    val scenario: String
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
                scenario = obj.getString("scenario")
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
            scenario = NetworkScenario.UNKNOWN.name
        )
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
            scenario = scenario.name
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
}