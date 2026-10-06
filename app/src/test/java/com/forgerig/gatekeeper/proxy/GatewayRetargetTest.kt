package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `retarget` is the load-bearing step for the front door: a gateway request
 * arrives with no real target, so the URL it carries is a placeholder, and
 * this function is what turns it into the upstream call. If it mishandles
 * the placeholder the request goes to the wrong host or the wrong path --
 * silently, because the upstream is a stranger that will simply 404 or,
 * worse, answer from the wrong model.
 */
class GatewayRetargetTest {

    private fun gatewayUrl(path: String) = "http://gateway.invalid$path"

    /** Retarget the way the front door does: the leg's scheme wins. */
    private fun retargetFromDoor(path: String, legBaseUrl: String) =
        ProviderStore.retarget(gatewayUrl(path), legBaseUrl, preferLegScheme = true)

    @Test
    fun `placeholder is replaced by the provider origin`() {
        assertEquals(
            "https://api.anthropic.com/v1/chat/completions",
            retargetFromDoor("/v1/chat/completions", "https://api.anthropic.com")
        )
    }

    @Test
    fun `provider base path is preserved`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            retargetFromDoor("/chat/completions", "https://openrouter.ai/api/v1")
        )
    }

    @Test
    fun `a base path already present in the request is not doubled`() {
        // The client is pointed at .../api/v1, so the request already carries
        // that prefix; appending it again would produce /api/v1/api/v1/...
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            retargetFromDoor("/api/v1/chat/completions", "https://openrouter.ai/api/v1")
        )
    }

    @Test
    fun `trailing slash on the base url does not produce a double slash`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            retargetFromDoor("/chat/completions", "https://openrouter.ai/api/v1/")
        )
    }

    @Test
    fun `query string survives retargeting`() {
        assertEquals(
            "https://api.anthropic.com/v1/models?limit=2",
            retargetFromDoor("/v1/models?limit=2", "https://api.anthropic.com")
        )
    }

    @Test
    fun `the default preserves the placeholder scheme, which is why the door opts in`() {
        // Documents the hazard the flag exists for: with the default, a
        // front-door URL would go upstream over plaintext http carrying the
        // provider API key. Every other test here goes through
        // retargetFromDoor, which opts in and gets https.
        assertEquals(
            "http://api.anthropic.com/v1/chat/completions",
            ProviderStore.retarget(gatewayUrl("/v1/chat/completions"), "https://api.anthropic.com")
        )
        assertEquals(
            "https://api.anthropic.com/v1/chat/completions",
            retargetFromDoor("/v1/chat/completions", "https://api.anthropic.com")
        )
    }

    @Test
    fun `an intercepted request keeps its own scheme`() {
        // Forward-proxy/MITM callers do not pass the flag: the client's own
        // scheme is authoritative there and must not be silently upgraded.
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            ProviderStore.retarget("https://api.openai.com/v1/messages", "https://api.anthropic.com")
        )
    }

    @Test
    fun `explicit non default port on the provider is kept`() {
        assertEquals(
            "http://127.0.0.1:9999/v1/chat/completions",
            retargetFromDoor("/v1/chat/completions", "http://127.0.0.1:9999")
        )
    }
}