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
        var cooledUntilMs: Long = 0
    ) {
        fun toJson() = JSONObject()
            .put("id", id).put("label", label).put("secret", secret)
            .put("enabled", enabled).put("failures", failures)
            .put("cooledUntilMs", cooledUntilMs)

        companion object {
            fun fromJson(o: JSONObject) = ApiKey(
                o.getString("id"), o.optString("label", ""),
                o.getString("secret"), o.optBoolean("enabled", true),
                o.optInt("failures", 0), o.optLong("cooledUntilMs", 0)
            )
        }
    }

    data class Provider(
        val id: String,
        val baseUrl: String,
        val authHeader: String = "x-api-key",
        val authScheme: String = "",
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

    fun toJson(): String {
        val o = JSONObject()
        o.put("keyRolloverEnabled", keyRolloverEnabled)
        o.put("routeFailoverEnabled", routeFailoverEnabled)
        o.put("coolDownMs", coolDownMs)
        o.put("providers", JSONArray(providers.values.map { it.toJson() }))
        o.put("routes", JSONArray(routes.values.map { it.toJson() }))
        return o.toString()
    }

    companion object {
        fun blank() = ProviderStore()

        fun fromJson(json: String): ProviderStore {
            val store = ProviderStore()
            val o = JSONObject(json)
            store.keyRolloverEnabled = o.optBoolean("keyRolloverEnabled", true)
            store.routeFailoverEnabled = o.optBoolean("routeFailoverEnabled", true)
            store.coolDownMs = o.optLong("coolDownMs", 60_000)
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
            return store
        }

        fun loadOrBlank(context: Context): ProviderStore {
            val json = CredentialVault.load(context) ?: return blank()
            return try { fromJson(json) } catch (e: Exception) {
                ProxyMetrics.eventError("ProviderStore: corrupt vault JSON, starting blank", e)
                blank()
            }
        }

        /** Well-known provider defaults (no secrets — user adds keys). */
        fun wellKnown(): List<Provider> = listOf(
            Provider("anthropic", "https://api.anthropic.com", "x-api-key"),
            Provider("openai", "https://api.openai.com", "Authorization", "Bearer "),
            Provider("openrouter", "https://openrouter.ai/api/v1", "Authorization", "Bearer "),
            Provider("deepseek", "https://api.deepseek.com", "Authorization", "Bearer "),
            Provider("glm", "https://open.bigmodel.cn/api/paas/v4", "Authorization", "Bearer "),
            // NVIDIA NIM cloud: OpenAI-compatible, Bearer key from build.nvidia.com.
            Provider("nvidia", "https://integrate.api.nvidia.com/v1", "Authorization", "Bearer "),
            // OpenCode Zen gateway. Anthropic-shaped traffic goes to the
            // /messages endpoint with x-api-key; OpenAI-shaped traffic to
            // the gateway root with Bearer. Longest-prefix match wins.
            Provider("opencode-zen-messages", "https://opencode.ai/zen/v1/messages", "x-api-key"),
            Provider("opencode-zen", "https://opencode.ai/zen/v1", "Authorization", "Bearer "),
        )

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

    /** Active key for [providerId], skipping disabled/cooled keys. Null if none usable. */
    @Synchronized
    fun activeKey(providerId: String): Pair<Provider, ApiKey>? {
        val p = providers[providerId] ?: return null
        if (p.keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val usable = p.keys.filter { it.enabled && it.cooledUntilMs <= now }
        if (usable.isEmpty()) return null
        val pick = usable[Math.floorMod(p.cursor + rr.getAndIncrement(), usable.size)]
        return p to pick
    }

    /**
     * Report a key outcome. Hard failures (401/403) disable the key;
     * soft failures (429/5xx) cool it down and advance the cursor so the
     * next request rolls to the next key.
     */
    @Synchronized
    fun report(providerId: String, keyId: String, statusCode: Int) {
        val p = providers[providerId] ?: return
        val key = p.keys.find { it.id == keyId } ?: return
        when (statusCode) {
            401, 403 -> {
                key.enabled = false
                key.failures++
                ProxyMetrics.eventWarning("Key '${key.label}' disabled (HTTP $statusCode)")
            }
            429 -> {
                key.cooledUntilMs = System.currentTimeMillis() + coolDownMs
                key.failures++
                p.cursor++
                ProxyMetrics.eventWarning("Key '${key.label}' cooling ${coolDownMs / 1000}s (429), rolled over")
            }
            in 500..599 -> {
                key.cooledUntilMs = System.currentTimeMillis() + coolDownMs / 2
                key.failures++
                p.cursor++
                ProxyMetrics.eventWarning("Key '${key.label}' cooling (HTTP $statusCode), rolled over")
            }
            else -> if (statusCode in 200..299 && key.failures > 0) key.failures = 0
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
