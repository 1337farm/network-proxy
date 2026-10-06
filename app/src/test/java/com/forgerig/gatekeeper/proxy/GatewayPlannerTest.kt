package com.forgerig.gatekeeper.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The front-door decision, with no Service, no Context and no socket.
 *
 * These are the cases that decide *whether a request the user made gets an
 * answer at all*, so they are pinned rather than inferred from the
 * end-to-end behaviour of a device.
 */
class GatewayPlannerTest {

    private val placeholder = "http://gateway.invalid/v1/chat/completions"

    private fun body(model: String) =
        """{"model":"$model","messages":[{"role":"user","content":"hi"}]}"""
            .toByteArray(Charsets.UTF_8)

    private fun key(id: String, enabled: Boolean = true) =
        ProviderStore.ApiKey(id, id, "secret-$id", enabled)

    private fun provider(
        id: String,
        base: String,
        vararg keys: ProviderStore.ApiKey,
        authHeader: String = "Authorization",
        authScheme: String = "Bearer ",
    ) = ProviderStore.Provider(id, base, authHeader, authScheme, keys.toMutableList())

    private fun store(build: ProviderStore.() -> Unit): ProviderStore =
        ProviderStore.blank().apply(build)

    // ------------------------------------------------------- route wins

    @Test
    fun aMatchingRouteIsRewrittenAndRetargeted() {
        val s = store {
            providers["nvidia"] = provider("nvidia", "https://nv.example", key("k"))
            routes["fast"] = ProviderStore.Route(
                "fast",
                listOf(ProviderStore.RouteLeg("nvidia", "z-ai/glm-5.3"))
            )
        }
        val plan = GatewayPlanner.plan(s, body("fast"), placeholder)
        assertTrue(plan is GatewayPlanner.Plan.Forward)
        plan as GatewayPlanner.Plan.Forward
        assertEquals("https://nv.example/v1/chat/completions", plan.targetUrl)
        assertEquals("nvidia/z-ai/glm-5.3", plan.routeLabel)
        // The client's model id must not survive into the upstream call.
        val sent = org.json.JSONObject(plan.body!!.toString(Charsets.UTF_8))
        assertEquals("z-ai/glm-5.3", sent.getString("model"))
        // And the rest of the body must be untouched.
        assertEquals(
            "hi",
            sent.getJSONArray("messages").getJSONObject(0).getString("content")
        )
    }

    @Test
    fun theFirstLegWithAUsableKeyWins() {
        val s = store {
            providers["dead"] = provider("dead", "https://dead.example")
            providers["live"] = provider("live", "https://live.example", key("k"))
            routes["m"] = ProviderStore.Route(
                "m",
                listOf(
                    ProviderStore.RouteLeg("dead", "d-model"),
                    ProviderStore.RouteLeg("live", "l-model")
                )
            )
        }
        val plan = GatewayPlanner.plan(s, body("m"), placeholder) as GatewayPlanner.Plan.Forward
        assertEquals("live/l-model", plan.routeLabel)
        assertEquals("https://live.example/v1/chat/completions", plan.targetUrl)
    }

    @Test
    fun aDisabledKeyMakesItsLegUnusable() {
        val s = store {
            providers["p"] = provider("p", "https://p.example", key("k", enabled = false))
            routes["m"] = ProviderStore.Route("m", listOf(ProviderStore.RouteLeg("p", "pm")))
        }
        // No usable leg, and no other provider, so this is a rejection rather
        // than a silent forward with no credential.
        val plan = GatewayPlanner.plan(s, body("m"), placeholder)
        assertTrue(plan is GatewayPlanner.Plan.Reject)
        assertEquals(503, (plan as GatewayPlanner.Plan.Reject).status)
    }

    @Test
    fun aDeadRouteFallsThroughToTheDefaultProvider() {
        // The route exists but nothing can serve it, while another provider
        // can. A configured-but-dead route must not become an outage.
        val s = store {
            providers["dead"] = provider("dead", "https://dead.example")
            providers["live"] = provider("live", "https://live.example", key("k"))
            routes["m"] = ProviderStore.Route("m", listOf(ProviderStore.RouteLeg("dead", "d")))
        }
        val plan = GatewayPlanner.plan(s, body("m"), placeholder) as GatewayPlanner.Plan.Forward
        assertNull("no route label: the leg was unusable", plan.routeLabel)
        assertEquals("https://live.example/v1/chat/completions", plan.targetUrl)
    }

    @Test
    fun routeFailoverDisabledSkipsRoutingEntirely() {
        val s = store {
            routeFailoverEnabled = false
            providers["nv"] = provider("nv", "https://nv.example", key("k"))
            providers["other"] = provider("other", "https://other.example", key("k"))
            routes["fast"] = ProviderStore.Route(
                "fast", listOf(ProviderStore.RouteLeg("nv", "upstream-model"))
            )
        }
        val plan = GatewayPlanner.plan(s, body("fast"), placeholder) as GatewayPlanner.Plan.Forward
        // Falls to the default provider instead of the route, and the model
        // is left alone.
        assertNull(plan.routeLabel)
        assertEquals("https://nv.example/v1/chat/completions", plan.targetUrl)
        val sent = org.json.JSONObject(plan.body!!.toString(Charsets.UTF_8))
        assertEquals("fast", sent.getString("model"))
    }

    // ------------------------------------------------------ no route

