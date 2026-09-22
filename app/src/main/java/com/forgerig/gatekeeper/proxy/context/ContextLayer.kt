package com.forgerig.gatekeeper.proxy.context

import org.json.JSONObject

/**
 * Single entry point for the context layer in [ProxyService.handleClient].
 *
 * Phase 1: structural hook only. Returns null unless routing is enabled,
 * so the existing forward path runs byte-identically. Later phases fill in
 * classify → rewrite → shadow fan-out behind this gate.
 */
object ContextLayer {
    @Volatile var policy: ContextPolicy = ContextPolicy.DISABLED
    val index = ConversationIndex(policy)

    data class Decision(
        val conv: ConvState,
        val req: CanonicalReq,
        val adapter: TranscriptAdapter
    )

    /**
     * Inspect a plain-HTTP API request. Returns a [Decision] when the
     * context layer takes over, null to run the legacy path untouched.
     * Never throws (fail-open).
     */
    fun maybeProcess(url: String, method: String, body: ByteArray?): Decision? {
        return try {
            if (!policy.enabled || body == null || !method.equals("POST", true)) return null
            val family = WireFamily.detect(url)
            if (family == WireFamily.UNKNOWN) return null
            val adapter = Adapters.forFamily(family)
            val parsed = JSONObject(body.toString(Charsets.UTF_8))
            val req = adapter.parse(url, parsed)
            val conv = index.correlate(req) ?: return null
            Decision(conv, req, adapter)
        } catch (_: Exception) {
            null
        }
    }
}
