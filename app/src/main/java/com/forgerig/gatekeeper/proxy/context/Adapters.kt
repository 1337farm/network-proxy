package com.forgerig.gatekeeper.proxy.context

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire-format adapters: harness JSON <-> [CanonicalReq].
 *
 * Disguise contract (see design doc): the frontier render path reproduces
 * the harness body shape exactly except the `model` field; synthesized
 * (shadow) requests are built from golden templates with minted
 * opencode-identical headers ([OpencodeFingerprint]).
 */
interface TranscriptAdapter {
    val family: WireFamily
    /** Parse a harness request body. Throws on unknown shapes → passthrough. */
    fun parse(url: String, body: JSONObject): CanonicalReq
    /**
     * Render an upstream body from canonical blocks. [model] is the upstream
     * model id; harness breakpoints/params preserved per adapter rules.
     */
    fun renderBody(req: CanonicalReq, blocks: List<CBlock>, model: String): ByteArray
}

/**
 * Exact harness fingerprint values (captured from opencode 1.18.31 binary):
 * every OpenRouter-bound request carries these; synthesized requests must
 * mint them verbatim.
 */
object OpencodeFingerprint {
    const val HTTP_REFERER = "https://opencode.ai/"
    const val X_TITLE = "opencode"
    const val X_BILLING_ORIGIN = "OpenCode"
    const val ANTHROPIC_VERSION = "2023-06-01"
    /** Pinned harness UA for minted requests; bump with opencode releases. */
    const val BUN_UA = "Bun/1.3.14"

    /** Mint the attribution headers for a synthesized upstream request. */
    fun attribution(billing: Boolean = true): Map<String, String> {
        val m = mutableMapOf(
            "HTTP-Referer" to HTTP_REFERER,
            "X-Title" to X_TITLE,
            "User-Agent" to BUN_UA
        )
        if (billing) m["X-BILLING-INVOKE-ORIGIN"] = X_BILLING_ORIGIN
        return m
    }
}

/** Shared helpers for adapters. */
internal object AdapterUtil {
    /** Split top-level fields into (messages-ish, tools-ish, model, stream, rest). */
    fun rawParams(body: JSONObject, skip: Set<String>): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (k in body.keys()) {
            if (k !in skip) out[k] = canonJson(body.get(k))
        }
        return out
    }

    fun optStr(o: JSONObject, k: String): String = o.optString(k, "")

    /** Best-effort text extraction from an Anthropic/OpenAI content item. */
    fun contentText(item: Any?): String = when (item) {
        is String -> item
        is JSONObject -> when (item.optString("type")) {
            "text", "input_text" -> item.optString("text", "")
            else -> canonJson(item)
        }
        else -> canonJson(item)
    }
}

/**
 * OpenAI chat-completions adapter (`/chat/completions`).
 * Covers OpenRouter, Nvidia, Zhipu/OpenAI-compatible endpoints.
 */
object OpenAiChatAdapter : TranscriptAdapter {
    override val family = WireFamily.OPENAI_CHAT
    private val skip = setOf("messages", "model", "tools", "stream")

