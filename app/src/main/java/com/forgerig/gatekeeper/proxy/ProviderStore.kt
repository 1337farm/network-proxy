package com.forgerig.gatekeeper.proxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provider + key registry ("we are the provider").
 *
 * Model: each provider (anthropic, openai, openrouter, …) owns an ordered
 * pool of API keys. The proxy injects the active key per request and rolls
 * over on 429 / 401 / 5xx: mark the key cooling, advance the cursor, retry
 * the same request with the next key. Clean rollover, no client changes.
 *
 * Custom routes ("we.register a custom provider and model"): a route maps
 * OUR model id (e.g. `nanogatekeeper/auto`) to an ordered list of
 * (provider, model) legs. The proxy rewrites the request body to the first
 * healthy leg; on failure it fails over to the next leg — Copilot-style
 * routing under the hood, invisible to the client.
 *
 * Persisted as JSON through [CredentialVault] (encrypted at rest).
 */
class ProviderStore private constructor() {
    data class ApiKey(
        val id: String,
        val label: String,
        val secret: String,
        var enabled: Boolean = true,
        var failures: Int = 0,
        var cooledUntilMs: Long = 0,
        /** Hits on this key in the current UTC day (resets on rollover). */
        var dayHits: Int = 0,
        /** Day bucket (yyyy-MM-dd UTC) that dayHits belongs to. */
        var dayBucket: String = "",
        /** Manual "spent for today" flag: cools until UTC midnight. */
        var dayLimitUntilMs: Long = 0,
        /** Consecutive 429s on this key (escalates cooldown). */
        var streak429: Int = 0
    ) {
        fun toJson() = JSONObject()
            .put("id", id).put("label", label).put("secret", secret)
            .put("enabled", enabled).put("failures", failures)
            .put("cooledUntilMs", cooledUntilMs)
            .put("dayHits", dayHits).put("dayBucket", dayBucket)
            .put("dayLimitUntilMs", dayLimitUntilMs)
            .put("streak429", streak429)

        companion object {
            fun fromJson(o: JSONObject) = ApiKey(
                o.getString("id"), o.optString("label", ""),
                o.getString("secret"), o.optBoolean("enabled", true),
                o.optInt("failures", 0), o.optLong("cooledUntilMs", 0),
                o.optInt("dayHits", 0), o.optString("dayBucket", ""),
                o.optLong("dayLimitUntilMs", 0), o.optInt("streak429", 0)
            )
        }
    }

    data class Provider(
        val id: String,
        var baseUrl: String,
        var authHeader: String = "x-api-key",
        var authScheme: String = "",
        val keys: MutableList<ApiKey> = mutableListOf(),
        var cursor: Int = 0
    ) {
        fun toJson() = JSONObject()
            .put("id", id).put("baseUrl", baseUrl)
            .put("authHeader", authHeader).put("authScheme", authScheme)
            .put("cursor", cursor)
            .put("keys", JSONArray(keys.map { it.toJson() }))

        companion object {
            fun fromJson(o: JSONObject): Provider {
                val keys = mutableListOf<ApiKey>()
                val arr = o.optJSONArray("keys") ?: JSONArray()
                for (i in 0 until arr.length()) keys.add(ApiKey.fromJson(arr.getJSONObject(i)))
                return Provider(
                    o.getString("id"), o.getString("baseUrl"),
                    o.optString("authHeader", "x-api-key"),
                    o.optString("authScheme", ""),
                    keys, o.optInt("cursor", 0)
                )
            }
        }
    }

    data class RouteLeg(val providerId: String, val model: String) {
        fun toJson() = JSONObject().put("provider", providerId).put("model", model)
        companion object {
            fun fromJson(o: JSONObject) = RouteLeg(o.getString("provider"), o.getString("model"))
        }
    }

    data class Route(val modelId: String, val legs: List<RouteLeg>) {
        fun toJson() = JSONObject()
            .put("model", modelId)
            .put("legs", JSONArray(legs.map { it.toJson() }))
        companion object {
            fun fromJson(o: JSONObject): Route {
                val legs = mutableListOf<RouteLeg>()
                val arr = o.optJSONArray("legs") ?: JSONArray()
                for (i in 0 until arr.length()) legs.add(RouteLeg.fromJson(arr.getJSONObject(i)))
                return Route(o.getString("model"), legs)
            }
        }
    }

