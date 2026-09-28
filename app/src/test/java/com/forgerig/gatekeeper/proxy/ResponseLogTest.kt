package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The verbose response-log ring: bounded at 20 entries / 256KB each,
 * newest-first snapshot, 200-char whitespace-collapsed previews, and
 * thread-safe against concurrent relay-thread writes.
 */
class ResponseLogTest {

    @Before
    fun reset() {
        ResponseLog.clear()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun snapshotIsEmptyBeforeAnyAdd() {
        assertTrue(ResponseLog.snapshot().isEmpty())
    }

    @Test
    fun addStoresEntryWithMetadata() {
        ResponseLog.add("api.anthropic.com", "application/json", bytes("""{"id":"msg_01A"}"""))
        val e = ResponseLog.snapshot().single()
        assertEquals("api.anthropic.com", e.host)
        assertEquals("application/json", e.contentType)
        assertEquals("""{"id":"msg_01A"}""", String(e.body, Charsets.UTF_8))
        assertTrue(e.timestampMs > 0)
    }

    @Test
    fun snapshotIsNewestFirst() {
        ResponseLog.add("h1", "text/plain", bytes("one"))
        ResponseLog.add("h2", "text/plain", bytes("two"))
        ResponseLog.add("h3", "text/plain", bytes("three"))
        assertEquals(listOf("h3", "h2", "h1"), ResponseLog.snapshot().map { it.host })
    }

    @Test
    fun ringBufferEvictsOldestBeyondTwenty() {
        for (i in 1..25) ResponseLog.add("h$i", "text/plain", bytes("body$i"))
        val snap = ResponseLog.snapshot()
        assertEquals(20, snap.size)
        // Newest first: h25 … h6; h1..h5 evicted.
        assertEquals("h25", snap.first().host)
        assertEquals("h6", snap.last().host)
        assertEquals((25 downTo 6).map { "h$it" }, snap.map { it.host })
    }

    @Test
    fun bodyIsTruncatedTo256KB() {
        val big = bytes("x".repeat(ResponseLog.MAX_ENTRY_BYTES + 5000))
        ResponseLog.add("h", "application/octet-stream", big)
        assertEquals(ResponseLog.MAX_ENTRY_BYTES, ResponseLog.snapshot().single().body.size)
    }

    @Test
    fun bodyAtTheCapIsKeptWhole() {
        val exact = bytes("y".repeat(ResponseLog.MAX_ENTRY_BYTES))
        ResponseLog.add("h", "application/octet-stream", exact)
        assertEquals(ResponseLog.MAX_ENTRY_BYTES, ResponseLog.snapshot().single().body.size)
    }

    @Test
    fun previewIsFirst200CharsWhitespaceCollapsed() {
        val body = "line one\n\nline   two\tthree" + "z".repeat(300)
        ResponseLog.add("h", "text/plain", bytes(body))
        val p = ResponseLog.snapshot().single().preview()
        assertEquals(200, p.length)
        assertFalse(p.contains("\n"))
        assertFalse(p.contains("  "))
        assertTrue(p.startsWith("line one line two three"))
    }

    @Test
    fun previewOfShortBodyIsTheWholeBody() {
        ResponseLog.add("h", "text/plain", bytes("short"))
        assertEquals("short", ResponseLog.snapshot().single().preview())
    }

    @Test
    fun clearEmptiesTheLog() {
        ResponseLog.add("h", "text/plain", bytes("x"))
        ResponseLog.clear()
        assertTrue(ResponseLog.snapshot().isEmpty())
    }

    @Test
    fun concurrentAddsAreSafeAndBounded() {
        val threads = 8
        val perThread = 50
        val latch = CountDownLatch(threads)
        val exec = Executors.newFixedThreadPool(threads)
        repeat(threads) { t ->
            exec.execute {
                try {
                    repeat(perThread) { i -> ResponseLog.add("h$t", "text/plain", bytes("b$i")) }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        exec.shutdown()
        val snap = ResponseLog.snapshot()
        // Bounded no matter how the interleaving landed.
        assertEquals(20, snap.size)
        // Newest first: the most recent add overall is some h*/b49.
        assertTrue(snap.first().preview().startsWith("b4"))
    }
}