    override fun parse(url: String, body: JSONObject): CanonicalReq {
        val arr = body.optJSONArray("messages")
            ?: throw IllegalArgumentException("openai-chat: missing messages[]")
        val blocks = mutableListOf<CBlock>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val role = m.optString("role", "user")
            when {
                // tool_calls on an assistant message
                m.has("tool_calls") -> {
                    val tc = m.getJSONArray("tool_calls")
                    // leading text content, if any
                    val text = m.opt("content")?.let {
                        if (it is String) it else ""
                    } ?: ""
                    if (text.isNotEmpty()) blocks.add(CBlock(role, CKind.TEXT, text))
                    for (j in 0 until tc.length()) {
                        val c = tc.getJSONObject(j)
                        val fn = c.optJSONObject("function") ?: JSONObject()
                        // arguments arrives as a JSON *string*; canonicalize
                        // the parsed value so render emits it raw (no
                        // double-encoding on round-trip).
                        val argsRaw = fn.optString("arguments", "{}")
                        val argsCanon = try {
                            canonJson(JSONObject(argsRaw))
                        } catch (_: Exception) {
                            canonJson(argsRaw)
                        }
                        blocks.add(
                            CBlock(
                                role, CKind.TOOL_USE,
                                argsCanon,
                                name = fn.optString("name", ""),
                                id = c.optString("id", "")
                            )
                        )
                    }
                }
                // tool result message
                role == "tool" -> blocks.add(
                    CBlock(
                        CRole.TOOL_RESULT, CKind.TOOL_RESULT,
                        AdapterUtil.contentText(m.opt("content")),
                        id = m.optString("tool_call_id", "")
                    )
                )
                else -> {
                    val c = m.opt("content")
                    val text = when (c) {
                        is String -> c
                        is JSONArray -> buildString {
                            for (k in 0 until c.length()) {
                                val part = c.get(k)
                                if (part is JSONObject && part.optString("type")
                                    .startsWith("image")
                                ) {
                                    append("[image_ref]")
                                } else append(AdapterUtil.contentText(part))
                            }
                        }
                        else -> AdapterUtil.contentText(c)
                    }
                    blocks.add(CBlock(role, CKind.TEXT, text))
                }
            }
        }
        val tools = body.optJSONArray("tools")?.let { canonJson(it) } ?: ""
        return CanonicalReq(
            family, body.optString("model", ""), blocks, tools,
            body.optBoolean("stream", false),
            AdapterUtil.rawParams(body, skip), prefixHashOf(blocks)
        )
    }

    override fun renderBody(req: CanonicalReq, blocks: List<CBlock>, model: String): ByteArray {
        val sb = StringBuilder()
        sb.append("{\"messages\":[")
        // Re-group canonical blocks into messages: consecutive TEXT blocks of
        // the same role merge; TOOL_USE attaches to its assistant message;
        // TOOL_RESULT becomes a tool message. Byte-order = block order.
        val msgs = regroup(blocks)
        msgs.forEachIndexed { i, m ->
            if (i > 0) sb.append(',')
            sb.append(m)
        }
        sb.append("],\"model\":")
        sb.append(canonJson(model))
        if (req.tools.isNotEmpty()) {
            sb.append(",\"tools\":")
            sb.append(req.tools)
        }
        // Verbatim harness params (temperature, stream_options, …) in sorted
        // order — deterministic; values byte-identical to what harness sent.
        for (k in req.rawParams.keys.sorted()) {
            sb.append(',')
            sb.append(canonJson(k))
            sb.append(':')
            sb.append(req.rawParams[k])
        }
        if (req.stream) sb.append(",\"stream\":true")
        sb.append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Canonical blocks → OpenAI message JSON fragments. */
    internal fun regroup(blocks: List<CBlock>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            when {
                b.kind == CKind.TOOL_RESULT -> {
                    out.add(
                        "{\"content\":${canonJson(b.text)}," +
                            "\"role\":\"tool\",\"tool_call_id\":${canonJson(b.id)}}"
                    )
                    i++
                }
                b.kind == CKind.TOOL_USE -> {
                    // collect leading assistant text + all consecutive tool uses
                    val texts = mutableListOf<String>()
                    val calls = mutableListOf<CBlock>()
                    var j = i
                    while (j < blocks.size && blocks[j].role == b.role &&
                        (blocks[j].kind == CKind.TEXT || blocks[j].kind == CKind.TOOL_USE)
                    ) {
                        if (blocks[j].kind == CKind.TEXT) texts.add(blocks[j].text)
                        else calls.add(blocks[j])
                        j++
                    }
                    val callsJson = calls.joinToString(",") {
                        // it.text already holds canonical arguments JSON → raw.
                        "{\"function\":{\"arguments\":${it.text}," +
                            "\"name\":${canonJson(it.name)}}," +
                            "\"id\":${canonJson(it.id)}," +
                            "\"type\":\"function\"}"
                    }
                    out.add(
                        "{\"content\":${canonJson(texts.joinToString(""))}," +
                            "\"role\":${canonJson(b.role)}," +
                            "\"tool_calls\":[$callsJson]}"
                    )
                    i = j
                }
                else -> {
                    // merge consecutive same-role TEXT (incl. thinking→text fallback)
                    var j = i
                    val acc = StringBuilder()
                    while (j < blocks.size && blocks[j].role == b.role &&
                        (blocks[j].kind == CKind.TEXT || blocks[j].kind == CKind.THINKING)
                    ) {
                        acc.append(blocks[j].text)
                        j++
                    }
                    out.add(
                        "{\"content\":${canonJson(acc.toString())}," +
                            "\"role\":${canonJson(b.role)}}"
                    )
                    i = j
                }
            }
        }
        return out
    }
}

