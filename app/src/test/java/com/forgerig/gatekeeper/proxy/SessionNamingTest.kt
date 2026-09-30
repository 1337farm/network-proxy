package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session-NAME contract, rung by rung: an explicit client name
 * (`x-session-name` / `x-session-title` header) beats a name in request
 * body metadata, which beats the harvested provider id, which beats the
 * historical `(client key) @ host`. Also pins the caps (name, title) and
 * the guarantee that a description is never blank.
 *
 * Synthetic bytes only — no fixtures, no device, no network. The row
 * *rendering* is Android-only and is not asserted here.
 */
class SessionNamingTest {

    @Before
    fun reset() {
        SessionTracker.clearAll()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun bodyWithMetadata(name: String) =
        bytes("""{"model":"m","metadata":{"session_name":"$name"},"messages":[]}""")

    // ---- rung 1: header ------------------------------------------------

    @Test
    fun headerNameIsTheName() {
        assertEquals(
            "Oc proxy",
            SessionTracker.nameOf(mapOf("x-session-name" to "Oc proxy"), null)
        )
        // Alias, and case-insensitivity of the lookup.
        assertEquals(
            "Oc proxy",
            SessionTracker.nameOf(mapOf("X-Session-Title" to "Oc proxy"), null)
        )
    }

    @Test
    fun headerBeatsBodyMetadata() {
        val name = SessionTracker.nameOf(
            mapOf("x-session-name" to "Header wins"),
            bodyWithMetadata("Body loses")
        )
        assertEquals("Header wins", name)
        // Header keys are precedence-ordered among themselves.
        assertEquals(
            "primary",
            SessionTracker.nameOf(
                mapOf("x-session-title" to "alias", "x-session-name" to "primary"), null
            )
        )
    }

    @Test
    fun unusableHeaderValueFallsThroughToTheBody() {
        // Blank, punctuation-only and over-long header values are not
        // names — a prompt fragment must not become a session headline.
        assertEquals("Body name", SessionTracker.nameOf(mapOf("x-session-name" to "   "), bodyWithMetadata("Body name")))
        assertEquals("Body name", SessionTracker.nameOf(mapOf("x-session-name" to "— —"), bodyWithMetadata("Body name")))
        assertEquals("", SessionTracker.nameOf(mapOf("x-session-name" to "y".repeat(400)), null))
    }

    // ---- rung 2: body metadata -----------------------------------------

    @Test
    fun bodyMetadataIsTheNameWhenNoHeaderIsSent() {
        assertEquals("Body name", SessionTracker.nameOf(null, bodyWithMetadata("Body name")))
        assertEquals("Named", SessionTracker.nameOf(emptyMap(), bodyWithMetadata("Named")))
        // The other metadata spellings clients use.
        for (key in SessionTracker.BODY_NAME_KEYS) {
            val b = bytes("""{"metadata":{"$key":"From $key"}}""")
            assertEquals("From $key", SessionTracker.nameOf(null, b))
        }
        // … and the top-level fallback when there is no metadata object.
        assertEquals("Top level", SessionTracker.nameOf(null, bytes("""{"session_name":"Top level"}""")))
    }

    @Test
    fun requestBodyFramingIsReadFromTheHead() {
        // Attributing a session while the tunnel is still open depends on
        // knowing where THIS request ends, so the framing headers have to be
        // parsed case-insensitively and a malformed one has to degrade to
        // "unknown" rather than to a bogus byte count.
        fun head(extra: String) = bytes("POST /v1/chat/completions HTTP/1.1\r\nHost: h\r\n$extra\r\n")
        assertEquals(1234L, ProxyService.contentLengthForTest(head("content-length: 1234")))
        assertEquals(0L, ProxyService.contentLengthForTest(head("Content-Length: 0")))
        assertEquals(7L, ProxyService.contentLengthForTest(head("CONTENT-LENGTH:   7  ")))
        assertTrue(ProxyService.isChunkedForTest(head("transfer-encoding: chunked")))
        assertTrue(ProxyService.isChunkedForTest(head("Transfer-Encoding: Chunked")))
        // No framing at all -> the caller must fall back to reading to EOF.
        assertEquals(-1L, ProxyService.contentLengthForTest(head("accept: */*")))
        assertFalse(ProxyService.isChunkedForTest(head("accept: */*")))
        // Malformed values must not be trusted as a length.
        assertEquals(-1L, ProxyService.contentLengthForTest(head("content-length: banana")))
        assertEquals(-1L, ProxyService.contentLengthForTest(head("content-length: -5")))
        // The request line must never be mistaken for a header.
        assertEquals(-1L, ProxyService.contentLengthForTest(bytes("GET content-length: 9 HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun framedBodyIsRelayedVerbatimAndAttributionCanRunEarly() {
        // The whole point: relay exactly Content-Length bytes and stop, so
        // the caller can attribute the session without waiting for the peer
        // to close. Everything must arrive upstream byte-for-byte, framing
        // included, or the provider rejects the request.
        val body = """{"model":"anthropic/claude-sonnet-4","messages":[{"role":"user","content":"hi"}]}"""
        val head = bytes("POST /v1/chat/completions HTTP/1.1\r\nHost: openrouter.ai\r\ncontent-length: ${body.length}\r\n\r\n")
        val raw = head + bytes(body)
        val out = java.io.ByteArrayOutputStream()
        val tap = java.io.ByteArrayOutputStream()
        val counter = java.util.concurrent.atomic.AtomicLong()
        val moved = ProxyService.relayExactlyForTest(
            java.io.ByteArrayInputStream(raw, head.size, raw.size - head.size), out, counter, tap, 1 shl 20,
            ProxyService.contentLengthForTest(head)
        )
        assertEquals(body.length.toLong(), moved)
        assertEquals(body, String(out.toByteArray(), Charsets.ISO_8859_1))
        assertEquals(body.length.toLong(), counter.get())
        // The tap holds head+body, which is what the model is sniffed from.
        assertEquals("anthropic/claude-sonnet-4", ProxyMetrics.sniffModel(head + tap.toByteArray(), head.size + tap.size()))
    }

    @Test
    fun chunkedBodyIsRelayedAndStopsAtTheTerminator() {
        val out = java.io.ByteArrayOutputStream()
        val counter = java.util.concurrent.atomic.AtomicLong()
        val wire = bytes(
            "5\r\nhello\r\n" +
            "6;ext=1\r\n world\r\n" +
            "0\r\n\r\n"
        )
        val moved = ProxyService.relayChunkedForTest(
            java.io.ByteArrayInputStream(wire), out, counter, null, 1 shl 20
        )
        // Upstream received the ORIGINAL chunked head, so the whole body must
        // be forwarded byte-for-byte, framing included. Dropping a size line or
        // the terminating 0-line would leave the provider reading a malformed
        // chunked stream.
        assertEquals(wire.size.toLong(), moved)
        assertEquals(
            "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n",
            String(out.toByteArray(), Charsets.ISO_8859_1)
        )
        assertEquals(wire.size.toLong(), counter.get())
    }

    @Test
    fun chunkedBodyWithATrailerIsForwardedAndStopsBeforeTheNextRequest() {
        // A keep-alive connection carries more than one request. The relay
        // must stop at the blank line that ends the trailer section and must
        // NOT consume a single byte of the request that follows, or the next
        // request upstream starts mid-line and fails to parse.
        val wire = bytes(
            "4\r\nabcd\r\n" +
            "0\r\n" +
            "X-Checksum: deadbeef\r\n" +
            "\r\n" +
            "POST /v1/messages HTTP/1.1\r\nHost: openrouter.ai\r\n\r\n"
        )
        val out = java.io.ByteArrayOutputStream()
        val counter = java.util.concurrent.atomic.AtomicLong()
        val input = java.io.ByteArrayInputStream(wire)
        val moved = ProxyService.relayChunkedForTest(
            input, out, counter, null, 1 shl 20
        )
        val expectedBody = "4\r\nabcd\r\n0\r\nX-Checksum: deadbeef\r\n\r\n"
        assertEquals(expectedBody.length.toLong(), moved)
        assertEquals(expectedBody, String(out.toByteArray(), Charsets.ISO_8859_1))
        // The next request is still fully readable by the caller.
        val rest = ByteArray(64)
        val n = input.read(rest)
        assertEquals("POST /v1/messages HTTP/1.1\r\nHost: openrouter.ai\r\n\r\n",
            String(rest, 0, n, Charsets.ISO_8859_1))
    }

    @Test
    fun aTruncatedBodyStopsWithoutHangingOrOverForwarding() {
        // Peer hangs up mid-body: relay what arrived, report the short count,
        // never pad and never block.
        val out = java.io.ByteArrayOutputStream()
        val moved = ProxyService.relayExactlyForTest(
            java.io.ByteArrayInputStream(bytes("short")), out,
            java.util.concurrent.atomic.AtomicLong(), null, 1 shl 20, 5000
        )
        assertEquals(5L, moved)
        assertEquals("short", String(out.toByteArray(), Charsets.ISO_8859_1))
    }

    @Test
    fun connectUuidIsMigratedToTheClientIdWithNameAndModel() {
        // Reproduces what attributeRequest() now does on the MITM path, in
        // order: the CONNECT-time UUID row is moved onto the client's own id
        // BEFORE the rename, so the rename has a row to land on. Doing it in
        // the other order is what left rows stuck on a bare UUID with no
        // name and no model for the whole session.
        val uuid = "conn-uuid-1"
        SessionTracker.note(uuid, "openrouter", "(proxy)", "", "openrouter.ai")

        val headId = "mitm-conv-9"
        val headName = "Oc proxy"
        val model = "anthropic/claude-sonnet-4"

        // 1. migrate the row
        SessionTracker.rekey(uuid, headId)
        // 2. rename on the NEW id
        SessionTracker.renameSession(headId, headName)
        // 3. note model + name on the NEW id
        SessionTracker.note(headId, "openrouter", "(client key)", model, "openrouter.ai", name = headName)

        val rows = SessionTracker.snapshot()
        // The old UUID row must be gone, not orphaned alongside.
        assertTrue("uuid row orphaned", rows.none { it.sessionId == uuid })
        val row = rows.single { it.sessionId == headId }
        assertEquals(headName, row.clientName)
        assertEquals("header", row.nameSource)
        assertEquals(model, row.model)
        assertEquals("openrouter.ai", row.host)
        // displayName must not fall back to the id
        assertEquals(headName, row.displayName)
    }

    @Test
    fun aLaterTurnRenamesWithoutErasingTheModel() {
        // The whole point of the header: the tab gets renamed mid-session.
        // A rename on turn two must update the name and leave the model the
        // first turn discovered intact.
        SessionTracker.note("mitm-conv-9", "openrouter", "(client key)", "anthropic/claude-sonnet-4", "openrouter.ai", name = "Untitled")
        SessionTracker.renameSession("mitm-conv-9", "Oc proxy")
        val row = SessionTracker.snapshot().single { it.sessionId == "mitm-conv-9" }
        assertEquals("Oc proxy", row.clientName)
        assertEquals("anthropic/claude-sonnet-4", row.model)
        // Usage credited after the rename lands on the renamed row.
        SessionTracker.noteUsage("mitm-conv-9", longArrayOf(10, 20, 30, 40))
        val after = SessionTracker.snapshot().single { it.sessionId == "mitm-conv-9" }
        assertEquals(10L, after.inputTokens)
        assertEquals(20L, after.outputTokens)
        assertEquals("Oc proxy", after.clientName)
    }

    @Test
    fun mitmHeadSplitStillYieldsIdNameAndModel() {
        // The full MITM extraction chain, on one realistic request: split
        // the head off, strip it, then read id/name from the ORIGINAL head
        // and the model from head+body. The model is the one that regressed
        // when the head was split out for stripping, because `"model"` only
        // ever appears in the body.
        val body = """{"model":"anthropic/claude-sonnet-4","messages":[{"role":"user","content":"hi"}]}"""
        val raw = bytes(
            "POST /api/v1/chat/completions HTTP/1.1\r\nHost: openrouter.ai\r\n" +
                "x-session-id: mitm-conv-9\r\n" +
                "x-session-name: Oc proxy\r\n" +
                "content-type: application/json\r\n\r\n" + body
        )
        val head = ProxyService.readRequestHeadForTest(raw)!!
        // id + name come off the head, before it is sanitized.
        assertEquals("mitm-conv-9", SessionTracker.sessionIdFromRequestHead(head))
        assertEquals("Oc proxy", SessionTracker.nameFromRequestHead(head))
        // The forwarded head must not carry them.
        val forwarded = String(ProxyService.stripSessionHeadersForTest(head), Charsets.ISO_8859_1)
        assertFalse(forwarded.contains("mitm-conv-9"))
        assertFalse(forwarded.contains("Oc proxy"))
        assertTrue(forwarded.contains("Host: openrouter.ai"))
        // The model is sniffed from head + body together.
        val tapped = raw
        assertEquals("anthropic/claude-sonnet-4", ProxyMetrics.sniffModel(tapped, tapped.size))
        // The head alone has no model, which is why the sniff needs the body.
        assertEquals("", ProxyMetrics.sniffModel(head, head.size))
    }

    @Test
    fun requestHeadIsSplitAtTheFirstBlankLine() {
        // The MITM rewrite consumes exactly the head and relays the rest as
        // body. Over-reading here would eat the payload and silently break
        // every request; under-reading would forward a truncated head.
        val body = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""
        val raw = bytes("POST /v1/chat/completions HTTP/1.1\r\nHost: h\r\ncontent-type: application/json\r\n\r\n" + body)
        val head = ProxyService.readRequestHeadForTest(raw)!!
        val headText = String(head, Charsets.ISO_8859_1)
        assertTrue(headText.endsWith("\r\n\r\n"))
        // The body must NOT be inside the head, otherwise stripping the head
        // would rewrite the payload too.
        assertFalse(headText.contains("\"messages\""))
        val rest = raw.copyOfRange(head.size, raw.size)
        assertEquals(body, String(rest, Charsets.ISO_8859_1))

        // A head-only request terminates cleanly at the blank line.
        val headOnly = ProxyService.readRequestHeadForTest(
            bytes("GET /ca.pem HTTP/1.1\r\nHost: h\r\n\r\n")
        )!!
        assertTrue(String(headOnly, Charsets.ISO_8859_1).endsWith("\r\n\r\n"))

        // Nothing at all: null, so the caller relays nothing.
        assertNull(ProxyService.readRequestHeadForTest(ByteArray(0)))
        // Peer closed mid-head: return what we got rather than throwing.
        val partial = ProxyService.readRequestHeadForTest(bytes("POST / HTTP/1.1\r\nHost: h\r\n"))
        assertEquals("POST / HTTP/1.1\r\nHost: h\r\n", String(partial!!, Charsets.ISO_8859_1))
        // Cap hit before the terminator: bounded, not unbounded.
        val capped = ProxyService.readRequestHeadForTest(
            bytes("POST / HTTP/1.1\r\nHost: h\r\n\r\n"), cap = 12
        )!!
        assertEquals(12, capped.size)
    }

    @Test
    fun sessionIdIsReadOffTheMitmRequestHead() {
        // The MITM branch never parses headers into a map, so the id has to
        // be recovered from the tapped bytes or every tunneled turn lands on
        // its own row.
        val raw = bytes(
            "POST /v1/chat/completions HTTP/1.1\r\nHost: api.openai.com\r\n" +
                "x-session-id: conv-42\r\ncontent-type: application/json\r\n\r\n" +
                """{"model":"m"}"""
        )
        assertEquals("conv-42", SessionTracker.sessionIdFromRequestHead(raw))
        // Case-insensitive, like every other header read in the proxy.
        val upper = bytes("POST / HTTP/1.1\r\nX-Session-Id: conv-7\r\n\r\n")
        assertEquals("conv-7", SessionTracker.sessionIdFromRequestHead(upper))
        // Absent, blank, invalid and head-less all stay empty rather than
        // inventing an id.
        assertEquals("", SessionTracker.sessionIdFromRequestHead(null))
        assertEquals("", SessionTracker.sessionIdFromRequestHead(ByteArray(0)))
        assertEquals(
            "",
            SessionTracker.sessionIdFromRequestHead(bytes("POST / HTTP/1.1\r\nHost: h\r\n\r\n"))
        )
        assertEquals(
            "",
            SessionTracker.sessionIdFromRequestHead(bytes("POST / HTTP/1.1\r\nx-session-id:  \r\n\r\n"))
        )
        // A tap cut before CRLFCRLF has no parsable head.
        assertEquals(
            "",
            SessionTracker.sessionIdFromRequestHead(bytes("POST /v1/chat HTTP/1.1\r\nx-session-id: c\r\n"))
        )
        // Body-only tap (no head at all) must not throw.
        assertEquals("", SessionTracker.sessionIdFromRequestHead(bytes("""{"x-session-id":"c"}""")))
    }

    @Test
    fun sessionHeadersAreStrippedFromTheForwardedHead() {
        // The MITM branch re-emits the client's head upstream; without an
        // explicit strip the provider would see proxy-internal metadata.
        val out = ProxyService.stripSessionHeadersForTest(
            bytes(
                "POST /v1/messages HTTP/1.1\r\nHost: h\r\n" +
                    "x-session-id: conv-1\r\n" +
                    "X-Session-Name: Oc proxy\r\n" +
                    "x-session-title: alias\r\n" +
                    "authorization: Bearer real\r\n\r\n"
            )
        )
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse(text.contains("x-session-id", ignoreCase = true))
        assertFalse(text.contains("x-session-name", ignoreCase = true))
        assertFalse(text.contains("x-session-title", ignoreCase = true))
        // Everything else survives untouched, request line and terminator
        // included, so the request still parses upstream.
        assertTrue(text.startsWith("POST /v1/messages HTTP/1.1"))
        assertTrue(text.contains("Host: h"))
        assertTrue(text.contains("authorization: Bearer real"))
        assertTrue(text.endsWith("\r\n\r\n"))
        // A head with nothing to strip comes back unchanged.
        val plain = bytes("POST / HTTP/1.1\r\nHost: h\r\n\r\n")
        assertArrayEquals(plain, ProxyService.stripSessionHeadersForTest(plain))
    }

    @Test
    fun bodyNameSurvivesAnHttpRequestHead() {
        // The MITM path only has the tapped request, head included.
        val raw = bytes(
            "POST /v1/chat/completions HTTP/1.1\r\nHost: api.openai.com\r\n" +
                "content-type: application/json\r\n\r\n" +
                """{"metadata":{"session_name":"Oc proxy"}}"""
        )
        assertEquals("Oc proxy", SessionTracker.nameFromRequestHead(raw))
        // Header inside the tap counts too, and still wins.
        val withHeader = bytes(
            "POST /v1/messages HTTP/1.1\r\nHost: h\r\nx-session-name: From head\r\n\r\n" +
                """{"metadata":{"session_name":"From body"}}"""
        )
        assertEquals("From head", SessionTracker.nameFromRequestHead(withHeader))
    }

    @Test
    fun noNameAnywhereYieldsEmpty() {
        assertEquals("", SessionTracker.nameOf(null, null))
        assertEquals("", SessionTracker.nameOf(null, bytes("""{"model":"m"}""")))
        assertEquals("", SessionTracker.nameOf(null, bytes("not json at all")))
        assertEquals("", SessionTracker.nameFromRequestHead(null))
        assertEquals("", SessionTracker.nameFromRequestHead(ByteArray(0)))
        // A truncated tap (head cut before CRLFCRLF) must not throw.
        assertEquals("", SessionTracker.nameFromRequestHead(bytes("POST /v1/chat HTTP/1.1\r\nHost: h\r\n")))
        // Over the tap cap: never parsed.
        val huge = bytes("""{"metadata":{"session_name":"${"x".repeat(SessionTracker.TAP_CAP_BYTES)}"}}""")
        assertEquals("", SessionTracker.nameOf(null, huge))
    }

    // ---- rungs 3 and 4: resolution ------------------------------------

    @Test
    fun precedenceNameBeatsIdBeatsKeyLabel() {
        assertEquals("Oc proxy", SessionTracker.resolveName("Oc proxy", "msg_01ABC", "(client key)", "opencode.ai"))
        assertEquals("msg_01ABC", SessionTracker.resolveName("", "msg_01ABC", "(client key)", "opencode.ai"))
        assertEquals(
            "(client key) @ opencode.ai",
            SessionTracker.resolveName("", "", "(client key)", "opencode.ai")
        )
        // Nothing at all still yields something readable.
        assertEquals("(client key)", SessionTracker.resolveName("", "", "", ""))
    }

    @Test
    fun harvestedIdIsShownWhenTheClientSendsNoName() {
        // The degradation the owner asked for: no name → the id the CLI
        // agent logs, NOT the generic "(client key)" string.
        SessionTracker.note("s1", "zen", "(client key)", "claude", "api.anthropic.com", 1000)
        SessionTracker.noteResponse("s1", bytes("""{"id":"msg_01RealId"}"""))
        val s = SessionTracker.snapshot().single()
        assertEquals("msg_01RealId", s.displayName)
        assertNotEquals("(client key)", s.displayName)
        assertTrue(s.displayName.startsWith("msg_"))
    }

    @Test
    fun clientNameReachesTheSessionAndSurvivesRerouting() {
        SessionTracker.note(
            "s2", "zen", "key-a", "claude", "api.anthropic.com", 1000,
            name = SessionTracker.nameOf(mapOf("x-session-name" to "Oc proxy"), null)
        )
        assertEquals("Oc proxy", SessionTracker.snapshot().single().displayName)
        // A key rollover re-notes without a name — it must not erase it.
        SessionTracker.note("s2", "openrouter", "key-b", "claude", "openrouter.ai", 1000)
        val after = SessionTracker.snapshot().single()
        assertEquals("Oc proxy", after.displayName)
        assertEquals("openrouter", after.providerId)
        // The row always has an id line to correlate on, even unnamed.
        assertEquals("", after.remoteId)
        assertEquals("key-b @ openrouter.ai",
            SessionTracker.resolveName("", after.remoteId, after.keyLabel, after.host))
    }

    @Test
    fun twoUnnamedSessionsStopLookingIdentical() {
        // Two tunnels, no client name, different provider ids.
        SessionTracker.note("a", "zen", "(client key)", "", "opencode.ai", 1000, remoteId = "msg_01A")
        SessionTracker.note("b", "zen", "(client key)", "", "opencode.ai", 1000, remoteId = "chatcmpl-B")
        val names = SessionTracker.snapshot().map { it.displayName }
        assertEquals(listOf("msg_01A", "chatcmpl-B"), names)
    }

    // ---- caps and descriptions ----------------------------------------

    @Test
    fun namesAreCappedWhitespaceCollapsedAndNeverBlank() {
        val long = "Implement the two consolidated statistics groups in the proxy dashboard"
        val n = SessionTracker.nameOf(mapOf("x-session-name" to long), null)
        assertEquals(SessionTracker.MAX_NAME_CHARS + 1, n.length)
        assertTrue(n.endsWith("…"))
        assertEquals("Oc proxy", SessionTracker.nameOf(mapOf("x-session-name" to "  Oc \n proxy "), null))
        // Control characters (a header-injection attempt) are flattened.
        assertEquals("Oc proxy", SessionTracker.nameOf(mapOf("x-session-name" to "Oc\tproxy"), null))
    }

    @Test
    fun descriptionIsTheCappedFirstUserTurnAndNeverBlank() {
        val body = bytes(
            """{"messages":[{"role":"user","content":[
               {"type":"text","text":"  Fix   my json please and then some extra words here"}]}]}"""
        )
        assertEquals("Fix my json please and then some extra w…", SessionTracker.titleOf(body))
        // Non-chat body: no title, so the description falls back to the
        // model, then the provider, then the host — never "".
        assertEquals("claude", SessionTracker.describe("", "claude", "zen", "api.anthropic.com"))
        assertEquals("zen", SessionTracker.describe("", "", "zen", "api.anthropic.com"))
        assertEquals("api.anthropic.com", SessionTracker.describe("", "", "", "api.anthropic.com"))
        assertEquals("session", SessionTracker.describe("", "", "", ""))
    }

    @Test
    fun sessionInfoDescriptionIsNeverBlank() {
        SessionTracker.note("d1", "", "(client key)", "", "", 1000)
        val s = SessionTracker.snapshot().single()
        assertTrue(s.description.isNotBlank())
        // And the rendered name is never blank either.
        assertTrue(s.displayName.isNotBlank())
    }
}
