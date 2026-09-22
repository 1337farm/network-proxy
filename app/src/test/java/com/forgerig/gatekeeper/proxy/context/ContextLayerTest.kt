package com.forgerig.gatekeeper.proxy.context

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-runnable coverage for the phase-1 context layer (pure logic). */
class ContextLayerTest {

    private fun chatBody(): JSONObject = JSONObject(
        """{"model":"meta/muse-spark-1.3-contributor",
        "messages":[
          {"role":"system","content":"You are a coder."},
          {"role":"user","content":"Fix my json"},
          {"role":"assistant","content":"Sure","tool_calls":[
            {"id":"c1","type":"function","function":{"name":"read","arguments":"{\"p\":\"a\"}"}}]},
          {"role":"tool","tool_call_id":"c1","content":"ok"}],
        "temperature":0.2,"stream":true,
        "stream_options":{"include_usage":true}}"""
    )

    private fun anthropicBody(): JSONObject = JSONObject(
        """{"model":"claude-haiku-4-5",
        "system":[{"text":"Sys.","type":"text","cache_control":{"type":"ephemeral"}}],
        "messages":[
          {"role":"user","content":[{"text":"Hi","type":"text"}]},
          {"role":"assistant","content":[{"text":"Hey","type":"text",
            "cache_control":{"type":"ephemeral"}}]}],
        "max_tokens":100}"""
    )

    @Test
    fun canonJsonSortsKeys() {
        assertEquals(
            """{"a":1,"b":[true,null],"c":{"x":"y"}}""",
            canonJson(JSONObject("""{"c":{"x":"y"},"b":[true,null],"a":1}"""))
        )
    }

    @Test
    fun openAiRoundTripIsStable() {
        val req = OpenAiChatAdapter.parse("https://openrouter.ai/api/v1/chat/completions", chatBody())
        assertEquals(WireFamily.OPENAI_CHAT, req.family)
        // system, user, assistant-text, tool_use, tool_result
        assertEquals(5, req.messages.size)
        val rendered = OpenAiChatAdapter.renderBody(req, req.messages, req.model).toString(Charsets.UTF_8)
        val req2 = OpenAiChatAdapter.parse(
            "https://openrouter.ai/api/v1/chat/completions", JSONObject(rendered)
        )
        assertEquals(
            req.messages.map { it.canonicalBytes() },
            req2.messages.map { it.canonicalBytes() }
        )
        // verbatim params survive
        assertTrue(rendered.contains("\"temperature\":0.2"))
        assertTrue(rendered.contains("\"stream_options\":{\"include_usage\":true}"))
    }

    @Test
    fun openAiRenderIsByteStableUnderKeyReorder() {
        val a = chatBody().toString()
        // same logical body, different whitespace/key order at top level
        val reordered = JSONObject(
            """{"stream":true,"temperature":0.2,
            "stream_options":{"include_usage":true},
            "model":"meta/muse-spark-1.3-contributor",
            "messages":[
              {"content":"You are a coder.","role":"system"},
              {"content":"Fix my json","role":"user"},
              {"content":"Sure","role":"assistant","tool_calls":[
                {"type":"function","id":"c1","function":{"arguments":"{\"p\":\"a\"}","name":"read"}}]},
              {"tool_call_id":"c1","role":"tool","content":"ok"}]}"""
        ).toString()
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val r1 = OpenAiChatAdapter.renderBody(
            OpenAiChatAdapter.parse(url, JSONObject(a)),
            OpenAiChatAdapter.parse(url, JSONObject(a)).messages,
            "meta/muse-spark-1.3-contributor"
        )
        val p2 = OpenAiChatAdapter.parse(url, JSONObject(reordered))
        val r2 = OpenAiChatAdapter.renderBody(p2, p2.messages, p2.model)
        assertArrayEquals(r1, r2)
    }

    @Test
    fun anthropicRoundTripPreservesBreakpoints() {
        val url = "https://opencode.ai/zen/v1/messages"
        val req = AnthropicAdapter.parse(url, anthropicBody())
        assertEquals(WireFamily.ANTHROPIC, req.family)
        val sys = req.messages.first { it.role == CRole.SYSTEM }
        assertEquals("ephemeral", sys.breakTtl)
        val rendered = AnthropicAdapter.renderBody(req, req.messages, req.model).toString(Charsets.UTF_8)
        // breakpoints verbatim in output
        assertEquals(2, Regex(""""cache_control":\{"type":"ephemeral"\}""").findAll(rendered).count())
        val req2 = AnthropicAdapter.parse(url, JSONObject(rendered))
        assertEquals(
            req.messages.map { it.canonicalBytes() },
            req2.messages.map { it.canonicalBytes() }
        )
        assertEquals(
            req.messages.map { it.breakTtl },
            req2.messages.map { it.breakTtl }
        )
    }

