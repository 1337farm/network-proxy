package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Session identity + per-session usage: the harvested provider id
 * (Anthropic `msg_…` / OpenAI `chatcmpl-…` / header fallback), its
 * degradation to today's key-label display, per-session token
 * accumulation, and the bounded-state guarantees (LRU cap, tap cap).
 *
 * Synthetic bytes only — no fixtures, no device, no network.
 */
class SessionIdentityTest {

    @Before
    fun reset() {
        SessionTracker.clearAll()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    // ---- payload ids -------------------------------------------------

    @Test
    fun anthropicTopLevelId() {
        val body = bytes(
            """{"id":"msg_01ABCdefGHIjkl","type":"message","role":"assistant",
               "content":[{"type":"text","text":"hi"}],
               "usage":{"input_tokens":10,"output_tokens":2}}"""
        )
        assertEquals("msg_01ABCdefGHIjkl", SessionTracker.payloadIdOf(body))
        assertEquals("msg_01ABCdefGHIjkl", SessionTracker.responseId(body))
    }

    @Test
    fun openAiTopLevelId() {
        val body = bytes(
            """{"id":"chatcmpl-9Xk2LmNoPq","object":"chat.completion",
               "choices":[{"message":{"role":"assistant","content":"yo"}}]}"""
        )
        assertEquals("chatcmpl-9Xk2LmNoPq", SessionTracker.payloadIdOf(body))
    }

    @Test
    fun idSurvivesAnHttpRequestHead() {
        // A tapped MITM request/response starts with the head; the id is
        // in the body past CRLFCRLF.
        val raw = bytes(
            "POST /v1/messages HTTP/1.1\r\nHost: api.anthropic.com\r\n" +
                "content-type: application/json\r\n\r\n" +
                """{"id":"msg_01HeadStripped","model":"claude"}"""
        )
        assertEquals("msg_01HeadStripped", SessionTracker.payloadIdOf(raw))
    }

    @Test
    fun sseMessageStartFrameYieldsId() {
        // Anthropic repeats the id as message.id in the message_start frame.
        val sse = bytes(
            "event: message_start\n" +
                """data: {"type":"message_start","message":{"id":"msg_01SSEframe","type":"message",""" +
                """"role":"assistant","content":[],"model":"claude"}}\n\n""" +
                "event: content_block_delta\n" +
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""" +
                "\n\nevent: message_stop\n" + "data: [DONE]\n\n"
        )
        assertEquals("msg_01SSEframe", SessionTracker.payloadIdOf(sse))
    }

    @Test
    fun ssePrefixIsStrippedBeforeJsonParsing() {
        // A bare `data:` frame with a top-level id (OpenAI's first chunk).
        val sse = bytes(
            "event: message\ndata: {\"id\":\"chatcmpl-SSEtop\",\"object\":\"chat.completion.chunk\"}\n\n"
        )
        assertEquals("chatcmpl-SSEtop", SessionTracker.payloadIdOf(sse))
        // Pure garbage around the frame must not throw.
        assertEquals("", SessionTracker.payloadIdOf(bytes("event: x\ndata: not-json\n\n")))
        assertEquals("", SessionTracker.payloadIdOf(bytes(": ping\n\ndata: \n")))
    }

    // ---- header fallback ---------------------------------------------

    @Test
    fun headerIdIsFallbackWhenPayloadHasNone() {
        val payload = bytes("""{"type":"message","usage":{"input_tokens":3}}""")
        assertEquals("", SessionTracker.payloadIdOf(payload))
        val headers = mapOf(
            "content-type" to "application/json",
            "request-id" to "req_01123abc",
            "x-request-id" to "req_LOWERPRIORITY"
        )
        assertEquals("req_01123abc", SessionTracker.headerIdOf(headers))
        // Payload empty → header wins, i.e. the combined call.
        assertEquals("req_01123abc", SessionTracker.responseId(payload, headers))
        // Payload id present → payload wins over the header.
        val withId = bytes("""{"id":"msg_01PayloadWins"}""")
        assertEquals("msg_01PayloadWins", SessionTracker.responseId(withId, headers))
    }

    @Test
    fun headerIdLookupIsCaseInsensitiveAndPrecedenceOrdered() {
        val h = mapOf("X-Request-Id" to "req_case", "openai-request-id" to "req_openai")
        assertEquals("req_case", SessionTracker.headerIdOf(h))
        assertEquals("req_openai", SessionTracker.headerIdOf(mapOf("OPENAI-Request-Id" to "req_openai")))
        // An unusable value is skipped in favour of the next key.
        val bad = mapOf("request-id" to "  ", "x-request-id" to "req_fallback")
        assertEquals("req_fallback", SessionTracker.headerIdOf(bad))
        assertEquals("", SessionTracker.headerIdOf(null))
        assertEquals("", SessionTracker.headerIdOf(emptyMap()))
        // `openai-organization` is an org, not a request id — never shown.
        assertEquals("", SessionTracker.headerIdOf(mapOf("openai-organization" to "org-abc123")))
    }

    // ---- degradation --------------------------------------------------

    @Test
    fun garbageIdDegradesToKeyLabelAndNeverThrows() {
        SessionTracker.note("s-garbage", "zen", "(client key)", "m", "h", 1000)
        SessionTracker.noteResponse("s-garbage", bytes("<not json at all>"))
        var s = SessionTracker.snapshot().single()
        assertEquals("", s.remoteId)
        assertEquals("(client key)", s.displayLabel)
        assertTrue(s.displayLabel.isNotBlank())

        // Empty / missing / wrong-typed / prompt-shaped ids are all rejected.
        for (bad in listOf(
            bytes("""{"id":""}"""),
            bytes("""{"id":null}"""),
            bytes("""{"id":{}}"""),
            bytes("""{"model":"m","messages":[]}"""),
            bytes("""{"id":"please summarise this long prompt about kittens"}"""),
            bytes("""{"id":"line\nbreak"}"""),
            bytes(""),
            null
        )) {
            SessionTracker.noteResponse("s-garbage", bad)
            s = SessionTracker.snapshot().single()
            assertEquals("", s.remoteId)
            assertEquals("(client key)", s.displayLabel)
        }
        // Over-long "id" is content, not an id.
        SessionTracker.noteResponse("s-garbage", bytes("""{"id":"${"a".repeat(200)}"}"""))
        assertEquals("", SessionTracker.snapshot().single().remoteId)

        // A body that is only an HTTP head: no id, no title, no crash.
        val headOnly = bytes("POST /v1/messages HTTP/1.1\r\nHost: h\r\n\r\n")
        assertEquals("", SessionTracker.payloadIdOf(headOnly))
        assertEquals("", SessionTracker.titleOf(headOnly))
        SessionTracker.noteResponse("s-garbage", headOnly)
        assertEquals("(client key)", SessionTracker.snapshot().single().displayLabel)
    }

    @Test
    fun titleStillExtractedAndCapsApplied() {
        val body = bytes(
            """{"model":"m","messages":[{"role":"user","content":[
               {"type":"text","text":"  Fix   my json please and then some extra words here"}]}]}"""
        )
        assertEquals("Fix my json please and then some extra w…", SessionTracker.titleOf(body))
        assertEquals("", SessionTracker.titleOf(null))
        assertEquals("", SessionTracker.titleOf(bytes("""{"x":1}""")))
        // A title passed straight to note() is capped too.
        SessionTracker.note("s-cap", "zen", "(client key)", "m", "h", 1000, "y".repeat(500))
        val s = SessionTracker.snapshot().single()
        assertEquals(SessionTracker.MAX_TITLE_CHARS + 1, s.title.length)
        assertTrue(s.title.endsWith("…"))
        // A blank title on re-note must not erase a known one.
        SessionTracker.note("s-cap", "zen", "(client key)", "m", "h", 1000, "")
        assertEquals(SessionTracker.MAX_TITLE_CHARS + 1, SessionTracker.snapshot().single().title.length)
    }

    // ---- id lands on the session --------------------------------------

    @Test
    fun noteResponseStampsHarvestedIdOntoSession() {
        SessionTracker.note("s1", "zen", "(client key)", "claude", "api.anthropic.com", 1000)
        SessionTracker.noteResponse(
            "s1",
            bytes("""{"id":"msg_01Stamped","usage":{"input_tokens":5}}"""),
            mapOf("request-id" to "req_header")
        )
        val s = SessionTracker.snapshot().single()
        assertEquals("msg_01Stamped", s.remoteId)
        assertEquals(SessionTracker.SOURCE_MSG, s.remoteIdSource)
        assertEquals("msg_01Stamped", s.displayLabel)
        // Provider/key/model/title survive the response-time update.
        assertEquals("zen", s.providerId)
        assertEquals("(client key)", s.keyLabel)
        assertEquals(1000L, s.startedMs)

        // An SSE stream on the same session keeps the payload id.
        SessionTracker.noteResponse(
            "s1",
            bytes("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_01Stream\"}}\n\n")
        )
        assertEquals("msg_01Stream", SessionTracker.snapshot().single().remoteId)

        // Unknown session: no-op, no new entry, no throw.
        SessionTracker.noteResponse("never-noted", bytes("""{"id":"msg_01Orphan"}"""))
        assertEquals(1, SessionTracker.size())
        // An id may also be supplied at note() time.
        SessionTracker.note("s2", "or", "key-b", "gpt", "h", 2000, remoteId = "chatcmpl-Noted")
        assertEquals("chatcmpl-Noted", SessionTracker.snapshot().first { it.sessionId == "s2" }.remoteId)
        // Blank remoteId on a later note keeps the harvested one.
        SessionTracker.noteResponse("s2", null, mapOf("request-id" to "req_only"))
        assertEquals("chatcmpl-Noted", SessionTracker.snapshot().first { it.sessionId == "s2" }.remoteId)
    }

    // ---- per-session token usage --------------------------------------

    @Test
    fun perSessionUsageAccumulates() {
        SessionTracker.note("u1", "zen", "(client key)", "claude", "api.anthropic.com", 1000)
        SessionTracker.noteUsage("u1", longArrayOf(100, 20, 50, 10))
        SessionTracker.noteUsage("u1", longArrayOf(7, 3, 0, 2))
        val s = SessionTracker.snapshot().single()
        assertEquals(107L, s.inputTokens)
        assertEquals(23L, s.outputTokens)
        assertEquals(50L, s.cacheReadTokens)
        assertEquals(12L, s.cacheWriteTokens)
        assertEquals(130L, s.totalTokens())

        // A key rotation mid-request must not reset the tallies.
        SessionTracker.note("u1", "openrouter", "key-b", "claude", "openrouter.ai", 1000)
        SessionTracker.noteUsage("u1", longArrayOf(1, 1, 0, 0))
        val after = SessionTracker.snapshot().single()
        assertEquals(108L, after.inputTokens)
        assertEquals("openrouter", after.providerId)
        assertEquals(1000L, after.startedMs)

        // No-ops: unknown session, null, wrong quad size, all-zero.
        SessionTracker.noteUsage("nope", longArrayOf(1, 1, 1, 1))
        SessionTracker.noteUsage(null, longArrayOf(1, 1, 1, 1))
        SessionTracker.noteUsage("u1", null)
        SessionTracker.noteUsage("u1", longArrayOf(1, 2, 3))
        SessionTracker.noteUsage("u1", LongArray(0))
        SessionTracker.noteUsage("u1", longArrayOf(0, 0, 0, 0))
        val final = SessionTracker.snapshot().single()
        assertEquals(108L, final.inputTokens)
        assertEquals(24L, final.outputTokens)
        assertEquals(1, SessionTracker.size())
    }

    // ---- bounded state -------------------------------------------------

    @Test
    fun lruCapEvictsOldestBeyondMax() {
        val cap = SessionTracker.MAX_SESSIONS
        assertTrue("cap should be a sane bound", cap in 8..1024)
        for (i in 0 until cap + 25) {
            SessionTracker.note("s$i", "zen", "key-$i", "m", "h", 1000L + i)
        }
        assertEquals(cap, SessionTracker.size())
        val live = SessionTracker.snapshot().map { it.sessionId }.toSet()
        // The 25 oldest were evicted, the newest survive.
        for (i in 0 until 25) assertTrue("s$i should be evicted", "s$i" !in live)
        for (i in 25 until cap + 25) assertTrue("s$i should survive", "s$i" in live)
    }

    @Test
    fun touchKeepsSessionAliveAcrossEviction() {
        val cap = SessionTracker.MAX_SESSIONS
        for (i in 0 until cap) {
            SessionTracker.note("t$i", "zen", "key", "m", "h", 1000L + i)
        }
        // Re-note + read the oldest so it is no longer the LRU victim.
        SessionTracker.note("t0", "zen", "key", "m", "h", 1000L)
        assertNotNull(SessionTracker.snapshot().firstOrNull { it.sessionId == "t0" })
        SessionTracker.note("tNew", "zen", "key", "m", "h", 9999L)
        val live = SessionTracker.snapshot().map { it.sessionId }.toSet()
        assertTrue("t0 was touched, it must not be the victim", "t0" in live)
        assertTrue("t1 is now the oldest", "t1" !in live)
        assertEquals(cap, SessionTracker.size())
    }

    @Test
    fun tapCapKeepsHugeInputBounded() {
        // A body past the 256 KB tap cap is a truncated prefix that can't
        // yield an id, and must not be parsed or buffered.
        val huge = StringBuilder("{\"id\":\"msg_01NeverRead\",\"pad\":\"")
        while (huge.length < SessionTracker.TAP_CAP_BYTES + 4096) huge.append('x')
        huge.append("\"}")
        val raw = bytes(huge.toString())
        assertTrue(raw.size > SessionTracker.TAP_CAP_BYTES)
        // Over-cap payload is never parsed (it is a truncated prefix) …
        assertEquals("", SessionTracker.payloadIdOf(raw))
        // … but an independent header id is still harvested.
        assertEquals("req_big", SessionTracker.responseId(raw, mapOf("request-id" to "req_big")))
        // A huge body with no id anywhere: still a clean "".
        assertEquals("", SessionTracker.payloadIdOf(ByteArray(SessionTracker.TAP_CAP_BYTES + 1) { 'q'.code.toByte() }))
        // A huge SSE stream with a real id near the front still works —
        // the cap is on input size, not on frame count.
        val bigStream = buildString {
            append("event: message_start\ndata: {\"message\":{\"id\":\"msg_01BigStream\"}}\n\n")
            while (length < SessionTracker.TAP_CAP_BYTES / 2) append("event: ping\ndata: {\"t\":1}\n\n")
        }.toByteArray()
        assertEquals("msg_01BigStream", SessionTracker.payloadIdOf(bigStream))
    }

    @Test
    fun clearLifecycleRemovesBothStateAndUsage() {
        SessionTracker.note("c1", "zen", "(client key)", "m", "h", 1000, remoteId = "msg_01Gone")
        SessionTracker.noteUsage("c1", longArrayOf(5, 5, 0, 0))
        SessionTracker.clear("c1")
        assertEquals(0, SessionTracker.size())
        // Post-clear hooks are inert, not crashes.
        SessionTracker.noteUsage("c1", longArrayOf(1, 1, 0, 0))
        SessionTracker.noteResponse("c1", bytes("""{"id":"msg_01Late"}"""))
        SessionTracker.noteEvent("c1", "noop")
        assertEquals(0, SessionTracker.size())
        SessionTracker.clearAll()
    }
}
