package com.forgerig.gatekeeper.proxy

/**
 * Tier-3 judge, implemented **locally** as a deterministic health heuristic.
 *
 * This is deliberately NOT an LLM call. Tier 3 runs inside
 * [ModelRouter.select], which sits on the relay path of a request that has
 * *already failed* — the worst possible moment to add latency, spend money
 * or ship the user's prompt/traffic off-box. A local heuristic gives us
 * zero added latency (a handful of map lookups over a <=12-entry list),
 * zero cost, no privacy exposure (nothing leaves the process), and fully
 * deterministic/reproducible output. A network judge remains a valid future
 * drop-in: assign a different [ModelRouter.JudgeFn] to [ModelRouter.judge].
 *
 * Decision inputs are the ones the router already tracks — the observed
 * (provider -> models) registry and the [ModelHealth] ledger. No new global
 * state is introduced; the single piece of extra bookkeeping is a bounded,
 * self-expiring fruitless-invocation counter (see [noteFruitless]).
 *
 * Contract: returns a model id taken **verbatim from the offered candidate
 * list**, or null when it has no defensible preference — in which case the
 * Tier-2 selection stands. Never throws.
 *
 * @param observedModelsOf observed model ids for a provider id.
 * @param isEligible false once a model is benched by repeated failures.
 * @param scoreOf Laplace-smoothed success rate (0.5 with no data).
 * @param lastFailOf epoch-ms of the last recorded failure, 0 if never.
 * @param now current time in epoch-ms; injectable so tests are deterministic.
 */
