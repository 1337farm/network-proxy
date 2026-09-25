package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the emitted setup script (see SetupScript.scriptFor):
 * pure ASCII (no em-dashes/smart-quotes/arrows that proot locales mangle
 * on paste), bash-parseable, and non-fatal when the proxy is down.
 */
class SetupScriptTest {

    private fun rendered(port: Int = 3128): String = SetupScript.scriptFor(port)

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
        val probe = script.lines().first { "proxy probe: listening" in it }
        assertTrue("probe must chain a fallback: $probe", probe.trimEnd().endsWith("|| \\"))
        val fallback = script.lines()[script.lines().indexOf(probe) + 1]
        assertTrue("fallback must echo, not traceback: $fallback", fallback.trimStart().startsWith("echo "))
    }

    @Test
    fun staleCaFallsBackToOpaqueTunnelMessage() {
        val script = rendered()
        assertTrue(script.contains("tunneled opaque (no MITM)"))
        assertEquals(1, script.lines().count { "MITM CA not found" in it })
    }

    @Test
    fun caTrustExportsLiveInAboveGuardBlock() {
        // Regression: the CA trust exports were appended BELOW the PS1
        // guard, so non-interactive shells (opencode serve via
        // opencode-start) never saw them -> "self-signed certificate in
        // certificate chain" on every MITM split. They must be part of
        // the above-guard managed block the bashrc python step writes.
        val script = rendered()
        val bashrcStep = script.substringAfter("python3 - \"3128\" ~/.bashrc")
        assertTrue(
            "SSL_CERT_FILE must be in the above-guard block",
            bashrcStep.contains("export SSL_CERT_FILE=")
        )
        assertTrue(
            "REQUESTS_CA_BUNDLE must be in the above-guard block",
            bashrcStep.contains("export REQUESTS_CA_BUNDLE=")
        )
        assertTrue(
            "NODE_EXTRA_CA_CERTS must be in the above-guard block",
            bashrcStep.contains("export NODE_EXTRA_CA_CERTS=")
        )
        assertTrue(
            "CURL_CA_BUNDLE must be in the above-guard block (curl/openssl clients)",
            bashrcStep.contains("export CURL_CA_BUNDLE=")
        )
        assertTrue(
            "GIT_SSL_CAINFO must be in the above-guard block (git via proxy MITM)",
            bashrcStep.contains("export GIT_SSL_CAINFO=")
        )
        // ...and must NOT be appended below the guard anymore.
        val appendIdx = script.indexOf("cat >> ~/.bashrc")
        assertTrue(
            "no below-guard CA append may remain (opencode-start never sees it)",
            appendIdx == -1 || !script.substring(appendIdx).contains("network-proxy-ca")
        )
    }

    @Test
    fun bashrcStepStripsStaleBelowGuardCaBlock() {
        // Upgrades from the old layout leave a orphan CA block below the
        // guard; the python step must remove it so trust lives in one
        // place (above the guard).
        val script = rendered()
        assertTrue(
            script.contains("network-proxy-ca (managed)")
        )
    }

    @Test
    fun bashrcStepConvergesWithoutRewrite() {
        // The unified block (proxy + CA exports) must short-circuit with
        // SystemExit(0): re-running must print the idempotent-skip line,
        // never "above PS1 guard" again (that message means a rewrite).
        val script = rendered()
        val step = script.substringAfter("python3 - \"3128\" ~/.bashrc")
        assertTrue(step.contains("idempotent skip"))
        assertTrue(step.contains("raise SystemExit(0)"))
    }

    @Test
    fun termuxProotFanoutSection() {
        // Pasted in Termux, the script must detect the environment and
        // push the CA into every Ubuntu proot distro (plus itself).
        val script = rendered()
        assertTrue(script.contains("IS_TERMUX=0"))
        assertTrue(script.contains("uname -o"))
        assertTrue(script.contains("ID=ubuntu"))
        assertTrue(script.contains("installed-rootfs"))
        assertTrue(script.contains("network-proxy-ca.crt"))
        assertTrue(script.contains("update-ca-certificates"))
        // Env vars do not cross proot: the script must say so.
        assertTrue(script.contains("do NOT cross") || script.contains("do not cross"))
    }
}