    val providers = mutableMapOf<String, Provider>()
    val routes = mutableMapOf<String, Route>()
    var keyRolloverEnabled = true
    var routeFailoverEnabled = true
    var coolDownMs: Long = 60_000
    /** When true, Zen 429s escalate: streak×base capped at hard ceiling. */
    var escalate429 = true
    /** Hard ceiling for a single escalated 429 cooldown. */
    var max429CoolDownMs: Long = 30 * 60_000
    /** Soft daily-hit threshold on Zen keys: exceeding cools till UTC midnight. */
    var zenDayHits: Int = 200
    /**
     * Strict LLM-only mode: refuse non-provider hosts with 403 instead of
     * tunneling them opaque. Default false (connectivity-preserving):
     * off-allowlist traffic tunnels byte-identical with no key/MITM/scan.
     */
    var llmOnlyStrict: Boolean = false

    /** Hosts that get full broker treatment (from providers' base URLs). */
    fun llmHosts(): Set<String> =
        LlmPolicy.hostsFromBaseUrls(providers.values.map { it.baseUrl })

    /** UTC day bucket "yyyy-MM-dd" + ms until next UTC midnight. */
    fun utcDay(): Pair<String, Long> {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val bucket = "%04d-%02d-%02d".format(y, m, d)
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return bucket to (cal.timeInMillis - System.currentTimeMillis())
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("keyRolloverEnabled", keyRolloverEnabled)
        o.put("routeFailoverEnabled", routeFailoverEnabled)
        o.put("coolDownMs", coolDownMs)
        o.put("escalate429", escalate429)
        o.put("max429CoolDownMs", max429CoolDownMs)
        o.put("zenDayHits", zenDayHits)
        o.put("llmOnlyStrict", llmOnlyStrict)
        o.put("providers", JSONArray(providers.values.map { it.toJson() }))
        o.put("routes", JSONArray(routes.values.map { it.toJson() }))
        return o.toString()
    }

