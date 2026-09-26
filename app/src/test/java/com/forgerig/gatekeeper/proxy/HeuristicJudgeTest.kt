package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM coverage for [HeuristicJudge], the local Tier-3 judge, and for the
 * [ModelRouter] wiring that makes Tier 3 actually run.
 *
 * No mocks: the judge is driven against the real [ModelRouter] observed
 * registry and the real [ModelHealth] ledger it is designed to read.
 */
class HeuristicJudgeTest {

    /** Must match [HeuristicJudge.recentFailWindowMs]. */
    private val RECENT_WINDOW_MS = 5L * 60 * 1000

    /** Frozen clock so recency/backoff assertions are exact. */
    private var clock = 1_700_000_000_000L

    @Before
    fun resetWorld() {
        // android.util.Log is stubbed under plain JVM tests ("not mocked").
        ProxyMetrics.logSink = { _, _, _ -> }
        ModelRouter.clear()
        ModelHealth.clear()
        ModelRouter.judge = null
        clock = 1_700_000_000_000L
    }

    private fun judge() = HeuristicJudge(now = { clock })

    /** Mark a model healthy with N successes -> score (N+1)/(N+2). */
    private fun healthy(providerId: String, modelId: String, oks: Int = 1) {
        ModelRouter.noteObserved(providerId, modelId)
        repeat(oks) { ModelHealth.recordOk(providerId, modelId) }
    }

    /** Observed but never recorded: neutral 0.5 prior, no evidence. */
    private fun observedOnly(providerId: String, modelId: String) {
        ModelRouter.noteObserved(providerId, modelId)
    }

    /** Mark a model failing right now (recent, not yet benched). */
    private fun failingNow(providerId: String, modelId: String, errs: Int = 1) {
        ModelRouter.noteObserved(providerId, modelId)
        repeat(errs) { ModelHealth.recordErr(providerId, modelId) }
        // recordErr stamps System.currentTimeMillis(); rewind so the judge
        // sees a deterministic recent failure.
        ModelHealth.snapshot(providerId, modelId)?.let { it.lastFailMs = clock }
    }

    /**
     * A strong model whose last failure is older than the recency window:
     * the recency filter must let it back in, and its score must then win.
     */
    private fun staleButStrong(providerId: String, modelId: String, oks: Int = 5) {
        ModelRouter.noteObserved(providerId, modelId)
        repeat(oks) { ModelHealth.recordOk(providerId, modelId) }
        ModelHealth.recordErr(providerId, modelId)
        ModelHealth.snapshot(providerId, modelId)?.let {
            it.lastFailMs = clock - RECENT_WINDOW_MS - 60_000L
        }
    }

    // ---------------------------------------------------------------- picks

    @Test
    fun prefersObservedHealthyCandidateOverRecentlyFailedOne() {
        healthy("a", "m-good", oks = 5)   // score 6/7
        failingNow("b", "m-hot", errs = 1)
        val pick = judge().pick("gpt-5", listOf("b" to "m-hot", "a" to "m-good"))
        assertEquals("m-good", pick)
    }

    @Test
    fun prefersHigherSuccessRateWhenAllAreCool() {
        healthy("a", "m-mid", oks = 2)  // 3/4 = 0.75
        healthy("b", "m-top", oks = 8)  // 9/10 = 0.9
        val pick = judge().pick("gpt-5", listOf("a" to "m-mid", "b" to "m-top"))
        assertEquals("m-top", pick)
    }

    @Test
    fun soleSurvivorWinsEvenWithLowScore() {
        failingNow("a", "m-dead")
        healthy("b", "m-ok") // only viable candidate: the rest failing is the evidence
        val pick = judge().pick("gpt-5", listOf("a" to "m-dead", "b" to "m-ok"))
        assertEquals("m-ok", pick)
    }

    @Test
    fun ignoresFailuresOlderThanTheRecencyWindow() {
        // 6/8 = 0.75, last failure long cold -> must be back in the running.
        staleButStrong("a", "m-stale", oks = 5)
        healthy("b", "m-fresh", oks = 1) // 2/3 = 0.667
        val pick = judge().pick("gpt-5", listOf("a" to "m-stale", "b" to "m-fresh"))
        assertEquals("m-stale", pick)
    }

