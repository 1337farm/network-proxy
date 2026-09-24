package com.forgerig.gatekeeper.proxy

/**
 * LLM-only enforcement: this proxy exists to broker LLM API traffic
 * (key injection, rollover, MITM usage scan). Everything else is either
 * tunneled opaque (default, connectivity-preserving) or refused outright
 * (strict mode — clients must bypass via NO_PROXY).
 *
 * Pure logic (no Android deps) so the policy matrix is unit-tested.
 */
object LlmPolicy {
    enum class Decision { BROKERED, TUNNEL, DENY }

    /**
     * Route [host] (bare hostname, no port): allowlisted LLM hosts get
     * full broker treatment; anything else tunnels opaque unless [strict],
     * which refuses with 403 + a bypass hint.
     */
    fun decide(host: String, llmHosts: Set<String>, strict: Boolean): Decision {
        if (host.isBlank()) return if (strict) Decision.DENY else Decision.TUNNEL
        if (matchesAny(host, llmHosts)) return Decision.BROKERED
        return if (strict) Decision.DENY else Decision.TUNNEL
    }

    /** Exact or subdomain-suffix match, case-insensitive. */
    fun matchesAny(host: String, llmHosts: Set<String>): Boolean {
        val h = host.lowercase().trim().trimEnd('.')
        if (h.isEmpty()) return false
        for (base in llmHosts) {
            val b = base.lowercase().trim().trimEnd('.')
            if (b.isEmpty()) continue
            if (h == b || h.endsWith(".$b")) return true
        }
        return false
    }

    /** Bare lowercase host from a URL, authority, or host:port. "" when unparseable. */
    fun extractHost(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        // host:port authority form (CONNECT target)
        if (!t.contains("://") && !t.startsWith("/")) {
            return validHost(t.substringBefore("/").substringBefore(":"))
        }
        return try {
            validHost(java.net.URI(t).host ?: return "")
        } catch (_: Exception) {
            try {
                validHost(java.net.URL(t).host)
            } catch (_: Exception) {
                ""
            }
        }
    }

    /** Lowercase host or "" — rejects strings that can't be DNS names/IPs. */
    private fun validHost(h: String): String {
        val v = h.lowercase().trim().trimEnd('.')
        if (v.isEmpty() || !v.contains('.')) return ""
        if (!v.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '[' || it == ']' }) return ""
        return v
    }

    /** Host set from provider base URLs (ports/paths stripped). */
    fun hostsFromBaseUrls(bases: List<String>): Set<String> =
        bases.mapNotNull {
            val h = extractHost(it)
            if (h.isEmpty()) null else h
        }.toSet()

    /** 403 body for strict denials (points at the bypass, not a dead end). */
    fun denyBody(host: String): String =
        "Proxy carries LLM API traffic only ($host is not a configured provider). " +
            "Bypass it: add the host to NO_PROXY or unset HTTP(S)_PROXY for non-LLM tools."
}