    companion object {
        fun blank() = ProviderStore()

        /**
         * Well-known provider defaults (no secrets — user adds keys).
         * Single source of truth: [ensureWellKnown] merges these into any
         * loaded store, so fresh installs AND upgrades converge without a
         * manual "Seed" step.
         */
        fun wellKnown(): List<Provider> = listOf(
            Provider("opencode-zen", "https://opencode.ai/zen/v1", "Authorization", "Bearer "),
            Provider("nvidia", "https://integrate.api.nvidia.com/v1", "Authorization", "Bearer "),
            Provider("z-ai", "https://open.bigmodel.cn/api/paas/v4", "Authorization", "Bearer "),
            // NOTE: no Google entry — no usable free LLM tier, so it is not
            // seeded. A custom google provider still works (and stays
            // allowlisted) if added manually with keys.
        )

        /**
         * Merge well-known providers into [store]: add missing ones, refresh
         * baseUrl/auth wiring on existing ones (never touches keys/cursor),
         * drop legacy ids no longer well-known ONLY when they hold no keys
         * (user data is never deleted). Returns the count added.
         */
        fun ensureWellKnown(store: ProviderStore): Int {
            var added = 0
            for (wk in wellKnown()) {
                val cur = store.providers[wk.id]
                if (cur == null) {
                    store.providers[wk.id] = wk
                    added++
                } else {
                    // Refresh wiring (e.g. endpoint moved) — keys stay put.
                    cur.baseUrl = wk.baseUrl
                    cur.authHeader = wk.authHeader
                    cur.authScheme = wk.authScheme
                }
            }
            val known = wellKnown().map { it.id }.toSet()
            for (id in store.providers.keys.toList()) {
                val p = store.providers[id]!!
                if (id !in known && p.keys.isEmpty()) {
                    store.providers.remove(id)
                }
            }
            return added
        }

        fun fromJson(json: String): ProviderStore {
            val store = ProviderStore()
            val o = JSONObject(json)
            store.keyRolloverEnabled = o.optBoolean("keyRolloverEnabled", true)
            store.routeFailoverEnabled = o.optBoolean("routeFailoverEnabled", true)
            store.coolDownMs = o.optLong("coolDownMs", 60_000)
            store.escalate429 = o.optBoolean("escalate429", true)
            store.max429CoolDownMs = o.optLong("max429CoolDownMs", 30 * 60_000)
            store.zenDayHits = o.optInt("zenDayHits", 200)
            store.llmOnlyStrict = o.optBoolean("llmOnlyStrict", false)
            o.optJSONArray("providers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = Provider.fromJson(arr.getJSONObject(i))
                    store.providers[p.id] = p
                }
            }
            o.optJSONArray("routes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = Route.fromJson(arr.getJSONObject(i))
                    store.routes[r.modelId] = r
                }
            }
            ensureWellKnown(store)
            return store
        }

        fun loadOrBlank(context: Context): ProviderStore {
            val json = CredentialVault.load(context)
            if (json == null) {
                // Fresh install: start with providers present, keys empty.
                val store = blank()
                ensureWellKnown(store)
                return store
            }
            return try { fromJson(json) } catch (e: Exception) {
                ProxyMetrics.eventError("ProviderStore: corrupt vault JSON, starting blank", e)
                blank().also { ensureWellKnown(it) }
            }
        }

        /**
         * Longest baseUrl-prefix match for [targetUrl]. Path-aware so
         * gateway sub-endpoints (e.g. Zen /messages vs root) resolve to the
         * right credential style.
         */
        fun matchProvider(store: ProviderStore, targetUrl: String): Provider? {
            var best: Provider? = null
            var bestLen = -1
            for (p in store.providers.values) {
                val base = p.baseUrl.trimEnd('/')
                if (targetUrl.startsWith(base, ignoreCase = true) && base.length > bestLen) {
                    // Guard: prefix must end on a path boundary.
                    val rest = targetUrl.substring(base.length)
                    if (rest.isEmpty() || rest[0] == '/' || rest[0] == '?') {
                        best = p
                        bestLen = base.length
                    }
                }
            }
            return best
        }

        /**
         * Retarget [oldUrl] onto the leg provider: keep the request path,
         * avoiding duplication when the base already carries it.
         */
        fun retarget(oldUrl: String, legBaseUrl: String): String {
            val old = java.net.URL(oldUrl)
            val leg = java.net.URL(legBaseUrl.trimEnd('/'))
            var path = old.file.ifEmpty { "/" }
            val basePath = leg.path.trimEnd('/')
            if (basePath.isNotEmpty() && path.startsWith(basePath)) {
                path = path.substring(basePath.length).ifEmpty { "/" }
            }
            val newPath = basePath + (if (path.startsWith("/")) path else "/$path")
            return java.net.URL(old.protocol, leg.host, leg.port, newPath).toString()
        }
    }

    fun save(context: Context) = CredentialVault.save(context, toJson())

    // ---- Key selection + rollover ----
    private val rr = AtomicInteger(0)

    /** Active key for [providerId], skipping disabled/cooled/day-limited keys. Null if none usable. */
    @Synchronized
    fun activeKey(providerId: String): Pair<Provider, ApiKey>? {
        val p = providers[providerId] ?: return null
        if (p.keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val (bucket, _) = utcDay()
        val usable = p.keys.filter { k ->
            if (k.dayBucket != bucket) { k.dayBucket = bucket; k.dayHits = 0 }
            k.enabled && k.cooledUntilMs <= now && k.dayLimitUntilMs <= now
        }
        if (usable.isEmpty()) return null
        val pick = usable[Math.floorMod(p.cursor + rr.getAndIncrement(), usable.size)]
        return p to pick
    }

    /** Record a 2xx on [key]: resets 429 streak, counts a Zen day-hit. */
    @Synchronized
    fun recordHit(provider: Provider, key: ApiKey) {
        if (key.failures > 0) key.failures = 0
        key.streak429 = 0
        if (provider.id.startsWith("opencode-zen")) {
            val (bucket, tillMidnight) = utcDay()
            if (key.dayBucket != bucket) { key.dayBucket = bucket; key.dayHits = 0 }
            key.dayHits++
            if (zenDayHits > 0 && key.dayHits >= zenDayHits) {
                key.dayLimitUntilMs = System.currentTimeMillis() + tillMidnight
                ProxyMetrics.eventWarning(
                    "Zen key '${key.label}' hit $zenDayHits/day — parked till UTC midnight"
                )
            }
        }
    }

    /** Manually park [keyId] until UTC midnight ("spent for today"). Returns false if not found. */
    @Synchronized
    fun parkTillMidnight(providerId: String, keyId: String): Boolean {
        val k = providers[providerId]?.keys?.find { it.id == keyId } ?: return false
        val (_, tillMidnight) = utcDay()
        k.dayLimitUntilMs = System.currentTimeMillis() + tillMidnight
        ProxyMetrics.eventWarning("Key '${k.label}' parked till UTC midnight (manual)")
        return true
    }

            /** + Honor Retry-After: max(hint, base). Used for 429 AND 503.
             *  Returns the effective cooldown ms so the proxy can sleep offline
             *  instead of hammering the gateway. */
    fun cooldownFor(providerId: String, retryAfterSecs: Long?): Long {
        val p = providers[providerId]
        return if (retryAfterSecs != null && retryAfterSecs > 0) {
            val hint = retryAfterSecs * 1000
            if (p != null) {
                val (_, mid) = utcDay() // unused; keeps call sites uniform
                hint.coerceAtLeast(coolDownMs)
            } else hint
        } else coolDownMs
    }

    /**
     * Report a key outcome. Hard failures (401/403) disable the key;
     * 429s escalate: streak×coolDownMs capped at max429CoolDownMs.
     * Honor [retryAfterSecs]: cooldown = max(hint, computed).
     * 5xx cools half and rolls the cursor. 2xx resets streak + counts day-hit.
     */
    @Synchronized
    fun report(providerId: String, keyId: String, statusCode: Int, retryAfterSecs: Long? = null) {
        val p = providers[providerId] ?: return
        val key = p.keys.find { it.id == keyId } ?: return
        when (statusCode) {
            401, 403 -> {
                key.enabled = false
                key.failures++
                key.streak429 = 0
                ProxyMetrics.eventWarning("Key '${key.label}' disabled (HTTP $statusCode)")
            }
            429 -> {
                key.streak429++
                var cd = coolDownMs
                if (escalate429 && key.streak429 > 1) {
                    cd = (coolDownMs * key.streak429).coerceAtMost(max429CoolDownMs)
                }
                if (retryAfterSecs != null && retryAfterSecs > 0) {
                    cd = (retryAfterSecs * 1000).coerceAtLeast(cd)
                }
                key.cooledUntilMs = System.currentTimeMillis() + cd
                key.failures++
                p.cursor++
                ProxyMetrics.eventWarning(
                    "Key '${key.label}' 429×${key.streak429} cooling ${cd / 1000}s, rolled over"
                )
            }
            503, in 500..599 -> {
                key.streak429 = 0
                var cd = coolDownMs / 2
                if (retryAfterSecs != null && retryAfterSecs > 0) {
                    cd = (retryAfterSecs * 1000).coerceAtLeast(cd)
                }
                key.cooledUntilMs = System.currentTimeMillis() + cd
                key.failures++
                p.cursor++
                ProxyMetrics.eventWarning("Key '${key.label}' cooling ${cd / 1000}s (HTTP $statusCode), rolled over")
            }
            else -> if (statusCode in 200..299) recordHit(p, key)
        }
    }

    /** Header value to inject for [provider] using [key] (scheme-aware). */
    fun authValue(provider: Provider, key: ApiKey): String =
        if (provider.authScheme.isNotEmpty()) provider.authScheme + key.secret else key.secret

    fun summary(): String {
        if (providers.isEmpty()) return "no providers configured"
        return providers.values.joinToString("; ") { p ->
            val usable = p.keys.count { it.enabled }
            "${p.id}: $usable/${p.keys.size} keys live"
        }
    }
}
