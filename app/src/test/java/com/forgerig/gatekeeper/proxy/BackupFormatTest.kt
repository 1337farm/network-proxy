package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The backup blob format is the one thing that can silently brick a user's
 * key vault, so it gets exercised without a device/Keystore.
 */
class BackupFormatTest {
    private val vault = """{"version":1,"entries":[{"providerId":"openrouter","label":"k","apiKey":"sk-x"}]}"""

    @Test
    fun roundTripKeepsVaultJson() {
        val blob = CredentialVault.encryptBackup(vault, "hunter2")
        assertTrue(blob.startsWith("npbk1:"))
        assertEquals(4, blob.split(":").size)
        assertEquals(vault, CredentialVault.decryptBackup(blob, "hunter2"))
    }

    @Test
    fun blobToleratesBomWhitespaceAndCrlf() {
        val blob = "\uFEFF\r\n" + CredentialVault.encryptBackup(vault, "pw") + "\r\n"
        assertEquals(vault, CredentialVault.decryptBackup(blob, "pw"))
    }

    @Test
    fun wrongPasswordFailsWithClearMessage() {
        val blob = CredentialVault.encryptBackup(vault, "right")
        try {
            CredentialVault.decryptBackup(blob, "wrong")
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("wrong password"))
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val parts = CredentialVault.encryptBackup(vault, "pw").split(":").toMutableList()
        val ct = parts[3].toCharArray()
        ct[ct.size - 2] = if (ct[ct.size - 2] == 'A') 'B' else 'A'
        parts[3] = String(ct)
        try {
            CredentialVault.decryptBackup(parts.joinToString(":"), "pw")
            fail("expected GCM auth failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("wrong password"))
        }
    }

    @Test
    fun nonBackupTextIsRejected() {
        try {
            CredentialVault.decryptBackup("just some notes", "pw")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not a network-proxy backup"))
        }
    }

    @Test
    fun corruptSaltReportsTheFriendlyMessage() {
        // A damaged salt used to leak the raw JDK error
        // ("Illegal base64 character ...") straight into the toast.
        val parts = CredentialVault.encryptBackup(vault, "pw").split(":").toMutableList()
        parts[1] = "!!!not-base64!!!"
        try {
            CredentialVault.decryptBackup(parts.joinToString(":"), "pw")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("wrong password"))
        }
    }

    @Test
    fun truncatedBlobReportsTheFriendlyMessage() {
        val blob = CredentialVault.encryptBackup(vault, "pw")
        try {
            CredentialVault.decryptBackup(blob.substring(0, blob.length / 2), "pw")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                e.message!!.contains("wrong password") || e.message!!.contains("not a network-proxy backup")
            )
        }
    }

    @Test
    fun backupNameFilterMatchesOnlyOurFiles() {
        assertTrue(CredentialVault.isBackupName("nanogatekeeper-backup-20260925-1052.txt"))
        assertTrue(!CredentialVault.isBackupName("notes.txt"))
        assertTrue(!CredentialVault.isBackupName("nanogatekeeper-backup-20260925-1052.bin"))
    }

    @Test
    fun exportFilenamesArePrefixed() {
        assertTrue(CredentialVault.backupFilename(0L).startsWith(CredentialVault.BACKUP_PREFIX))
        assertTrue(CredentialVault.backupFilename(0L).endsWith(".txt"))
    }
}

/**
 * Regression cover for the MITM conversation title: the tap holds a raw
 * HTTP request (request line + headers + body), and feeding that to
 * JSONObject made every title silently blank.
 */
class SessionTitleTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun titleSurvivesHttpHeaders() {
        val raw = bytes(
            "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: openrouter.ai\r\n" +
                "Authorization: Bearer sk-test\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                """{"model":"m","messages":[{"role":"user","content":"Please refactor the router"}]}"""
        )
        assertEquals("Please refactor the router", SessionTracker.titleOf(raw))
    }

    @Test
    fun titleFromBareBodyStillWorks() {
        val raw = bytes("""{"messages":[{"role":"system","content":"sys"},{"role":"user","content":"hello there"}]}""")
        assertEquals("hello there", SessionTracker.titleOf(raw))
    }

    @Test
    fun titleHandlesAnthropicContentBlocks() {
        val raw = bytes(
            "POST /v1/messages HTTP/1.1\r\nHost: opencode.ai\r\n\r\n" +
                """{"messages":[{"role":"user","content":[{"type":"text","text":"fix the flaky test"}]}]}"""
        )
        assertEquals("fix the flaky test", SessionTracker.titleOf(raw))
    }

    @Test
    fun truncatedBodyYieldsNoTitleInsteadOfThrowing() {
        val raw = bytes(
            "POST /v1/messages HTTP/1.1\r\nHost: x\r\n\r\n" +
                """{"messages":[{"role":"user","content":"a very long prompt that got cut off mid-"""
        )
        assertEquals("", SessionTracker.titleOf(raw))
    }

    @Test
    fun headerlessTunnelDataYieldsNoTitle() {
        assertEquals("", SessionTracker.titleOf(bytes("not json at all")))
        assertEquals("", SessionTracker.titleOf(null))
    }

    @Test
    fun jsonBodyStripsHeadAndIsNoOpForBareBody() {
        val bare = bytes("""{"a":1}""")
        assertEquals("""{"a":1}""", String(SessionTracker.jsonBody(bare), Charsets.UTF_8))
        val withHead = bytes("GET /x HTTP/1.1\r\nH: v\r\n\r\n{\"a\":1}")
        assertEquals("""{"a":1}""", String(SessionTracker.jsonBody(withHead), Charsets.UTF_8))
        // No header terminator and not a body -> nothing usable.
        assertEquals(0, SessionTracker.jsonBody(bytes("GET /x HTTP/1.1\r\nH: v")).size)
    }
}

/** Pan direction was inverted: dragging right must go back in time. */
class RateChartPanTest {
    private val pps = 2f // 2 px per second of history

    @Test
    fun draggingRightGoesBackInTime() {
        assertEquals(10L, TokenRateView.panOffset(0L, 20f, pps))
    }

    @Test
    fun draggingLeftReturnsTowardLive() {
        assertEquals(0L, TokenRateView.panOffset(30L, -60f, pps))
    }

    @Test
    fun clampsAtTheLiveEdge() {
        assertEquals(0L, TokenRateView.panOffset(0L, -500f, pps))
    }

    @Test
    fun clampsAtTheRetentionLimit() {
        val maxBack = (TokenRateView.MAX_BACK_SECS - TokenRateView.WINDOW_SECS).toLong()
        assertEquals(maxBack, TokenRateView.panOffset(0L, 1_000_000f, pps))
    }

    @Test
    fun zeroPixelScaleFallsBackToOnePixelPerSecond() {
        // Floor instead of /0: a 10px drag is then 10s of history.
        assertEquals(10L, TokenRateView.panOffset(0L, 10f, 0f))
        assertEquals(10L, TokenRateView.panOffset(0L, 10f, -5f))
    }
}

class NotificationTextTest {
    @Test
    fun collapsedRowCarriesTheRate() {
        val b = NotificationText.running(listOf("100.81.194.26", "192.168.68.126"), 3128, 91.8, "1h 04m")
        assertEquals("192.168.68.126:3128 · 92 tok/s  +1 more", b.collapsed)
        // Expanded: every address, then uptime + rate.
        assertTrue(b.expanded.contains("100.81.194.26:3128"))
        assertTrue(b.expanded.contains("192.168.68.126:3128"))
        assertTrue(b.expanded.contains("up 1h 04m"))
        assertTrue(b.expanded.contains("92 tok/s"))
    }

    @Test
    fun idleRateStillRenders() {
        val b = NotificationText.running(listOf("192.168.1.5"), 3128, 0.0, "12s")
        assertEquals("192.168.1.5:3128 · 0.0 tok/s", b.collapsed)
    }

    @Test
    fun noUptimeOmitsTheLine() {
        val b = NotificationText.running(listOf("192.168.1.5"), 3128, 5.0, null)
        assertEquals("192.168.1.5:3128 · 5.0 tok/s", b.collapsed)
        assertFalse(b.expanded.contains("up "))
    }

    @Test
    fun noAddressesFallsBackToTheBindAddress() {
        val b = NotificationText.running(emptyList(), 8080, 3.0, "5s")
        assertEquals("listening on 0.0.0.0:8080 · 3.0 tok/s", b.collapsed)
    }
}

class LocalIpsPrimaryTest {
    @Test
    fun prefersTheLanAddress() {
        assertEquals(
            "192.168.68.126",
            LocalIps.primary(listOf("100.81.194.26", "172.30.197.213", "192.168.68.126"))
        )
    }

    @Test
    fun fallsBackThroughPrivateRanges() {
        assertEquals("10.0.0.5", LocalIps.primary(listOf("100.81.1.1", "10.0.0.5")))
        assertEquals("172.16.0.2", LocalIps.primary(listOf("100.81.1.1", "172.16.0.2")))
    }

    @Test
    fun publicAddressIsUsedWhenThereIsNoPrivateOne() {
        assertEquals("100.81.1.1", LocalIps.primary(listOf("100.81.1.1")))
    }

    @Test
    fun emptyListHasNoPrimary() {
        assertEquals(null, LocalIps.primary(emptyList()))
    }
}

class LocalIpsTest {
    @Test
    fun dropsLoopbackAndLinkLocalSortsNumerically() {
        val out = LocalIps.fromInterfaces(
            listOf("127.0.0.1", "10.0.0.9", "192.168.1.5", "169.254.7.7", "10.0.0.2", "fe80::1%wlan0", "not-an-ip")
        )
        assertEquals(listOf("10.0.0.2", "10.0.0.9", "192.168.1.5"), out)
    }

    @Test
    fun rejectsOutOfRangeOctets() {
        assertEquals(emptyList<String>(), LocalIps.fromInterfaces(listOf("999.1.1.1", "1.2.3.256")))
    }

    @Test
    fun sortsOnAllFourOctets() {
        val out = LocalIps.fromInterfaces(listOf("10.0.0.10", "10.0.0.9", "9.255.255.255", "10.0.1.2"))
        assertEquals(listOf("9.255.255.255", "10.0.0.9", "10.0.0.10", "10.0.1.2"), out)
    }

    @Test
    fun dedupes() {
        assertEquals(listOf("10.0.0.5"), LocalIps.fromInterfaces(listOf("10.0.0.5", "10.0.0.5")))
    }
}
