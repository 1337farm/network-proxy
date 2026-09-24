package com.forgerig.gatekeeper.proxy

/**
 * Per-(provider, model) health ledger: remembers which models are
 * erroring or down so spillover avoids them.
 *
 * Memory-only (process lifetime): the proxy is a long-lived foreground
 * service, so downtime memory survives the sessions that matter; a
 * restart resets to neutral priors (fail-open, never fail-closed).
 * Thread-safe; pure scoring is unit-tested.
 */
object ModelHealth {
    data class Stats(
        var ok: Long = 0,
        var err: Long = 0,
        var consecFails: Int = 0,
        var lastFailMs: Long = 0,
        var lastOkMs: Long = 0
    )

    /** Consecutive failures that bench a model until it recovers. */
    const val BENCH_FAILS = 3

    private val stats = java.util.concurrent.ConcurrentHashMap<String, Stats>()

    fun key(providerId: String, modelId: String) = "$providerId\u0001${modelId.lowercase()}"

    @Synchronized
    fun recordOk(providerId: String, modelId: String) {
        if (modelId.isBlank()) return
        val s = stats.getOrPut(key(providerId, modelId)) { Stats() }
        s.ok++
        s.consecFails = 0
        s.lastOkMs = System.currentTimeMillis()
    }

    @Synchronized
    fun recordErr(providerId: String, modelId: String) {
        if (modelId.isBlank()) return
        val s = stats.getOrPut(key(providerId, modelId)) { Stats() }
        s.err++
        s.consecFails++
        s.lastFailMs = System.currentTimeMillis()
    }

    /** Benched while failing repeatedly (recovers on next recorded ok). */
    fun eligible(providerId: String, modelId: String): Boolean {
        val s = stats[key(providerId, modelId)] ?: return true
        return s.consecFails < BENCH_FAILS
    }

    /**
     * Laplace-smoothed success rate: neutral 0.5 with no data (fail-open),
     * converges to observed rate. Deterministic — no clocks in scoring.
     */
    fun score(providerId: String, modelId: String): Double {
        val s = stats[key(providerId, modelId)] ?: return 0.5
        return (s.ok + 1.0) / (s.ok + s.err + 2.0)
    }

    /** Best eligible candidate by score, ties → first in list order. Null if none eligible. */
    fun pickBest(providerId: String, candidates: List<String>): String? {
        var best: String? = null
        var bestScore = -1.0
        for (c in candidates) {
            if (!eligible(providerId, c)) continue
            val sc = score(providerId, c)
            if (sc > bestScore) {
                bestScore = sc
                best = c
            }
        }
        return best
    }

    @Synchronized
    fun clear() = stats.clear()

    fun snapshot(providerId: String, modelId: String): Stats? = stats[key(providerId, modelId)]
}
