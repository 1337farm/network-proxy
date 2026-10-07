package com.forgerig.gatekeeper.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Failover decisions and retry budget, without a Service or socket.
 *
 * The relay loop in [ProxyService] delegates here; these tests pin the
 * contract: sibling keys first, route legs with model+URL+length rewrite,
 * same-family spillover, null when exhausted — and a hard elapsed cap plus
 * capped sleeps so one poisoned request cannot hang a pool thread for
 * minutes.
 */
class FailoverTest {

    private val noJitter = RetryPolicy(maxRetries = 5, baseBackoffMs = 2_000, maxBackoffMs = 30_000, jitter = false)

    @After
    fun resetGlobals() {
        ModelRouter.clear()
        ModelHealth.clear()
    }

    private fun provider(
        id: String, base: String, vararg keys: ProviderStore.ApiKey
    ) = ProviderStore.Provider(id, base, "Authorization", "Bearer ", keys.toMutableList())

    private fun key(id: String) = ProviderStore.ApiKey(id, id, "secret-$id")

    // ---- budget ----

    @Test
    fun deadlineBoundsTotalRetryTime() {
        assertFalse(Failover.deadlineExceeded(1_000L, nowMs = 1_000L + Failover.RETRY_DEADLINE_MS - 1))
        assertFalse(Failover.deadlineExceeded(1_000L, nowMs = 1_000L + Failover.RETRY_DEADLINE_MS))
        assertTrue(Failover.deadlineExceeded(1_000L, nowMs = 1_000L + Failover.RETRY_DEADLINE_MS + 1))
    }

    @Test
    fun smallRetryAfterIsHonored() {
        assertEquals(5_000L, Failover.cappedRetryDelay(5, 0, noJitter))
    }

    @Test
    fun hugeRetryAfterIsCappedAtTheCeiling() {
        // A malicious/buggy Retry-After: 3600 must not park a pool thread
        // for an hour.
        assertEquals(30_000L, Failover.cappedRetryDelay(3_600, 0, noJitter))
    }

    @Test
    fun absentRetryAfterUsesPolicyBackoff() {
        assertEquals(2_000L, Failover.cappedRetryDelay(null, 0, noJitter))
        assertEquals(4_000L, Failover.cappedRetryDelay(0, 1, noJitter))
        assertEquals(4_000L, Failover.cappedRetryDelay(-3, 1, noJitter))
    }

    // ---- next key ----

    @Test
    fun siblingKeyOnTheSameProviderWins() {
        val s = ProviderStore.blank()
        s.providers["a"] = provider("a", "https://a.example.com/v1", key("k1"), key("k2"))
        val events = mutableListOf<String>()
        val next = Failover.nextUsableKey(
            s, s.providers["a"]!!, "k1", null,
            """{"model":"m"}""".toByteArray(), "https://a.example.com/v1/chat",
            mutableMapOf(), mutableMapOf(), events::add
        )!!
        assertEquals("k2", next.key.second.id)
        assertEquals("https://a.example.com/v1/chat", next.url)
        assertTrue(events.isEmpty())
    }

    @Test
    fun exhaustedPoolWithUnknownFamilyReturnsNull() {
        val s = ProviderStore.blank()
        s.providers["a"] = provider("a", "http://127.0.0.1:9", key("k1"))
        val next = Failover.nextUsableKey(
            s, s.providers["a"]!!, "k1", null,
            """{"model":"m"}""".toByteArray(), "http://127.0.0.1:9/x",
            mutableMapOf(), mutableMapOf()
        )
        assertNull(next)
    }

    @Test
    fun legAdvanceRewritesModelUrlAndLength() {
        val s = ProviderStore.blank()
        s.providers["a"] = provider("a", "https://a.example.com/v1", key("ka"))
        s.providers["b"] = provider("b", "https://b.example.com/v1", key("kb"))
        s.routes["fast"] = ProviderStore.Route(
            "fast", listOf(
                ProviderStore.RouteLeg("a", "m1"),
                ProviderStore.RouteLeg("b", "m2")
            )
        )
        val headers = mutableMapOf("Content-Length" to "999")
        val mutable = mutableMapOf("Content-Length" to "999")
        val events = mutableListOf<String>()
        val body = """{"model":"m1","messages":[]}""".toByteArray()
        val next = Failover.nextUsableKey(
            s, s.providers["a"]!!, "ka", ProviderStore.RouteLeg("a", "m1"),
            body, "https://a.example.com/v1/chat",
            headers, mutable, events::add
        )!!
        assertEquals("kb", next.key.second.id)
        assertEquals(ProviderStore.RouteLeg("b", "m2"), next.leg)
        assertEquals("m2", org.json.JSONObject(next.body!!.toString(Charsets.UTF_8)).getString("model"))
        assertTrue("retargeted to leg base: ${next.url}", next.url.startsWith("https://b.example.com/v1/chat"))
        assertEquals(next.body!!.size.toString(), headers["Content-Length"])
        assertEquals(next.body!!.size.toString(), mutable["Content-Length"])
        assertEquals(1, events.size)
        assertTrue(events[0].contains("Leg failover"))
    }

    @Test
    fun spilloverPicksSameModelOnAPeer() {
        val s = ProviderStore.blank()
        s.providers["a"] = provider("a", "https://a.example.com/v1/chat/completions", key("ka"))
        s.providers["b"] = provider("b", "https://b.example.com/v1/chat/completions", key("kb"))
        ModelRouter.noteObserved("b", "m-x")
        val events = mutableListOf<String>()
        val next = Failover.nextUsableKey(
            s, s.providers["a"]!!, "ka", null,
            """{"model":"m-x"}""".toByteArray(), "https://a.example.com/v1/chat/completions",
            mutableMapOf(), mutableMapOf(), events::add
        )!!
        assertEquals("b", next.key.first.id)
        assertEquals("kb", next.key.second.id)
        assertEquals("m-x", org.json.JSONObject(next.body!!.toString(Charsets.UTF_8)).getString("model"))
        assertTrue(events[0].contains("Spillover"))
    }

    @Test
    fun errorCooldownKeepsTheKeyEnabledButBenched() {
        val realSink = ProxyMetrics.logSink
        ProxyMetrics.logSink = { _, _, _ -> }
        try {
            val s = ProviderStore.blank()
            s.providers["a"] = provider("a", "https://a.example.com/v1", key("k1"))
            val before = System.currentTimeMillis()
            s.reportError("a", "k1")
            val key = s.providers["a"]!!.keys[0]
            assertTrue("errors cool, never disable", key.enabled)
            assertTrue(key.cooledUntilMs > before)
        } finally {
            ProxyMetrics.logSink = realSink
        }
    }
}
