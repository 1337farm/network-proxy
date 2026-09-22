package com.forgerig.gatekeeper.proxy

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Local CA for the opt-in HTTPS split (MITM).
 *
 * Your traffic, your device: the proxy can only read TLS bodies after YOU
 * install this CA into the client's trust store (the setup script does it
 * for proot Ubuntu + Termux). Without that install, clients reject the
 * spoofed leafs and fall back to opaque tunneling — nothing breaks.
 *
 * Thread-safe, cached per host. RSA-2048, 10y CA / 825d leafs.
 */
object MitmCa {
    private const val DIR = "mitm"
    private const val CA_CERT = "ca-cert.pem"
    private const val CA_KEY = "ca-key.pk8"
    private const val STORE_PASS = "networkproxy-mitm"

    @Volatile private var caKey: PrivateKey? = null
    @Volatile private var caCert: X509Certificate? = null
    private val leafContexts = ConcurrentHashMap<String, SSLContext>()

    fun caDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
    fun caCertFile(context: Context): File = File(caDir(context), CA_CERT)

    /** Load or (first run) generate the CA. Returns false on failure. */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        if (caKey != null && caCert != null) return true
        return try {
            val dir = caDir(context)
            val certF = File(dir, CA_CERT)
            val keyF = File(dir, CA_KEY)
            if (certF.exists() && keyF.exists()) {
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                caCert = certF.inputStream().use { cf.generateCertificate(it) as X509Certificate }
                val keyBytes = keyF.readBytes()
                val spec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
                caKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(spec)
                // Self-check: a CA whose subject DN can't round-trip as a
                // leaf issuer (e.g. pre-RFC4514-ordering CAs) fails closed
                // here instead of issuing unverifiable leafs at runtime.
                if (!selfIssues(context, "mitm-selfcheck.invalid")) {
                    ProxyMetrics.eventWarning("MITM CA subject order stale - regenerating CA")
                    leafContexts.clear()
                    issueFreshCa(dir, certF, keyF)
                }
            } else {
                issueFreshCa(dir, certF, keyF)
            }
            ProxyMetrics.event("MITM CA ready (${caCertFile(context).name})")
            true
        } catch (e: Exception) {
            ProxyMetrics.eventError("MITM CA init failed: ${e.message}", e)
            false
        }
    }

    /**
     * Canonical CA subject, RFC 4514 order (most-significant first):
     * C, O, CN. BouncyCastle's X500Name(string) keeps declaration order,
     * so the literal order here IS the DER order. The leaf issuer MUST be
     * byte-identical to this or clients fail with "unable to get local
     * issuer certificate" (see serverContext - it reuses this constant).
     */
    private const val CA_SUBJECT = "C=US,O=1337farm,CN=NetworkProxy Local CA"

    private fun issueFreshCa(dir: File, certF: File, keyF: File) {
        val kp = genRsa()
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            X500Name(CA_SUBJECT),
            BigInteger(64, SecureRandom()),
            Date(now - 60_000), Date(now + 10L * 365 * 86400_000),
            X500Name(CA_SUBJECT),
            kp.public
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        caCert = JcaX509CertificateConverter().getCertificate(holder)
        caKey = kp.private
        certF.writeBytes(pemEncode("CERTIFICATE", holder.encoded).toByteArray())
        keyF.writeBytes(kp.private.encoded)
    }

    /**
     * Can this CA issue a leaf for [host] whose issuer verifies against
     * the CA cert? Issues a throwaway leaf and checks signature + DN
     * equality. Runs at load so a stale on-device CA regenerates once
     * instead of failing every CONNECT at runtime.
     */
    private fun selfIssues(context: Context, host: String): Boolean {
        return try {
            val key = caKey ?: return false
            val ca = caCert ?: return false
            val kp = genRsa()
            val now = System.currentTimeMillis()
            val holder: X509CertificateHolder = JcaX509v3CertificateBuilder(
                X500Name.getInstance(org.bouncycastle.asn1.ASN1Sequence.getInstance(ca.subjectX500Principal.encoded)),
                BigInteger(64, SecureRandom()),
                Date(now - 60_000), Date(now + 60_000),
                X500Name("CN=$host"),
                kp.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(key))
            val leaf = JcaX509CertificateConverter().getCertificate(holder)
            leaf.verify(ca.publicKey)
            leaf.issuerX500Principal == ca.subjectX500Principal
        } catch (_: Exception) {
            false
        }
    }

    /** Exportable PEM for the setup script / share sheet. */
    fun caPem(context: Context): String? {
        if (!ensureLoaded(context)) return null
        return try { caCertFile(context).readText() } catch (_: Exception) { null }
    }

    /**
     * TLS server context presenting a leaf for [host] signed by the local CA.
     * Cached per host. Null when the CA isn't loaded.
     */
    fun serverContext(context: Context, host: String): SSLContext? {
        if (!ensureLoaded(context)) return null
        leafContexts[host]?.let { return it }
        return try {
            val key = caKey ?: return null
            val ca = caCert ?: return null
            val kp = genRsa()
            val now = System.currentTimeMillis()
            // Issuer MUST be byte-identical to the CA subject: reuse the
            // CA cert's own encoded subject (X500Principal.getName gives
            // RFC 1779 order, which BouncyCastle would re-encode in a
            // different RDN order - unverifiable leafs, "unable to get
            // local issuer certificate" on every client).
            val holder: X509CertificateHolder = JcaX509v3CertificateBuilder(
                X500Name.getInstance(org.bouncycastle.asn1.ASN1Sequence.getInstance(ca.subjectX500Principal.encoded)),
                BigInteger(64, SecureRandom()),
                Date(now - 60_000), Date(now + 825L * 86400_000),
                X500Name("CN=$host"),
                kp.public
            ).addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(
                    Extension.subjectAlternativeName, false,
                    GeneralNames(GeneralName(GeneralName.dNSName, host))
                )
                .build(JcaContentSignerBuilder("SHA256withRSA").build(key))
            val leaf = JcaX509CertificateConverter().getCertificate(holder)
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("leaf", kp.private, STORE_PASS.toCharArray(), arrayOf<Certificate>(leaf, ca))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, STORE_PASS.toCharArray())
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(kmf.keyManagers, arrayOf(trustAll()), SecureRandom())
            leafContexts[host] = ctx
            ctx
        } catch (e: Exception) {
            ProxyMetrics.eventError("MITM leaf issue failed for $host: ${e.message}", e)
            null
        }
    }

    /** Upstream side keeps REAL verification — we only spoof downstream. */
    fun upstreamContext(): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }

    fun reset() {
        leafContexts.clear()
        caKey = null
        caCert = null
    }

    private fun genRsa(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.genKeyPair()

    private fun pemEncode(kind: String, der: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
        return "-----BEGIN $kind-----\n" + b64.chunked(64).joinToString("\n") +
            "\n-----END $kind-----\n"
    }

    private fun trustAll() = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    }
}
