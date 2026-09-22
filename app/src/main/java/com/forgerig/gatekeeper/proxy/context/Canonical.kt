package com.forgerig.gatekeeper.proxy.context

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canonical (wire-format-neutral) transcript model for the context layer.
 *
 * Design contract: [canonicalBytes] is the SINGLE source of truth for
 * hashing, prefix comparison, and upstream rendering. One function, so a
 * canonicalization bug breaks unit tests before it can bust prompt cache.
 *
 * Byte-stability rules (all load-bearing for cache preservation):
 * - Object keys sorted lexicographically, compact separators, UTF-8, LF.
 * - org.json is never used for *emission* (Android's JSONObject does not
 *   guarantee key order); [canonJson] walks values and emits manually.
 */
enum class WireFamily {
    ANTHROPIC,
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    GOOGLE,
    UNKNOWN;

    companion object {
        /** Family detection from the upstream URL path. Never throws. */
        fun detect(url: String): WireFamily {
            val u = url.lowercase()
            return when {
                "/messages" in u -> ANTHROPIC
                "/responses" in u -> OPENAI_RESPONSES
                "/chat/completions" in u -> OPENAI_CHAT
                "/models/" in u && ("gemini" in u || "google" in u) -> GOOGLE
                "generativelanguage" in u -> GOOGLE
                else -> UNKNOWN
            }
        }
    }
}

/** Block roles in canonical space. */
object CRole {
    const val SYSTEM = "system"
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val TOOL_RESULT = "tool_result"
}

/** Block kinds in canonical space. */
object CKind {
    const val TEXT = "text"
    const val TOOL_USE = "tool_use"
    const val TOOL_RESULT = "tool_result"
    const val THINKING = "thinking"
    const val IMAGE_REF = "image_ref"
}

/**
 * One content unit, family-neutral.
 *
 * - TEXT: [text] is the literal text.
 * - TOOL_USE: [name] + [id]; [text] holds canonicalized arguments JSON.
 * - TOOL_RESULT: [id] is the call id; [text] is result content (canonical JSON
 *   when the source was structured).
 * - THINKING: [text] is the thinking text (dropped on families that lack it).
 * - IMAGE_REF: [text] is "mediaType:shaHex-prefix" — bytes are NEVER stored.
 */
data class CBlock(
    val role: String,
    val kind: String,
    val text: String,
    val name: String = "",
    val id: String = "",
    /** Flattened block index that carried a harness cache breakpoint, or -1. */
    var breakTtl: String = ""
) {
    fun stableHash(): Long = fnv1a64(canonicalBytes())
}

/**
 * A parsed harness request in canonical space.
 *
 * [rawParams] preserves every top-level body field OUTSIDE messages/system/
 * tools/model as verbatim canonical fragments, so the frontier render path
 * reproduces harness params (temperature, stream_options, …) exactly.
 * [prefixHash] chains per block: hash(parent.prefixHash, block) — O(1)
 * continuation checks for [ConversationIndex].
 */
data class CanonicalReq(
    val family: WireFamily,
    val model: String,
    val messages: List<CBlock>,
    val tools: String,
    val stream: Boolean,
    val rawParams: Map<String, String>,
    val prefixHash: Long
)

/** FNV-1a 64: deterministic across processes (unlike String.hashCode seeds). */
fun fnv1a64(s: String): Long {
    var h = -3750763034362895579L // offset basis (signed)
    for (c in s) {
        h = h xor c.code.toLong()
        h *= 1099511628211L
    }
    return h
}

fun fnv1a64(bytes: ByteArray): Long {
    var h = -3750763034362895579L
    for (b in bytes) {
        h = h xor (b.toLong() and 0xFF)
        h *= 1099511628211L
    }
    return h
}

/**
 * Canonical JSON emission with sorted keys. Accepts org.json values,
 * Maps, Lists, Strings, Numbers, Booleans, null. Throws on unknown types
 * (fail-closed: caller falls back to passthrough).
 */
fun canonJson(v: Any?): String {
    val sb = StringBuilder()
    writeCanon(v, sb)
    return sb.toString()
}

private fun writeCanon(v: Any?, sb: StringBuilder) {
    when (v) {
        null, JSONObject.NULL -> sb.append("null")
        is String -> {
            sb.append('"')
            for (c in v) when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
            sb.append('"')
        }
        is Boolean -> sb.append(if (v) "true" else "false")
        is Number -> sb.append(v.toString())
        is JSONObject -> {
            val keys = v.keys().asSequence().toList().sorted()
            sb.append('{')
            keys.forEachIndexed { i, k ->
                if (i > 0) sb.append(',')
                writeCanon(k, sb)
                sb.append(':')
                writeCanon(v.get(k), sb)
            }
            sb.append('}')
        }
        is JSONArray -> {
            sb.append('[')
            for (i in 0 until v.length()) {
                if (i > 0) sb.append(',')
                writeCanon(v.get(i), sb)
            }
            sb.append(']')
        }
        is Map<*, *> -> {
            val keys = v.keys.map { it.toString() }.sorted()
            sb.append('{')
            keys.forEachIndexed { i, k ->
                if (i > 0) sb.append(',')
                writeCanon(k, sb)
                sb.append(':')
                writeCanon(v[k], sb)
            }
            sb.append('}')
        }
        is List<*> -> {
            sb.append('[')
            v.forEachIndexed { i, e ->
                if (i > 0) sb.append(',')
                writeCanon(e, sb)
            }
            sb.append(']')
        }
        else -> throw IllegalArgumentException("canonJson: unsupported ${v::class}")
    }
}

/** Canonical bytes of one block — the hashing/rendering atom. */
fun CBlock.canonicalBytes(): String =
    """{"id":${canonJson(id)},"kind":${canonJson(kind)},"name":${canonJson(name)},"role":${canonJson(role)},"text":${canonJson(text)}}"""

/** Chain hash: extend [parent] with one block. */
fun chainHash(parent: Long, b: CBlock): Long {
    var h = parent
    val bytes = b.canonicalBytes().toByteArray(Charsets.UTF_8)
    for (x in bytes) {
        h = h xor (x.toLong() and 0xFF)
        h *= 1099511628211L
    }
    return h
}

/** Prefix hash of a whole block list (seed 0). */
fun prefixHashOf(blocks: List<CBlock>): Long {
    var h = 0L
    for (b in blocks) h = chainHash(h, b)
    return h
}
