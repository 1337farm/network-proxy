package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpoint-certificate request gate: loopback callers only, and only
 * for the exact cert path. There is no CA endpoint anymore (no MITM), so
 * these tests also pin that the old `/ca.pem` path is NOT answered locally
 * — it must fall through to normal request handling.
 */
class EndpointCertEndpointTest {

    @Test
    fun loopbackOriginFormFetchesTheCert() {
        assertTrue(
            RouterEndpoint.isLocalEndpointCertRequest(ENDPOINT_CERT_PATH, peerIsLoopback = true)
        )
    }

    @Test
    fun absoluteFormFromLoopbackFetchesTheCert() {
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
    fun remotePeersCannotFetchIt() {
        assertFalse(
            RouterEndpoint.isLocalEndpointCertRequest(ENDPOINT_CERT_PATH, peerIsLoopback = false)
        )
        assertFalse(
            RouterEndpoint.isLocalEndpointCertRequest(
                "http://127.0.0.1:3129$ENDPOINT_CERT_PATH", peerIsLoopback = false
            )
        )
    }

    @Test
    fun otherPathsAreNotTheCertEndpoint() {
        for (p in listOf("/", "/ca.pem", "/v1/models", "/endpoint-cert.pem.bak")) {
            assertFalse("must not answer for '$p'", RouterEndpoint.isLocalEndpointCertRequest(p))
        }
    }
}