    @Test
    fun anUnknownModelGoesToTheDefaultProviderUnchanged() {
        val s = store {
            providers["p"] = provider("p", "https://p.example", key("k"))
        }
        val inBody = body("some-model-we-do-not-know")
        val plan = GatewayPlanner.plan(s, inBody, placeholder) as GatewayPlanner.Plan.Forward
        assertNull(plan.routeLabel)
        assertEquals("https://p.example/v1/chat/completions", plan.targetUrl)
        // Upstream gets to decide whether it knows the model.
        val sent = org.json.JSONObject(plan.body!!.toString(Charsets.UTF_8))
        assertEquals("some-model-we-do-not-know", sent.getString("model"))
    }

    @Test
    fun theDefaultProviderIsDeterministic() {
        val s = store {
            providers["a"] = provider("a", "https://a.example", key("k"))
            providers["b"] = provider("b", "https://b.example", key("k"))
        }
        // Insertion order, every time: session affinity depends on it.
        val first = GatewayPlanner.plan(s, body("x"), placeholder) as GatewayPlanner.Plan.Forward
        repeat(5) {
            val again = GatewayPlanner.plan(s, body("x"), placeholder) as GatewayPlanner.Plan.Forward
            assertEquals(first.targetUrl, again.targetUrl)
        }
    }

    // -------------------------------------------------------- rejections

    @Test
    fun noProvidersAtAllIsRejected() {
        val plan = GatewayPlanner.plan(ProviderStore.blank(), body("m"), placeholder)
        assertTrue(plan is GatewayPlanner.Plan.Reject)
        plan as GatewayPlanner.Plan.Reject
        assertEquals(503, plan.status)
        assertTrue(
            "message must tell the user what to do: '${plan.message}'",
            plan.message.contains("provider")
        )
    }

    @Test
    fun providersWithoutUsableKeysAreRejected() {
        val s = store {
            providers["p"] = provider("p", "https://p.example", key("k", enabled = false))
        }
        val plan = GatewayPlanner.plan(s, body("m"), placeholder)
        assertTrue(plan is GatewayPlanner.Plan.Reject)
    }

    @Test
    fun aBodylessGetIsStillRoutedToTheDefaultProvider() {
        // /v1/models and friends carry no body and no route. They must still
        // reach a provider rather than 503.
        val s = store {
            providers["p"] = provider("p", "https://p.example", key("k"))
        }
        val plan = GatewayPlanner.plan(
            s, null, "http://gateway.invalid/v1/models"
        ) as GatewayPlanner.Plan.Forward
        assertEquals("https://p.example/v1/models", plan.targetUrl)
        assertNull(plan.body)
    }

    // -------------------------------------------------- scheme safety

    @Test
    fun thePlaceholderSchemeNeverReachesTheProvider() {
        val s = store {
            providers["p"] = provider("p", "https://p.example", key("k"))
        }
        val plan = GatewayPlanner.plan(s, body("m"), placeholder) as GatewayPlanner.Plan.Forward
        // The placeholder is plain http. Inheriting it would send the
        // provider API key in cleartext, so the leg's https must win.
        assertTrue(
            "upstream must be https: ${plan.targetUrl}",
            plan.targetUrl.startsWith("https://")
        )
    }

    @Test
    fun aPlaintextProviderStaysPlaintext() {
        // An http:// provider is legitimate (a local stub, say) and must not
        // be silently upgraded: the client asked for http on loopback and the
        // provider is not on the wire in between.
        val s = store {
            providers["local"] = provider("local", "http://127.0.0.1:9999", key("k"))
        }
        val plan = GatewayPlanner.plan(s, body("m"), placeholder) as GatewayPlanner.Plan.Forward
        assertTrue(plan.targetUrl.startsWith("http://127.0.0.1:9999/"))
    }

    @Test
    fun pathsAndQueriesSurvivePlanning() {
        val s = store {
            providers["p"] = provider("p", "https://p.example/api/v1", key("k"))
        }
        val plan = GatewayPlanner.plan(
            s, body("m"), "http://gateway.invalid/v1/models?limit=2"
        ) as GatewayPlanner.Plan.Forward
        assertEquals("https://p.example/api/v1/v1/models?limit=2", plan.targetUrl)
    }

    // ------------------------------------------------------ bad bodies

    @Test
    fun aNonJsonBodyFallsThroughToTheDefaultProvider() {
        val s = store {
            providers["p"] = provider("p", "https://p.example", key("k"))
            routes["m"] = ProviderStore.Route("m", listOf(ProviderStore.RouteLeg("p", "up")))
        }
        val weird = "not json at all".toByteArray()
        val plan = GatewayPlanner.plan(s, weird, placeholder) as GatewayPlanner.Plan.Forward
        // No model to match, so no route -- but it must still be forwarded
        // rather than dropped.
        assertNull(plan.routeLabel)
        assertEquals("not json at all", plan.body!!.toString(Charsets.UTF_8))
    }

    @Test
    fun anEmptyModelDoesNotMatchARoute() {
        val s = store {
            providers["p"] = provider("p", "https://p.example", key("k"))
            routes[""] = ProviderStore.Route("", listOf(ProviderStore.RouteLeg("p", "up")))
        }
        // A body naming no model must not be rewritten just because someone
        // defined a route under the empty string.
        val plan = GatewayPlanner.plan(s, """{"messages":[]}""".toByteArray(), placeholder)
            as GatewayPlanner.Plan.Forward
        assertNull(plan.routeLabel)
    }
}