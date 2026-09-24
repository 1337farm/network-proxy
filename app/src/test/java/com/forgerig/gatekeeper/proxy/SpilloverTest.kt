package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-runnable coverage for cross-provider spillover (pure store logic). */
class SpilloverTest {

    @org.junit.Before
    fun silenceLog() {
        // android.util.Log is stubbed under plain JVM tests ("not mocked").
        ProxyMetrics.logSink = { _, _, _ -> }
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
    fun spillsToSameFamilyProvider() {
        // openrouter base is chat/completions-shaped (OPENAI_CHAT) while zen
        // /messages is ANTHROPIC — adjust: give openrouter a messages-family
        // base so they match, mirroring Zen's multi-family gateway.
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        val (p, k) = s.spilloverTarget("zen", "z1")!!
        assertEquals("openrouter", p.id)
        assertEquals("o1", k.id)
    }

    @Test
    fun skipsDifferentFamilyProvider() {
        val s = store() // openrouter = OPENAI_CHAT, zen = ANTHROPIC
        assertNull(s.spilloverTarget("zen", "z1"))
    }

    @Test
    fun skipsProvidersWithNoLiveKeys() {
        val s = store()
        s.providers["openrouter"] = keyed(
            "openrouter", "https://openrouter.ai/zen/v1/messages", key("o1")
        )
        // cool the only candidate key
        s.report("openrouter", "o1", 429)
        assertNull(s.spilloverTarget("zen", "z1"))
    }

    @Test
    fun excludesFailedKeyId() {
        val s = ProviderStore.blank()
        s.providers.clear()
        s.providers["a"] = keyed("a", "https://a.example/zen/v1/messages", key("shared"))
        s.providers["b"] = keyed("b", "https://b.example/zen/v1/messages", key("shared"))
        // same key id on the candidate must be skipped
        assertNull(s.spilloverTarget("a", "shared"))
    }

    @Test
    fun honorsFailoverSwitchAndUnknownFamily() {
        val s = store()
        s.routeFailoverEnabled = false
        assertNull(s.spilloverTarget("zen", "z1"))
        s.routeFailoverEnabled = true
        s.providers["weird"] = keyed("weird", "https://weird.example/thing", key("w1"))
        // "weird" has no known family markers AND zen can't spill to it;
        // from-side unknown family also returns null:
        s.providers["odd"] = keyed("odd", "https://odd.example/stuff", key("q1"))
        assertNull(s.spilloverTarget("odd", "q1"))
    }

    @Test
    fun deterministicOrderById() {
        val s = ProviderStore.blank()
        s.providers.clear()
        s.providers["zen"] = keyed("zen", "https://z.example/zen/v1/messages", key("z1"))
        s.providers["b-second"] = keyed("b-second", "https://b.example/zen/v1/messages", key("b1"))
        s.providers["a-first"] = keyed("a-first", "https://a.example/zen/v1/messages", key("a1"))
        val (p, _) = s.spilloverTarget("zen", "z1")!!
        assertTrue(p.id == "a-first")
    }
}
