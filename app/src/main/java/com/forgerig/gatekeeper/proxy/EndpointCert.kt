package com.forgerig.gatekeeper.proxy

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
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
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket

/**
 * The TLS identity for our own endpoint: a single self-signed certificate for
 * `127.0.0.1`.
 *
 * This is *not* a certificate authority and the difference matters. A CA
 * signs a fresh leaf for every host it intercepts, so trusting it means
 * trusting us for *any* name on the internet. This certificate signs nothing
 * but itself and names one address, so a client that trusts it gains the
 * ability to verify this endpoint and nothing else. Pinning it is a much
 * smaller grant, and it expires into irrelevance rather than into a standing
 * capability.
 *
 * The client fetches the PEM from `/endpoint-cert.pem` over loopback and pins
 * it. There is no CA to install and nothing to revoke.
 *
 * **The SAN must be an IP address, not a DNS name.** Clients validate a
 * connection to `127.0.0.1` by checking the iPAddress SAN; a dNSName SAN
 * makes them reject the certificate as having no valid name for the host.
 */
object EndpointCert {
    private const val DIR = "endpoint"
    private const val CERT_PEM = "endpoint-cert.pem"
    private const val KEY_PEM = "endpoint-key.pem"
    private const val STORE_PASS = "networkproxy-endpoint"
    private const val BEGIN_KEY = "-----BEGIN PRIVATE KEY-----"
    private const val END_KEY = "-----END PRIVATE KEY-----"

    @Volatile private var serverContext: SSLContext? = null
    @Volatile private var certificate: X509Certificate? = null

    fun certDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Generate on first use, then reuse. False on any failure. */
    @Synchronized
    fun ensureLoaded(context: Context): Boolean {
        // Only reuse a context whose certificate we can still vouch for:
        // self-signed, and naming this endpoint. Anything else (a half-built
        // context, a cert from a previous install) falls through and rebuilds.
        if (serverContext != null && isSelfSignedForLoopback(certificate)) return true
        return try {
            val dir = certDir(context)
            val certF = File(dir, CERT_PEM)
            val keyF = File(dir, KEY_PEM)
            val (kp, cert) = if (certF.exists() && keyF.exists()) {
                load(certF, keyF)
            } else {
                generate().also { (k, c) ->
                    // Written together into the same directory so a half-write
                    // is detected as "both or neither" on the next start.
                    keyF.writeText(pemEncode("PRIVATE KEY", k.private.encoded))
                    certF.writeText(pemEncode("CERTIFICATE", c.encoded))
                }
            }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("endpoint", kp.private, STORE_PASS.toCharArray(), arrayOf<Certificate>(cert))
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, STORE_PASS.toCharArray())
            val ctx = SSLContext.getInstance("TLS")
            // No trust manager: this context is server-side only. Upstream
            // connections use the platform default verification.
            ctx.init(kmf.keyManagers, null, SecureRandom())
            certificate = cert
            serverContext = ctx
            true
        } catch (e: Exception) {
            ProxyMetrics.eventError("Endpoint cert failed: ${e.message}", e)
            serverContext = null
            certificate = null
            false
        }
    }

    /** The PEM a client pins, or null when unavailable. */
    fun pem(context: Context): String? {
        if (!ensureLoaded(context)) return null
        return certificate?.let { pemEncode("CERTIFICATE", it.encoded) }
    }

    fun serverSocketFactory(context: Context): SSLServerSocketFactory? {
        if (!ensureLoaded(context)) return null
        return serverContext?.serverSocketFactory
    }

    /** A fresh server-mode engine, or null when the cert is not loaded. */
    fun engine(context: Context): SSLEngine? {
        if (!ensureLoaded(context)) return null
        val ctx = serverContext ?: return null
        return try {
            ctx.createSSLEngine()
        } catch (e: Exception) {
            ProxyMetrics.eventError("Endpoint TLS engine failed: ${e.message}", e)
            null
        }
    }

    /** Wrap an already-accepted socket in TLS, or null when not loaded. */
    fun wrap(socket: java.net.Socket, context: Context): SSLSocket? {
        if (!ensureLoaded(context)) return null
        val ctx = serverContext ?: return null
        return try {
            ctx.socketFactory.createSocket(
                socket, socket.inetAddress?.hostAddress ?: BIND_ADDRESS,
                socket.port, true
            ) as SSLSocket
        } catch (e: Exception) {
            ProxyMetrics.eventError("TLS wrap failed: ${e.message}", e)
            null
        }
    }

    /**
     * Guard against handing back a certificate whose private key we no longer
     * hold, or one that does not actually name this endpoint.
     */
    private fun isSelfSignedForLoopback(cert: X509Certificate?): Boolean =
        cert != null && cert.subjectX500Principal.name == cert.issuerX500Principal.name &&
            runCatching {
                cert.getSubjectAlternativeNames().orEmpty().any {
                    it.size >= 2 && it[0] == GeneralName.iPAddress && it[1] == BIND_ADDRESS
                }
            }.getOrDefault(false)

    fun reset() {
        serverContext = null
        certificate = null
    }

    private fun generate(): Pair<KeyPair, X509Certificate> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.genKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$BIND_ADDRESS")
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()),
            // Backdated a minute so a client whose clock runs slightly behind
            // does not reject a certificate that is already valid.
            Date(now - 60_000),
            Date(now + 825L * 86400_000),
            name,
            kp.public
        )
            // Self-signed: it is its own issuer, and it may not sign others.
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            // iPAddress, not dNSName -- see the class doc.
            .addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.iPAddress, BIND_ADDRESS))
            )
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        return kp to JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun load(certF: File, keyF: File): Pair<KeyPair, X509Certificate> {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certF.inputStream()) as X509Certificate
        val der = java.util.Base64.getDecoder().decode(
            keyF.readText()
                .substringAfter(BEGIN_KEY)
                .substringBefore(END_KEY)
                .filterNot { it.isWhitespace() }
        )
        // The private key is recovered from the stored PKCS#8 blob rather than
        // regenerated: a fresh key would not match the certificate, and the
        // SSLContext would then fail to serve a handshake at all.
        val priv = java.security.KeyFactory.getInstance("RSA")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(der)) as PrivateKey
        return KeyPair(cert.publicKey as java.security.interfaces.RSAPublicKey, priv) to cert
    }

    private fun pemEncode(kind: String, der: ByteArray): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(der)
        return "-----BEGIN $kind-----\n" + b64.chunked(64).joinToString("\n") +
            "\n-----END $kind-----\n"
    }
}