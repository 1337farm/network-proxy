package com.forgerig.gatekeeper.proxy.context

/**
 * Conversation correlation: stateless API requests → conversation state.
 *
 * Strategy (see design doc):
 * - Fast path O(1): hash-chain continuation — req.prefixHash extends a known
 *   conversation tip (the common case: harness appends one turn).
 * - Slow path: longest-common-prefix scan over the K most-recent
 *   conversations (block hashes compared, threshold = all-but-last-turn).
 * - Fail-open: any exception or low confidence → null → caller takes the
 *   legacy passthrough path. This function MUST never throw.
 *
 * Memory: RAM only, LRU eviction, hard caps (phone-safe). Transcripts are
 * never persisted — the vault stays keys+policy only.
 */
data class ConvState(
    val id: String,
    val upstream: MutableList<CBlock> = mutableListOf(),
    var upstreamModel: String = "",
    var upstreamFamily: WireFamily = WireFamily.UNKNOWN,
    var lastSeenMs: Long = System.currentTimeMillis(),
    var bytesHeld: Long = 0L,
    /** Staged (uncommitted) new turn — committed on upstream 2xx. */
    val staged: MutableList<CBlock> = mutableListOf()
) {
    /** Tip hash of committed + staged blocks. */
    fun tipHash(): Long {
        var h = 0L
        for (b in upstream) h = chainHash(h, b)
        for (b in staged) h = chainHash(h, b)
        return h
    }
}

data class ContextPolicy(
    val enabled: Boolean = false,
    val maxConvs: Int = 32,
    val maxBytesTotal: Long = 8L * 1024 * 1024,
    val maxBytesPerConv: Long = 1024L * 1024,
    val slowPathScan: Int = 8
) {
    fun toJson(): String =
        """{"enabled":$enabled,"maxBytesPerConv":$maxBytesPerConv,"maxBytesTotal":$maxBytesTotal,"maxConvs":$maxConvs,"slowPathScan":$slowPathScan}"""

    companion object {
        val DISABLED = ContextPolicy(false)
        fun fromJson(json: String): ContextPolicy {
            return try {
                val o = org.json.JSONObject(json)
                ContextPolicy(
                    o.optBoolean("enabled", false),
                    o.optInt("maxConvs", 32),
                    o.optLong("maxBytesTotal", 8L * 1024 * 1024),
                    o.optLong("maxBytesPerConv", 1024L * 1024),
                    o.optInt("slowPathScan", 8)
                )
            } catch (_: Exception) {
                DISABLED
            }
        }
    }
}

class ConversationIndex(private val policy: ContextPolicy = ContextPolicy.DISABLED) {
    // insertion-ordered (access-order) LRU
    private val convs = object : LinkedHashMap<String, ConvState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConvState>?): Boolean {
            return size > policy.maxConvs || totalBytes() > policy.maxBytesTotal
        }
    }
    // tip-hash → convId for the O(1) continuation path
    private val tipIndex = mutableMapOf<Long, String>()
    private var bytesTotal = 0L

    @Synchronized
    fun size(): Int = convs.size

    /**
     * Correlate [req] to a conversation, creating one when needed.
     * Returns null ONLY on internal error (fail-open signal).
     */
    @Synchronized
    fun correlate(req: CanonicalReq, clientIp: String = ""): ConvState? {
        return try {
            correlateInner(req, clientIp)
        } catch (_: Exception) {
            null
        }
    }

    private fun correlateInner(req: CanonicalReq, clientIp: String): ConvState {
        val now = System.currentTimeMillis()
        // Fast path: tip continuation.
        tipIndex[req.prefixHash]?.let { id ->
            convs[id]?.let { c ->
                attach(c, req, now)
                return c
            }
        }
        // Slow path: longest common prefix over most-recent convs.
        // Threshold needs a REAL overlap (bestLen > 0): empty-upstream
        // convs and single unrelated turns must never merge.
        var best: ConvState? = null
        var bestLen = -1
        var scanned = 0
        val recent = convs.values.toList().takeLast(policy.slowPathScan)
        for (c in recent) {
            scanned++
            val l = commonPrefixLen(c.upstream, req.messages)
            if (l > bestLen) {
                bestLen = l
                best = c
            }
        }
        if (best != null && req.messages.isNotEmpty() &&
            bestLen > 0 && bestLen >= req.messages.size - 1
        ) {
            attach(best, req, now)
            return best
        }
        // New conversation.
        val c = ConvState(
            id = java.util.UUID.randomUUID().toString(),
            upstreamModel = req.model,
            upstreamFamily = req.family,
            lastSeenMs = now
        )
        convs[c.id] = c
        attach(c, req, now)
        evictOverflow()
        return c
    }

    /**
     * Attach [req]'s transcript to [c]. Stateless APIs resend full history,
     * so the committed upstream tracks the longest transcript seen
     * (commit/rollback staging arrives in a later phase).
     */
    private fun attach(c: ConvState, req: CanonicalReq, now: Long) {
        if (req.messages.size >= c.upstream.size) {
            bytesTotal -= c.bytesHeld
            c.upstream.clear()
            c.upstream.addAll(req.messages)
            c.bytesHeld = req.messages.sumOf { it.text.length * 2L + 64 }
            bytesTotal += c.bytesHeld
        }
        touch(c, now)
    }

    /** Refresh tip index + LRU position after a request mutates [c]. */
    @Synchronized
    fun touch(c: ConvState, now: Long = System.currentTimeMillis()) {
        c.lastSeenMs = now
        // re-insert for LRU order
        convs.remove(c.id)
        convs[c.id] = c
        tipIndex[c.tipHash()] = c.id
    }

    /** Account block bytes against caps (callers update as transcripts grow). */
    @Synchronized
    fun accountBytes(c: ConvState, delta: Long) {
        c.bytesHeld += delta
        bytesTotal += delta
        evictOverflow()
    }

    @Synchronized
    fun clear() {
        convs.clear()
        tipIndex.clear()
        bytesTotal = 0L
    }

    private fun totalBytes(): Long = bytesTotal

    private fun evictOverflow() {
        while ((convs.size > policy.maxConvs || bytesTotal > policy.maxBytesTotal) && convs.isNotEmpty()) {
            val eldest = convs.entries.iterator().next()
            bytesTotal -= eldest.value.bytesHeld
            // drop stale tip refs pointing at the evicted conv
            tipIndex.entries.removeIf { it.value == eldest.key }
            convs.remove(eldest.key)
        }
        // per-conv cap: trim oldest committed blocks (rebase logged by caller)
        for (c in convs.values) {
            while (c.bytesHeld > policy.maxBytesPerConv && c.upstream.size > 1) {
                val dropped = c.upstream.removeAt(0)
                val freed = dropped.text.length * 2L + 64
                c.bytesHeld -= freed
                bytesTotal -= freed
            }
        }
    }

    companion object {
        /** Block-hash longest common prefix length. */
        fun commonPrefixLen(a: List<CBlock>, b: List<CBlock>): Int {
            var n = 0
            val ha = a.map { fnv1a64(it.canonicalBytes()) }
            val hb = b.map { fnv1a64(it.canonicalBytes()) }
            while (n < ha.size && n < hb.size && ha[n] == hb[n]) n++
            return n
        }
    }
}
