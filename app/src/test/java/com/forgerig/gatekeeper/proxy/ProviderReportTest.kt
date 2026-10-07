package com.forgerig.gatekeeper.proxy

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Key-health reporting: 401 bricks a key (wrong/revoked — only the owner
 * can re-enable it in Manage Keys), while 403 cools down and recovers. A
 * Cloudflare-edge 403 once disabled two good keys with no path back, so
 * the 403-must-not-disable invariant is pinned here.
 */
class ProviderReportTest {

    private val realSink = ProxyMetrics.logSink

    @Before
    fun muteLog() {
        ProxyMetrics.logSink = { _, _, _ -> }
    }

    @After
    fun restoreLog() {
        ProxyMetrics.logSink = realSink
    }

    private fun store(): ProviderStore {
        val s = ProviderStore.blank()
        s.providers["p"] = ProviderStore.Provider(
            "p", "https://api.example.com", "Authorization", "Bearer ",
            mutableListOf(ProviderStore.ApiKey("k1", "one", "s1"))
        )
        return s
    }

    @Test
    fun forbiddenCoolsDownInsteadOfDisabling() {
        val s = store()
        val before = System.currentTimeMillis()
        s.report("p", "k1", 403)
        val key = s.providers["p"]!!.keys[0]
        assertTrue("403 must not disable the key", key.enabled)
        assertTrue("403 must cool the key", key.cooledUntilMs > before)
    }

    @Test
    fun unauthorizedStillDisables() {
        val s = store()
        s.report("p", "k1", 401)
        val key = s.providers["p"]!!.keys[0]
        assertFalse("401 must disable the key", key.enabled)
    }

    @Test
    fun retryAfterExtendsTheCooldown() {
        val s = store()
        val before = System.currentTimeMillis()
        s.report("p", "k1", 429, retryAfterSecs = 120)
        val key = s.providers["p"]!!.keys[0]
        assertTrue(key.enabled)
        assertTrue(key.cooledUntilMs >= before + 120_000)
    }
}
