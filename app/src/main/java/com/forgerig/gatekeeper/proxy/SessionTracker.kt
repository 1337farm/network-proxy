package com.forgerig.gatekeeper.proxy

/**
 * Which key is running which session: live registry of brokered and
 * provider-matched sessions. Plain-HTTP brokered requests record the
 * proxy-injected key label; CONNECT tunnels record the matched provider
 * with "(client key)" (the client holds its own key inside the tunnel)
 * until MITM inner injection lands. Unmatched hosts are absent.
 *
 * RAM-only, cleared per session end. Thread-safe.
 */
object SessionTracker {
    data class SessionInfo(
        val sessionId: String,
        val providerId: String,
        val keyLabel: String,
        val model: String,
        val host: String,
        val startedMs: Long,
        /** Conversation title (first user text) — "" until known. */
        val title: String = "",
        /** Latest routing event ("429 → key-b", "spill → openrouter/m") + time. */
        val lastEvent: String = "",
        val lastEventMs: Long = 0L
    )

    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SessionInfo>()

    fun note(
        sessionId: String,
        providerId: String,
        keyLabel: String,
        model: String,
        host: String,
        startedMs: Long = System.currentTimeMillis(),
        title: String = ""
    ) {
        // Preserve the original start time (and title, unless a new one
        // arrives) across key rotations mid-request.
        val prev = sessions[sessionId]
        sessions[sessionId] = SessionInfo(
            sessionId, providerId, keyLabel, model, host,
            prev?.startedMs ?: startedMs,
            title.ifBlank { prev?.title ?: "" },
            prev?.lastEvent ?: "",
            prev?.lastEventMs ?: 0L
        )
    }

    /** Stamp a routing event (429, rollover, spill) onto a live session. */
    fun noteEvent(sessionId: String, text: String) {
        val prev = sessions[sessionId] ?: return
        sessions[sessionId] = prev.copy(
            lastEvent = text,
            lastEventMs = System.currentTimeMillis()
        )
    }

    /**
     * Conversation title: first user-text block, whitespace-collapsed,
     * 40 chars. Both families carry messages[] with role/content. ""
     * when the body isn't JSON Chat (tunnels, non-LLM).
     */
    fun titleOf(body: ByteArray?): String {
        if (body == null || body.size > 512 * 1024) return ""
        return try {
            val o = org.json.JSONObject(jsonBody(body).toString(Charsets.UTF_8))
            val arr = o.optJSONArray("messages") ?: return ""
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (m.optString("role") != "user") continue
                val c = m.opt("content") ?: continue
                val text = when (c) {
                    is String -> c
                    is org.json.JSONArray -> buildString {
                        for (j in 0 until c.length()) {
                            val p = c.optJSONObject(j) ?: continue
                            if (p.optString("type") in listOf("text", "input_text")) {
                                append(p.optString("text", ""))
                            }
                        }
                    }
                    else -> ""
                }.replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) {
                    return if (text.length <= 40) text else text.take(40) + "…"
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * The JSON body out of a raw request stream. A tapped MITM request
     * starts with `POST /v1/messages HTTP/1.1` + headers, which makes
     * JSONObject throw ("must begin with '{'") and silently blank every
     * title — so the request line/headers are stripped first. A no-op for
     * callers that already pass a bare body. Pure (unit-tested).
     */
    internal fun jsonBody(raw: ByteArray): ByteArray {
        val headEnd = indexOfHeaderEnd(raw)
        if (headEnd >= 0) return raw.copyOfRange(headEnd, raw.size)
        // No CRLFCRLF yet (tap cut short): a stream that starts with '{'
        // is already the body, anything else has no usable body.
        return if (raw.isNotEmpty() && raw[0] == '{'.code.toByte()) raw else ByteArray(0)
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

    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun clearAll() {
        sessions.clear()
    }

    /** Oldest-first snapshot for the UI list. */
    fun snapshot(): List<SessionInfo> = sessions.values.sortedBy { it.startedMs }
}
