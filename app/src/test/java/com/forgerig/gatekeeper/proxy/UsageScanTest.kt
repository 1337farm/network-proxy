package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM-runnable coverage for the usage-block scanner (pure regex logic). */
class UsageScanTest {

    @Test
    fun anthropicFieldsTallied() {
        val body = """{"type":"message","usage":{"input_tokens":1200,"output_tokens":340,
            "cache_creation_input_tokens":500,"cache_read_input_tokens":700}}"""
        assertArrayEquals(
            longArrayOf(1200, 340, 700, 500),
            ProxyMetrics.scanUsage(body)
        )
    }

    @Test
    fun openAiFieldsTallied() {
        val body = """{"usage":{"prompt_tokens":200,"completion_tokens":50,
            "prompt_tokens_details":{"cached_tokens":30}}}"""
        assertArrayEquals(longArrayOf(200, 50, 30, 0), ProxyMetrics.scanUsage(body))
    }

    @Test
    fun sseStreamChunkTallied() {
        val body = "event: message_start\ndata: {\"type\": \"message_start\"}\n" +
            "event: message_delta\ndata: {\"usage\":{\"output_tokens\":17}}\n" +
            "event: message_stop\ndata: {\"type\": \"message_stop\"}\n"
        assertArrayEquals(longArrayOf(0, 17, 0, 0), ProxyMetrics.scanUsage(body))
    }

    @Test
    fun noUsageYieldsZeros() {
        assertArrayEquals(longArrayOf(0, 0, 0, 0), ProxyMetrics.scanUsage("<html>hi</html>"))
    }

    @Test
    fun zenRollover429EscalatesAndHonorsRetryAfter() {
        val store = ProviderStore.blank()
        val z = ProviderStore.Provider(
            "opencode-zen", "https://opencode.ai/zen/v1", "Authorization", "Bearer ",
            mutableListOf(
                ProviderStore.ApiKey("k1", "a", "s1"),
                ProviderStore.ApiKey("k2", "b", "s2")
            )
        )
        z.keys[0].id.let { }
        store.providers[z.id] = z
        store.coolDownMs = 60_000
        // First 429 cools base.
        store.report("opencode-zen", "k1", 429)
        val first = z.keys.find { it.id == "k1" }!!.cooledUntilMs
        assert(first > System.currentTimeMillis()) { "first 429 should cool" }
        // Second consecutive 429 escalates (streak 2).
        store.report("opencode-zen", "k1", 429)
        val second = z.keys.find { it.id == "k1" }!!.cooledUntilMs
        assert(second > first) { "escalated cooldown should exceed base" }
        // Retry-After wins when bigger: 600s.
        store.report("opencode-zen", "k2", 429, 600)
        val rt = z.keys.find { it.id == "k2" }!!.cooledUntilMs - System.currentTimeMillis()
        assert(rt >= 599_000) { "Retry-After 600s should dominate, got ${rt}ms" }
        // Active key rolls away from cooled key toward k2... both cooled now,
        // so usable is empty; free k2 manually and check streak reset on 200.
        z.keys.find { it.id == "k2" }!!.cooledUntilMs = 0
        store.report("opencode-zen", "k2", 200)
        assertEquals(0, z.keys.find { it.id == "k2" }!!.streak429)
    }

    @Test
    fun eventBufferCapsAndFormats() {
        ProxyMetrics.logSink = { _, _, _ -> }
        ProxyMetrics.clearEvents()
        ProxyMetrics.event("hello")
        ProxyMetrics.eventError("boom")
        val recent = ProxyMetrics.recentEvents(5)
        assertEquals(2, recent.size)
        assert(recent[0].endsWith("ERROR: boom"))
        assert(recent[1].endsWith("hello"))
        ProxyMetrics.clearEvents()
        assertEquals(0, ProxyMetrics.recentEvents(5).size)
    }
}
