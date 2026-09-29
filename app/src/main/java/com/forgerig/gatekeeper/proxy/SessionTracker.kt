package com.forgerig.gatekeeper.proxy

/**
 * Which key is running which session: live registry of brokered and
 * provider-matched sessions. Plain-HTTP brokered requests record the
 * proxy-injected key label; CONNECT tunnels record the matched provider
 * with "(client key)" (the client holds its own key inside the tunnel)
 * until MITM inner injection lands. Unmatched hosts are absent.
 *
 * On top of that key↔session mapping this tracks the *identity the CLI
 * agent itself knows about* — the provider's own response id
 * (`msg_01…` for Anthropic, `chatcmpl-…` for OpenAI-compatible, else a
 * `request-id` response header) — plus the token usage attributed to the
 * session, so a session in the dashboard can be matched to an external
 * agent run. Both degrade to today's behaviour when unavailable: the
 * display falls back to the key label and a never-throwing "" id.
 *
 * PRIVACY: the harvested id is provider metadata (an opaque handle), not
 * prompt content — it may be logged and exported with metrics. No new
 * prompt text is captured here; the only body text this file keeps is
 * the pre-existing 40-char conversation title, plus a ≤[MAX_NAME_CHARS]
 * client-supplied session name, which is a label the client chose to
 * publish, not conversation content (see [nameOf]).
 *
 * RAM-only, cleared per session end. Thread-safe. Bounded: at most
 * [MAX_SESSIONS] live sessions (LRU-evicted), the title is capped at
 * [MAX_TITLE_CHARS], and id harvesting reads at most [TAP_CAP_BYTES] of
 * any tap — a leak in this map is the same class of bug already fixed
 * twice in the codebase.
 */
object SessionTracker {
    /** Hard cap on live sessions; the least-recently-touched one is evicted. */
    const val MAX_SESSIONS = 64

    /**
     * Cap on the bytes any body tap is read from (matches the MITM
     * request tap cap in [ProxyService]). Bigger input means a truncated
     * prefix, which can't yield an id anyway.
     */
    const val TAP_CAP_BYTES = 256 * 1024

    /**
     * Conversation-title cap: text chars kept before the ellipsis, so a
     * capped title is [MAX_TITLE_CHARS] + 1 long (unchanged from the
     * original `take(40) + "…"`).
     */
    const val MAX_TITLE_CHARS = 40

    /**
     * Client-supplied name cap, in the same spirit as [MAX_TITLE_CHARS]:
     * a session NAME is a couple of words, so anything longer is a title
     * or (worse) prompt content that a client stuffed into a header.
     */
    const val MAX_NAME_CHARS = 48

    /** Ids are opaque handles; anything longer is content, not an id. */
    const val MAX_ID_CHARS = 64

    /**
     * Session-id cap: `x-session-id` values are conversation handles
     * (UUIDs, gateway tokens), so the alphabet is the handle alphabet of
     * [cleanId] but the cap is wider than [MAX_ID_CHARS] — anything past
     * it is content, not an id.
     */
    const val MAX_SESSION_ID_CHARS = 128

    /** Request header carrying the client's conversation id (see [sessionIdOf]). */
    const val ID_HEADER_KEY = "x-session-id"