    @Test
    fun skipsBenchedModels() {
        healthy("a", "m-benched", oks = 1)
        repeat(ModelHealth.BENCH_FAILS) { ModelHealth.recordErr("a", "m-benched") }
        healthy("b", "m-clean", oks = 1)
        val pick = judge().pick("gpt-5", listOf("a" to "m-benched", "b" to "m-clean"))
        assertEquals("m-clean", pick)
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun refusesOnEmptyCandidateList() {
        assertNull(judge().pick("gpt-5", emptyList()))
    }

    @Test
    fun refusesOnSingleCandidate() {
        healthy("a", "m-only", oks = 9)
        assertNull(judge().pick("gpt-5", listOf("a" to "m-only")))
    }

    @Test
    fun refusesWhenEveryCandidateFailedRecently() {
        failingNow("a", "m-x")
        failingNow("b", "m-y")
        assertNull(judge().pick("gpt-5", listOf("a" to "m-x", "b" to "m-y")))
    }

    @Test
    fun refusesWhenEveryCandidateIsBenched() {
        for ((p, m) in listOf("a" to "m-x", "b" to "m-y")) {
            ModelRouter.noteObserved(p, m)
            repeat(ModelHealth.BENCH_FAILS) { ModelHealth.recordErr(p, m) }
        }
        assertNull(judge().pick("gpt-5", listOf("a" to "m-x", "b" to "m-y")))
    }

    @Test
    fun returnsNullWhenNoEvidenceAllScoresTie() {
        // Observed but never recorded ok/err: every score is the neutral
        // 0.5 prior, so there is no defensible preference.
        ModelRouter.noteObserved("a", "m-one")
        ModelRouter.noteObserved("b", "m-two")
        assertEquals(0.5, ModelHealth.score("a", "m-one"), 1e-9)
        assertEquals(0.5, ModelHealth.score("b", "m-two"), 1e-9)
        assertNull(judge().pick("gpt-5", listOf("a" to "m-one", "b" to "m-two")))
    }

    @Test
    fun returnsNullOnScoreTieDespiteIdenticalEvidence() {
        healthy("a", "m-one", oks = 3) // 4/5 = 0.8
        healthy("b", "m-two", oks = 3) // 4/5 = 0.8
        assertNull(judge().pick("gpt-5", listOf("a" to "m-one", "b" to "m-two")))
    }

    @Test
    fun skipsUnobservedCandidates() {
        // In the observed registry but not offered -> never a pick.
        healthy("a", "m-seen", oks = 9)
        val pick = judge().pick("gpt-5", listOf("ghost" to "m-seen", "a" to "m-seen"))
        assertNull(pick)
    }

    // ----------------------------------------------------- validity guards

    @Test
    fun neverReturnsTheFailedModel() {
        healthy("a", "shared", oks = 1)  // 0.667
        healthy("b", "other", oks = 8)  // 0.9
        observedOnly("c", "third")      // 0.5
        val offered = listOf("a" to "shared", "b" to "other", "c" to "third")
        // Exact id match, and the vendor-qualified form both resolve to the
        // same normalized id, which must be excluded from the pool.
        assertEquals("other", judge().pick("shared", offered))
        assertEquals("other", judge().pick("vendor/shared:free", offered))
        assertEquals("other", judge().pick("SHARED", offered))
    }

    @Test
    fun neverReturnsTheFailedModelEvenWhenItScoresBest() {
        // Different model entirely from the failed one, so this exercises
        // the recency rule rather than the failed-model exclusion.
        healthy("a", "flapper", oks = 20) // 21/23 = 0.913, by far the best score
        healthy("b", "boring", oks = 1)   // 0.667
        observedOnly("c", "dull")         // 0.5
        failingNow("a", "flapper", errs = 1) // ...but it just failed
        val pick = judge().pick(
            "gpt-5",
            listOf("a" to "flapper", "b" to "boring", "c" to "dull")
        )
        assertEquals("boring", pick)
    }

    @Test
    fun neverReturnsAnIdOutsideTheCandidateSet() {
        healthy("a", "m-a", oks = 1)
        healthy("b", "m-b", oks = 9)
        val offered = listOf("a" to "m-a", "b" to "m-b")
        val pick = judge().pick("gpt-5", offered)
        assertTrue("pick must be offered: $pick", offered.any { it.second == pick })
    }

    @Test
    fun handlesDuplicateAndBlankEntries() {
        healthy("a", "m-a", oks = 1)
        healthy("b", "m-b", oks = 9)
        val messy = listOf(
            "a" to "m-a", "a" to "m-a",       // exact dupe
            "b" to "m-b", "b" to "vendor/m-b:free" // same model, qualified form
        )
        assertEquals("m-b", judge().pick("gpt-5", messy))
        // Blank ids cannot manufacture a pool.
        assertNull(judge().pick("gpt-5", listOf("" to "m-a", "b" to "")))
    }

    @Test
    fun doesNotThrowOnHostileInput() {
        healthy("a", "m-a", oks = 1)
        val j = judge()
        assertNull(j.pick("", listOf("a" to "m-a", "b" to "m-b")))
        assertNull(j.pick("   ", listOf("a" to "m-a", "b" to "m-b")))
        assertNull(j.pick("///", listOf("a" to "///", "b" to "m-b")))
    }

    @Test
    fun isDeterministicAcrossRepeatedCalls() {
        healthy("a", "m-a", oks = 2)
        healthy("b", "m-b", oks = 8)
        val j = judge()
        val offered = listOf("a" to "m-a", "b" to "m-b")
        val first = j.pick("gpt-5", offered)
        assertEquals("m-b", first)
        repeat(25) { assertEquals(first, j.pick("gpt-5", offered)) }
        // A fresh instance with the same inputs must agree.
        assertEquals(first, judge().pick("gpt-5", offered))
        // Order-independence of the verdict itself (winner stays winner).
        assertEquals(first, j.pick("gpt-5", offered.reversed()))
    }

    // ------------------------------------------------- bounded bookkeeping

    @Test
    fun opensCircuitAfterRepeatedFruitlessPools() {
        failingNow("a", "m-x")
        failingNow("b", "m-y")
        val j = judge()
        val offered = listOf("a" to "m-x", "b" to "m-y")
        repeat(j.fruitlessLimit) { assertNull(j.pick("gpt-5", offered)) }
        // Circuit now open: a newly-viable candidate is ignored until the
        // backoff window expires, so we do not re-score a known-dead fan-out.
        healthy("c", "m-rescued", oks = 1)
        assertNull(j.pick("gpt-5", offered + ("c" to "m-rescued")))
        // After the window it re-evaluates normally.
        clock += j.backoffMs + 1_000L
        assertEquals("m-rescued", j.pick("gpt-5", offered + ("c" to "m-rescued")))
    }

    @Test
    fun backoffStateIsBounded() {
        val j = judge()
        val churn = j.maxTracked + 40
        // Every one of these pools has zero viable candidates, so each
        // records a fruitless invocation and grows the tracked map.
        for (i in 0 until churn) {
            for (m in listOf("m1-$i", "m2-$i")) {
                ModelRouter.noteObserved("a", m)
                repeat(ModelHealth.BENCH_FAILS) { ModelHealth.recordErr("a", m) }
            }
            assertNull(j.pick("failed-$i", listOf("a" to "m1-$i", "a" to "m2-$i")))
        }
        // Still correct and still cheap after >2x the cap: the judge works.
        observedOnly("fresh", "m-keep")
        healthy("z", "m-ok", oks = 9) // 10/11
        assertEquals("m-ok", j.pick("gpt-5", listOf("fresh" to "m-keep", "z" to "m-ok")))
        j.reset()
        assertEquals("m-ok", j.pick("gpt-5", listOf("fresh" to "m-keep", "z" to "m-ok")))
    }

    @Test
    fun circuitDoesNotSuppressAPositiveVerdict() {
        healthy("a", "m-a", oks = 5)
        healthy("b", "m-b", oks = 1)
        val j = judge()
        val offered = listOf("a" to "m-a", "b" to "m-b")
        // Repeatedly picking the same winner never trips the breaker.
        repeat(20) { assertEquals("m-a", j.pick("gpt-5", offered)) }
    }

    // ---------------------------------------------------------- wiring

    @Test
    fun defaultJudgeIsInstalledAndHeuristic() {
        ModelRouter.ensureJudgeInstalled()
        assertTrue(ModelRouter.judge is HeuristicJudge)
    }

    @Test
    fun ensureJudgeInstalledIsIdempotentAndRespectsOverrides() {
        val custom = ModelRouter.JudgeFn { _, _ -> null }
        ModelRouter.judge = custom
        ModelRouter.ensureJudgeInstalled()
        assertSame(custom, ModelRouter.judge)
        // Null is honoured as an explicit "disable Tier 3" switch.
        ModelRouter.judge = null
        assertNull(ModelRouter.judge)
    }

    @Test
    fun tier3FlipsTheSelectionWhenTheTier2PickIsHot() {
        val s = store()
        ModelRouter.judge = judge()
        // aaa/m1: high success rate but failed just now -> Tier 2 likes it,
        // the judge sees it as hot and moves to bbb/m2.
        healthy("aaa", "m1", oks = 5)
        failingNow("aaa", "m1", errs = 1)
        healthy("bbb", "m2", oks = 1)
        val sel = s.spilloverTarget("zen", "something-else", "zk")!!
        assertEquals(3, sel.tier)
        assertEquals("bbb", sel.provider.id)
        assertEquals("m2", sel.model)
    }

    @Test
    fun tier3LeavesTier2StandingWhenTheJudgeHasNoPreference() {
        val s = store()
        ModelRouter.judge = judge()
        // Only one peer -> single candidate -> nothing to arbitrate.
        healthy("aaa", "m1", oks = 5)
        val sel = s.spilloverTarget("zen", "something-else", "zk")!!
        assertEquals(2, sel.tier)
    }

    @Test
    fun tier3RunsWithoutAnyCallSiteSetup() {
        val s = store()
        ModelRouter.ensureJudgeInstalled()
        // The default judge runs on the real clock, so align the test clock
        // with it to make "just failed" mean just.
        clock = System.currentTimeMillis()
        healthy("aaa", "m1", oks = 5)
        failingNow("aaa", "m1", errs = 1)
        healthy("bbb", "m2", oks = 1)
        val sel = s.spilloverTarget("zen", "something-else", "zk")!!
        assertEquals(3, sel.tier)
        assertEquals("m2", sel.model)
    }

    @Test
    fun tier2StillWinsWhenNoJudgeIsInstalled() {
        val s = store()
        ModelRouter.judge = null // Tier 3 disabled
        healthy("aaa", "m1", oks = 5)
        failingNow("aaa", "m1", errs = 1)
        healthy("bbb", "m2", oks = 1)
        val sel = s.spilloverTarget("zen", "something-else", "zk")!!
        assertEquals(2, sel.tier)
        assertEquals("aaa", sel.provider.id)
        assertEquals("m1", sel.model)
    }

    // ------------------------------------------------------------- helpers

    private fun prov(id: String) = ProviderStore.Provider(
        id,
        "https://$id.example/zen/v1/messages",
        "x-api-key",
        "",
        mutableListOf(ProviderStore.ApiKey("${id}k", "${id}k", "secret-$id"))
    )

    /** Three same-family (ANTHROPIC) providers so Tier 2 has a real choice. */
    private fun store(): ProviderStore {
        val s = ProviderStore.blank()
        s.providers.clear()
        s.providers["zen"] = prov("zen")
        s.providers["aaa"] = prov("aaa")
        s.providers["bbb"] = prov("bbb")
        return s
    }
}
