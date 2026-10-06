package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases of the notification body text. The rule under test: the
 * expanded text always carries the rate that the collapsed row shows, and
 * it never grows a blank line when the uptime is unknown.
 */

/** The one address a loopback-only listener can be reached on. */
private const val ADDR = "127.0.0.1"
class NotificationTextEdgeTest {

    private val RATE = Regex("\\d+(?:\\.\\d+)? tok/s")

    @Test
    fun nullUptimeKeepsTheRateAndHasNoBlankLine() {
        val b = NotificationText.running(ADDR, 3128, 3.2, null)
        assertEquals("3.2 tok/s", b.collapsed)
        assertEquals("3.2 tok/s\nlistening on 127.0.0.1:3128", b.expanded)
    }

    @Test
    fun expandedAlwaysContainsTheRateThatCollapsedShows() {
        val cases = listOf(
            null to 0.0,
            "1h 04m" to 12.0,
            "" to 0.04,
            "12m 30s" to 987.6,
            null to 7.5
        )
        for ((uptime, tps) in cases) {
            val b = NotificationText.running(ADDR, 3128, tps, uptime)
            val rate = requireNotNull(RATE.find(b.collapsed)) {
                "collapsed must carry a rate: ${b.collapsed}"
            }
            assertTrue(
                "expanded '${b.expanded}' must contain rate '${rate.value}'",
                b.expanded.contains(rate.value)
            )
            assertFalse("expanded must not have a blank line: '${b.expanded}'", b.expanded.contains("\n\n"))
        }
    }

    @Test
    fun uptimeLineIsOmittedOnlyWhenUnknown() {
        val withUptime = NotificationText.running(ADDR, 3128, 5.0, "1h 04m")
        assertEquals("up 1h 04m · 5.0 tok/s", withUptime.collapsed)
        // Blank/whitespace is treated as unknown, not rendered as "up  · ...".
        for (blank in listOf(null, "", " ", "   ", "	")) {
            val b = NotificationText.running(ADDR, 3128, 5.0, blank)
            assertEquals("5.0 tok/s", b.collapsed)
            assertFalse(b.collapsed.contains("up "))
            assertFalse(b.expanded.contains("\n\n"))
        }
    }

    @Test
    fun collapsedIsStatusOnlySoNoInterfaceCountRemains() {
        val b = NotificationText.running(ADDR, 3128, 92.0, "12m")
        // The row is status-only by contract; an interface count would be a
        // dangling summary with no list attached in the collapsed view.
        assertFalse(b.collapsed.contains("more"))
        assertEquals("up 12m · 92 tok/s", b.collapsed)
        // The one reachable address is spelled out in the expanded body.
        assertTrue(b.expanded.contains("127.0.0.1:3128"))
    }

    @Test
    fun rateFormattingIsStable() {
        assertTrue(NotificationText.running(ADDR, 3128, 91.8).collapsed.endsWith("92 tok/s"))
        assertTrue(NotificationText.running(ADDR, 3128, 0.0).collapsed.endsWith("0.0 tok/s"))
        assertTrue(NotificationText.running(ADDR, 3128, 4.4).collapsed.endsWith("4.4 tok/s"))
    }

    @Test
    fun plainIsUnchanged() {
        val b = NotificationText.plain("Proxy stopped")
        assertEquals("Proxy stopped", b.collapsed)
        assertEquals("Proxy stopped", b.expanded)
    }
}