    data class SessionInfo(
        val sessionId: String,
        val providerId: String,
        val keyLabel: String,
        val model: String,
        val host: String,
        val startedMs: Long,
        /** Conversation title (first user text) — "" until known. */
        val title: String = "",
        /**
         * Name the CLIENT supplied for this session, capped — "" when it
         * sent none. Set from `x-session-name` / `x-session-title` or from
         * request-body metadata (see [nameOf]); see [displayName] for how
         * it ranks against the other identity sources.
         */
        val clientName: String = "",
        /** Which rung produced [clientName]: "header", "body", or "". */
        val nameSource: String = "",
        /** Latest routing event ("429 → key-b", "spill → openrouter/m") + time. */
        val lastEvent: String = "",
        val lastEventMs: Long = 0L,
        /**
         * Provider-side id harvested from the response payload or a
         * response header ("msg_01…", "chatcmpl-…", "req_…"). "" when
         * nothing harvestable was seen — see [displayLabel].
         */
        val remoteId: String = "",
        /** Provider-side id kind, for the UI ("msg", "chatcmpl", "header", ""). */
        val remoteIdSource: String = "",
        /** Tokens credited to this session (see [noteUsage]). */
        val inputTokens: Long = 0L,
        val outputTokens: Long = 0L,
        val cacheReadTokens: Long = 0L,
        val cacheWriteTokens: Long = 0L,
        /** Last time this session was written or read (LRU ordering). */
        val touchedMs: Long = 0L
    ) {
        /**
         * What the session list shows as the session's identity: the
         * harvested id when there is one, else the key label ("(client
         * key)" inside a tunnel) — never blank, never the raw session
         * UUID, which is meaningless outside the proxy.
         *
         * Superseded for display by [displayName], which also honours a
         * client-supplied name; kept as the compact id-or-key form.
         */
        val displayLabel: String
            get() = remoteId.ifBlank { keyLabel }

        /**
         * The session's display NAME, resolved by this precedence — the
         * single place the contract lives (see [resolveName]):
         *
         *  1. [clientName] from the request header `x-session-name`
         *     (alias `x-session-title`) — the only source where the human
         *     picked the label, so it wins outright.
         *  2. [clientName] from request-body metadata
         *     (`metadata.session_name` and friends) — also chosen by the
         *     human, but weaker than a header: bodies are proxied
         *     verbatim upstream, so a name there is convention, not
         *     contract.
         *  3. [remoteId] (`msg_…` / `chatcmpl-…` / `request-id`) — not a
         *     name, but it is the stable correlator: it is what the CLI
         *     agent prints in its own logs, so a proxy row can be joined
         *     to the agent run. Preferred over the key label because
         *     "(client key)" is identical on every row and makes the
         *     list unreadable.
         *  4. `(client key) @ host` — the historical fallback, used only
         *     when nothing above exists (plain CONNECT, no client name,
         *     no harvestable id).
         *
         * Never blank.
         */
        val displayName: String get() = resolveName(clientName, remoteId, keyLabel, host, sessionId)

        /**
         * Short one-line description of what the session is: the
         * conversation title (first user turn, capped at
         * [MAX_TITLE_CHARS]), else the model, else the provider, else the
         * host. Never blank — a session must always be describable.
         */
        val description: String get() = describe(title, model, providerId, host)

        /** in + out, for the "tokens" column. */
        fun totalTokens(): Long = inputTokens + outputTokens
    }

    /**
     * The name contract as one pure function, so precedence is testable
     * and both [SessionInfo.displayName] and the UI agree on it.
     *
     * Rungs, highest first:
     *  1. [clientName] — header `x-session-name` (alias
     *     `x-session-title`), else request-body metadata. The client
     *     chose it, so nothing may override it.
     *  2. [remoteId] — the provider's own response id, the stable
     *     correlator with the CLI agent's own logs.
     *  3. `(client key) @ host` — the historical last resort.
     *
     * [host] is optional: the UI passes `providerId/model` for brokered
     * sessions (which is what it used to show) and the bare host for
     * tunnels. Blank anywhere in rung 3 falls back to the key label alone.
     */
    fun resolveName(clientName: String, remoteId: String, keyLabel: String, host: String = "", sessionId: String = ""): String {
        if (clientName.isNotBlank()) return clientName
        if (remoteId.isNotBlank()) return remoteId
        if (sessionId.isNotBlank()) return sessionId
        val key = keyLabel.ifBlank { "(client key)" }
        return if (host.isNotBlank()) "$key @ $host" else key
    }

