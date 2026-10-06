package com.forgerig.gatekeeper.proxy

/**
 * The single port this proxy binds.
 *
 * This used to be a text field, which made it possible for the listener,
 * the generated setup script, and the status line to disagree: typing a
 * port updated the script immediately but only moved the listener on the
 * next Start. A constant removes that entire class of mismatch, and keeps
 * the value reachable from pure JVM unit tests (no Context, no Activity,
 * no Service) so the setup script can still be tested.
 *
 * Deliberately a top-level const rather than something on ProxyService:
 * SetupScript is pure and is exercised by JVM tests that must not touch
 * Android classes.
 *
 * 3128 is the only port this needs. The host stack's retry proxy listens
 * on 3130 and forwards here precisely so the app can own 3128 outright.
 */
const val PROXY_PORT = 3128