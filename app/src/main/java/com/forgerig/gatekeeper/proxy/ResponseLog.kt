package com.forgerig.gatekeeper.proxy

/**
 * In-memory ring buffer of recent response payloads, for the verbose
 * response-logging toggle in the dashboard (PRIVACY: opt-in, off by
 * default, RAM-only — nothing here is exported or persisted).
 *
 * Bounded twice over: at most [MAX_ENTRIES] entries (oldest evicted)
 * and at most [MAX_ENTRY_BYTES] body bytes per entry (truncated), so a
 * client that leaves the toggle on cannot grow this without limit. The
 * buffer is only fed JSON/SSE taps (capped at 512KB upstream), which
 * this trims to the first 256KB.
 *
 * Thread-safe: the proxy pool writes from relay threads while the UI
 * thread snapshots on a 2s poll, so every method is synchronized on
 * this object's monitor.
 */
object ResponseLog {

    /** Hard cap on retained entries; the oldest is evicted. */
    const val MAX_ENTRIES = 20

    /** Per-entry body cap; longer bodies are truncated on store. */
    const val MAX_ENTRY_BYTES = 256 * 1024

    /** Preview length shown in the dashboard list rows. */
    const val PREVIEW_CHARS = 200

    /**
     * One retained response: when it arrived, which host it was for,
     * the response content type, and the (truncated) body bytes.
     */
    data class ResponseEntry(
        val timestampMs: Long,
        val host: String,
        val contentType: String,
        val body: ByteArray
    ) {
        /**
         * One-line dashboard preview: the body decoded as UTF-8,
         * whitespace-collapsed, first [PREVIEW_CHARS] chars.
         */
        fun preview(): String =
            String(body, Charsets.UTF_8)
                .replace(Regex("\\s+"), " ")
                .take(PREVIEW_CHARS)
    }

    /** Newest first. */
    private val entries = java.util.ArrayDeque<ResponseEntry>()

    /** Store [body] (truncated to [MAX_ENTRY_BYTES]); evicts the oldest when full. */
    @Synchronized
    fun add(host: String, contentType: String, body: ByteArray) {
        val capped = if (body.size > MAX_ENTRY_BYTES) body.copyOf(MAX_ENTRY_BYTES) else body
        entries.addFirst(ResponseEntry(System.currentTimeMillis(), host, contentType, capped))
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    /** Newest-first copy for the UI poll. */
    @Synchronized
    fun snapshot(): List<ResponseEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
