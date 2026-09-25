package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Live MITM usage scanning: tokens must count per chunk as they stream
 * (keep-alive tunnels close minutes late), exactly once, plus a
 * gzip/deflate close-time fallback for encoded bodies, plus tok/s.
 */
class UsageStreamTest {

    private val sseUsage =
        "data: {\"id\":\"chatcmpl-1\",\"usage\":{\"prompt_tokens\":7843," +
            "\"completion_tokens\":14,\"total_tokens\":7857}}\n" +
            "data: [DONE]\n"

    private fun feedAll(u: ProxyMetrics.StreamingUsage, bytes: ByteArray, chunk: Int): LongArray {
        val total = LongArray(4)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunk, bytes.size - i)
            val f = u.feed(bytes.copyOfRange(i, i + n), n)
            for (k in 0..3) total[k] += f[k]
            i += n
        }
        // Mirror the relay lifecycle: per-chunk feeds, then flush at close.
        val tail = u.flush()
        for (k in 0..3) total[k] += tail[k]
        return total
    }

    @Test
    fun wholeAndChunkedFeedsAgree() {
        val bytes = sseUsage.toByteArray(Charsets.UTF_8)
        for (chunk in listOf(bytes.size, 64, 7, 3, 1)) {
            val got = feedAll(ProxyMetrics.StreamingUsage(), bytes, chunk)
            assertArrayEquals("chunk=$chunk", longArrayOf(7843, 14, 0, 0), got)
        }
    }

    @Test
    fun streamEndingMidNumberCountsViaFlush() {
        // Stream cut exactly after the final digit: nothing is knowable
        // until close, then flush() completes it — exactly once.
        val bytes = "{\"usage\":{\"prompt_tokens\":200}}".toByteArray(Charsets.UTF_8)
        val cut = bytes.size - 2 // ...":20  (trailing `}}` withheld)
        val u = ProxyMetrics.StreamingUsage()
        val a = u.feed(bytes.copyOfRange(0, cut), cut)
        assertEquals(0L, a[0] + a[1])
        val rest = bytes.copyOfRange(cut, bytes.size)
        val b = u.feed(rest, rest.size)
        val tail = u.flush()
        assertEquals(200L, a[0] + b[0] + tail[0])
    }

    @Test
    fun splitFieldAcrossBoundaryCountsOnce() {
        // Split inside the key name itself at every offset around it.
        val bytes = sseUsage.toByteArray(Charsets.UTF_8)
        val keyAt = sseUsage.indexOf("prompt_tokens")
        for (split in (keyAt - 2)..(keyAt + 15)) {
            val u = ProxyMetrics.StreamingUsage()
            val a = u.feed(bytes.copyOfRange(0, split), split)
            val b = u.feed(bytes.copyOfRange(split, bytes.size), bytes.size - split)
            assertEquals(
                "split=$split",
                7843L,
                a[0] + b[0]
            )
            assertEquals("split=$split", 14L, a[1] + b[1])
        }
    }

    @Test
    fun chunkedFramingIsHarmless() {
        val body = sseUsage.toByteArray(Charsets.UTF_8)
        val framed = ("1a\r\n" + "x".repeat(26) + "\r\n" +
            body.size.toString(16) + "\r\n").toByteArray(Charsets.UTF_8) +
            body + "\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(
            longArrayOf(7843, 14, 0, 0),
            feedAll(ProxyMetrics.StreamingUsage(), framed, 11)
        )
    }

    @Test
    fun anthropicStreamTalliesInputAndOutput() {
        val body = ("event: message_start\ndata: {\"type\":\"message_start\"," +
            "\"message\":{\"usage\":{\"input_tokens\":1200}}}\n" +
            "event: message_delta\ndata: {\"usage\":{\"output_tokens\":17}}\n").toByteArray()
        assertArrayEquals(
            longArrayOf(1200, 17, 0, 0),
            feedAll(ProxyMetrics.StreamingUsage(), body, 23)
        )
    }

    @Test
    fun plaintextTapReturnsZerosAtClose() {
        // Live scanning already counted plaintext; close must not recount.
        val tap = ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n" + sseUsage)
            .toByteArray(Charsets.UTF_8)
        assertArrayEquals(LongArray(4) { 0 }, ProxyMetrics.scanTapBytesForClose(tap))
    }

    @Test
    fun gzipChunkedTapDecodesAtClose() {
        val json = "{\"usage\":{\"prompt_tokens\":200,\"completion_tokens\":50}}"
        val gzBos = ByteArrayOutputStream()
        GZIPOutputStream(gzBos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val gz = gzBos.toByteArray()
        val framed = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
            "Content-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n" +
            gz.size.toString(16) + "\r\n").toByteArray(Charsets.UTF_8) +
            gz + "\r\n0\r\n\r\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(
            longArrayOf(200, 50, 0, 0),
            ProxyMetrics.scanTapBytesForClose(framed)
        )
    }

    @Test
    fun dechunkRoundTrips() {
        val parts = listOf("hello ", "world")
        val framed = parts.joinToString("") { it.length.toString(16) + "\r\n$it\r\n" } + "0\r\n\r\n"
        assertEquals(
            "hello world",
            ProxyMetrics.tryDechunk(framed.toByteArray(Charsets.UTF_8))!!.toString(Charsets.UTF_8)
        )
        assertEquals(null, ProxyMetrics.tryDechunk("{\"a\":1}".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun tokensPerSecondTrailingWindow() {
        ProxyMetrics.resetTallies()
        // Rate credit flows only through the sampler (spread buckets),
        // never as an addTokens side effect (that spiked on arrival).
        ProxyMetrics.sampleOutput(30)
        assertEquals(1.0, ProxyMetrics.outputTokensPerSecond(30_000), 0.0001)
        assertEquals(3.0, ProxyMetrics.outputTokensPerSecond(10_000), 0.0001)
        // Tallies alone move no rate.
        ProxyMetrics.resetTallies()
        ProxyMetrics.addTokens(50, 30, 0, 0)
        assertEquals(0.0, ProxyMetrics.outputTokensPerSecond(), 0.0)
        assertEquals(30L, ProxyMetrics.outputTokens)
        ProxyMetrics.resetTallies()
    }

    @Test
    fun perHostTalliesFeedTableAndTotals() {
        ProxyMetrics.resetTallies()
        ProxyMetrics.addTokens(7843, 14, 113, 0, "openrouter.ai")
        ProxyMetrics.addTokens(100, 200, 0, 0, "integrate.api.nvidia.com")
        ProxyMetrics.addTokens(10, 0, 0, 0) // no host: totals only
        // Globals are the totals row.
        assertEquals(7953L, ProxyMetrics.inputTokens)
        assertEquals(214L, ProxyMetrics.outputTokens)
        assertEquals(113L, ProxyMetrics.cacheReadTokens)
        // Per-host rows sorted by volume desc.
        val rows = ProxyMetrics.tokenSummary(5)
        assertEquals(2, rows.size)
        assertEquals("openrouter.ai", rows[0].host)
        assertEquals(7843L, rows[0].inTokens)
        assertEquals(14L, rows[0].outTokens)
        assertEquals(113L, rows[0].cacheRead)
        assertEquals("integrate.api.nvidia.com", rows[1].host)
        // Reset clears rows and totals together.
        ProxyMetrics.resetTallies()
        assertTrue(ProxyMetrics.tokenSummary(5).isEmpty())
        assertEquals(0L, ProxyMetrics.inputTokens)
    }
}
