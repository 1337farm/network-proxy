package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LocalIps must be total: [LocalIps.primary] is public and its comparator
 * runs inside the notification path, so malformed input has to sort
 * predictably instead of throwing NumberFormatException /
 * IndexOutOfBoundsException on the service's scheduled thread.
 *
 * Scope: the pure helpers only. [LocalIps.list] wraps
 * NetworkInterface.getNetworkInterfaces(), which is not unit-testable (it
 * needs a real device with a real interface set); the filtering and
 * sorting it delegates to is covered here via [LocalIps.fromInterfaces].
 */
class LocalIpsHardeningTest {

    /** Every entry here is NOT a usable IPv4 literal. */
    private val junk = listOf(
        "", " ", "not-an-ip", "10.0.0", "10.0.0.1.2", "1.2.3.4.5.6",
        "10.0.0.x", "x.0.0.1", "10..0.1", "10.0.0.", ".10.0.0.1",
        "fe80::1%wlan0", "::1", "999.1.1.1", "1.2.3.256", "-1.0.0.1",
        "10.0.0.1/24", "1 0.0.0.1", "10.0.0.+1", "10.0.0.1:80", "NaN"
    )

    @Test
    fun primaryNeverThrowsOnJunk() {
        for (j in junk) LocalIps.primary(listOf(j))
        LocalIps.primary(junk)
        LocalIps.primary(junk + listOf("192.168.1.5") + junk)
        LocalIps.primary(junk.shuffled())
    }

    @Test
    fun fromInterfacesNeverThrowsOnJunk() {
        LocalIps.fromInterfaces(junk)
        LocalIps.fromInterfaces(junk + listOf("192.168.1.5"))
    }

    @Test
    fun primaryIgnoresJunkAndPrefersTheLanAddress() {
        assertEquals("192.168.1.5", LocalIps.primary(junk + listOf("192.168.1.5")))
    }

    @Test
    fun primaryIsNullWhenNothingUsableIsPresent() {
        assertNull(LocalIps.primary(emptyList()))
        assertNull(LocalIps.primary(junk))
    }

    @Test
    fun fromInterfacesDropsJunk() {
        assertEquals(emptyList<String>(), LocalIps.fromInterfaces(junk))
    }

    @Test
    fun primaryTrimsBeforeJudgingTheRange() {
        assertEquals("192.168.1.5", LocalIps.primary(listOf("  192.168.1.5  ")))
    }

    @Test
    fun sortingIsNumericAndOrderIndependentWithJunkPresent() {
        val mixed = listOf("192.168.1.5", "bogus", "10.0.0.9", "10.0.0.10", "172.16.0.2", "nope")
        assertEquals(
            listOf("10.0.0.9", "10.0.0.10", "172.16.0.2", "192.168.1.5"),
            LocalIps.fromInterfaces(mixed)
        )
        assertEquals(
            LocalIps.fromInterfaces(mixed),
            LocalIps.fromInterfaces(mixed.shuffled())
        )
    }

    @Test
    fun survivingAddressWinsOverJunkInPrimary() {
        assertEquals("10.0.0.2", LocalIps.primary(listOf("junk", "10.0.0.2", "worse-junk")))
    }

    @Test
    fun outOfRangeDigitsAreDroppedNotParsed() {
        assertEquals(emptyList<String>(), LocalIps.fromInterfaces(listOf("999.1.1.1", "1.2.3.256")))
        assertNull(LocalIps.primary(listOf("999.1.1.1", "1.2.3.256")))
    }

    @Test
    fun loopbackAndLinkLocalStillDropped() {
        assertEquals(
            listOf("10.0.0.2"),
            LocalIps.fromInterfaces(listOf("127.0.0.1", "169.254.10.1", "10.0.0.2"))
        )
    }

    @Test
    fun twoSpellingsOfOneAddressSortDeterministically() {
        // The octets tie, so the comparator's lexical tie-break decides —
        // without it the order would depend on the input order.
        val spelled = LocalIps.fromInterfaces(listOf("10.0.0.1", "010.0.0.1"))
        assertEquals(listOf("010.0.0.1", "10.0.0.1"), spelled)
        assertEquals(spelled, LocalIps.fromInterfaces(spelled.reversed()))
        // Both spellings are 10/8, so primary() picks the canonical one
        // regardless of input order.
        assertEquals("10.0.0.1", LocalIps.primary(listOf("10.0.0.1", "010.0.0.1")))
        assertEquals("10.0.0.1", LocalIps.primary(listOf("010.0.0.1", "10.0.0.1")))
    }

    @Test
    fun dedupeUnchanged() {
        assertEquals(listOf("10.0.0.5"), LocalIps.fromInterfaces(listOf("10.0.0.5", "10.0.0.5")))
    }

    @Test
    fun nonPrivateAddressIsStillTheLastResort() {
        assertEquals("100.81.1.1", LocalIps.primary(listOf("100.81.1.1")))
        assertEquals("100.81.1.1", LocalIps.primary(listOf("100.81.1.1", "junk")))
    }
}
