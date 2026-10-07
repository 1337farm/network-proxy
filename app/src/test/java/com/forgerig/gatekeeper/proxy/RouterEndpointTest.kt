package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing rules for the router-local endpoints (loopback callers only). */
class RouterEndpointTest {

    @Test
    fun originFormFromLoopbackMatches() {
        assertTrue(RouterEndpoint.isLocalEndpointCertRequest(ENDPOINT_CERT_PATH, peerIsLoopback = true))
    }

    @Test
    fun absoluteFormFromLoopbackMatches() {
        assertTrue(
            RouterEndpoint.isLocalEndpointCertRequest(
                "http://127.0.0.1:3129$ENDPOINT_CERT_PATH", peerIsLoopback = true
            )
        )
        assertTrue(
            RouterEndpoint.isLocalEndpointCertRequest(
                "http://localhost:3129$ENDPOINT_CERT_PATH", peerIsLoopback = true
            )
        )
    }

    @Test
    fun upstreamUrlsNeverMatch() {
        assertFalse(RouterEndpoint.isLocalEndpointCertRequest("http://example.com$ENDPOINT_CERT_PATH"))
        assertFalse(RouterEndpoint.isLocalEndpointCertRequest("https://cdn.example.com:443$ENDPOINT_CERT_PATH"))
    }

    @Test
    fun otherPathsDoNotMatch() {
        for (p in listOf("/", "/ca.pem", "/v1/models", "/endpoint-cert.pem.bak", "/endpoint-cert.pem.evil")) {
            assertFalse("must not answer for '$p'", RouterEndpoint.isLocalEndpointCertRequest(p))
        }
    }

    @Test
    fun remotePeersCannotFetchIt() {
        assertFalse(RouterEndpoint.isLocalEndpointCertRequest(ENDPOINT_CERT_PATH, peerIsLoopback = false))
        assertFalse(
            RouterEndpoint.isLocalEndpointCertRequest(
                "http://127.0.0.1:3129$ENDPOINT_CERT_PATH", peerIsLoopback = false
            )
        )
    }

    @Test
    fun loopbackPeers() {
        assertTrue(RouterEndpoint.isLoopbackPeer("127.0.0.1"))
        assertTrue(RouterEndpoint.isLoopbackPeer("127.1.2.3"))
        assertTrue(RouterEndpoint.isLoopbackPeer("::1"))
        assertTrue(RouterEndpoint.isLoopbackPeer("[::1]"))
        assertTrue(RouterEndpoint.isLoopbackPeer("127.0.0.1%wlan0"))
        assertFalse(RouterEndpoint.isLoopbackPeer("192.168.1.5"))
        assertFalse(RouterEndpoint.isLoopbackPeer("10.0.0.2"))
        assertFalse(RouterEndpoint.isLoopbackPeer(null))
        assertFalse(RouterEndpoint.isLoopbackPeer(""))
    }

    @Test
    fun pathParsing() {
        assertEquals(ENDPOINT_CERT_PATH, RouterEndpoint.pathOf(ENDPOINT_CERT_PATH))
        assertEquals(ENDPOINT_CERT_PATH, RouterEndpoint.pathOf("http://127.0.0.1:3129$ENDPOINT_CERT_PATH?x=1"))
        assertEquals("/", RouterEndpoint.pathOf("http://127.0.0.1:3129"))
    }
}
