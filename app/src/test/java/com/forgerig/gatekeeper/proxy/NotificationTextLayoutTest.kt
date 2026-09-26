package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification's new layout: status first, addresses second.
 *
 * [NotificationTextEdgeTest] and the [NotificationTextTest] block in
 * BackupFormatTest cover the edge cases; this file pins the *layout*
 * contract the product owner asked for:
 *  - the collapsed row is the status line and nothing else,
 *  - the expanded body opens with that identical status line and then lists
 *    one address per line, each carrying the port,
 *  - an unknown uptime degrades to the rate alone, with no dangling
 *    separator and no blank line,
 *  - `plain` is untouched.
 */
class NotificationTextLayoutTest {

    private val IPV4_LINE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d+$")

    // ---------------------------------------------------------------- row

    @Test
    fun collapsedRowIsTheStatusLineOnly() {
        val b = NotificationText.running(
            listOf("192.168.68.126", "100.81.194.26"), 3128, 92.0, "12m 30s"
        )
        assertEquals("up 12m 30s · 92 tok/s", b.collapsed)
        // Status-only: no address, no port, no interface count.
        assertFalse("collapsed must not carry an address: '${b.collapsed}'", b.collapsed.contains("192.168"))
        assertFalse("collapsed must not carry the port: '${b.collapsed}'", b.collapsed.contains("3128"))
    }

    @Test
    fun collapsedRowIsNeverEmpty() {
        for ((ips, uptime, tps) in CASES) {
            val b = NotificationText.running(ips, 3128, tps, uptime)
            assertTrue("collapsed must never be blank", b.collapsed.isNotBlank())
            assertEquals("collapsed must not be padded", b.collapsed, b.collapsed.trim())
        }
    }

    // ----------------------------------------------------------- expanded

    @Test
    fun expandedStartsWithTheStatusLineThenOneAddressPerLine() {
        val b = NotificationText.running(
            listOf("192.168.68.126", "100.81.194.26"), 3128, 92.0, "12m 30s"
        )
        assertEquals(
            "up 12m 30s · 92 tok/s\n" +
                "192.168.68.126:3128\n" +
                "100.81.194.26:3128",
            b.expanded
        )
    }

    @Test
    fun portIsOnEveryAddressLine() {
        val ips = listOf("192.168.68.126", "100.81.194.26", "fd00::1")
        val b = NotificationText.running(ips, 3128, 4.0, "3m")
        val addressLines = b.expanded.lines().drop(1)
        assertEquals(ips.size, addressLines.size)
        for (line in addressLines) {
            assertTrue("address line must end with the port: '$line'", line.endsWith(":3128"))
        }
    }

    // ------------------------------------------------------ shared status

    @Test
    fun statusLineIsByteIdenticalInBothStrings() {
        for ((ips, uptime, tps) in CASES) {
            val b = NotificationText.running(ips, 3128, tps, uptime)
            val firstLine = b.expanded.substringBefore('\n')
            assertEquals(
                "status line must match for ips=$ips uptime=$uptime tps=$tps",
                b.collapsed,
                firstLine
            )
            // And byte-for-byte, not merely equal after normalisation.
            assertTrue(
                "collapsed must be a prefix of expanded for uptime=$uptime",
                b.expanded.startsWith(b.collapsed)
            )
        }
    }

    @Test
    fun statusLineIsBuiltOnceAndNeverLeaksASeparator() {
        for ((ips, uptime, tps) in CASES) {
            val b = NotificationText.running(ips, 3128, tps, uptime)
            val s = b.collapsed
            assertFalse("dangling separator in '$s'", s.endsWith("·"))
            assertFalse("leading separator in '$s'", s.startsWith("·"))
            assertFalse("doubled separator in '$s'", s.contains("·  "))
            assertFalse("blank line in '${b.expanded}'", b.expanded.contains("\n\n"))
        }
    }

    // ------------------------------------------------------- null uptime

    @Test
    fun nullUptimeDegradesToTheRateAlone() {
        val b = NotificationText.running(
            listOf("192.168.68.126", "100.81.194.26"), 3128, 92.0, uptime = null
        )
        assertEquals("92 tok/s", b.collapsed)
        assertEquals("92 tok/s\n192.168.68.126:3128\n100.81.194.26:3128", b.expanded)
    }

