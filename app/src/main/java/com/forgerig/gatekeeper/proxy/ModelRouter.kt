package com.forgerig.gatekeeper.proxy

/**
 * Smart spillover target selection: when a provider's pool is exhausted,
 * choose the best (provider, key, model) to retry with.
 *
 * Comparability ladder (cheapest decisive tier wins — the LLM judge is
 * the LAST resort, never the first call, because it costs a billed
 * request on the failure path and can itself be rate-limited):
 *
 * - Tier 0: exact model id on another same-family provider.
 * - Tier 1: normalized id match — strip `vendor/` prefix and `:suffix`
 *   qualifiers (`openrouter/anthropic/claude-opus-5:free` ≈
 *   `claude-opus-5`), case-insensitive.
 * - Tier 2: any observed model on a same-family provider, ranked by
 *   [ModelHealth] (downtime/errors sink, healthy float).
 * - Tier 3: [JudgeFn] — ask a cheap healthy leg for pros/cons + a pick.
 *   Verdicts cached per failed-model with TTL; on judge failure, one
 *   narrowed follow-up, then Tier-2 best. (Execution wiring lands next;
 *   until then Tier 2 is the terminal tier.)
 *
 * Candidates come from the observed-model registry (models actually seen
 * in passing traffic per provider) — ground truth with zero catalog
 * fetching and zero curated maps to rot.
 */
object ModelRouter {
    /** Observed (provider → models) from live traffic. Bounded. */
    private val observed = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    private const val MAX_PER_PROVIDER = 64

    /** Judge verdict cache: failed-model → pick, with expiry. */
    private val verdicts = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    const val VERDICT_TTL_MS = 6L * 60 * 60 * 1000

    /**
     * Judge hook: given the failed model, candidate (provider, model)
     * pairs and a retry budget of 1 follow-up, return the picked model id
     * or null to fall back to Tier 2. Implementations must timeout fast
     * and never throw (null = fallback).
     */
    fun interface JudgeFn {
        fun pick(failedModel: String, candidates: List<Pair<String, String>>): String?
    }

    @Volatile var judge: JudgeFn? = null

    data class Selection(
        val provider: ProviderStore.Provider,
        val key: ProviderStore.ApiKey,
        val model: String,
        val tier: Int
    )

    /** Record a model observed in traffic (call-site: request ingress). */
    fun noteObserved(providerId: String, modelId: String) {
        if (modelId.isBlank()) return
        val set = observed.getOrPut(providerId) {
            java.util.Collections.synchronizedSet(LinkedHashSet())
        }
        synchronized(set) {
            if (set.size >= MAX_PER_PROVIDER && modelId !in set) {
                set.remove(set.iterator().next())
            }
            set.add(modelId)
        }
    }

    fun observedModels(providerId: String): List<String> =
        observed[providerId]?.toList() ?: emptyList()

    /** Normalize for Tier-1 comparison: vendor prefix + :suffix stripped. */
    fun normalize(modelId: String): String {
        var m = modelId.lowercase().trim()
        if ("/" in m) m = m.substringAfterLast("/")
        if (":" in m) m = m.substringBefore(":")
        return m
    }

    /**
     * Best spillover target for a request that failed on ([fromId],
     * [failedModel]). Returns null when nothing qualifies (caller keeps
     * legacy behavior). Never throws.
     */
    @Synchronized
    fun select(
        store: ProviderStore,
        fromId: String,
        failedModel: String,
        failedKeyId: String
    ): Selection? {
        return try {
            selectInner(store, fromId, failedModel, failedKeyId)
        } catch (_: Exception) {
            null
        }
    }

