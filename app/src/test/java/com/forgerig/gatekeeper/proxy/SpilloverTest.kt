package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-runnable coverage for smart spillover (router + health ledger). */
class SpilloverTest {

    @org.junit.Before
    fun silenceLogAndReset() {
        // android.util.Log is stubbed under plain JVM tests ("not mocked").
        ProxyMetrics.logSink = { _, _, _ -> }
        ModelRouter.clear()
        ModelRouter.judge = null
        ModelHealth.clear()
    }

    private fun keyed(id: String, base: String, vararg keys: ProviderStore.ApiKey) =
        ProviderStore.Provider(id, base, "Authorization", "Bearer ", keys.toMutableList())

    private fun key(id: String) = ProviderStore.ApiKey(id, id, "secret-$id")

    private fun store(): ProviderStore {
        val s = ProviderStore.blank()
        s.providers.clear()
        // family-explicit bases: ANTHROPIC vs OPENAI_CHAT
        s.providers["zen"] = keyed("zen", "https://opencode.ai/zen/v1/messages", key("z1"))
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/api/v1/chat/completions", key("o1")
        )
        return s
    }

    @Test
    fun tier0ExactModelMatch() {
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        ModelRouter.noteObserved("openrouter", "claude-opus-5")
        val sel = s.spilloverTarget("zen", "claude-opus-5", "z1")!!
        assertEquals("openrouter", sel.provider.id)
        assertEquals("o1", sel.key.id)
        assertEquals("claude-opus-5", sel.model)
        assertEquals(0, sel.tier)
    }

    @Test
    fun tier1NormalizedMatch() {
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        ModelRouter.noteObserved("openrouter", "anthropic/claude-opus-5:free")
        val sel = s.spilloverTarget("zen", "Claude-Opus-5", "z1")!!
        assertEquals(1, sel.tier)
        assertEquals("anthropic/claude-opus-5:free", sel.model)
    }

    @Test
    fun tier2HealthBestWhenNoIdMatch() {
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        ModelRouter.noteObserved("openrouter", "model-a")
        ModelRouter.noteObserved("openrouter", "model-b")
        // model-a is flapping: bench it, model-b must win
        repeat(3) { ModelHealth.recordErr("openrouter", "model-a") }
        ModelHealth.recordOk("openrouter", "model-b")
        val sel = s.spilloverTarget("zen", "something-else", "z1")!!
        assertEquals(2, sel.tier)
        assertEquals("model-b", sel.model)
    }

    @Test
    fun skipsDifferentFamilyProvider() {
        val s = store() // openrouter = OPENAI_CHAT, zen = ANTHROPIC
        ModelRouter.noteObserved("openrouter", "gpt-5")
        assertNull(s.spilloverTarget("zen", "claude-opus-5", "z1"))
    }

    @Test
    fun skipsProvidersWithNoLiveKeys() {
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        ModelRouter.noteObserved("openrouter", "claude-opus-5")
        s.report("openrouter", "o1", 429)
        assertNull(s.spilloverTarget("zen", "claude-opus-5", "z1"))
    }

    @Test
    fun excludesFailedKeyId() {
        val s = ProviderStore.blank()
        s.providers.clear()
        s.providers["a"] = keyed("a", "https://a.example/zen/v1/messages", key("shared"))
        s.providers["b"] = keyed("b", "https://b.example/zen/v1/messages", key("shared"))
        ModelRouter.noteObserved("b", "m")
        assertNull(s.spilloverTarget("a", "m", "shared"))
    }

    @Test
    fun honorsFailoverSwitchAndUnknownFamily() {
        val s = store()
        ModelRouter.noteObserved("openrouter", "gpt-5")
        s.routeFailoverEnabled = false
        assertNull(s.spilloverTarget("zen", "claude-opus-5", "z1"))
        s.routeFailoverEnabled = true
        s.providers["odd"] = keyed("odd", "https://odd.example/stuff", key("q1"))
        assertNull(s.spilloverTarget("odd", "whatever", "q1"))
    }

    @Test
    fun judgeVerdictOverridesTier2() {
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        s.providers["nvidia"] = keyed(
            "nvidia", "https://nvidia.example/zen/v1/messages", key("n1")
        )
        ModelRouter.noteObserved("openrouter", "model-a")
        ModelRouter.noteObserved("nvidia", "model-n")
        // dent model-n so Tier 2 picks openrouter/model-a (0.5 beats 0.33),
        // letting the judge genuinely override to nvidia/model-n
        ModelHealth.recordErr("nvidia", "model-n")
        ModelRouter.judge = ModelRouter.JudgeFn { _, _ -> "model-n" }
        val sel = s.spilloverTarget("zen", "something-else", "z1")!!
        assertEquals(3, sel.tier)
        assertEquals("nvidia", sel.provider.id)
        assertEquals("model-n", sel.model)
    }

    @Test
    fun judgeNullFallsBackToTier2() {
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        ModelRouter.noteObserved("openrouter", "model-a")
        ModelRouter.judge = ModelRouter.JudgeFn { _, _ -> null }
        val sel = s.spilloverTarget("zen", "something-else", "z1")!!
        assertEquals(2, sel.tier)
    }

    @Test
    fun normalizeStripsVendorAndSuffix() {
        assertEquals("claude-opus-5", ModelRouter.normalize("openrouter/anthropic/claude-opus-5:free"))
        assertEquals("gpt-5", ModelRouter.normalize("GPT-5"))
        assertEquals("m", ModelRouter.normalize("m"))
    }

    @Test
    fun healthScoringAndBench() {
        assertEquals(0.5, ModelHealth.score("p", "new-model"), 1e-9)
        assertTrue(ModelHealth.eligible("p", "new-model"))
        ModelHealth.recordOk("p", "m")
        ModelHealth.recordOk("p", "m")
        ModelHealth.recordErr("p", "m")
        // (2+1)/(2+1+2) = 0.6
        assertEquals(0.6, ModelHealth.score("p", "m"), 1e-9)
        assertTrue(ModelHealth.eligible("p", "m"))
        repeat(3) { ModelHealth.recordErr("p", "m") }
        assertTrue(!ModelHealth.eligible("p", "m"))
        assertNull(ModelHealth.pickBest("p", listOf("m")))
        ModelHealth.recordOk("p", "m") // recovery unbenched
        assertTrue(ModelHealth.eligible("p", "m"))
    }
}
