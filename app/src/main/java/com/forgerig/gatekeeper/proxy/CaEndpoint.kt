package com.forgerig.gatekeeper.proxy

/**
 * Pure routing logic for the curl-able local CA endpoint (`GET /ca.pem`).
 *
 * Kept free of Android dependencies so it runs under plain JVM unit tests.
 * [ProxyService] delegates to this; the endpoint is only served when the
 * request is addressed at THIS proxy (loopback host), never for upstream
 * URLs that merely end in `/ca.pem`.
 */
object CaEndpoint {

    /**
     * True for requests addressed at this proxy asking for the CA.
     *
     * [peerIsLoopback] is mandatory. It used to be the only thing keeping
     * the CA private, back when the listener bound 0.0.0.0 and any host on
     * the LAN could `GET /ca.pem`. The listener is loopback-only now, so
     * there is no remote peer to exclude -- but the check stays, because it
     * is a second independent guard on a secret and costs nothing. Do not
     * drop it on the grounds that the bind address already covers it.
     */
    fun isLocalCaRequest(target: String, peerIsLoopback: Boolean = true): Boolean {
        if (!peerIsLoopback) return false
        val path = pathOf(target) ?: return false
        if (path != "/ca.pem") return false
        if (target.startsWith("/")) return true
        return try {
            val host = java.net.URI(target).host?.lowercase() ?: return false
            host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0" ||
                host == "::1" || host == "[::1]"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True for a request asking us to hand back the endpoint certificate.
     *
     * Same gate as [isLocalCaRequest]: loopback callers only. The endpoint
     * certificate is not a secret in the way the MITM CA is, but publishing it
     * is still only ever something this device's own client should do, and the
     * check costs nothing.
     */
    fun isLocalEndpointCertRequest(target: String, peerIsLoopback: Boolean = true): Boolean {
        if (!peerIsLoopback) return false
        val path = pathOf(target) ?: return false
        if (path != ENDPOINT_CERT_PATH) return false
        if (target.startsWith("/")) return true
        return try {
            val host = java.net.URI(target).host?.lowercase() ?: return false
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
        } catch (_: Exception) {
            false
        }
    }

    /** True when [remoteAddress] is a loopback literal. */
    fun isLoopbackPeer(remoteAddress: String?): Boolean {
        val a = remoteAddress?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        if (a.isEmpty()) return false
        return a == "::1" || a == "localhost" ||
            a.startsWith("127.") || a.substringBefore('%').startsWith("127.")
    }

    /** Path component of an origin-form or absolute-form request target. */
    fun pathOf(target: String): String? {
        return try {
            if (target.startsWith("/")) {
                target.substringBefore("?").ifEmpty { "/" }
            } else {
                java.net.URI(target).path?.substringBefore("?")?.ifEmpty { "/" }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Path the client fetches the pinned endpoint certificate from. */
const val ENDPOINT_CERT_PATH = "/endpoint-cert.pem"