    @Test
    fun familyDetection() {
        assertEquals(WireFamily.ANTHROPIC, WireFamily.detect("https://opencode.ai/zen/v1/messages"))
        assertEquals(WireFamily.OPENAI_CHAT, WireFamily.detect("https://openrouter.ai/api/v1/chat/completions"))
        assertEquals(WireFamily.OPENAI_CHAT, WireFamily.detect("https://integrate.api.nvidia.com/v1/chat/completions"))
        assertEquals(WireFamily.OPENAI_RESPONSES, WireFamily.detect("https://opencode.ai/zen/v1/responses"))
        assertEquals(WireFamily.UNKNOWN, WireFamily.detect("https://api.github.com/zen"))
    }

    @Test
    fun unsupportedFamilyThrowsForPassthrough() {
        try {
            Adapters.forFamily(WireFamily.OPENAI_RESPONSES)
            throw AssertionError("expected UnsupportedOperationException")
        } catch (e: UnsupportedOperationException) {
            // expected → caller passes through
        }
    }

    @Test
    fun correlationContinuationAndBranching() {
        val index = ConversationIndex(ContextPolicy(true))
        val url = "https://openrouter.ai/api/v1/chat/completions"
        val base = OpenAiChatAdapter.parse(url, chatBody())
        val c1 = index.correlate(base)!!
        // continuation: same transcript + one new turn → same conversation
        val extended = base.copy(messages = base.messages + CBlock("user", CKind.TEXT, "next"))
        val cont = OpenAiChatAdapter.parse(
            url, JSONObject(
                OpenAiChatAdapter.renderBody(base, extended.messages, base.model).toString(Charsets.UTF_8)
            )
        )
        // note: renderBody merges/splits but canonical equality of prefix holds
        val c2 = index.correlate(cont)!!
        assertEquals(c1.id, c2.id)
        // unrelated transcript → new conversation
        val other = base.copy(
            messages = listOf(CBlock("user", CKind.TEXT, "totally different")),
            prefixHash = prefixHashOf(listOf(CBlock("user", CKind.TEXT, "totally different")))
        )
        val c3 = index.correlate(other)!!
        assertTrue(c3.id != c1.id)
        assertEquals(2, index.size())
    }

    @Test
    fun indexEvictsLruUnderCaps() {
        val index = ConversationIndex(ContextPolicy(true, maxConvs = 3))
        val mk = { t: String ->
            CanonicalReq(WireFamily.OPENAI_CHAT, "m", listOf(CBlock("user", CKind.TEXT, t)),
                "", false, emptyMap(), prefixHashOf(listOf(CBlock("user", CKind.TEXT, t))))
        }
        val ids = (1..5).map { index.correlate(mk("conv-$it"))!!.id }
        assertEquals(3, index.size())
        assertEquals(3, ids.toSet().size - 2) // oldest two evicted
    }

    @Test
    fun contextLayerGatedOffByDefault() {
        assertNull(
            ContextLayer.maybeProcess(
                "https://openrouter.ai/api/v1/chat/completions", "POST",
                chatBody().toString().toByteArray()
            )
        )
    }

    @Test
    fun contextLayerFailOpenOnGarbage() {
        ContextLayer.policy = ContextPolicy(true)
        try {
            assertNull(
                ContextLayer.maybeProcess("https://openrouter.ai/api/v1/chat/completions", "POST", "{nope".toByteArray())
            )
            assertNull(ContextLayer.maybeProcess("https://api.github.com/zen", "GET", null))
        } finally {
            ContextLayer.policy = ContextPolicy.DISABLED
        }
    }

    @Test
    fun policyJsonRoundTrip() {
        val p = ContextPolicy(true, 10, 1024, 512, 4)
        val q = ContextPolicy.fromJson(p.toJson())
        assertEquals(p, q)
        assertEquals(ContextPolicy.DISABLED, ContextPolicy.fromJson("{broken"))
    }

    @Test
    fun fingerprintAttribution() {
        val m = OpencodeFingerprint.attribution()
        assertEquals("https://opencode.ai/", m["HTTP-Referer"])
        assertEquals("opencode", m["X-Title"])
        assertEquals("OpenCode", m["X-BILLING-INVOKE-ORIGIN"])
        assertNotNull(m["User-Agent"])
    }
}
