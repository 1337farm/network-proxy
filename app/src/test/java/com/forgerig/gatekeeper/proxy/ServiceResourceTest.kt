package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Resource-ownership coverage for [ProxyService.UpstreamRawGuard] — the fix for
 * the fd leaked on every declined/failed MITM CONNECT, where
 * `createSocket(raw, .., autoClose = true)` never adopted the raw socket and
 * the surrounding catch could only close the (still null) `tlsUp`.
 *
 * The guard's whole job is a two-way decision — "does anything still own this
 * socket?" — so it is tested against *real* loopback sockets and the peer-side
 * observable effect (a FIN reaches the far end), not against a mock. That is
 * what makes "the fd was released" and "the live connection was not severed"
 * distinguishable from each other.
 *
 * Everything else in this change (OkHttp teardown on restart, state-callback
 * lifecycle, listener-thread handle) needs an Android runtime and a live
 * service; those are inspection-only and are checked on-device.
 */
class ServiceResourceTest {

    /**
     * One real connected loopback pair. [peerState] is what the *far* end
     * observed: "eof" = our socket was closed (fd released, connection torn
     * down), "open" = still connected after the read timeout (so closing it
     * would have cut a live connection), "data"/"error:.." = unexpected.
     */
    private class Loopback(peerReadTimeoutMs: Int = 2_000) {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket()
        private val accepted = AtomicReference<Socket?>()
        val peerState = AtomicReference("pending")
        private val reader: Thread

        init {
            client.connect(
                InetSocketAddress(InetAddress.getLoopbackAddress(), serverSocket.localPort),
                5_000
            )
            val peer = serverSocket.accept()
            accepted.set(peer)
            reader = Thread {
                try {
                    peer.soTimeout = peerReadTimeoutMs
                    val n = peer.getInputStream().read(ByteArray(16))
                    peerState.set(if (n < 0) "eof" else "data")
                } catch (_: SocketTimeoutException) {
                    peerState.set("open")
                } catch (e: Exception) {
                    peerState.set("error:" + e)
                }
            }
            reader.isDaemon = true
            reader.start()
        }

        fun awaitPeer(expected: String, timeoutMs: Long = 3_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (peerState.get() == expected) return
                Thread.sleep(10)
            }
            assertEquals("peer never reached '$expected'", expected, peerState.get())
        }

        fun closeAll() {
            runCatching { client.close() }
            runCatching { accepted.get()?.close() }
            runCatching { serverSocket.close() }
            reader.join(1_000)
        }
    }

    /**
     * The leak itself: the wrap threw before adopting the socket, so the
     * finally block is the only thing that can close it. Asserts the close
     * happened *and* that the far end saw the FIN — the fd is gone, not just
     * a flag flipped.
     */
    @Test
    fun unownedRawSocketIsClosedWhenTlsWrapFails() {
        val pair = Loopback()
        try {
            val guard = ProxyService.UpstreamRawGuard()
            guard.track(pair.client)
            assertFalse("nothing is handed off yet", guard.handedOff)

            // createSocket(...) threw here; tlsUp is still null, so the catch
            // block cannot reach the socket. Only the guard can.
            assertTrue("guard must own the un-wrapped socket", guard.closeIfUnowned())
            assertTrue(pair.client.isClosed)
            pair.awaitPeer("eof")
        } finally {
            pair.closeAll()
        }
    }

    /**
     * The opposite obligation: once the TLS socket owns the raw socket,
     * closing it here would sever a working MITM relay. The peer must still
     * be connected after the guard runs.
     */
    @Test
    fun handedOffRawSocketIsLeftToTheTlsWrapper() {
        val pair = Loopback(peerReadTimeoutMs = 400)
        try {
            val guard = ProxyService.UpstreamRawGuard()
            guard.track(pair.client)
            guard.handOff()
            assertTrue(guard.handedOff)

            assertFalse("nothing left to close after hand-off", guard.closeIfUnowned())
            assertFalse("a live connection must not be cut", pair.client.isClosed)
            // 'open', not 'eof': the guard did not close it.
            pair.awaitPeer("open")
            assertFalse(pair.client.isClosed)
        } finally {
            pair.closeAll()
        }
    }

    /** Cleanup is idempotent: a second pass must not double-close or throw. */
    @Test
    fun closeIsIdempotent() {
        val pair = Loopback()
        try {
            val guard = ProxyService.UpstreamRawGuard()
            guard.track(pair.client)
            assertTrue(guard.closeIfUnowned())
            assertFalse(guard.closeIfUnowned())
            assertFalse(guard.closeIfUnowned())
            assertTrue(pair.client.isClosed)
        } finally {
            pair.closeAll()
        }
    }

    /**
     * Failures *before* the raw socket exists (e.g. MitmCa.upstreamContext()
     * throwing, or the client TLS handshake being declined) must leave the
     * finally block a no-op rather than an NPE or a stray close.
     */
    @Test
    fun guardWithNoTrackedSocketIsANoOp() {
        val guard = ProxyService.UpstreamRawGuard()
        assertFalse(guard.handedOff)
        assertFalse(guard.closeIfUnowned())

        // handOff() with nothing tracked must not arm the guard either.
        val other = ProxyService.UpstreamRawGuard()
        other.handOff()
        assertTrue(other.handedOff)
        assertFalse(other.closeIfUnowned())
    }

    /**
     * A close() that fails must not escape: this runs in a `finally` on the
     * MITM path, so a thrown IOException here would replace the real
     * "client declined our CA" reason and skip the opaque-tunnel fallback.
     */
    @Test
    fun failingCloseIsSwallowed() {
        val socket = ExplodingSocket()
        val guard = ProxyService.UpstreamRawGuard()
        guard.track(socket)
        assertTrue("the close is still reported as done", guard.closeIfUnowned())
        assertEquals("exactly one close attempt", 1, socket.closeAttempts)
        // And the guard is still clean afterwards, so a retry is a no-op.
        assertFalse(guard.closeIfUnowned())
    }

    /** A socket whose close() blows up, to pin the swallow-and-report contract. */
    private class ExplodingSocket : Socket() {
        var closeAttempts = 0
        override fun close() {
            closeAttempts++
            throw IOException("close failed")
        }
    }
}
