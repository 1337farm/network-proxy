package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receiver has to work without a UI, so the port it binds is whatever
 * was persisted last. These cover the cases that would otherwise only show
 * up as a device with no connectivity after a reboot.
 */
class BootReceiverTest {

    @Test
    fun `default port is used when nothing was persisted`() {
        assertEquals(ProxyPrefs.DEFAULT_PORT, ProxyPrefs.resolvePort(ProxyPrefs.DEFAULT_PORT))
    }

    @Test
    fun `a custom port survives`() {
        assertEquals(8080, ProxyPrefs.resolvePort(8080))
    }

    @Test
    fun `range edges are accepted`() {
        assertEquals(1, ProxyPrefs.resolvePort(1))
        assertEquals(65535, ProxyPrefs.resolvePort(65535))
    }

    @Test
    fun `an out of range stored port falls back instead of throwing`() {
        // Binding these would fail and leave the device offline, which is
        // the exact outcome this receiver exists to prevent.
        assertEquals(ProxyPrefs.DEFAULT_PORT, ProxyPrefs.resolvePort(0))
        assertEquals(ProxyPrefs.DEFAULT_PORT, ProxyPrefs.resolvePort(-1))
        assertEquals(ProxyPrefs.DEFAULT_PORT, ProxyPrefs.resolvePort(65536))
        assertEquals(ProxyPrefs.DEFAULT_PORT, ProxyPrefs.resolvePort(Int.MAX_VALUE))
        assertEquals(ProxyPrefs.DEFAULT_PORT, ProxyPrefs.resolvePort(Int.MIN_VALUE))
    }

    @Test
    fun `boot and package replace are handled, nothing else is`() {
        assertTrue(BootReceiver.isHandledAction(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertTrue(BootReceiver.isHandledAction(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(BootReceiver.isHandledAction(null))
        assertFalse(BootReceiver.isHandledAction(""))
        assertFalse(BootReceiver.isHandledAction(android.content.Intent.ACTION_POWER_CONNECTED))
        assertFalse(BootReceiver.isHandledAction(android.content.Intent.ACTION_SCREEN_ON))
        // Deliberately not handled: it fires before unlock, where reading
        // the opt-out preference is not safe.
        assertFalse(BootReceiver.isHandledAction(android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED))
    }
}
