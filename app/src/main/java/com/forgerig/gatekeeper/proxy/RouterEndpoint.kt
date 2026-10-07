package com.forgerig.gatekeeper.proxy

/**
 * Pure routing logic for the router-local endpoints (currently
 * `GET /endpoint-cert.pem`, which serves the pinned TLS certificate).
 *
 * Kept free of Android dependencies so it runs under plain JVM unit tests.
 * [ProxyService] delegates to this; an endpoint is only served when the
 * request is addressed at THIS router (loopback host), never for upstream
 * URLs that merely share the path.
 */
object RouterEndpoint {

    /**
     * True for a request asking us to hand back the endpoint certificate.
     *
     * [peerIsLoopback] is mandatory: the listener is loopback-only, so
     * there is no remote peer to exclude -- but the check stays as a second
     * independent guard, because it costs nothing. Do not drop it on the
     * grounds that the bind address already covers it.
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
