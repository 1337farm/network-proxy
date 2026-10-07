package com.forgerig.gatekeeper.proxy

/**
 * The single port this router binds.
 *
 * This used to be a text field, which made it possible for the listener
 * and the status line to disagree: typing a port only moved the listener
 * on the next Start. A constant removes that entire class of mismatch,
 * and keeps the value reachable from pure JVM unit tests (no Context, no
 * Activity, no Service).
 *
 * Deliberately a top-level const rather than something on ProxyService:
 * it is read from UI code and JVM tests that must not touch the service.
 *
 * 3129 is the only port this needs: one above the stable proxy's 3128
 * so both apps run side by side on the same device. (The host stack's
 * retry proxy listens on 3130 and forwards to the STABLE app, not here.)
 */
const val PROXY_PORT = 3129

/**
 * The address this proxy binds.
 *
 * Loopback only, deliberately. The proxy carries provider API keys: it
 * terminates the client connection, swaps in the real credential, and
 * forwards. It has no client authentication of its own, so binding
 * anything wider than loopback would put an unauthenticated credential-
 * injecting proxy on the LAN, reachable by every other device on the same
 * network with nothing standing between them and the keys.
 *
 * Same reasoning as [PROXY_PORT] for being a top-level const: the listener,
 * the status line and the bind-failure message all quote it, and the setup
 * script needs it reachable from pure JVM tests.
 *
 * A caller on another host cannot reach this. That is the intended
 * outcome -- there is no supported remote-access path, and adding one is a
 * deliberate change with its own authentication design, not a widening of
 * this constant.
 */
const val BIND_ADDRESS = "127.0.0.1"
