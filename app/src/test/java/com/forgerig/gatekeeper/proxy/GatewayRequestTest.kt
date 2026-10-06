package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Getting this wrong is the difference between "the proxy is the provider
 * I call" and "the proxy silently hijacks unrelated traffic", so the
 * detection is pinned down case by case.
 */
class GatewayRequestTest {

    private val port = 3128

    // --- the front door: origin-form + loopback Host ---

    @Test
    fun `origin-form with loopback host and port is the front door`() {
        assertTrue(GatewayRequest.isAddressedToUs("/v1/chat/completions", "127.0.0.1:3128", port))
    }

    @Test
    fun `origin-form with localhost is the front door`() {
        assertTrue(GatewayRequest.isAddressedToUs("/v1/messages", "localhost:3128", port))
    }

    @Test
    fun `origin-form with bare loopback host and no port is the front door`() {
        assertTrue(GatewayRequest.isAddressedToUs("/v1/models", "127.0.0.1", port))
    }

    @Test
    fun `ipv6 loopback is the front door`() {
        assertTrue(GatewayRequest.isAddressedToUs("/v1/models", "[::1]:3128", port))
    }

    // --- forwarding traffic must never look like the front door ---

    @Test
    fun `absolute-form is always a forward-proxy request`() {
        assertFalse(GatewayRequest.isAddressedToUs("http://api.anthropic.com/v1/messages", "api.anthropic.com", port))
        assertFalse(GatewayRequest.isAddressedToUs("https://api.anthropic.com/v1/messages", "api.anthropic.com", port))
    }

    @Test
    fun `absolute-form aimed at loopback is still not the front door`() {
        // http://127.0.0.1:3128/ would loop back into ourselves.
        assertFalse(GatewayRequest.isAddressedToUs("http://127.0.0.1:3128/v1/models", "127.0.0.1:3128", port))
    }

    @Test
    fun `a remote host on our port is not the front door`() {
        assertFalse(GatewayRequest.isAddressedToUs("/v1/models", "10.0.0.5:3128", port))
    }

    @Test
    fun `another local service on another port is not the front door`() {
        // Right host, wrong port: that is some other listener, not us.
        assertFalse(GatewayRequest.isAddressedToUs("/v1/models", "127.0.0.1:8080", port))
    }

    @Test
    fun `no host header is not the front door`() {
        // HTTP/1.0 origin-form: we cannot tell it from a relative target, so
        // refusing is the safe answer.
        assertFalse(GatewayRequest.isAddressedToUs("/v1/models", null, port))
        assertFalse(GatewayRequest.isAddressedToUs("/v1/models", "", port))
        assertFalse(GatewayRequest.isAddressedToUs("/v1/models", "   ", port))
    }

    @Test
    fun `host matching is case insensitive`() {
        assertTrue(GatewayRequest.isAddressedToUs("/v1/models", "LOCALHOST:3128", port))
    }

    // --- authority parsing ---

    @Test
    fun `malformed ipv6 literal is rejected`() {
        assertFalse(GatewayRequest.isLoopbackAuthority("[::1", port))
    }

    @Test
    fun `loopback host with our port or none is accepted`() {
        assertTrue(GatewayRequest.isLoopbackAuthority("127.0.0.1:3128", port))
        assertTrue(GatewayRequest.isLoopbackAuthority("127.0.0.1", port))
        assertTrue(GatewayRequest.isLoopbackAuthority("[::1]:3128", port))
    }

    @Test
    fun `path is preserved for upstream retargeting`() {
        assertEquals("/v1/chat/completions", GatewayRequest.pathOf("/v1/chat/completions"))
        assertEquals("/v1/models?limit=2", GatewayRequest.pathOf("/v1/models?limit=2"))
        assertEquals("/", GatewayRequest.pathOf(""))
    }
}