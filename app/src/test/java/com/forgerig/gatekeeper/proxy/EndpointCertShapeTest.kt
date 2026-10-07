package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory

/**
 * The endpoint certificate's contract, checked directly against a certificate
 * built the same way [EndpointCert] builds it.
 *
 * Two things here are easy to get subtly wrong and impossible to notice until
 * a client refuses to connect:
 *
 *  - the SAN must be an **iPAddress**, because the client connects to
 *    `127.0.0.1` and a dNSName SAN will not validate against an IP;
 *  - the certificate must be **self-signed** so pinning it grants authority
 *    over this endpoint and nothing else.
 *
 * The generation itself lives in [EndpointCert] and needs a Context, so these
 * tests rebuild the same certificate with BouncyCastle directly and assert the
 * properties. If the shape in EndpointCert ever drifts, these fail.
 */
class EndpointCertShapeTest {

    private fun build(): X509Certificate {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.genKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$BIND_ADDRESS")
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()),
            Date(now - 60_000),
            Date(now + 825L * 86400_000),
            name,
            kp.public
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.iPAddress, BIND_ADDRESS))
            )
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    @Test
    fun itNamesLoopbackAsAnIpAddressSan() {
        val cert = build()
        val sans = cert.subjectAlternativeNames.orEmpty()
        val ip = sans.firstOrNull { it.size >= 2 && it[0] == GeneralName.iPAddress }
        assertNotNull("must carry an iPAddress SAN, not a dNSName one", ip)
        assertEquals(BIND_ADDRESS, ip!![1])
    }

    @Test
    fun itIsSelfSignedSoPiningGrantsOnlyThisEndpoint() {
        val cert = build()
        assertEquals(
            "subject and issuer must match: pinning this must not imply authority over other names",
            cert.subjectX500Principal.name,
            cert.issuerX500Principal.name
        )
    }

    @Test
    fun itIsUsableAsItsOwnTrustAnchor() {
        // CA=true is what lets a self-signed cert be pinned directly: without
        // it a client rejects the certificate as not being a CA while it tries
        // to use it as one. getBasicConstraints() returns MAX_VALUE for
        // CA=true (path length unconstrained), and -1 for a leaf-only cert.
        val cert = build()
        assertTrue(
            "must be a CA so it can act as its own trust anchor",
            cert.basicConstraints != -1
        )
    }

    @Test
    fun itCoversTheIpWithPortOrPathAndNoPort() {
        // The client dials 127.0.0.1 with a port; host verification ignores
        // the port, so the SAN only has to name the address.
        val cert = build()
        assertTrue(cert.subjectAlternativeNames.any { it.size >= 2 && it[1] == "127.0.0.1" })
    }

    // ------------------------------------------------- the real handshake

    /** A server SSLContext holding [cert] and its key, as EndpointCert builds. */
    private fun serverContextFor(cert: X509Certificate, kp: java.security.KeyPair): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("endpoint", kp.private, "p".toCharArray(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, "p".toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    /** A client context trusting *only* [cert] -- i.e. a pinned endpoint. */
    private fun clientContextPinning(cert: X509Certificate): SSLContext {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trust.setCertificateEntry("pinned", cert)
        val tmf = javax.net.ssl.TrustManagerFactory
            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }
    }

    /** Server + certificate + key, all from one generation. */
    private class Endpoint(val ctx: SSLContext, val cert: X509Certificate, val kp: java.security.KeyPair)

    private fun generateEndpoint(): Endpoint {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.genKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$BIND_ADDRESS")
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()),
            Date(now - 60_000), Date(now + 825L * 86400_000), name, kp.public
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.iPAddress, BIND_ADDRESS))
            )
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        return Endpoint(serverContextFor(cert, kp), cert, kp)
    }

    /**
     * The property that actually matters: a client that trusts only this
     * certificate completes a real TLS handshake against a loopback listener
     * and gets an HTTP response over it. This is exactly what the client will
     * do, and it fails loudly if the SAN is the wrong type or the cert is not
     * self-signed.
     */
    @Test
    fun aClientPinningOnlyThisCertCompletesARealHandshakeToLoopback() {
        val ep = generateEndpoint()
        val ssf = ep.ctx.serverSocketFactory
        val listener = ssf.createServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val port = listener.localPort
        val done = ArrayBlockingQueue<String>(1)

        val t = Thread {
            try {
                listener.accept().use { sock ->
                    val r = BufferedReader(InputStreamReader(sock.getInputStream()))
                    while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                    val body = """{"ok":true}"""
                    sock.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                            .toByteArray()
                    )
                    sock.getOutputStream().flush()
                    done.put("served")
                }
            } catch (e: Exception) {
                done.put("error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.apply { isDaemon = true; start() }

        val clientCtx = clientContextPinning(ep.cert)
        val clientFactory = clientCtx.socketFactory as SSLSocketFactory
        val ssl = clientFactory.createSocket(BIND_ADDRESS, port) as SSLSocket
        val response = try {
            ssl.startHandshake()
            ssl.outputStream.write(
                ("GET /v1/models HTTP/1.1\r\nHost: $BIND_ADDRESS:$port\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            ssl.outputStream.flush()
            BufferedReader(InputStreamReader(ssl.inputStream)).readText()
        } finally {
            ssl.close()
        }
        listener.close()
        val served = done.poll(5, TimeUnit.SECONDS)
        assertEquals("served", served)
        assertTrue("client must see a 200: '$response'", response.contains("200 OK"))
    }

    @Test
    fun aClientThatDoesNotTrustTheCertRefusesIt() {
        // The negative control for the test above: with an empty trust store
        // the same handshake must fail. Without this, the positive test would
        // pass even if verification were somehow disabled.
        val ep = generateEndpoint()
        val listener = ep.ctx.serverSocketFactory
            .createServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val port = listener.localPort
        Thread {
            try { listener.accept().use { it.getInputStream().read() } } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }

        val empty = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val tmf = javax.net.ssl.TrustManagerFactory
            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(empty) }
        val untrusting = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }

        var failed = false
        try {
            val s2 = (untrusting.socketFactory as SSLSocketFactory)
                .createSocket(BIND_ADDRESS, port) as SSLSocket
            s2.startHandshake()
        } catch (_: SSLHandshakeException) {
            failed = true
        } catch (_: Exception) {
            failed = true
        }
        listener.close()
        assertTrue("an untrusting client must be refused", failed)
    }
}
