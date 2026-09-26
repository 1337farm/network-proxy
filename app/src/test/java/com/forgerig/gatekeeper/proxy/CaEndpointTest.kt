package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing rules for the curl-able local CA endpoint (`GET /ca.pem`). */
class CaEndpointTest {

    @Test
    fun originFormMatches() {
        assertTrue(CaEndpoint.isLocalCaRequest("/ca.pem"))
    }

    @Test
    fun proxiedLoopbackAbsoluteFormMatches() {
        assertTrue(CaEndpoint.isLocalCaRequest("http://127.0.0.1:3128/ca.pem"))
        assertTrue(CaEndpoint.isLocalCaRequest("http://localhost:8080/ca.pem"))
        assertTrue(CaEndpoint.isLocalCaRequest("http://127.0.0.1:3128/ca.pem?x=1"))
    }

    @Test
    fun upstreamCaPemPassesThrough() {
        // Same path, foreign host — must be forwarded upstream, never served.
        assertFalse(CaEndpoint.isLocalCaRequest("http://example.com/ca.pem"))
        assertFalse(CaEndpoint.isLocalCaRequest("https://cdn.example.com:443/ca.pem"))
    }

    @Test
    fun otherPathsPassThrough() {
        assertFalse(CaEndpoint.isLocalCaRequest("/"))
        assertFalse(CaEndpoint.isLocalCaRequest("/cafepem"))
        assertFalse(CaEndpoint.isLocalCaRequest("/ca.pem.evil"))
        assertFalse(CaEndpoint.isLocalCaRequest("http://127.0.0.1:3128/other"))
        assertFalse(CaEndpoint.isLocalCaRequest("http://127.0.0.1:3128/"))
    }

    @Test
    fun remotePeersCannotFetchTheCa() {
        // The listener binds 0.0.0.0, so without a loopback gate any LAN
        // host could fingerprint that this device runs a MITM proxy.
        assertFalse(CaEndpoint.isLocalCaRequest("/ca.pem", peerIsLoopback = false))
        assertFalse(CaEndpoint.isLocalCaRequest("http://127.0.0.1:3128/ca.pem", peerIsLoopback = false))
    }

    @Test
    fun loopbackPeerDetection() {
        assertTrue(CaEndpoint.isLoopbackPeer("127.0.0.1"))
        assertTrue(CaEndpoint.isLoopbackPeer("127.1.2.3"))
        assertTrue(CaEndpoint.isLoopbackPeer("::1"))
        assertTrue(CaEndpoint.isLoopbackPeer("[::1]"))
        assertTrue(CaEndpoint.isLoopbackPeer("127.0.0.1%wlan0"))
        assertFalse(CaEndpoint.isLoopbackPeer("192.168.1.5"))
        assertFalse(CaEndpoint.isLoopbackPeer("10.0.0.2"))
        assertFalse(CaEndpoint.isLoopbackPeer(null))
        assertFalse(CaEndpoint.isLoopbackPeer(""))
    }

    @Test
    fun pathExtraction() {
        assertEquals("/ca.pem", CaEndpoint.pathOf("/ca.pem"))
        assertEquals("/ca.pem", CaEndpoint.pathOf("http://127.0.0.1:3128/ca.pem?x=1"))
        assertEquals("/", CaEndpoint.pathOf("http://127.0.0.1:3128"))
    }
}
