package com.forgerig.gatekeeper.proxy

import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Upstream TLS transport for brokered provider calls.
 *
 * The platform TLS stack on some devices emits a ClientHello fingerprint
 * that bot-management edges (Cloudflare et al) block outright: same URL,
 * same key and same headers pass via curl/OpenSSL and via JDK TLS, but
 * the on-device Conscrypt handshake draws an HTTP-layer 403 every time
 * (TLS 1.2/1.3, HTTP/1.1/2 and cipher tweaks all failed identically).
 * BouncyCastle's pure-Java TLS stack emits a different hello that those
 * edges accept, and its bytes are identical on every device — no OEM
 * variance, no native libraries.
 *
 * Only the *brokered upstream* calls use this. The front-door server
 * side ([EndpointTls]) and everything else stay on platform TLS.
 * Hostname verification is still OkHttp's own, and trust still comes
 * from the platform CA store — only the handshake bytes change.
 */
internal object TlsTransport {

    /** BCJSSE provider name, for explicit [javax.net.ssl.SSLContext] lookup. */
    const val PROVIDER_NAME = "BCJSSE"

    /** Ensure the BCJSSE provider is registered. Idempotent. */
    fun ensureProvider(): Boolean {
        return try {
            if (java.security.Security.getProvider(PROVIDER_NAME) == null) {
                java.security.Security.addProvider(
                    org.bouncycastle.jsse.provider.BouncyCastleJsseProvider()
                )
            }
            java.security.Security.getProvider(PROVIDER_NAME) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * An (SSLSocketFactory, trust-manager) pair driving brokered TLS through
     * BouncyCastle, or null when anything fails (caller keeps the platform
     * default). A [java.security.SecureRandom] is always passed explicitly:
     * letting BC look one up by name fails on runtimes without it.
     */
    fun upstreamFactory(): Pair<SSLSocketFactory, X509TrustManager>? {
        return try {
            if (!ensureProvider()) return null
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return null
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS", PROVIDER_NAME)
            ctx.init(null, arrayOf(tm), java.security.SecureRandom())
            ctx.socketFactory to tm
        } catch (_: Exception) {
            null
        }
    }
}
