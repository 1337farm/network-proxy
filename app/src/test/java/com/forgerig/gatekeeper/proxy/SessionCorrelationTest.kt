package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The session-correlation headers contract: `x-session-id` names the
 * conversation a request belongs to (case-insensitive, validated,
 * capped), and `renameSession` updates the display name on a live
 * session without disturbing the rest of its identity.
 *
 * Synthetic headers only — no device, no network.
 */
class SessionCorrelationTest {

    @Before
    fun reset() {
        SessionTracker.clearAll()
    }

    // ---- sessionIdOf ---------------------------------------------------

    @Test
    fun sessionIdOfExtractsTheHeaderCaseInsensitively() {
        assertEquals("conv-1", SessionTracker.sessionIdOf(mapOf("x-session-id" to "conv-1")))
        assertEquals("conv-1", SessionTracker.sessionIdOf(mapOf("X-Session-Id" to "conv-1")))
        assertEquals("conv-1", SessionTracker.sessionIdOf(mapOf("X-SESSION-ID" to "conv-1")))
        // A UUID-shaped value passes the handle-character gate.
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            SessionTracker.sessionIdOf(mapOf("x-session-id" to "550e8400-e29b-41d4-a716-446655440000"))
        )
        // Other headers don't interfere.
        assertEquals(
            "conv-1",
            SessionTracker.sessionIdOf(mapOf("x-session-name" to "n", "x-session-id" to "conv-1"))
        )
    }

    @Test
    fun sessionIdOfReturnsEmptyWhenAbsentOrNull() {
        assertEquals("", SessionTracker.sessionIdOf(null))
        assertEquals("", SessionTracker.sessionIdOf(emptyMap()))
        assertEquals("", SessionTracker.sessionIdOf(mapOf("x-session-name" to "n")))
        assertEquals("", SessionTracker.sessionIdOf(mapOf("authorization" to "Bearer x")))
    }

    @Test
    fun sessionIdOfRejectsBlankAndInvalidIds() {
        assertEquals("", SessionTracker.sessionIdOf(mapOf("x-session-id" to "   ")))
        // Spaces, slashes and other non-handle characters are content, not an id.
        assertEquals("", SessionTracker.sessionIdOf(mapOf("x-session-id" to "has spaces")))
        assertEquals("", SessionTracker.sessionIdOf(mapOf("x-session-id" to "bad/chars?")))
        assertEquals("", SessionTracker.sessionIdOf(mapOf("x-session-id" to "id\ninjection")))
        // Over the cap.
        assertEquals(
            "",
            SessionTracker.sessionIdOf(mapOf("x-session-id" to "a".repeat(SessionTracker.MAX_SESSION_ID_CHARS + 1)))
        )
        // At the cap: fine.
        assertEquals(
            "a".repeat(SessionTracker.MAX_SESSION_ID_CHARS),
            SessionTracker.sessionIdOf(mapOf("x-session-id" to "a".repeat(SessionTracker.MAX_SESSION_ID_CHARS)))
        )
    }

    // ---- renameSession --------------------------------------------------

    @Test
    fun renameSessionUpdatesNameAndStampsHeaderSourceOnALiveSession() {
        SessionTracker.note("s1", "zen", "key-a", "claude", "api.anthropic.com", 1000)
        SessionTracker.renameSession("s1", "  Oc   proxy ")
        val s = SessionTracker.snapshot().single()
        assertEquals("Oc proxy", s.clientName)
        assertEquals("header", s.nameSource)
        assertEquals("Oc proxy", s.displayName)
    }

    @Test
    fun renameSessionIsNoOpForUnknownSession() {
        SessionTracker.renameSession("ghost", "name")
        assertTrue(SessionTracker.snapshot().isEmpty())
    }

    @Test
    fun renameSessionIsNoOpForBlankOrUnusableName() {
        SessionTracker.note("s1", "zen", "key-a", "claude", "api.anthropic.com", 1000, name = "original")
        SessionTracker.renameSession("s1", "   ")
        SessionTracker.renameSession("s1", "— —")
        val s = SessionTracker.snapshot().single()
        assertEquals("original", s.clientName)
        assertEquals("", s.nameSource)
    }

    @Test
    fun renameSessionCapsAndCleansTheName() {
        SessionTracker.note("s1", "zen", "key-a", "claude", "api.anthropic.com", 1000)
        // Over the name cap but under cleanName's 4x reject threshold:
        // capped, not rejected.
        SessionTracker.renameSession("s1", "x".repeat(100))
        val s = SessionTracker.snapshot().single()
        assertEquals(SessionTracker.MAX_NAME_CHARS + 1, s.clientName.length)
        assertTrue(s.clientName.endsWith("…"))
    }

    @Test
    fun renameSessionPreservesHarvestedIdAndTokens() {
        SessionTracker.note("s1", "zen", "key-a", "claude", "api.anthropic.com", 1000, remoteId = "msg_01X")
        SessionTracker.noteUsage("s1", longArrayOf(5, 7, 0, 0))
        SessionTracker.renameSession("s1", "Oc proxy")
        val s = SessionTracker.snapshot().single()
        assertEquals("msg_01X", s.remoteId)
        assertEquals(5, s.inputTokens)
        assertEquals(7, s.outputTokens)
        assertEquals("zen", s.providerId)
    }

    @Test
    fun renameSessionAppliesToTheSessionTheIdNames() {
        // The correlation flow end to end: two requests carrying the
        // same x-session-id land on one row, and a rename on the
        // second request updates that row.
        SessionTracker.note("conv-1", "zen", "key-a", "claude", "api.anthropic.com", 1000)
        SessionTracker.note("conv-1", "zen", "key-a", "claude", "api.anthropic.com", 1000)
        assertEquals(1, SessionTracker.size())
        SessionTracker.renameSession("conv-1", "Oc proxy")
        val s = SessionTracker.snapshot().single()
        assertEquals("Oc proxy", s.displayName)
        assertEquals("header", s.nameSource)
    }
}
