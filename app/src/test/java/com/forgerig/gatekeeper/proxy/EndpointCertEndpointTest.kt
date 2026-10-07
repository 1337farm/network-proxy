package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The endpoint-certificate request gate.
 *
 * Same shape as the MITM CA endpoint: loopback callers only. These tests
 * exist mostly to pin the *difference* between the two paths, since the whole
 * reason the endpoint cert is a separate mechanism is that it grants a much
 * smaller capability than the MITM CA does.
 */
class EndpointCertEndpointTest {

    @Test
    fun loopbackOriginFormFetchesTheCert() {
        assertTrue(
            CaEndpoint.isLocalEndpointCertRequest(ENDPOINT_CERT_PATH, peerIsLoopback = true)
        )
    }

    @Test
    fun absoluteFormFromLoopbackFetchesTheCert() {
        assertTrue(
            CaEndpoint.isLocalEndpointCertRequest(
                "http://127.0.0.1:3128$ENDPOINT_CERT_PATH", peerIsLoopback = true
            )
        )
        assertTrue(
            CaEndpoint.isLocalEndpointCertRequest(
                "http://localhost:3128$ENDPOINT_CERT_PATH", peerIsLoopback = true
            )
        )
    }

    @Test
    fun remotePeersCannotFetchIt() {
        assertFalse(
            CaEndpoint.isLocalEndpointCertRequest(ENDPOINT_CERT_PATH, peerIsLoopback = false)
        )
        assertFalse(
            CaEndpoint.isLocalEndpointCertRequest(
                "http://127.0.0.1:3128$ENDPOINT_CERT_PATH", peerIsLoopback = false
            )
        )
    }

    @Test
    fun otherPathsAreNotTheCertEndpoint() {
        for (p in listOf("/", "/ca.pem", "/v1/models", "/endpoint-cert.pem.bak")) {
            assertFalse("must not answer for '$p'", CaEndpoint.isLocalEndpointCertRequest(p))
        }
    }

    @Test
    fun itDoesNotCollideWithTheMitmCaEndpoint() {
        // The two paths must stay distinct: serving one in place of the other
        // would make a client pin the wrong certificate.
        assertTrue(CaEndpoint.isLocalCaRequest("/ca.pem"))
        assertFalse(CaEndpoint.isLocalCaRequest(ENDPOINT_CERT_PATH))
        assertTrue(CaEndpoint.isLocalEndpointCertRequest(ENDPOINT_CERT_PATH))
        assertFalse(CaEndpoint.isLocalEndpointCertRequest("/ca.pem"))
    }
}