    /**
     * Short description, pure: the conversation title wins; otherwise the
     * most specific thing known about the session (model, then provider,
     * then host). Never returns "".
     */
    fun describe(title: String, model: String = "", providerId: String = "", host: String = ""): String =
        listOf(title, model, providerId, host).firstOrNull { it.isNotBlank() } ?: "session"

    /**
     * The client's session NAME for one request, in the precedence the
     * dashboard renders: header [NAME_HEADER_KEYS] first (an explicit,
     * out-of-band declaration), then request-body [BODY_NAME_KEYS]
     * (metadata convention). "" when the client sent none — the caller
     * then falls through to the harvested id, per [resolveName].
     *
     * [body] may be a bare JSON body or a tapped MITM request (HTTP head
     * + body): [jsonBody] strips the head, same as [titleOf].
     */
    fun nameOf(headers: Map<String, String>?, body: ByteArray? = null): String {
        val fromHeader = headerNameOf(headers)
        if (fromHeader.isNotBlank()) return fromHeader
        return bodyNameOf(body)
    }

    /**
     * [nameOf] for a raw tapped request: the head's headers and the body
     * both count, so the MITM path (which only has the tap bytes) gets
     * the same contract as the plain-HTTP path.
     */
    fun nameFromRequestHead(tap: ByteArray?): String {
        if (tap == null || tap.isEmpty()) return ""
        if (indexOfHeaderEnd(tap) < 0) return nameOf(null, tap)
        return nameOf(headMap(tap), tap)
    }

    /**
     * [sessionIdOf] for a raw tapped request: the MITM path only holds the
     * tap bytes, and needs the same id contract as the plain-HTTP path so
     * both merge every turn of one conversation onto one session row.
     */
    fun sessionIdFromRequestHead(tap: ByteArray?): String {
        if (tap == null || tap.isEmpty()) return ""
        if (indexOfHeaderEnd(tap) < 0) return ""
        return sessionIdOf(headMap(tap))
    }

