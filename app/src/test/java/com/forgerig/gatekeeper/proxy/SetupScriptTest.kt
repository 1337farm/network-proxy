package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the emitted setup script (see SetupScript.scriptFor):
 * pure ASCII (no em-dashes/smart-quotes/arrows that proot locales mangle
 * on paste), bash-parseable, and non-fatal when the router is down.
 *
 * There is no MITM anymore: the script wires proxy env, fetches the
 * router's pinned endpoint certificate for TLS front-door clients, and
 * touches no trust store anywhere.
 */
class SetupScriptTest {

    private fun rendered(port: Int = PROXY_PORT): String = SetupScript.scriptFor(port)

    @Test
    fun emittedScriptIsPureAscii() {
        val script = rendered()
        val bad = script.filter { it.code > 127 }.toSet()
        assertTrue("non-ASCII chars in setup script: ${bad.map { "'$it' (U+${it.code.toString(16).uppercase()})" }}", bad.isEmpty())
    }

    @Test
    fun emittedScriptHasNoBareLineContinuations() {
        // A line ending in backslash-space or backslash-newline must be a
        // valid continuation: bash -n rejects a lone `\` command word.
        val script = rendered()
        for (line in script.lines()) {
            assert(!line.trimEnd().endsWith("\\\\") || line.endsWith("\\")) {
                "suspect continuation: '$line'"
            }
        }
    }

    @Test
    fun probeFailureWarnsInsteadOfTraceback() {
        val script = rendered()
        // Probe line must tolerate refusal: `|| echo ...` on the SAME
        // logical line (trailing backslash would split it into a lone `\`).
        val probe = script.lines().first { "router probe: listening" in it }
        assertTrue("probe must chain a fallback: $probe", probe.trimEnd().endsWith("|| \\"))
        val fallback = script.lines()[script.lines().indexOf(probe) + 1]
        assertTrue("fallback must echo, not traceback: $fallback", fallback.trimStart().startsWith("echo "))
    }

    @Test
    fun noMitmRemnants() {
        // No CA fetch, no bundles, no trust-store writes, no proot fan-out:
        // the TLS front door pins the endpoint cert instead.
        val script = rendered()
        for (banned in listOf(
            "/ca.pem", "MITM", "bundle.pem",
            "update-ca-certificates", "ca-certificates", "NODE_EXTRA_CA_CERTS",
            "SSL_CERT_FILE", "GIT_SSL_CAINFO", "IS_TERMUX", "installed-rootfs"
        )) {
            assertFalse("MITM remnant still emitted: '$banned'", banned in script)
        }
        assertTrue(script.contains("BEGIN CERTIFICATE"))
    }

    @Test
    fun endpointCertIsFetchedForPinning() {
        val script = rendered()
        assertTrue(script.contains("/endpoint-cert.pem"))
        assertTrue(script.contains(".config/forge-router/endpoint-cert.pem"))
        assertTrue(script.contains("FORGE_ROUTER_CERT="))
    }

    @Test
    fun certExportLivesInAboveGuardBlock() {
        // Non-interactive shells (opencode serve via opencode-start) only
        // see the above-guard managed block, so the cert path export must
        // be part of it.
        val script = rendered()
        val bashrcStep = script.substringAfter("python3 - \"$PROXY_PORT\" ~/.bashrc")
        assertTrue(
            "FORGE_ROUTER_CERT must be in the above-guard block",
            bashrcStep.contains("export FORGE_ROUTER_CERT=")
        )
    }

    @Test
    fun managedMarkersAreRouterScoped() {
        // The stable proxy's script owns the network-proxy markers; ours
        // must not claim them, or the two scripts overwrite each other's
        // terminal wiring.
        val script = rendered()
        assertTrue(script.contains("begin = \"# >>> forge-router (managed) >>>\""))
        assertTrue(
            "must not emit stable-app markers as our own block",
            script.lines().none { it.trim() == "begin = \"# >>> network-proxy (managed) >>>\"" }
        )
    }

    @Test
    fun legacyForgeVariantBlockIsStrippedButStableSurvives() {
        // The short-lived #73 build wrote network-proxy blocks carrying
        // :3129; the new script removes those while leaving a stable
        // :3128 block (the other app's) untouched.
        val script = rendered()
        assertTrue(script.contains("legacy_begin"))
        assertTrue(script.contains("\"3129\""))
        assertTrue(script.contains("belongs to the other app"))
    }

    @Test
    fun bashrcStepConvergesWithoutRewrite() {
        // The unified block (router + cert exports) must short-circuit with
        // SystemExit(0): re-running must print the idempotent-skip line,
        // never "above PS1 guard" again (that message means a rewrite).
        val script = rendered()
        val step = script.substringAfter("python3 - \"$PROXY_PORT\" ~/.bashrc")
        assertTrue(step.contains("idempotent skip"))
        assertTrue(step.contains("raise SystemExit(0)"))
    }

    @Test
    fun cleanupIsScopedToThisRouter() {
        // The cleanup must only remove forge-router blocks and our own
        // port's exports: wiping the stable app's :3128 wiring would take
        // down the other router.
        val script = SetupScript.cleanup()
        assertTrue(script.contains("forge-router"))
        assertFalse(
            "cleanup must not match stable-app markers",
            script.contains("# >>> network-proxy")
        )
        assertTrue(
            "bare-export strip must be port-scoped",
            script.contains("(\"127.0.0.1:\" + port) in s")
        )
    }
}
