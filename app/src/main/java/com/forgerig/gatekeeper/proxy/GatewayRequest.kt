package com.forgerig.gatekeeper.proxy

/**
 * Recognises requests that are addressed to the proxy itself, rather than
 * being forwarded through it.
 *
 * This is the difference between "you are the provider I call" and "you
 * intercept what I call". A client configured with
 * `baseURL = http://127.0.0.1:3128/v1` sends an origin-form target
 * (`/v1/chat/completions`) with `Host: 127.0.0.1:3128`. Nobody is asking
 * the proxy to reach anywhere on their behalf; the destination is the
 * proxy's own front door, and the upstream is decided by the route table.
 *
 * A forward-proxy request looks different: absolute-form target
 * (`POST http://api.anthropic.com/v1/messages HTTP/1.1`), where the target
 * names a remote host. That one is genuinely being tunnelled.
 *
 * Pure and Android-free so it can be covered by JVM unit tests.
 */
internal object GatewayRequest {

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1")

    /**
     * True when [requestTarget] is origin-form *and* the Host header points
     * at this proxy. Both halves matter:
     *
     * - absolute-form means a forward-proxy request, never the front door;
     * - an origin-form target with no Host, or a Host that is not loopback,
     *   is an HTTP/1.0-style request we should not treat as gateway traffic,
     *   because we cannot tell it apart from a tunnelled relative target.
     *
     * [port] is accepted so a Host like `127.0.0.1:3128` parses, and so a
     * non-loopback host on our own port is still rejected.
     */
    fun isAddressedToUs(requestTarget: String, hostHeader: String?, port: Int): Boolean {
        if (requestTarget.startsWith("http://", true) ||
            requestTarget.startsWith("https://", true)
        ) return false

        val host = hostHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return isLoopbackAuthority(host, port)
    }

    /** True when [authority] is loopback, with or without a matching port. */
    fun isLoopbackAuthority(authority: String, port: Int): Boolean {
        val hostPart: String
        val portPart: String?
        if (authority.startsWith("[")) {
            // IPv6 literal: [::1] or [::1]:3128
            val close = authority.indexOf(']')
            if (close < 0) return false
            hostPart = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            portPart = if (rest.startsWith(":")) rest.substring(1) else null
        } else {
            val colon = authority.lastIndexOf(':')
            // No colon, or more than one (bare IPv6) -> host only.
            hostPart = if (colon >= 0 && authority.indexOf(':') == colon) {
                authority.substring(0, colon)
            } else {
                authority
            }
            portPart = if (hostPart !== authority) authority.substring(colon + 1) else null
        }

        if (hostPart.lowercase() !in LOOPBACK_HOSTS) return false
        // A loopback host is us regardless of port; when a port is present it
        // must be ours, otherwise it is some other local service.
        return portPart == null || portPart == port.toString()
    }

    /**
     * The path an origin-form request is asking for, with any query
     * preserved. Used as the upstream path when a route retargets.
     */
    fun pathOf(requestTarget: String): String =
        requestTarget.ifEmpty { "/" }
}