    private fun selectInner(
        store: ProviderStore,
        fromId: String,
        failedModel: String,
        failedKeyId: String
    ): Selection? {
        if (!store.routeFailoverEnabled) return null
        val from = store.providers[fromId] ?: return null
        val fam = com.forgerig.gatekeeper.proxy.context.WireFamily.detect(from.baseUrl)
        if (fam == com.forgerig.gatekeeper.proxy.context.WireFamily.UNKNOWN) return null
        val peers = store.providers.values
            .filter { it.id != fromId }
            .filter {
                com.forgerig.gatekeeper.proxy.context.WireFamily.detect(it.baseUrl) == fam
            }
            .sortedBy { it.id }
        if (peers.isEmpty()) return null

        fun liveKey(p: ProviderStore.Provider): ProviderStore.ApiKey? {
            val k = store.activeKey(p.id)?.second ?: return null
            return if (k.id == failedKeyId) null else k
        }

        // Tier 0: exact model id on a peer.
        if (failedModel.isNotBlank()) {
            for (p in peers) {
                val k = liveKey(p) ?: continue
                if (failedModel in observedModels(p.id)) {
                    return Selection(p, k, failedModel, 0)
                }
            }
            // Tier 1: normalized match.
            val want = normalize(failedModel)
            for (p in peers) {
                val k = liveKey(p) ?: continue
                val hit = observedModels(p.id).firstOrNull { normalize(it) == want }
                if (hit != null) return Selection(p, k, hit, 1)
            }
        }
        // Tier 2: health-best observed model on a peer.
        var best: Selection? = null
        var bestScore = -1.0
        for (p in peers) {
            val k = liveKey(p) ?: continue
            val cands = observedModels(p.id)
            if (cands.isEmpty()) continue
            val pick = ModelHealth.pickBest(p.id, cands) ?: continue
            val sc = ModelHealth.score(p.id, pick)
            if (sc > bestScore) {
                bestScore = sc
                best = Selection(p, k, pick, 2)
            }
        }
        if (best != null) {
            // Tier 3: judge override when hooked (cached verdicts; null = Tier 2 stands).
            judgedOverride(store, failedModel, best)?.let { return it }
            return best
        }
        return null
    }

    /**
     * Tier 3: consult the judge hook for a better pick among the same
     * peers. Cached verdicts keep the judge off the hot path; a null
     * verdict (or no hook) leaves the Tier-2 selection standing. The
     * follow-up retry lives inside the hook implementation (one narrowed
     * re-ask), not here.
     */
    private fun judgedOverride(
        store: ProviderStore,
        failedModel: String,
        tier2: Selection
    ): Selection? {
        val j = judge ?: return null
        if (failedModel.isBlank()) return null
        val now = System.currentTimeMillis()
        verdicts[failedModel]?.let { (pick, until) ->
            if (now < until) {
                return resolveVerdict(store, tier2, pick) ?: tier2
            } else verdicts.remove(failedModel)
        }
        val cands = mutableListOf<Pair<String, String>>()
        for (p in store.providers.values.sortedBy { it.id }) {
            if (p.id == tier2.provider.id) continue
            for (m in observedModels(p.id)) cands.add(p.id to m)
            if (cands.size >= 12) break
        }
        cands.add(0, tier2.provider.id to tier2.model)
        val pick = try {
            j.pick(failedModel, cands)
        } catch (_: Exception) {
            null
        } ?: return null
        return resolveVerdict(store, tier2, pick)?.also {
            verdicts[failedModel] = pick to now + VERDICT_TTL_MS
        } ?: tier2
    }

    /** A verdict names a model id: resolve to a live (provider, key). */
    private fun resolveVerdict(
        store: ProviderStore,
        tier2: Selection,
        pick: String
    ): Selection? {
        if (pick.isBlank()) return null
        // verdict for the tier-2 pick itself (or same id) = confirm tier 2
        if (pick == tier2.model || normalize(pick) == normalize(tier2.model)) return tier2
        for (p in store.providers.values.sortedBy { it.id }) {
            val hit = observedModels(p.id).firstOrNull {
                it == pick || normalize(it) == normalize(pick)
            } ?: continue
            val k = store.activeKey(p.id)?.second ?: continue
            if (!ModelHealth.eligible(p.id, hit)) continue
            return Selection(p, k, hit, 3)
        }
        return null
    }

    @Synchronized
    fun clear() {
        observed.clear()
        verdicts.clear()
    }
}
