package com.forgerig.gatekeeper.proxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted local vault for provider API keys.
 *
 * - Payloads are AES-256-GCM encrypted with a key held in the Android
 *   Keystore (never leaves hardware when available).
 * - The encrypted blob lives in app-private storage (no backup, no perms).
 * - Export/import wraps the blob in a second password-based AES layer
 *   (PBKDF2-HMAC-SHA256, 210k rounds) so backup files are safe to move
 *   around; the Keystore key never leaves the device either way.
 */
object CredentialVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "nanogatekeeper-vault"
    private const val DIR = "vault"
    private const val FILE = "providers.enc"

    private fun vaultFile(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }.let { File(it, FILE) }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    /** Encrypt [plain] for local storage. Format: v1:base64(iv):base64(ct). */
    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        val ct = c.doFinal(plain)
        return "v1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun unseal(blob: String): ByteArray {
        val parts = blob.split(":")
        require(parts.size == 3 && parts[0] == "v1") { "unknown vault format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE, masterKey(),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP))
        )
        return c.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
    }

    fun save(context: Context, json: String) {
        vaultFile(context).writeText(seal(json.toByteArray(Charsets.UTF_8)))
        ProxyMetrics.event("Vault: provider store sealed (${json.length} chars plaintext)")
    }

    fun load(context: Context): String? {
        val f = vaultFile(context)
        if (!f.exists()) return null
        return try {
            unseal(f.readText()).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            ProxyMetrics.eventError("Vault: unseal failed (${e.message})", e)
            null
        }
    }

    fun exists(context: Context): Boolean = vaultFile(context).exists()

    fun wipe(context: Context) {
        vaultFile(context).delete()
        ProxyMetrics.eventWarning("Vault: wiped")
    }

    // ---- Portable backup: password-wrapped, Keystore-independent ----
    private const val PBKDF2_ROUNDS = 210_000

    /** Timestamped filename for Downloads exports. Pure (unit-tested). */
    fun backupFilename(nowMs: Long = System.currentTimeMillis()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
        return "nanogatekeeper-backup-" + fmt.format(java.util.Date(nowMs)) + ".txt"
    }

    fun exportBackup(context: Context, password: String): String {
        val raw = load(context) ?: throw IllegalStateException("vault empty, nothing to export")
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val key = pbkdf2(password, salt)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(raw.toByteArray(Charsets.UTF_8))
        val out = "npbk1:" + Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
        ProxyMetrics.event("Vault: backup exported (password-wrapped)")
        return out
    }

    fun importBackup(context: Context, backup: String, password: String) {
        val parts = backup.trim().split(":")
        require(parts.size == 4 && parts[0] == "npbk1") { "not a nanoproxy backup" }
        val key = pbkdf2(password, Base64.decode(parts[1], Base64.NO_WRAP))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(128, Base64.decode(parts[2], Base64.NO_WRAP))
        )
        val plain = try {
            c.doFinal(Base64.decode(parts[3], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("wrong password or corrupt backup", e)
        }
        // Validate shape before sealing: must be our provider-store JSON.
        ProviderStore.fromJson(plain)
        save(context, plain)
        ProxyMetrics.event("Vault: backup imported")
    }

    private fun pbkdf2(password: String, salt: ByteArray): SecretKey {
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, PBKDF2_ROUNDS, 256)
        val bytes = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }
}
