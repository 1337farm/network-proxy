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
