package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-runnable coverage for LLM-only enforcement (pure logic). */
class LlmPolicyTest {

    private val llm = setOf(
        "opencode.ai", "integrate.api.nvidia.com",
        "generativelanguage.googleapis.com", "open.bigmodel.cn"
    )

    @Test
    fun brokeredForAllowlistedHosts() {
        assertEquals(
            LlmPolicy.Decision.BROKERED,
            LlmPolicy.decide("opencode.ai", llm, false)
        )
        // subdomains ride along (zen gateway, regional endpoints)
        assertEquals(
            LlmPolicy.Decision.BROKERED,
            LlmPolicy.decide("models.opencode.ai", llm, false)
        )
        assertEquals(
            LlmPolicy.Decision.BROKERED,
            LlmPolicy.decide("INTEGRATE.API.NVIDIA.COM", llm, false)
        )
    }

    @Test
    fun foreignHostsTunnelByDefault() {
        for (h in listOf("api.github.com", "github.com", "repo.maven.apache.org", "dl.google.com", "")) {
            assertEquals(
                "host $h", LlmPolicy.Decision.TUNNEL, LlmPolicy.decide(h, llm, false)
            )
        }
    }

    @Test
    fun foreignHostsDeniedInStrictMode() {
        assertEquals(
            LlmPolicy.Decision.DENY, LlmPolicy.decide("api.github.com", llm, true)
        )
        // allowlisted still brokered under strict
        assertEquals(
            LlmPolicy.Decision.BROKERED, LlmPolicy.decide("opencode.ai", llm, true)
        )
        assertTrue(LlmPolicy.denyBody("api.github.com").contains("NO_PROXY"))
    }

    @Test
    fun suffixMatchDoesNotOverreach() {
        // notopencode.ai must NOT match opencode.ai
        assertFalse(LlmPolicy.matchesAny("notopencode.ai", llm))
        assertFalse(LlmPolicy.matchesAny("opencode.ai.evil.com", llm))
        assertTrue(LlmPolicy.matchesAny("x.opencode.ai", llm))
    }

    @Test
    fun extractHostShapes() {
        assertEquals("opencode.ai", LlmPolicy.extractHost("https://opencode.ai/zen/v1/messages"))
        assertEquals("api.github.com", LlmPolicy.extractHost("api.github.com:443"))
        assertEquals("repo.maven.apache.org", LlmPolicy.extractHost("https://repo.maven.apache.org/maven2/"))
        assertEquals("", LlmPolicy.extractHost(":::"))
        assertEquals("", LlmPolicy.extractHost(""))
    }

    @Test
    fun hostsFromBaseUrls() {
        val hosts = LlmPolicy.hostsFromBaseUrls(
            listOf(
                "https://opencode.ai/zen/v1",
                "https://integrate.api.nvidia.com/v1",
                "not a url"
            )
        )
        assertEquals(setOf("opencode.ai", "integrate.api.nvidia.com"), hosts)
    }

    @Test
    fun storeAllowlistTracksProviders() {
        val store = ProviderStore.blank()
        store.providers["custom"] = ProviderStore.Provider("custom", "https://llm.example.com/v1")
        // well-known providers auto-seed on blank()? No — blank is empty.
        // Custom host present; well-known absent until ensureWellKnown.
        assertTrue(store.llmHosts().contains("llm.example.com"))
        ProviderStore.ensureWellKnown(store)
        assertTrue(store.llmHosts().contains("opencode.ai"))
        assertFalse(store.llmHosts().contains("api.github.com"))
    }

    @Test
    fun strictFlagPersists() {
        val store = ProviderStore.blank()
        assertFalse(store.llmOnlyStrict)
        store.llmOnlyStrict = true
        val re = ProviderStore.fromJson(store.toJson())
        assertTrue(re.llmOnlyStrict)
    }

    @Test
    fun categoriesAreHostAware() {
        // api.github.com: legacy shape logic said "file" (github.com rule);
        // host-aware says passthrough (not an LLM host).
        assertEquals("passthrough", ProxyMetrics.categorizeUrl("api.github.com:443", llm))
        assertEquals("passthrough", ProxyMetrics.categorizeUrl("https://repo.maven.apache.org/x", llm))
        // allowlisted hosts keep shape logic (api-first: v1/ path wins)
        assertEquals(
            "api",
            ProxyMetrics.categorizeUrl("https://integrate.api.nvidia.com/v1/chat/completions", llm)
        )
        // empty host set preserves legacy shape-only behavior
        // (api shape wins over file shape since the reorder)
        assertEquals("api", ProxyMetrics.categorizeUrl("api.github.com:443", emptySet()))
        assertEquals("file", ProxyMetrics.categorizeUrl("https://dl.google.com/model.zip", emptySet()))
    }
}
