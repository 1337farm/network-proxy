package com.forgerig.gatekeeper.proxy

/**
 * The front-door request decision, extracted from [ProxyService] so it can
 * be tested without a Service, a Context, or a socket.
 *
 * A client that treats this proxy as its provider sends an ordinary
 * origin-form request (`POST /v1/chat/completions`, `Host: 127.0.0.1:3128`).
 * Nothing in the request says *which* provider should answer, so that choice
 * has to be made here. Three outcomes, in order:
 *
 *  1. the body's `model` matches a configured route with a usable leg ->
 *     rewrite the model and retarget to that leg's provider;
 *  2. no matching route, but some provider has a usable key -> send it there
 *     unchanged and let the upstream decide whether it knows the model. A
 *     client should not have to know our routing table to get an answer;
 *  3. nothing usable -> [Reject] with a 503 and a message the user can act on.
 *
 * Everything here is a pure function of ([ProviderStore], body, target). The
 * auth decision is deliberately *not* part of it: once the target is
 * retargeted, the existing `ProviderStore.matchProvider` path resolves the
 * provider from the rewritten host and owns the credential. Duplicating auth
 * in here would give it two homes.
 */
object GatewayPlanner {

    /** What the service should do with this request. */
    sealed interface Plan {
        /** Forward, optionally with the model rewritten to a leg's model. */
        data class Forward(
            val targetUrl: String,
            val body: ByteArray?,
            /** Set when the model came from a route, for logging/metrics. */
            val routeLabel: String?,
        ) : Plan {
            // ByteArray in a data class needs these spelled out.
            override fun equals(other: Any?) = other is Forward &&
                targetUrl == other.targetUrl &&
                routeLabel == other.routeLabel &&
                (body?.contentEquals(other.body) ?: (other.body == null))

            override fun hashCode() =
                (body?.contentHashCode() ?: 0) * 31 + targetUrl.hashCode() * 7 +
                    (routeLabel?.hashCode() ?: 0)
        }

        /** Answer locally; never open an upstream connection. */
        data class Reject(val status: Int, val message: String) : Plan
    }

    /**
     * Plan a front-door request.
     *
     * [targetUrl] must be the placeholder built by the service (a
     * `gateway.invalid` URL carrying the client's original path and query).
     * [preferLegScheme] is forwarded to [ProviderStore.retarget] so the
     * placeholder's `http` scheme cannot be inherited by a real provider
     * call -- that would put the provider API key on the wire in cleartext.
     */
    fun plan(
        store: ProviderStore,
        body: ByteArray?,
        targetUrl: String,
        preferLegScheme: Boolean = true,
    ): Plan {
        val contentTypeIsJson = body != null  // caller only routes JSON bodies

        // (1) a configured route wins.
        if (contentTypeIsJson && store.routeFailoverEnabled) {
            val wantModel = requestedModel(body)
            // Only a body that actually names a model may be routed. An
            // absent model, or one that failed to parse, yields "" -- and
            // "" must never be looked up, or a route defined under the empty
            // key would rewrite a request that asked for nothing in
            // particular.
            val route = if (wantModel.isEmpty()) null else store.routes[wantModel]
            if (route != null) {
                for (leg in route.legs) {
                    val provider = store.providers[leg.providerId] ?: continue
                    if (store.activeKey(provider.id) == null) continue
                    val rewritten = rewriteModel(body, leg.model)
                    return Plan.Forward(
                        targetUrl = ProviderStore.retarget(
                            targetUrl, provider.baseUrl, preferLegScheme
                        ),
                        body = rewritten,
                        routeLabel = "${provider.id}/${leg.model}",
                    )
                }
                // Route exists but no leg is usable. Fall through to the
                // default provider: a configured-but-dead route should not
                // turn into an outage when another provider can still answer.
            }
        }

        // (2) any provider with a usable key.
        val fallback = store.defaultProvider()
        if (fallback != null) {
            return Plan.Forward(
                targetUrl = ProviderStore.retarget(
                    targetUrl, fallback.first.baseUrl, preferLegScheme
                ),
                body = body,
                routeLabel = null,
            )
        }

        // (3) nothing to send it to.
        return Plan.Reject(
            503,
            "No provider configured. Add one before calling the proxy as your provider."
        )
    }

    /** The `model` field, or "" when the body is absent or not JSON. */
    private fun requestedModel(body: ByteArray?): String {
        if (body == null) return ""
        return try {
            org.json.JSONObject(body.toString(Charsets.UTF_8)).optString("model", "")
        } catch (_: Exception) {
            ""
        }
    }

    /** Body with `model` set to [model], or the original if it is not JSON. */
    private fun rewriteModel(body: ByteArray?, model: String): ByteArray? {
        if (body == null) return null
        return try {
            val o = org.json.JSONObject(body.toString(Charsets.UTF_8))
            o.put("model", model)
            o.toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            body
        }
    }
}