class HeuristicJudge(
    private val observedModelsOf: (String) -> List<String> = ModelRouter::observedModels,
    private val isEligible: (String, String) -> Boolean = ModelHealth::eligible,
    private val scoreOf: (String, String) -> Double = ModelHealth::score,
    private val lastFailOf: (String, String) -> Long = { p, m ->
        ModelHealth.snapshot(p, m)?.lastFailMs ?: 0L
    },
    private val now: () -> Long = System::currentTimeMillis
) : ModelRouter.JudgeFn {

    /** A model that failed within this window is treated as still hot. */
    val recentFailWindowMs: Long = 5L * 60 * 1000

    /** Fruitless Tier-3 calls for one failed model before we short-circuit. */
    val fruitlessLimit: Int = 3

    /** How long a fruitless-pool counter keeps suppressing re-evaluation. */
    val backoffMs: Long = 10L * 60 * 1000

    /** Hard cap on tracked failed models (access-ordered, eldest evicted). */
    val maxTracked: Int = 128

    private data class Streak(val count: Int, val firstMs: Long)

    private val streaks = object : LinkedHashMap<String, Streak>(16, 0.75f, true) {}
    private val streakLock = Any()

    override fun pick(
        failedModel: String,
        candidates: List<Pair<String, String>>
    ): String? = try {
        pickInner(failedModel, candidates)
    } catch (_: Exception) {
        // A judge must never break the relay path; null = "Tier 2 stands".
        null
    }

    private fun pickInner(
        failedModel: String,
        candidates: List<Pair<String, String>>
    ): String? {
        if (candidates.isEmpty()) return null

        // Failed model is the thing that just broke — never re-pick it.
        val failedNorm = ModelRouter.normalize(failedModel)

        // Sanitize: blank pairs dropped, the failed model excluded,
        // duplicates collapsed (first occurrence wins, so the Tier-2 pick
        // offered at index 0 keeps its priority).
        val pool = ArrayList<Pair<String, String>>(candidates.size)
        val seen = HashSet<String>(candidates.size * 2)
        // One snapshot per provider per call: the same provider usually
        // offers several models, and the registry is not iteration-safe.
        val observedCache = HashMap<String, Set<String>>(4)
        for (cand in candidates) {
            val pid = cand.first
            val mid = cand.second
            if (pid.isBlank() || mid.isBlank()) continue
            val norm = ModelRouter.normalize(mid)
            if (norm.isEmpty()) continue
            if (norm == failedNorm) continue
            // Must be in the observed registry: that is what makes the
            // verdict resolvable by ModelRouter.resolveVerdict.
            if (!isObserved(observedCache, pid, norm)) continue
            if (!seen.add(pid + '\u0001' + norm)) continue
            pool.add(pid to mid)
        }
        // Nothing to arbitrate between: never manufacture a preference.
        if (pool.size < 2) return null
        if (suppressed(failedModel)) return null

        val t = now()
        val viable = ArrayList<Pair<String, String>>(pool.size)
        for (cand in pool) {
            if (!isEligible(cand.first, cand.second)) continue
            val lastFail = lastFailOf(cand.first, cand.second)
            if (lastFail > 0L && t - lastFail < recentFailWindowMs) continue
            viable.add(cand)
        }
        if (viable.isEmpty()) {
            noteFruitless(failedModel, t)
            return null
        }

        // Deterministic argmax over viable candidates; ties keep the earlier
        // entry, which is the caller's preference order (Tier-2 pick first).
        var best = viable[0]
        var bestScore = scoreOf(best.first, best.second)
        for (i in 1 until viable.size) {
            val s = scoreOf(viable[i].first, viable[i].second)
            if (s > bestScore) {
                best = viable[i]
                bestScore = s
            }
        }
        // Sole survivor: the rest of the pool failing is the evidence.
        if (viable.size == 1) return best.second

        // With real contenders, a win must be strict. Equal scores mean no
        // defensible preference, so defer to Tier 2.
        var runnerUp = Double.NEGATIVE_INFINITY
        for (cand in viable) {
            if (cand == best) continue
            val s = scoreOf(cand.first, cand.second)
            if (s > runnerUp) runnerUp = s
        }
        return if (bestScore > runnerUp) best.second else null
    }

    /** Normalized observed set for a provider, snapshotted once per call. */
    private fun isObserved(
        cache: MutableMap<String, Set<String>>,
        providerId: String,
        normalizedModel: String
    ): Boolean {
        var norms = cache[providerId]
        if (norms == null) {
            val seen = observedModelsOf(providerId)
            if (seen.isEmpty()) return false
            norms = HashSet<String>(seen.size * 2)
            for (m in seen) {
                val n = ModelRouter.normalize(m)
                if (n.isNotEmpty()) norms.add(n)
            }
            cache[providerId] = norms
        }
        return normalizedModel in norms
    }

    /**
     * True when this failed model's candidate pool has gone fruitless
     * [fruitlessLimit] times inside [backoffMs]: stop re-scoring a pool we
     * already know is dead and let Tier 2 handle it.
     */
    private fun suppressed(failedModel: String): Boolean {
        if (failedModel.isBlank()) return false
        val t = now()
        synchronized(streakLock) {
            prune(t)
            val s = streaks[failedModel] ?: return false
            return s.count >= fruitlessLimit && t - s.firstMs < backoffMs
        }
    }

    private fun noteFruitless(failedModel: String, t: Long) {
        if (failedModel.isBlank()) return
        synchronized(streakLock) {
            prune(t)
            val prev = streaks[failedModel]
            val count = (if (prev != null && t - prev.firstMs < backoffMs) prev.count else 0) + 1
            streaks[failedModel] = if (count >= fruitlessLimit) {
                Streak(fruitlessLimit, t) // keep it as an open suppressor
            } else {
                Streak(count, if (prev == null) t else prev.firstMs)
            }
        }
    }

    /**
     * Bounded housekeeping: called only on Tier-3 evaluations, never on a
     * timer, and never grows past [maxTracked] entries. Drops expired
     * counters first, then evicts eldest-accessed when still at the cap.
     */
    private fun prune(t: Long) {
        if (streaks.size >= maxTracked) {
            val it = streaks.entries.iterator()
            while (it.hasNext()) {
                if (t - it.next().value.firstMs >= backoffMs) it.remove()
            }
        }
        // Still at the cap: evict least-recently-used. Access-ordered, so
        // this is a plain head removal.
        while (streaks.size > maxTracked) {
            val eldest = streaks.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    /** Drops the bounded backoff state (test/debug hook). */
    fun reset() {
        synchronized(streakLock) { streaks.clear() }
    }
}
