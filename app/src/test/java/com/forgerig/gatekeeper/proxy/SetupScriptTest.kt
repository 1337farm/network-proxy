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
}
