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
class NotificationTextEdgeTest {

    private val RATE = Regex("\\d+(?:\\.\\d+)? tok/s")

    @Test
    fun multipleIpsWithNullUptimeKeepsRateAndHasNoBlankLine() {
        val b = NotificationText.running(
            listOf("192.168.1.5", "10.0.0.2"), 3128, tps = 3.2, uptime = null
        )
        assertEquals("192.168.1.5:3128\n10.0.0.2:3128\n3.2 tok/s", b.expanded)
        assertFalse(b.expanded.contains("\n\n"))
        assertFalse(b.expanded.contains("up "))
        assertTrue(b.expanded.endsWith("3.2 tok/s"))
    }

    @Test
    fun emptyIpsWithNullUptimeHasNoBlankLine() {
        val b = NotificationText.running(emptyList(), 8080, tps = 0.0, uptime = null)
        assertEquals("listening on 0.0.0.0:8080 · 0.0 tok/s", b.collapsed)
        assertEquals("listening on 0.0.0.0:8080 · 0.0 tok/s", b.expanded)
        assertFalse(b.expanded.contains("\n\n"))
    }

    @Test
    fun expandedAlwaysContainsTheRateThatCollapsedShows() {
        val cases = listOf(
            Triple(listOf("192.168.1.5"), null, 0.0),
            Triple(listOf("192.168.1.5", "10.0.0.2"), "1h 04m", 12.0),
            Triple(listOf("100.81.194.26"), "", 0.04),
            Triple(listOf("192.168.1.5", "10.0.0.2", "fd00::1"), "12m 30s", 987.6),
            Triple(emptyList(), null, 7.5)
        )
        for ((ips, uptime, tps) in cases) {
            val b = NotificationText.running(ips, 3128, tps, uptime)
            val rate = requireNotNull(RATE.find(b.collapsed)) {
                "collapsed must carry a rate: '${b.collapsed}'"
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
        val known = NotificationText.running(listOf("192.168.1.5"), 3128, 1.0, "12m 30s")
        assertEquals("192.168.1.5:3128\nup 12m 30s · 1.0 tok/s", known.expanded)

        val blank = NotificationText.running(listOf("192.168.1.5"), 3128, 1.0, "   ")
        assertEquals("192.168.1.5:3128\n1.0 tok/s", blank.expanded)
    }

    @Test
    fun collapsedStillCountsTheExtraInterfaces() {
        val b = NotificationText.running(
            listOf("192.168.1.5", "10.0.0.2", "100.81.194.26"), 3128, 5.0, null
        )
        assertEquals("192.168.1.5:3128 · 5.0 tok/s  +2 more", b.collapsed)
    }

    @Test
    fun rateFormattingIsStable() {
        assertEquals("192.168.1.5:3128 · 0.0 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 0.0).collapsed)
        assertEquals("192.168.1.5:3128 · 0.4 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 0.44).collapsed)
        assertEquals("192.168.1.5:3128 · 9.9 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 9.94).collapsed)
        assertEquals("192.168.1.5:3128 · 10 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 10.4).collapsed)
    }

    @Test
    fun plainIsUnchanged() {
        val b = NotificationText.plain("Proxy stopped")
        assertEquals("Proxy stopped", b.collapsed)
        assertEquals("Proxy stopped", b.expanded)
    }
}
