package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brokered-upstream TLS transport wires up without a device: provider
 * present, platform trust manager resolved, socket factory built. The
 * handshake itself is proven live on-device; no network here.
 */
class TlsTransportTest {

    @Test
    fun providerRegisters() {
        assertTrue(TlsTransport.ensureProvider())
        assertTrue(TlsTransport.ensureProvider()) // idempotent
        assertEquals(
            TlsTransport.PROVIDER_NAME,
            java.security.Security.getProvider(TlsTransport.PROVIDER_NAME).name
        )
    }

    @Test
    fun factoryBuildsWithPlatformTrust() {
        val (factory, tm) = TlsTransport.upstreamFactory()
            ?: throw AssertionError("factory must build on JVM")
        assertNotNull(factory)
        assertTrue(tm.acceptedIssuers.isNotEmpty())
    }
}
