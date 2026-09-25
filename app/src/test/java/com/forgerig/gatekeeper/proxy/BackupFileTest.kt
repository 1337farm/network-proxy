package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filenames for Downloads backup exports (pure logic). */
class BackupFileTest {

    @Test
    fun filenameShape() {
        val name = CredentialVault.backupFilename(1_789_000_000_000L)
        assertTrue(
            "unexpected shape: $name",
            Regex("""^nanogatekeeper-backup-\d{8}-\d{4}\.txt$""").matches(name)
        )
    }

    @Test
    fun filenameVariesWithTime() {
        val a = CredentialVault.backupFilename(1_789_000_000_000L)
        val b = CredentialVault.backupFilename(1_789_000_000_000L + 3_600_000L)
        assertTrue(a != b)
        assertEquals(a.length, b.length)
    }
}