/**
 * Anthropic Messages adapter (`/messages`).
 *
 * Cache contract: harness breakpoints (`cache_control: {type:"ephemeral"}`)
 * are preserved verbatim per block index ([CBlock.breakTtl]); the proxy
 * never invents breakpoints on the frontier path.
 */
object AnthropicAdapter : TranscriptAdapter {
    override val family = WireFamily.ANTHROPIC
    private val skip = setOf("messages", "system", "model", "tools", "stream")

    override fun parse(url: String, body: JSONObject): CanonicalReq {
        val arr = body.optJSONArray("messages")
            ?: throw IllegalArgumentException("anthropic: missing messages[]")
        val blocks = mutableListOf<CBlock>()
        // system: string or blocks → SYSTEM/TEXT blocks (breakpoint preserved)
        when (val sys = body.opt("system")) {
            is String -> if (sys.isNotEmpty()) blocks.add(CBlock(CRole.SYSTEM, CKind.TEXT, sys))
            is JSONArray -> for (i in 0 until sys.length()) {
                val p = sys.getJSONObject(i)
                blocks.add(
                    CBlock(
                        CRole.SYSTEM, CKind.TEXT, p.optString("text", ""),
                        breakTtl = p.optJSONObject("cache_control")?.optString("type", "")
                            ?: ""
                    )
                )
            }
        }
        val tools = body.optJSONArray("tools")?.let { canonJson(it) } ?: ""
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val role = m.optString("role", "user")
            val content = m.opt("content")
            if (content is String) {
                blocks.add(CBlock(role, CKind.TEXT, content))
                continue
            }
            if (content is JSONArray) {
                for (j in 0 until content.length()) {
                    val part = content.getJSONObject(j)
                    val ttl = part.optJSONObject("cache_control")?.optString("type", "") ?: ""
                    when (part.optString("type")) {
                        "text" -> blocks.add(CBlock(role, CKind.TEXT, part.optString("text", ""), breakTtl = ttl))
                        "thinking" -> blocks.add(CBlock(role, CKind.THINKING, part.optString("thinking", ""), breakTtl = ttl))
                        "tool_use" -> blocks.add(
                            CBlock(
                                role, CKind.TOOL_USE, canonJson(part.opt("input")),
                                name = part.optString("name", ""),
                                id = part.optString("id", ""), breakTtl = ttl
                            )
                        )
                        "tool_result" -> {
                            val c = part.opt("content")
                            val text = if (c is String) c else canonJson(c)
                            blocks.add(
                                CBlock(
                                    CRole.TOOL_RESULT, CKind.TOOL_RESULT, text,
                                    id = part.optString("tool_use_id", ""), breakTtl = ttl
                                )
                            )
                        }
                        "image" -> {
                            val src = part.optJSONObject("source")
                            val ref = (src?.optString("media_type", "") ?: "") + ":" +
                                fnv1a64(canonJson(src)).toString(16)
                            blocks.add(CBlock(role, CKind.IMAGE_REF, ref, breakTtl = ttl))
                        }
                        else -> blocks.add(CBlock(role, CKind.TEXT, canonJson(part), breakTtl = ttl))
                    }
                }
            }
        }
        return CanonicalReq(
            family, body.optString("model", ""), blocks, tools,
            body.optBoolean("stream", false),
            AdapterUtil.rawParams(body, skip), prefixHashOf(blocks)
        )
    }

    override fun renderBody(req: CanonicalReq, blocks: List<CBlock>, model: String): ByteArray {
        val sb = StringBuilder()
        sb.append('{')
        // system first (Anthropic shape): SYSTEM blocks as content blocks
        val sys = blocks.filter { it.role == CRole.SYSTEM }
        val rest = blocks.filter { it.role != CRole.SYSTEM }
        if (sys.isNotEmpty()) {
            sb.append("\"system\":[")
            sys.forEachIndexed { i, b ->
                if (i > 0) sb.append(',')
                sb.append("{\"text\":${canonJson(b.text)},\"type\":\"text\"")
                if (b.breakTtl.isNotEmpty()) sb.append(",\"cache_control\":{\"type\":${canonJson(b.breakTtl)}}")
                sb.append('}')
            }
            sb.append("],")
        }
        sb.append("\"messages\":[")
        // group non-system blocks into messages by role runs; tool_result
        // blocks group into the following user message per Anthropic shape
        var first = true
        var i = 0
        while (i < rest.size) {
            val role = if (rest[i].role == CRole.TOOL_RESULT) CRole.USER else rest[i].role
            if (!first) sb.append(',')
            first = false
            sb.append("{\"content\":[")
            var j = i
            var ci = 0
            while (j < rest.size &&
                (rest[j].role == role || rest[j].role == CRole.TOOL_RESULT)
            ) {
                // role flip user<->tool_result stays in one message; flip to
                // assistant (or anything else) closes it
                if (rest[j].role != role && rest[j].role != CRole.TOOL_RESULT) break
                if (ci++ > 0) sb.append(',')
                sb.append(renderPart(rest[j]))
                j++
            }
            sb.append("],\"role\":${canonJson(role)}}")
            i = j
        }
        sb.append("],\"model\":")
        sb.append(canonJson(model))
        if (req.tools.isNotEmpty()) {
            sb.append(",\"tools\":")
            sb.append(req.tools)
        }
        for (k in req.rawParams.keys.sorted()) {
            sb.append(',')
            sb.append(canonJson(k))
            sb.append(':')
            sb.append(req.rawParams[k])
        }
        if (req.stream) sb.append(",\"stream\":true")
        sb.append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun renderPart(b: CBlock): String {
        val ttl = if (b.breakTtl.isNotEmpty())
            ",\"cache_control\":{\"type\":${canonJson(b.breakTtl)}}" else ""
        return when (b.kind) {
            CKind.TOOL_USE -> "{\"id\":${canonJson(b.id)}," +
                "\"input\":${b.text},\"name\":${canonJson(b.name)}," +
                "\"type\":\"tool_use\"$ttl}"
            CKind.TOOL_RESULT -> "{\"content\":${canonJson(b.text)}," +
                "\"tool_use_id\":${canonJson(b.id)}," +
                "\"type\":\"tool_result\"$ttl}"
            CKind.THINKING -> "{\"thinking\":${canonJson(b.text)}," +
                "\"type\":\"thinking\"$ttl}"
            CKind.IMAGE_REF -> "{\"source\":{\"data\":\"\"," +
                "\"media_type\":\"image/ref\",\"type\":\"base64\"}," +
                "\"type\":\"image\"$ttl}"
            else -> "{\"text\":${canonJson(b.text)},\"type\":\"text\"$ttl}"
        }
    }
}

/** Registry: family → adapter. Responses/Google throw → passthrough for now. */
object Adapters {
    fun forFamily(f: WireFamily): TranscriptAdapter = when (f) {
        WireFamily.OPENAI_CHAT -> OpenAiChatAdapter
        WireFamily.ANTHROPIC -> AnthropicAdapter
        else -> throw UnsupportedOperationException("no adapter for $f (passthrough)")
    }

    fun forUrl(url: String): TranscriptAdapter = forFamily(WireFamily.detect(url))
}