    @Test
    fun blankUptimeDegradesToTheRateAlone() {
        for (blank in listOf("", " ", "   ", "\t")) {
            val b = NotificationText.running(listOf("192.168.1.5"), 3128, 1.0, blank)
            assertEquals("blank uptime '$blank' must degrade", "1.0 tok/s", b.collapsed)
            assertEquals("1.0 tok/s\n192.168.1.5:3128", b.expanded)
        }
    }

    @Test
    fun knownUptimeKeepsTheUpPrefix() {
        val b = NotificationText.running(listOf("192.168.1.5"), 3128, 1.0, "12m 30s")
        assertTrue(b.collapsed.startsWith("up 12m 30s · "))
    }

    // --------------------------------------------------------- empty ips

    @Test
    fun emptyIpsKeepTheStatusLineAndAddNoBlankLine() {
        val b = NotificationText.running(emptyList(), 8080, 3.0, "5s")
        assertEquals("up 5s · 3.0 tok/s", b.collapsed)
        assertEquals("up 5s · 3.0 tok/s\nlistening on 0.0.0.0:8080", b.expanded)
        assertFalse(b.expanded.contains("\n\n"))
        assertEquals(2, b.expanded.lines().size)
    }

    @Test
    fun emptyIpsWithoutUptimeStillShowTheRate() {
        val b = NotificationText.running(emptyList(), 8080, 0.0, null)
        assertEquals("0.0 tok/s", b.collapsed)
        assertEquals("0.0 tok/s\nlistening on 0.0.0.0:8080", b.expanded)
        assertFalse("must not dangle a separator", b.collapsed.contains("·"))
    }

    // ---------------------------------------------------------- +N more

    @Test
    fun theInterfaceCountIsGoneFromBothStrings() {
        val b = NotificationText.running(
            listOf("192.168.1.5", "10.0.0.2", "100.81.194.26"), 3128, 5.0, "1h"
        )
        for (s in listOf(b.collapsed, b.expanded)) {
            assertFalse("no interface count in '$s'", s.contains("more"))
        }
        // The addresses it used to stand in for are all spelled out instead.
        assertEquals(4, b.expanded.lines().size)
    }

    // ------------------------------------------------------------ plain

    @Test
    fun plainStaysMessageOnly() {
        for (message in listOf("Proxy stopped", "Failed to bind port 3128")) {
            val b = NotificationText.plain(message)
            assertEquals(message, b.collapsed)
            assertEquals(message, b.expanded)
            assertFalse(b.collapsed.contains("tok/s"))
        }
    }

    // ------------------------------------------------- rate passthrough

    @Test
    fun rateFormattingIsUnchangedAndStillDrivesTheStatusLine() {
        assertEquals("0.0 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 0.0).collapsed)
        assertEquals("0.4 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 0.44).collapsed)
        assertEquals("9.9 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 9.94).collapsed)
        assertEquals("10 tok/s", NotificationText.running(listOf("192.168.1.5"), 3128, 10.4).collapsed)
        // Uptime never changes how the rate itself is spelled: 91.8 still
        // rounds to 92 (the >= 10 branch), exactly as it did before.
        assertEquals(
            NotificationText.running(listOf("192.168.1.5"), 3128, 91.8, "1h 04m").collapsed,
            "up 1h 04m · 92 tok/s"
        )
    }

    @Test
    fun everyAddressLineLooksLikeHostPort() {
        val b = NotificationText.running(
            listOf("192.168.68.126", "100.81.194.26"), 3128, 92.0, "12m 30s"
        )
        val addressLines = b.expanded.lines().drop(1)
        assertEquals(2, addressLines.size)
        for (line in addressLines) {
            assertTrue("not a host:port line: '$line'", IPV4_LINE.matches(line))
        }
    }

    private companion object {
        /** ips, uptime, tps — reused by the invariants that hold for every input. */
        val CASES = listOf(
            Triple(listOf("192.168.68.126", "100.81.194.26"), "12m 30s", 92.0),
            Triple(listOf("192.168.1.5"), null, 0.0),
            Triple(listOf("192.168.1.5", "10.0.0.2"), "1h 04m", 12.0),
            Triple(listOf("100.81.194.26"), "", 0.04),
            Triple(listOf("192.168.1.5", "10.0.0.2", "fd00::1"), "12m 30s", 987.6),
            Triple(emptyList(), null, 7.5),
            Triple(emptyList(), "   ", 0.0)
        )
    }
}
