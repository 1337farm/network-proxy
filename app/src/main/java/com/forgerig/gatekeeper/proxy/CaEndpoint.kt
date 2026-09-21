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

    /** True for requests addressed at this proxy asking for the CA. */
    fun isLocalCaRequest(target: String): Boolean {
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
