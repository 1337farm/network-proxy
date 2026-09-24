package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Well-known providers converge without a manual Seed step. */
class ProviderSeedTest {

    @Test
    fun blankStoreSeedsWellKnown() {
        val store = ProviderStore.blank()
        val added = ProviderStore.ensureWellKnown(store)
        assertEquals(ProviderStore.wellKnown().size, added)
        assertEquals(
            ProviderStore.wellKnown().map { it.id }.toSet(),
            store.providers.keys
        )
    }

    @Test
    fun ensureWellKnownIsIdempotentAndPreservesKeys() {
        val store = ProviderStore.blank()
        ProviderStore.ensureWellKnown(store)
        val zen = store.providers["opencode-zen"]!!
        zen.keys.add(ProviderStore.ApiKey("k1", "a", "s1"))
        val added = ProviderStore.ensureWellKnown(store)
        assertEquals(0, added)
        assertEquals(1, store.providers["opencode-zen"]!!.keys.size)
        assertEquals("s1", store.providers["opencode-zen"]!!.keys[0].secret)
    }

    @Test
    fun ensureWellKnownRefreshesWiringButKeepsKeys() {
        val store = ProviderStore.blank()
        store.providers["nvidia"] = ProviderStore.Provider(
            "nvidia", "https://stale.example.com", "x-api-key", "",
            mutableListOf(ProviderStore.ApiKey("k9", "old", "s9"))
        )
        ProviderStore.ensureWellKnown(store)
        val nvidia = store.providers["nvidia"]!!
        assertEquals("https://integrate.api.nvidia.com/v1", nvidia.baseUrl)
        assertEquals("Authorization", nvidia.authHeader)
        assertEquals("Bearer ", nvidia.authScheme)
        assertEquals(1, nvidia.keys.size)
    }

    @Test
    fun ensureWellKnownDropsKeylessLegacyButKeepsKeyedCustom() {
        val store = ProviderStore.blank()
        ProviderStore.ensureWellKnown(store)
        store.providers["legacy-gone"] = ProviderStore.Provider("legacy-gone", "https://x.example")
        store.providers["custom-mine"] = ProviderStore.Provider(
            "custom-mine", "https://mine.example",
            keys = mutableListOf(ProviderStore.ApiKey("k", "l", "s"))
        )
        ProviderStore.ensureWellKnown(store)
        assert(!store.providers.containsKey("legacy-gone"))
        assertTrue(store.providers.containsKey("custom-mine"))
    }

    @Test
    fun wellKnownExcludesGoogleAndPrunesKeylessGoogle() {
        // Google offers no usable free LLM tier: never seeded, and a
        // keyless leftover is pruned on load (keyed entries are kept —
        // user data is never deleted).
        assertTrue(ProviderStore.wellKnown().none { it.id == "google" })
        val store = ProviderStore.blank()
        ProviderStore.ensureWellKnown(store)
        assert(!store.providers.containsKey("google"))
        store.providers["google"] = ProviderStore.Provider(
            "google", "https://generativelanguage.googleapis.com/v1beta"
        )
        ProviderStore.ensureWellKnown(store)
        assert(!store.providers.containsKey("google"))
    }

    // NOTE: fromJson() itself isn't unit-tested here — org.json is an
    // Android stub under plain JVM tests ("not mocked"). fromJson delegates
    // to ensureWellKnown, which the cases above cover.
}
