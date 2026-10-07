package com.forgerig.gatekeeper.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * A server-side TLS session driven explicitly over two arbitrary byte streams.
 *
 * This is what makes one listening port able to serve both TLS front-door
 * traffic and plaintext retry-proxy traffic. The alternative -- an SSLSocket
 * layered over the accepted socket -- reads the underlying socket's file
 * descriptor directly on Android, so a single byte that was already read for
 * protocol sniffing can never be replayed into it and every TLS handshake
 * fails with an "unexpected message". Driving the [SSLEngine] from our own
 * streams means the sniffed byte simply becomes the first byte we hand the
 * handshake.
 *
 * Byte-buffer contract used here (the SSLEngine two-buffer model):
 *  - [netIn] holds *unconsumed ciphertext* only. Before reading from the
 *    network it is compacted (unconsumed tail slides to the front), more
 *    bytes are appended, and it is flipped for `unwrap`.
 *  - [netOut] is cleared, `wrap` writes into it, and after the result its
 *    produced bytes (index 0 .. bytesProduced) are written to the wire.
 *  - [appIn] receives plaintext from `unwrap`; it is cleared before each
 *    call so any leftover app data was already drained by the reader.
 */
internal class EndpointTls private constructor(
    private val engine: SSLEngine,
    private val rawIn: InputStream,
    private val rawOut: OutputStream,
) : AutoCloseable {
    // netIn always holds flipped (read-mode) ciphertext: [position, limit) is
    // unconsumed network data. It starts flipped-empty (position=0, limit=0)
    // so "empty" is exactly !hasRemaining(); a buffer with position==0 and
    // limit>0 holds a fully-unconsumed record and must be compacted, never
    // cleared -- clearing it would wipe the first segment of a ClientHello
    // that arrived in split TCP segments and the next unwrap would fail with
    // "Unable to parse TLS packet header" (or stall forever waiting for a
    // ClientHello that can never be reassembled).
    private var netIn = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
    private var appIn = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var sessionClosed = false

    companion object {
        /** Run the server handshake to completion; throws on any failure. */
        fun handshake(
            engine: SSLEngine,
            rawIn: InputStream,
            rawOut: OutputStream,
        ): EndpointTls {
            engine.useClientMode = false
            engine.beginHandshake()
            val tls = EndpointTls(engine, rawIn, rawOut)
            tls.runHandshake()
            // Handshake unwrap calls leave appIn in write-mode; reset it to
            // read-mode-empty so the first readPlain entry-drain sees "no
            // leftover" instead of a full buffer of zeros. (The handshake
            // itself produces no application data.)
            tls.appIn.clear()
            tls.appIn.flip()
            return tls
        }
    }

    /** The plaintext stream a request is read from. */
    fun input(): InputStream = TlsInput()
    /** The plaintext stream a response is written to. */
    fun output(): OutputStream = TlsOutput()

    private fun runHandshake() {
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                SSLEngineResult.HandshakeStatus.FINISHED,
                -> return
                SSLEngineResult.HandshakeStatus.NEED_TASK ->
                    engine.drainTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val r = engine.wrap(EMPTY_INPUT, netOut)
                    requireWrapOk(r)
                    writeProduced(netOut, r)
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // Prefer bytes already buffered: reading from the socket
                    // while a complete next-flight message sits in netIn would
                    // block forever with the peer waiting for our flight.
                    if (!netIn.hasRemaining() && readIntoNetIn() == -1)
                        throw SSLException("TLS handshake: peer closed before completion")
                    val r = try { engine.unwrap(netIn, appIn) } catch (e: SSLException) {
                        ProxyMetrics.eventWarning("TLS handshake failed: ${e.message}")
                        throw e
                    }
                    requireUnwrapOk(r)
                    if (r.status == SSLEngineResult.Status.CLOSED)
                        throw SSLException("TLS handshake: peer closed")
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW &&
                        readIntoNetIn() == -1
                    )
                        throw SSLException("TLS handshake: peer closed before completion")
                }
                else -> throw SSLException("TLS handshake: unexpected state ${engine.handshakeStatus}")
            }
        }
    }

    // ----- reading plaintext -------------------------------------------------

    private fun readPlain(b: ByteArray, off: Int, len: Int): Int {
        if (sessionClosed) return -1
        // Invariant: at every entry and every return, appIn is in read-mode
        // (flipped); [position, limit) is undelivered plaintext. Serve that
        // first so a previous unwrap that produced more than one read asked
        // for is not lost.
        if (appIn.hasRemaining()) return drainApp(b, off, len)
        // Return the first chunk available (InputStream semantics): a caller
        // asking for a big buffer must not make us block until it is full,
        // or we deadlock a request when the plaintext arrives in several
        // records with the client already waiting for our response.
        while (true) {
            appIn.clear()
            if (!netIn.hasRemaining() && readIntoNetIn() == -1) {
                marshallClose()
                return -1
            }
            val r = try {
                engine.unwrap(netIn, appIn)
            } catch (e: SSLException) {
                ProxyMetrics.eventWarning("TLS read failed: ${e.message}")
                marshallClose()
                throw e
            }
            if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                // Plaintext did not fit; retry the same ciphertext into a
                // bigger buffer. `continue` re-clears above (it must not hit
                // the entry drain: a cleared buffer is write-mode, not
                // leftover plaintext).
                appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                continue
            }
            if (r.status == SSLEngineResult.Status.CLOSED) {
                marshallClose()
                return -1
            }
            // Flip BEFORE measuring: without this, remaining() is
            // capacity-minus-produced and the caller receives zeros.
            appIn.flip()
            if (appIn.hasRemaining()) return drainApp(b, off, len)
            when (r.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> engine.drainTasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> writePolicy()
                else -> {}
            }
            if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW &&
                readIntoNetIn() == -1
            ) {
                // Partial record pending; the rest is fetched and the loop
                // unwraps again.
                marshallClose()
                return -1
            }
        }
    }

    private fun drainApp(b: ByteArray, off: Int, len: Int): Int {
        val n = minOf(appIn.remaining(), len)
        appIn.get(b, off, n)
        return n
    }

    // ----- writing plaintext ------------------------------------------------

    private fun writePlain(b: ByteArray, off: Int, len: Int) {
        if (sessionClosed) throw IOException("TLS session closed")
        if (len == 0) return
        val plain = ByteBuffer.allocate(len)
        plain.put(b, off, len)
        plain.flip()
        var loops = 0
        while (plain.hasRemaining()) {
            if (++loops > 1_000_000) throw IOException("TLS write stalled")
            netOut.clear()
            val r = engine.wrap(plain, netOut)
            requireWrapOk(r)
            writeProduced(netOut, r)
        }
        rawOut.flush()
    }

    private fun writePolicy() {
        netOut.clear()
        val r = engine.wrap(EMPTY_INPUT, netOut)
        requireWrapOk(r)
        writeProduced(netOut, r)
        rawOut.flush()
    }

    // ----- plumbing -----------------------------------------------------------

    /**
     * Compact any unconsumed ciphertext to the front of [netIn], append fresh
     * network bytes in the freed space, and flip for `unwrap`. Returns -1 on
     * EOF and 0 when the peer produced no bytes yet.
     */
    private fun readIntoNetIn(): Int {
        // Empty (fresh or fully consumed) clears back to the start; a buffer
        // holding an unconsumed ciphertext tail slides that tail forward so
        // split records are reassembled instead of overwritten.
        if (!netIn.hasRemaining()) netIn.clear()
        else netIn.compact()
        val n = rawIn.read(netIn.array(), netIn.position(), netIn.remaining())
        if (n > 0) netIn.position(netIn.position() + n)
        netIn.flip()
        return n
    }

    private fun writeProduced(net: ByteBuffer, r: SSLEngineResult) {
        if (r.bytesProduced() > 0) {
            rawOut.write(net.array(), net.arrayOffset(), r.bytesProduced())
            rawOut.flush()
        }
    }

    private fun requireWrapOk(r: SSLEngineResult) {
        if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW)
            throw IOException("TLS write buffer overflow")
    }

    private fun requireUnwrapOk(r: SSLEngineResult) {
        if (r.status == SSLEngineResult.Status.BUFFER_OVERFLOW)
            appIn = ByteBuffer.allocate(appIn.capacity() * 2)
    }

    private fun marshallClose() {
        if (sessionClosed) return
        sessionClosed = true
        runCatching {
            engine.closeOutbound()
            netOut.clear()
            val r = engine.wrap(EMPTY_INPUT, netOut)
            writeProduced(netOut, r)
        }
        runCatching { rawOut.flush() }
    }

    override fun close() = marshallClose()

    private inner class TlsInput : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = readPlain(one, 0, 1)
            return if (n < 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            return readPlain(b, off, len)
        }
    }

    private inner class TlsOutput : OutputStream() {
        override fun write(b: Int) = writePlain(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = writePlain(b, off, len)
        override fun flush() {
            runCatching { rawOut.flush() }
        }
    }

}

private val EMPTY_INPUT = ByteBuffer.allocate(0)

private fun SSLEngine.drainTasks() {
    var task = delegatedTask
    while (task != null) {
        task.run()
        task = delegatedTask
    }
}