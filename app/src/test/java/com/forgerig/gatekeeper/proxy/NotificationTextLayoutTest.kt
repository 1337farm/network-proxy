package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification's layout: status first, the single reachable address
 * second.
 *
 * [NotificationTextEdgeTest] and the [NotificationTextTest] block in
 * BackupFormatTest cover the edge cases; this file pins the *layout*
 * contract the product owner asked for:
 *  - the collapsed row is the status line and nothing else,
 *  - the expanded body opens with that identical status line and then names
 *    the one address clients can actually connect to,
 *  - an unknown uptime degrades to the rate alone, with no dangling
 *    separator and no blank line,
 *  - `plain` is untouched.
 *
 * The address used to be a list of every routable IP on the device. It is a
 * single value now: the listener binds loopback only, so `127.0.0.1` is the
 * only address a client can reach, and advertising the others would have
 * pointed users at endpoints that refuse the connection.
 */

/** The one address a loopback-only listener can be reached on. */
private const val ADDR = "127.0.0.1"

class NotificationTextLayoutTest {

    private val HOST_PORT_LINE = Regex("^listening on \\d{1,3}(\\.\\d{1,3}){3}:\\d+$")

    // ---------------------------------------------------------------- row

    @Test
    fun collapsedRowIsTheStatusLineOnly() {
        val b = NotificationText.running(ADDR, 3128, 92.0, "12m 30s")
        assertEquals("up 12m 30s · 92 tok/s", b.collapsed)
        // Status-only: no address, no port, no interface count.
        assertFalse("collapsed must not carry an address: '${b.collapsed}'", b.collapsed.contains("127.0.0.1"))
        assertFalse("collapsed must not carry the port: '${b.collapsed}'", b.collapsed.contains("3128"))
    }

    @Test
    fun collapsedRowIsNeverEmpty() {
        for ((uptime, tps) in CASES) {
            val b = NotificationText.running(ADDR, 3128, tps, uptime)
            assertTrue("collapsed must never be blank", b.collapsed.isNotBlank())
            assertEquals("collapsed must not be padded", b.collapsed, b.collapsed.trim())
        }
    }

    // ----------------------------------------------------------- expanded

    @Test
    fun expandedStartsWithTheStatusLineThenTheAddress() {
        val b = NotificationText.running(ADDR, 3128, 92.0, "12m 30s")
        assertEquals(
            "up 12m 30s · 92 tok/s\n" +
                "listening on 127.0.0.1:3128",
            b.expanded
        )
    }

    @Test
    fun theExpandedBodyNamesTheBindAddressAndNotAWildcard() {
        val b = NotificationText.running(ADDR, 3128, 4.0, "3m")
        val addressLines = b.expanded.lines().drop(1)
        assertEquals(1, addressLines.size)
        // 0.0.0.0 is what the *old* listener bound and what the old
        // notification advertised. It is not a connectable address, so it
        // must never come back.
        assertFalse(
            "notification must not advertise a wildcard address",
            b.expanded.contains("0.0.0.0")
        )
    }

    // ------------------------------------------------------ shared status

    @Test
    fun statusLineIsByteIdenticalInBothStrings() {
        for ((uptime, tps) in CASES) {
            val b = NotificationText.running(ADDR, 3128, tps, uptime)
            val firstLine = b.expanded.substringBefore('\n')
            assertEquals(
                "status line must match for uptime=$uptime tps=$tps",
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
        for ((uptime, tps) in CASES) {
            val b = NotificationText.running(ADDR, 3128, tps, uptime)
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
        val b = NotificationText.running(ADDR, 3128, 92.0, uptime = null)
        assertEquals("92 tok/s", b.collapsed)
        assertEquals("92 tok/s\nlistening on 127.0.0.1:3128", b.expanded)
    }

    @Test
    fun blankUptimeDegradesToTheRateAlone() {
        for (blank in listOf("", " ", "   ", "\t")) {
            val b = NotificationText.running(ADDR, 3128, 1.0, blank)
            assertEquals("blank uptime '$blank' must degrade", "1.0 tok/s", b.collapsed)
            assertEquals("1.0 tok/s\nlistening on 127.0.0.1:3128", b.expanded)
        }
    }

    @Test
    fun knownUptimeKeepsTheUpPrefix() {
        val b = NotificationText.running(ADDR, 3128, 1.0, "12m 30s")
        assertTrue(b.collapsed.startsWith("up 12m 30s · "))
    }

    // ---------------------------------------------------------- two lines

    @Test
    fun theBodyIsAlwaysExactlyTwoLines() {
        for ((uptime, tps) in CASES) {
            val b = NotificationText.running(ADDR, 3128, tps, uptime)
            assertEquals(
                "expanded must be status + one address for uptime=$uptime",
                2,
                b.expanded.lines().size
            )
        }
    }

    @Test
    fun anyPortStillRendersTheAddressLine() {
        // The old "no addresses fell back to a literal" branch is gone, so
        // the port must come from the caller unconditionally.
        for (port in listOf(8080, 3128, 65535)) {
            val b = NotificationText.running(ADDR, port, 3.0, "5s")
            assertTrue(
                "port $port must appear: '${b.expanded}'",
                b.expanded.endsWith("listening on 127.0.0.1:$port")
            )
        }
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
        assertEquals("0.0 tok/s", NotificationText.running(ADDR, 3128, 0.0).collapsed)
        assertEquals("0.4 tok/s", NotificationText.running(ADDR, 3128, 0.44).collapsed)
        assertEquals("9.9 tok/s", NotificationText.running(ADDR, 3128, 9.94).collapsed)
        assertEquals("10 tok/s", NotificationText.running(ADDR, 3128, 10.4).collapsed)
        // Uptime never changes how the rate itself is spelled: 91.8 still
        // rounds to 92 (the >= 10 branch), exactly as it did before.
        assertEquals(
            NotificationText.running(ADDR, 3128, 91.8, "1h 04m").collapsed,
            "up 1h 04m · 92 tok/s"
        )
    }

    @Test
    fun theAddressLineLooksLikeListenHostPort() {
        val b = NotificationText.running(ADDR, 3128, 92.0, "12m 30s")
        val addressLines = b.expanded.lines().drop(1)
        assertEquals(1, addressLines.size)
        for (line in addressLines) {
            assertTrue("not a 'listening on host:port' line: '$line'", HOST_PORT_LINE.matches(line))
        }
    }

    private companion object {
        /** uptime, tps — reused by the invariants that hold for every input. */
        val CASES = listOf(
            "12m 30s" to 92.0,
            null to 0.0,
            "1h 04m" to 12.0,
            "" to 0.04,
            "   " to 0.0,
            null to 987.6,
            "12m 30s" to 987.6
        )
    }
}