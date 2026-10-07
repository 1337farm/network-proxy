package com.forgerig.gatekeeper.proxy

/**
 * Failover and retry-budget decisions for the upstream relay, extracted
 * from [ProxyService] so they are unit-testable without a Service,
 * a Context, or a socket.
 *
 * Two responsibilities:
 *
 * 1. **Where next** ([nextUsableKey]): prefer a sibling key on the same
 *    provider, else the next healthy leg of the request's route (rewriting
 *    model + URL + content-length), else cross-provider spillover to a
 *    same-family provider, else any usable key on the original provider.
 *    Null when nothing is usable. Events go to [onEvent] (the service
 *    passes `ProxyMetrics::event`); tests pass a recorder.
 *
 * 2. **For how long** ([deadlineExceeded], [cappedRetryDelay]): one
 *    request's total retry/failover time is bounded by [RETRY_DEADLINE_MS]
 *    across key rounds (the per-key attempt counter resets on rollover, so
 *    attempt counts alone cannot bound it), and a single sleep never
 *    exceeds the policy ceiling — even when the upstream sends an absurd
 *    `Retry-After`.
 */
internal object Failover {

    /**
     * Hard cap on one request's total retry/failover time, measured from
     * request start. A single client call must never hang for many minutes
     * (thread exhaustion under concurrent failures, client timeouts firing
     * blind) just because every leg answers 429/5xx.
     */
    const val RETRY_DEADLINE_MS = 120_000L

    data class NextKey(
        val key: Pair<ProviderStore.Provider, ProviderStore.ApiKey>,
        val leg: ProviderStore.RouteLeg?,
        val url: String,
        val body: ByteArray?
    )

    /** True once [RETRY_DEADLINE_MS] has elapsed since [startedAtMs]. */
    fun deadlineExceeded(startedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - startedAtMs > RETRY_DEADLINE_MS

    /**
     * Sleep before the next retry: an explicit `Retry-After` wins but is
     * capped at the policy ceiling (an hour-long backoff parks a pool
     * thread and outlives every client); otherwise the policy's own
     * exponential delay with jitter.
     */
    fun cappedRetryDelay(retryAfterSecs: Long?, attempt: Int, policy: RetryPolicy): Long {
        val afterMs = if (retryAfterSecs != null && retryAfterSecs > 0) retryAfterSecs * 1000 else -1L
        return if (afterMs >= 0) afterMs.coerceAtMost(policy.maxBackoffMs)
        else policy.nextDelay(attempt)
    }

    fun nextUsableKey(
        store: ProviderStore,
        provider: ProviderStore.Provider,
        failedKeyId: String,
        leg: ProviderStore.RouteLeg?,
        body: ByteArray?,
        url: String,
        headers: MutableMap<String, String>,
        mutableHeaders: MutableMap<String, String>,
        onEvent: (String) -> Unit = {},
    ): NextKey? {
        val same = store.activeKey(provider.id)
        if (same != null && same.second.id != failedKeyId) {
            return NextKey(same, leg, url, body)
        }
        // Same-provider pool exhausted — walk route legs after the current one.
        // (Skipped entirely without a leg context; spillover below covers it.)
        if (leg != null && body != null) {
            val wantModel = try {
                org.json.JSONObject(body.toString(Charsets.UTF_8)).optString("model", "")
            } catch (_: Exception) { null }
            val route = if (wantModel != null) {
                store.routes.values.firstOrNull { r ->
                    r.legs.any { it.providerId == leg.providerId && it.model == leg.model }
                }
            } else null
            if (route != null) {
                val idx = route.legs.indexOfFirst { it.providerId == leg.providerId && it.model == leg.model }
                for (next in route.legs.drop(idx + 1)) {
                    val lp = store.providers[next.providerId] ?: continue
                    val lk = store.activeKey(lp.id)?.second ?: continue
                    try {
                        val bj = org.json.JSONObject(body.toString(Charsets.UTF_8))
                        bj.put("model", next.model)
                        val nb = bj.toString().toByteArray(Charsets.UTF_8)
                        val nu = ProviderStore.retarget(url, lp.baseUrl)
                        headers["Content-Length"] = nb.size.toString()
                        mutableHeaders["Content-Length"] = nb.size.toString()
                        onEvent("Leg failover '$wantModel' → ${lp.id}/${next.model}")
                        return NextKey(lp to lk, next, nu, nb)
                    } catch (_: Exception) { continue }
                }
            }
        }
        // Cross-provider spillover (no route config needed): best
        // comparable model on a same-family provider, retargeted URL.
        // Lets Zen-exhausted traffic spill to OpenRouter/etc. mid-keyLoop.
        val failedModel = modelOf(body)
        store.spilloverTarget(provider.id, failedModel, failedKeyId)?.let { sel ->
            var nb = body
            if (sel.model.isNotBlank() && body != null) {
                try {
                    val bj = org.json.JSONObject(body.toString(Charsets.UTF_8))
                    bj.put("model", sel.model)
                    nb = bj.toString().toByteArray(Charsets.UTF_8)
                } catch (_: Exception) { /* keep original body */ }
            }
            val nu = ProviderStore.retarget(url, sel.provider.baseUrl)
            val len = (nb?.size ?: 0).toString()
            headers["Content-Length"] = len
            mutableHeaders["Content-Length"] = len
            onEvent(
                "Spillover '${provider.id}' → '${sel.provider.id}/${sel.model}' (tier ${sel.tier})"
            )
            return NextKey(sel.provider to sel.key, null, nu, nb)
        }
        // Full circle: any usable key on the ORIGINAL provider (cooldowns may differ).
        val retry = store.activeKey(provider.id)
        return if (retry != null && retry.second.id != failedKeyId) {
            NextKey(retry, leg, url, body)
        } else null
    }

    /** Top-level "model" field of a JSON API body ("" when absent/opaque). */
    fun modelOf(body: ByteArray?): String {
        if (body == null) return ""
        return try {
            org.json.JSONObject(body.toString(Charsets.UTF_8)).optString("model", "")
        } catch (_: Exception) {
            ""
        }
    }
}
