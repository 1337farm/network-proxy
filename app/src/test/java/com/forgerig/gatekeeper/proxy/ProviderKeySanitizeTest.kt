package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

/**
 * Pasted API keys arrive with stray whitespace (wrapped lines smuggle
 * interior newlines past end-trimming) and OkHttp rejects such a header
 * with IllegalArgumentException — a zero-byte dead connection. Secrets
 * must be sanitized at rest and at injection.
 */
class ProviderKeySanitizeTest {

    private fun provider() = ProviderStore.Provider(
        "p", "https://api.example.com", "Authorization", "Bearer "
    )

    @Test
    fun authValueStripsInteriorWhitespace() {
        val key = ProviderStore.ApiKey("k", "l", "sk-abc\ndef\rghi jkl\ttail\n")
        assertEquals("Bearer sk-abcdefghijkltail", ProviderStore.blank().authValue(provider(), key))
    }

    @Test
    fun authValueWithoutScheme() {
        val p = ProviderStore.Provider("p", "https://api.example.com", "x-api-key", "")
        val key = ProviderStore.ApiKey("k", "l", "  secret\n")
        assertEquals("secret", ProviderStore.blank().authValue(p, key))
    }

    @Test
    fun fromJsonSanitizesSecret() {
        val o = JSONObject()
            .put("id", "k")
            .put("label", "l")
            .put("secret", "sk-abc\ndef ")
        assertEquals("sk-abcdef", ProviderStore.ApiKey.fromJson(o).secret)
    }
}
