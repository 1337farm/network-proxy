package com.forgerig.gatekeeper.proxy

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
    fun dedupes() {
        assertEquals(listOf("10.0.0.5"), LocalIps.fromInterfaces(listOf("10.0.0.5", "10.0.0.5")))
    }
}