    /**
     * Header map of a tapped request, keys lowercased, first value wins
     * (mirrors what the plain-HTTP parser sees). null when the tap holds no
     * complete head.
     */
    fun headMap(tap: ByteArray): Map<String, String>? {
        val headEnd = indexOfHeaderEnd(tap)
        if (headEnd < 0) return null
        val head = String(tap, 0, headEnd, Charsets.ISO_8859_1)
        val headers = HashMap<String, String>()
        head.lineSequence().drop(1).forEach { line ->
            val i = line.indexOf(':')
            if (i <= 0) return@forEach
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) headers.putIfAbsent(k, v)
        }
        return headers
    }

    /**
     * `x-session-name` (alias `x-session-title`), case-insensitive and
     * precedence-ordered; "" when absent or unusable ([cleanName]).
     */
    fun headerNameOf(headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty()) return ""
        val lower = HashMap<String, String>(headers.size * 2)
        for ((k, v) in headers) {
            val lk = k.lowercase()
            if (lk !in lower) lower[lk] = v
        }
        for (key in NAME_HEADER_KEYS) {
            cleanName(lower[key])?.let { return it }
        }
        return ""
    }

    /**
     * The client's conversation id out of the request headers —
     * [ID_HEADER_KEY], case-insensitive; "" when absent or unusable
     * ([cleanId] gated at [MAX_SESSION_ID_CHARS]). The caller swaps the
     * per-request UUID for this so every request in one conversation
     * lands on a single session row.
     */
    fun sessionIdOf(headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty()) return ""
        val lower = HashMap<String, String>(headers.size * 2)
        for ((k, v) in headers) {
            val lk = k.lowercase()
            if (lk !in lower && v.isNotBlank()) lower[lk] = v
        }
        return cleanId(lower[ID_HEADER_KEY], MAX_SESSION_ID_CHARS) ?: ""
    }

    /**
     * Name out of the request body's `metadata` object, else the top-level
     * [BODY_NAME_KEYS]. Agents that already pass conversation metadata
     * (`metadata.session_name` on OpenAI-compatible gateways,
     * `metadata.name` / `metadata.title` on others) name the run there;
     * "" when they do not. Reads at most [TAP_CAP_BYTES] and never throws.
     */
    fun bodyNameOf(body: ByteArray?): String {
        if (body == null || body.isEmpty() || body.size > TAP_CAP_BYTES) return ""
        val json = jsonBody(body)
        if (json.isEmpty()) return ""
        return try {
            val o = org.json.JSONObject(json.toString(Charsets.UTF_8))
            val meta = o.optJSONObject("metadata")
            val found = BODY_NAME_KEYS.firstNotNullOfOrNull { k -> cleanName(meta?.optString(k, "")) }
                ?: TOP_LEVEL_NAME_KEYS.firstNotNullOfOrNull { k -> cleanName(o.optString(k, "")) }
            found ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * A usable client name: trimmed, whitespace-collapsed, control
     * characters dropped, capped at [MAX_NAME_CHARS]. Rejects the empty
     * string and anything that is only punctuation — the gate that stops
     * a prompt fragment from becoming a session's headline.
     */
    internal fun cleanName(raw: String?): String? {
        if (raw == null) return null
        val v = raw.map { if (it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (v.isEmpty() || v.length > MAX_NAME_CHARS * 4) return null
        if (v.none { it.isLetterOrDigit() }) return null
        return if (v.length <= MAX_NAME_CHARS) v else v.take(MAX_NAME_CHARS).trimEnd() + "…"
    }

    /**
     * Access-ordered so the snapshot's own reads can't reorder the LRU,
     * guarded by this object's monitor ([note] etc. are `@Synchronized`).
     * Never call out to [ProxyMetrics] from here: `recordUsage` is
     * `@Synchronized` on that object and calls [noteUsage] — a call back
     * the other way would be a lock-order inversion.
     */
    private val sessions =
        object : LinkedHashMap<String, SessionInfo>(16, 0.75f, true) {}

    /**
     * @param remoteId harvested provider id, if already known (blank
     *   keeps any id a previous [note]/[noteResponse] stored).
     * @param title conversation title, capped; blank keeps the previous.
     * @param name client-supplied session name ([nameOf]), capped; blank
     *   keeps the previous, so a mid-request re-note (key rollover) that
     *   has no name cannot erase one already harvested.
     * @param nameSource which rung produced [name] ("header" / "body"),
     *   informational; blank keeps the previous tag.
     */
    @Synchronized
    fun note(
        sessionId: String,
        providerId: String,
        keyLabel: String,
        model: String,
        host: String,
        startedMs: Long = System.currentTimeMillis(),
        title: String = "",
        remoteId: String = "",
        name: String = "",
        nameSource: String = ""
    ) {
        // Preserve the original start time (and title, unless a new one
        // arrives) across key rotations mid-request.
        val prev = sessions[sessionId]
        val name0 = cleanName(name)
        sessions[sessionId] = SessionInfo(
            sessionId, providerId, keyLabel, model, host,
            prev?.startedMs ?: startedMs,
            capTitle(title).ifBlank { prev?.title ?: "" },
            name0 ?: prev?.clientName ?: "",
            nameSource.ifBlank { prev?.nameSource ?: "" },
            prev?.lastEvent ?: "",
            prev?.lastEventMs ?: 0L,
            (cleanId(remoteId) ?: "").ifBlank { prev?.remoteId ?: "" },
            prev?.remoteIdSource ?: "",
            prev?.inputTokens ?: 0L,
            prev?.outputTokens ?: 0L,
            prev?.cacheReadTokens ?: 0L,
            prev?.cacheWriteTokens ?: 0L,
            System.currentTimeMillis()
        )
        evictOverflow()
    }

    /**
     * Rename a live session from a client-supplied name (the
     * `x-session-name` header arriving on a later request of the same
     * conversation): updates [SessionInfo.clientName] and stamps
     * [SessionInfo.nameSource] "header". No-op for an unknown session
     * or a blank/unusable name ([cleanName]) — a rename must never
     * erase a name another source already harvested.
     */
    @Synchronized
    fun renameSession(sessionId: String, name: String) {
        if (sessionId.isEmpty()) return
        val cleaned = cleanName(name) ?: return
        val prev = sessions[sessionId] ?: return
        sessions[sessionId] = prev.copy(
            clientName = cleaned,
            nameSource = "header",
            touchedMs = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun rekey(oldId: String, newId: String) {
        if (oldId == newId || oldId.isEmpty() || newId.isEmpty()) return
        val prev = sessions.remove(oldId)
        if (prev == null) return
        val existing = sessions[newId]
        sessions[newId] = if (existing != null) {
            prev.copy(
                sessionId = newId,
                inputTokens = prev.inputTokens + existing.inputTokens,
                outputTokens = prev.outputTokens + existing.outputTokens,
                cacheReadTokens = prev.cacheReadTokens + existing.cacheReadTokens,
                cacheWriteTokens = prev.cacheWriteTokens + existing.cacheWriteTokens,
                touchedMs = System.currentTimeMillis()
            )
        } else {
            prev.copy(sessionId = newId, touchedMs = System.currentTimeMillis())
        }
        evictOverflow()
        android.util.Log.d("SessionTracker", "rekey: done, new sessionId=${sessions[newId]?.sessionId}")
    }

    fun hashOf(text: String): String {
        if (text.isEmpty()) return ""
        var h = -3750763034362895579L
        for (c in text) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return h.toString(16)
    }

    /** Stamp a routing event (429, rollover, spill) onto a live session. */
    @Synchronized
    fun noteEvent(sessionId: String, text: String) {
        val prev = sessions[sessionId] ?: return
        sessions[sessionId] = prev.copy(
            lastEvent = text,
            lastEventMs = System.currentTimeMillis(),
            touchedMs = System.currentTimeMillis()
        )
    }

    /**
     * Response-time hook: harvest the provider id off a response and
     * stamp it onto the session. Payload id wins; a `request-id` header
     * is the fallback. A session that isn't registered yet is a no-op
     * (call [note] first), as is a response with no harvestable id — the
     * label then stays the key label, as before.
     *
     * HOOK for the `ProxyService` owner (that file was not touched). One
     * line per response path, placed where the tap is complete and
     * `r`/`sessionId` are both in scope:
     *
     * ```
     * // plain-HTTP brokered path, inside `resp.use { r -> … }` right
     * // after the tap is fully written (ProxyService.kt:603-617):
     * SessionTracker.noteResponse(sessionId, tap?.toByteArray(), r.headers.toMap())
     *
     * // MITM tunnel path, after t1/t2 join and the note() at
     * // ProxyService.kt:873 (so the session is registered first):
     * SessionTracker.noteResponse(sessionId, tap.toByteArray(), null)
     * ```
     *
     * The tunnel tap is client-request plaintext, so it usually carries
     * no response id — pass the upstream response headers there if the
     * owner also keeps them, otherwise the `null` is harmless.
     * Ordering matters only in that [note] must come first; the existing
     * `SessionTracker.clear(sessionId)` on both `finally` paths
     * (ProxyService.kt:656, :782) is unchanged and still the lifecycle.
     */
    @Synchronized
    fun noteResponse(
        sessionId: String,
        body: ByteArray?,
        headers: Map<String, String>? = null
    ) {
        if (sessionId.isEmpty() || !sessions.containsKey(sessionId)) return
        noteRemoteId(sessionId, body, headers)
    }

    /**
     * Id-only hook for a caller that already has the response tap but no
     * header map. Unknown session = no-op, same rule as [noteResponse]:
     * id and provider/key attribution must land in one [note] call.
     */
    @Synchronized
    fun noteRemoteId(sessionId: String, body: ByteArray?, headers: Map<String, String>? = null) {
        val prev = sessions[sessionId] ?: return
        val (id, source) = harvest(body, headers)
        if (id.isEmpty()) {
            // Keep the entry alive (LRU touch) but change nothing.
            sessions[sessionId] = prev.copy(touchedMs = System.currentTimeMillis())
            return
        }
        // Payload id already known wins over a later header-only find.
        if (prev.remoteId.isNotEmpty() && source == SOURCE_HEADER) {
            sessions[sessionId] = prev.copy(touchedMs = System.currentTimeMillis())
            return
        }
        sessions[sessionId] = prev.copy(
            remoteId = id,
            remoteIdSource = source,
            touchedMs = System.currentTimeMillis()
        )
    }

    /**
     * Per-session token accumulation. [found] is the same reduced quad
     * `(input, output, cacheRead, cacheWrite)` that
     * [ProxyMetrics.recordUsage] credits, so a session's numbers always
     * add up to (a subset of) the global tallies. Null session, wrong
     * quad size, unknown session, or an all-zero quad = no-op, never a
     * throw — this runs inside the metrics choke point.
     *
     * HOOK for the `ProxyMetrics` owner (that file was not touched): add
     * a session-aware overload next to the existing 4-arg `recordUsage`
     * (ProxyMetrics.kt:666) and let it delegate, so the one call site per
     * path stays single:
     *
     * ```
     * fun recordUsage(host: String, model: String, requestId: String?, sessionId: String?, found: LongArray) {
     *     SessionTracker.noteUsage(sessionId, found)   // no-op when sessionId is null
     *     recordUsage(host, model, requestId, found)
     * }
     * ```
     *
     * `requestId` is NOT the session id (they are minted separately), so
     * the session has to be passed explicitly. The `ProxyService` owner
     * then passes `sessionId` — already in scope at all three
     * `recordUsage` call sites (ProxyService.kt:610, :889, :901).
     * Cheaper alternative, if `ProxyMetrics` stays untouched: add
     * `SessionTracker.noteUsage(sessionId, found)` right beside those
     * three existing calls. Do NOT do both (double counting).
     */
    @Synchronized
    fun noteUsage(sessionId: String?, found: LongArray?) {
        if (sessionId == null || sessionId.isEmpty()) return
        if (found == null || found.size != 4) return
        if (found[0] == 0L && found[1] == 0L && found[2] == 0L && found[3] == 0L) return
        val prev = sessions[sessionId] ?: return
        sessions[sessionId] = prev.copy(
            inputTokens = found[0],
            outputTokens = found[1],
            cacheReadTokens = found[2],
            cacheWriteTokens = found[3],
            touchedMs = System.currentTimeMillis()
        )
    }

    /**
     * Conversation title: first user-text block, whitespace-collapsed,
     * 40 chars. Both families carry messages[] with role/content. ""
     * when the body isn't JSON Chat (tunnels, non-LLM).
     */
    fun titleOf(body: ByteArray?): String {
        if (body == null || body.size > 512 * 1024) return ""
        return try {
            val o = org.json.JSONObject(jsonBody(body).toString(Charsets.UTF_8))
            val arr = o.optJSONArray("messages") ?: return ""
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (m.optString("role") != "user") continue
                val c = m.opt("content") ?: continue
                val text = when (c) {
                    is String -> c
                    is org.json.JSONArray -> buildString {
                        for (j in 0 until c.length()) {
                            val p = c.optJSONObject(j) ?: continue
                            if (p.optString("type") in listOf("text", "input_text")) {
                                append(p.optString("text", ""))
                            }
                        }
                    }
                    else -> ""
                }.replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) {
                    return capTitle(text)
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * The provider's own id for this response, or "" — the display then
     * stays the key label. Precedence:
     *
     *  1. top-level `"id"` of a JSON response (`msg_01…` Anthropic,
     *     `chatcmpl-…` OpenAI-compatible);
     *  2. the same field inside an SSE `data:` frame, which for Anthropic
     *     repeats it as `message.id` in `message_start` and for OpenAI
     *     repeats the top-level id in the first chunk;
     *  3. nothing in the payload.
     *
     * Header ids are a separate, lower-priority source — see
     * [responseId] for payload-then-header in one call. Reads at most
     * [TAP_CAP_BYTES]; never throws.
     */
    fun payloadIdOf(body: ByteArray?): String {
        if (body == null || body.isEmpty() || body.size > TAP_CAP_BYTES) return ""
        val text = try {
            String(body, Charsets.UTF_8)
        } catch (_: Exception) {
            return ""
        }
        // SSE first: a stream starts with "event:", which jsonBody()
        // would discard as a non-JSON head.
        idFromSse(text)?.let { return it }
        val json = jsonBody(body)
        if (json.isEmpty()) return ""
        return idFromJson(json.toString(Charsets.UTF_8)) ?: ""
    }

    /**
     * Response id from headers only, in precedence order
     * [HEADER_ID_KEYS] (case-insensitive). "" when absent or malformed.
     */
    fun headerIdOf(headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty()) return ""
        // Case-insensitive, but stable in precedence: look each wanted
        // key up directly instead of scanning (a map may hold dozens of
        // headers, and order would otherwise decide).
        val lower = HashMap<String, String>(headers.size * 2)
        for ((k, v) in headers) {
            val lk = k.lowercase()
            if (lk !in lower && v.isNotBlank()) lower[lk] = v
        }
        for (key in HEADER_ID_KEYS) {
            cleanId(lower[key])?.let { return it }
        }
        return ""
    }

    /**
     * Payload id first, header id second — the one call a response path
     * needs. "" when neither yields a plausible id.
     */
    fun responseId(body: ByteArray?, headers: Map<String, String>? = null): String =
        payloadIdOf(body).ifBlank { headerIdOf(headers) }

    /** The `id` + its source tag ("msg", "chatcmpl", "header") for a response. */
    private fun harvest(body: ByteArray?, headers: Map<String, String>?): Pair<String, String> {
        val payload = payloadIdOf(body)
        if (payload.isNotEmpty()) return payload to idSourceOf(payload)
        val header = headerIdOf(headers)
        return if (header.isEmpty()) "" to "" else header to SOURCE_HEADER
    }

    /**
     * First JSON `id` (or `message.id`) among the `data:` frames of an
     * SSE stream. `event:`/comment/`[DONE]` lines are skipped; at most
     * [MAX_SSE_FRAMES] frames are inspected so a pathological stream
     * can't turn this into a scan of the whole tap.
     */
    private fun idFromSse(text: String): String? {
        if (!text.contains("data:")) return null
        var frames = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line[0] == ':' || line.startsWith("event:")) continue
            if (!line.startsWith("data:")) continue
            if (++frames > MAX_SSE_FRAMES) break
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            idFromJson(payload)?.let { return it }
        }
        return null
    }

    /**
     * `"id"` at the top level, else `"id"` under `"message"` (the
     * Anthropic `message_start` shape), else null. `org.json` is lenient
     * about types, so [cleanId] is the real gate: a non-string id comes
     * back as its JSON text and is rejected there.
     */
    private fun idFromJson(json: String): String? = try {
        val o = org.json.JSONObject(json)
        cleanId(o.optString("id")) ?: cleanId(o.optJSONObject("message")?.optString("id"))
    } catch (_: Exception) {
        null
    }

    /** Cheap shape tag for the UI; not part of precedence. */
    private fun idSourceOf(id: String): String = when {
        id.startsWith("msg_") -> SOURCE_MSG
        id.startsWith("chatcmpl-") -> SOURCE_CHATCMPL
        else -> SOURCE_PAYLOAD
    }

    /**
     * A plausible id: non-blank, ≤[maxChars], and restricted to
     * handle characters. This is what stops garbage — a nested object
     * stringified by `optString`, a prompt fragment, a 4 KB blob — from
     * becoming a session's identity. [maxChars] defaults to
     * [MAX_ID_CHARS]; session ids get the wider [MAX_SESSION_ID_CHARS].
     */
    internal fun cleanId(raw: String?, maxChars: Int = MAX_ID_CHARS): String? {
        val v = raw?.trim() ?: return null
        if (v.isEmpty() || v.length > maxChars) return null
        for (c in v) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '_' || c == '-' || c == '.' || c == ':'
            if (!ok) return null
        }
        return v
    }

    private fun capTitle(t: String): String =
        if (t.length <= MAX_TITLE_CHARS) t else t.take(MAX_TITLE_CHARS) + "…"

    /** Drop the least-recently-touched sessions once over [MAX_SESSIONS]. */
    private fun evictOverflow() {
        val it = sessions.entries.iterator()
        var excess = sessions.size - MAX_SESSIONS
        while (excess > 0 && it.hasNext()) {
            it.next()
            it.remove()
            excess--
        }
    }

    /**
     * The JSON body out of a raw request stream. A tapped MITM request
     * starts with `POST /v1/messages HTTP/1.1` + headers, which makes
     * JSONObject throw ("must begin with '{'") and silently blank every
     * title — so the request line/headers are stripped first. A no-op for
     * callers that already pass a bare body. Pure (unit-tested).
     */
    internal fun jsonBody(raw: ByteArray): ByteArray {
        val headEnd = indexOfHeaderEnd(raw)
        if (headEnd >= 0) return raw.copyOfRange(headEnd, raw.size)
        // No CRLFCRLF yet (tap cut short): a stream that starts with '{'
        // is already the body, anything else has no usable body.
        return if (raw.isNotEmpty() && raw[0] == '{'.code.toByte()) raw else ByteArray(0)
    }

    private fun indexOfHeaderEnd(raw: ByteArray): Int {
        var i = 0
        while (i + 3 < raw.size) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
            ) return i + 4
            i++
        }
        return -1
    }

    /** Per-session lifecycle hook (called on both `finally` paths). */
    @Synchronized
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    @Synchronized
    fun clearAll() {
        sessions.clear()
    }

    /** Oldest-first snapshot for the UI list. */
    @Synchronized
    fun snapshot(): List<SessionInfo> = sessions.values.sortedBy { it.startedMs }

    /** Live session count (LRU tests / diagnostics). */
    @Synchronized
    fun size(): Int = sessions.size

    const val SOURCE_MSG = "msg"
    const val SOURCE_CHATCMPL = "chatcmpl"
    const val SOURCE_PAYLOAD = "payload"
    const val SOURCE_HEADER = "header"

    /** Request-body metadata keys that may carry the session name. */
    const val SOURCE_BODY = "body"

    /**
     * Request-header name keys, highest precedence first. `x-session-name`
     * is the contract; `x-session-title` is the alias accepted because
     * several clients already spell it that way.
     */
    val NAME_HEADER_KEYS = listOf("x-session-name", "x-session-title")

    /** Body-metadata keys, highest precedence first. */
    val BODY_NAME_KEYS = listOf("session_name", "session_title", "name", "title")

    /**
     * Top-level body keys, checked only when there is no `metadata`
     * object — some clients put the name straight on the request.
     */
    val TOP_LEVEL_NAME_KEYS = listOf("session_name", "session_title")

    /** Response-header id keys, highest precedence first. */
    val HEADER_ID_KEYS = listOf("request-id", "x-request-id", "openai-request-id", "x-amzn-requestid")

    /** How many SSE `data:` frames id harvesting will inspect. */
    private const val MAX_SSE_FRAMES = 64
}
