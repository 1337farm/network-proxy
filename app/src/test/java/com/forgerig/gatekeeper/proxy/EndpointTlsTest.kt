package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Exercises [EndpointTls] end to end: a real TLS client (a pinned [SSLSocket])
 * completes a handshake *through* the engine pump and exchanges an HTTP
 * request/response. This is the path the front door uses and it catches the
 * buffer bookkeeping bugs that an SSLSocket-layered alternative hides until
 * the first client connection.
 */
class EndpointTlsTest {

    private fun generateEndpoint(): KeyPair {
        return KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048, SecureRandom()) }
            .genKeyPair()
    }

    private fun certFor(kp: KeyPair): X509Certificate {
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
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun serverContext(kp: KeyPair, cert: X509Certificate): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        ks.setKeyEntry("endpoint", kp.private, "p".toCharArray(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, "p".toCharArray()) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    private fun pinnedClientContext(cert: X509Certificate): SSLContext {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trust.setCertificateEntry("pinned", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trust) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }
    }

    /**
     * The server side of the test drives [EndpointTls] exactly the way
     * [ProxyService] does: raw byte streams in, plaintext streams handed to the
     * HTTP layer.
     */
    private fun runServer(listener: ServerSocket, sslCtx: SSLContext, done: ArrayBlockingQueue<String>) {
        Thread {
            try {
                listener.accept().use { sock ->
                    val engine = sslCtx.createSSLEngine()
                    val tls = EndpointTls.handshake(engine, sock.getInputStream(), sock.getOutputStream())
                    val reader = BufferedReader(InputStreamReader(tls.input()))
                    var line: String? = reader.readLine()
                    while (line != null && line.isNotBlank()) line = reader.readLine()
                    val body = """{"ok":true}"""
                    tls.output().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
                            .toByteArray()
                    )
                    tls.output().flush()
                    done.put("served")
                }
            } catch (e: Exception) {
                done.put("error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    @Test
    fun handshakeAndHttpThroughThePump() {
        val kp = generateEndpoint()
        val cert = certFor(kp)
        val sslCtx = serverContext(kp, cert)
        val listener = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val port = listener.localPort
        val done = ArrayBlockingQueue<String>(1)
        runServer(listener, sslCtx, done)

        val clientCtx = pinnedClientContext(cert)
        val ssl = (clientCtx.socketFactory as SSLSocketFactory)
            .createSocket(BIND_ADDRESS, port) as SSLSocket
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
        assertEquals("server must complete its side: $served", "served", served)
        assertTrue("client must see a 200: '$response'", response.contains("200 OK"))
    }

    @Test
    fun anUntrustingClientIsRefusedDuringTheHandshake() {
        val kp = generateEndpoint()
        val cert = certFor(kp)
        val sslCtx = serverContext(kp, cert)
        val listener = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val port = listener.localPort
        val done = ArrayBlockingQueue<String>(1)
        runServer(listener, sslCtx, done)

        val empty = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(empty) }
        val untrusting = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }

        var failed = false
        try {
            val ssl = (untrusting.socketFactory as SSLSocketFactory)
                .createSocket(BIND_ADDRESS, port) as SSLSocket
            ssl.startHandshake()
        } catch (_: Exception) {
            failed = true
        }
        listener.close()
        val served = done.poll(5, TimeUnit.SECONDS)
        assertTrue("an untrusting client must be refused (server saw: $served)", failed)
    }
}