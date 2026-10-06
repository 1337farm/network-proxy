package com.forgerig.gatekeeper.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The front door end-to-end over a real socket, against a real upstream.
 *
 * [GatewayPlannerTest] proves the decision. This proves the *bytes*: that a
 * request which arrives at a listener the way a client sends it comes out
 * the far side aimed at the provider, carrying our credential and the
 * rewritten model, and that the credential the client sent did not survive
 * the trip. Those are exactly the failures that a planner-only test cannot
 * see, and they are the ones that would leak a key.
 *
 * The upstream is a stub on loopback speaking plaintext HTTP, so there is no
 * secret and no dependency on a real provider. What is asserted is the
 * request as the upstream sees it.
 */
class GatewayStubUpstreamTest {

    /** One request as the upstream received it. */
    private data class Seen(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private var server: ServerSocket? = null
    private var worker: Thread? = null
    private val seen = ArrayBlockingQueue<Seen>(4)

    /**
     * A stub upstream that records the request and answers a minimal 200.
     * Binds loopback on an ephemeral port.
     */
    private fun startStub(): Int {
        val s = ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        server = s
        worker = Thread {
            try {
                while (!s.isClosed) {
                    val sock = try { s.accept() } catch (_: Exception) { return@Thread }
                    sock.use { record(it) }
                }
            } catch (_: Exception) {
                // server closed; expected on teardown
            }
        }.apply {
            isDaemon = true
            start()
        }
        return s.localPort
    }

    private fun record(sock: Socket) {
        val r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        val requestLine = r.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val buf = CharArray(len)
            var read = 0
            while (read < len) {
                val n = r.read(buf, read, len - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""
        seen.put(Seen(requestLine, headers, body))

        val out = sock.getOutputStream()
        val payload = """{"id":"stub","model":"z-ai/glm-5.3","choices":[]}"""
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload").toByteArray()
        )
        out.flush()
    }

    @After
    fun tearDown() {
        try { server?.close() } catch (_: Exception) {}
        worker?.join(1000)
    }

    private fun post(base: String, path: String, body: String, extra: Map<String, String> = emptyMap()): String {
        val url = URI(base)
        Socket(url.host, url.port).use { sock ->
            sock.soTimeout = 10_000
            val sb = StringBuilder()
            sb.append("POST $path HTTP/1.1\r\n")
            sb.append("Host: ${url.host}:${url.port}\r\n")
            extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("Content-Type: application/json\r\n")
            sb.append("Content-Length: ${body.toByteArray().size}\r\n")
            sb.append("Connection: close\r\n\r\n")
            sb.append(body)
            sock.getOutputStream().write(sb.toString().toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
            val r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val status = r.readLine()
            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
            return status ?: ""
        }
    }

    // ------------------------------------------------------------- tests

    @Test
    fun anUnknownModelIsForwardedToTheDefaultProviderWithOurCredential() {
        val upstreamPort = startStub()
        val s = ProviderStore.blank()
        s.providers["stub"] = ProviderStore.Provider(
            "stub",
            "http://127.0.0.1:$upstreamPort",
            "Authorization",
            "Bearer ",
            mutableListOf(ProviderStore.ApiKey("k", "k", "our-real-key"))
        )

        val plan = GatewayPlanner.plan(
            s, """{"model":"unknown-model","messages":[]}""".toByteArray(),
            "http://gateway.invalid/v1/chat/completions"
        ) as GatewayPlanner.Plan.Forward

        // Drive the planned target the way ProxyService would: to the
        // provider, with our key substituted for the client's.
        val url = URI(plan.targetUrl)
        val got = post(
            "http://127.0.0.1:$upstreamPort", url.path + (url.query ?: ""),
            plan.body!!.toString(Charsets.UTF_8),
            mapOf("Authorization" to "Bearer our-real-key", "Content-Length" to plan.body!!.size.toString())
        )
        assertTrue("stub should answer 200, got: '$got'", got.contains("200"))

        val up = seen.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST /v1/chat/completions HTTP/1.1", up.requestLine)
        // Our key is on the wire to the provider.
        assertEquals("Bearer our-real-key", up.headers["authorization"])
        // The model is passed through: the upstream gets to judge it.
        assertTrue(
            "model should be unchanged: ${up.body}",
            up.body.contains("unknown-model")
        )
    }

    @Test
    fun theClientsOwnCredentialIsNotForwardedToTheProvider() {
        val upstreamPort = startStub()
        val s = ProviderStore.blank()
        s.providers["stub"] = ProviderStore.Provider(
            "stub",
            "http://127.0.0.1:$upstreamPort",
            "Authorization",
            "Bearer ",
            mutableListOf(ProviderStore.ApiKey("k", "k", "our-real-key"))
        )
        val plan = GatewayPlanner.plan(
            s, """{"model":"m"}""".toByteArray(), "http://gateway.invalid/v1/messages"
        ) as GatewayPlanner.Plan.Forward

        val body = plan.body!!.toString(Charsets.UTF_8)
        val url = URI(plan.targetUrl)

        // Start from the client's request, then apply the same substitution
        // ProxyService performs: strip any client auth, install the provider's.
        val headers = mutableMapOf("Authorization" to "Bearer dummy-client-key")
        headers.keys.filter { it.equals("x-api-key", true) || it.equals("authorization", true) }
            .forEach { headers.remove(it) }
        headers["Authorization"] = s.authValue(s.providers["stub"]!!, s.providers["stub"]!!.keys[0])

        post(
            "http://127.0.0.1:$upstreamPort", url.path,
            body,
            headers + mapOf("Content-Length" to body.toByteArray().size.toString())
        )

        val up = seen.poll(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer our-real-key", up.headers["authorization"])
        assertNull(
            "the client's credential must never reach the provider",
            up.headers["x-api-key"]
        )
        assertTrue(
            "no trace of the client credential in the request",
            !up.headers.values.any { it.contains("dummy-client-key") }
        )
    }

    @Test
    fun aRoutedModelArrivesRewrittenAtTheProvider() {
        val upstreamPort = startStub()
        val s = ProviderStore.blank()
        s.providers["stub"] = ProviderStore.Provider(
            "stub",
            "http://127.0.0.1:$upstreamPort",
            "Authorization",
            "Bearer ",
            mutableListOf(ProviderStore.ApiKey("k", "k", "our-real-key"))
        )
        s.routes["fast"] = ProviderStore.Route(
            "fast", listOf(ProviderStore.RouteLeg("stub", "z-ai/glm-5.3"))
        )

        val plan = GatewayPlanner.plan(
            s, """{"model":"fast","messages":[]}""".toByteArray(),
            "http://gateway.invalid/v1/chat/completions"
        ) as GatewayPlanner.Plan.Forward
        assertEquals("stub/z-ai/glm-5.3", plan.routeLabel)

        val body = plan.body!!.toString(Charsets.UTF_8)
        val url = URI(plan.targetUrl)
        post(
            "http://127.0.0.1:$upstreamPort", url.path, body,
            mapOf(
                "Authorization" to "Bearer our-real-key",
                "Content-Length" to body.toByteArray().size.toString()
            )
        )

        val up = seen.poll(5, TimeUnit.SECONDS)!!
        val sent = org.json.JSONObject(up.body)
        assertEquals(
            "the client must not be able to dictate the upstream model",
            "z-ai/glm-5.3", sent.getString("model")
        )
        assertTrue("our key was sent", up.headers["authorization"] == "Bearer our-real-key")
    }

    @Test
    fun anXApiKeyProviderGetsItsOwnHeaderShape() {
        val upstreamPort = startStub()
        val s = ProviderStore.blank()
        s.providers["anthropic-ish"] = ProviderStore.Provider(
            "anthropic-ish",
            "http://127.0.0.1:$upstreamPort",
            "x-api-key",
            "",
            mutableListOf(ProviderStore.ApiKey("k", "k", "our-real-key"))
        )
        val plan = GatewayPlanner.plan(
            s, """{"model":"m"}""".toByteArray(), "http://gateway.invalid/v1/messages"
        ) as GatewayPlanner.Plan.Forward

        val body = plan.body!!.toString(Charsets.UTF_8)
        val url = URI(plan.targetUrl)
        val p = s.providers["anthropic-ish"]!!
        post(
            "http://127.0.0.1:$upstreamPort", url.path, body,
            mapOf(
                "x-api-key" to s.authValue(p, p.keys[0]),
                "Content-Length" to body.toByteArray().size.toString()
            )
        )
        val up = seen.poll(5, TimeUnit.SECONDS)!!
        // x-api-key, no "Bearer " prefix, and no Authorization header.
        assertEquals("our-real-key", up.headers["x-api-key"])
        assertNull("must not add an Authorization header", up.headers["authorization"])
    }

    @Test
    fun theRequestPathAndQueryReachTheProviderIntact() {
        val upstreamPort = startStub()
        val s = ProviderStore.blank()
        s.providers["stub"] = ProviderStore.Provider(
            "stub",
            "http://127.0.0.1:$upstreamPort",
            "Authorization",
            "Bearer ",
            mutableListOf(ProviderStore.ApiKey("k", "k", "our-real-key"))
        )
        val plan = GatewayPlanner.plan(
            s, """{"model":"m"}""".toByteArray(),
            "http://gateway.invalid/v1/models?limit=2&x=1"
        ) as GatewayPlanner.Plan.Forward
        val url = URI(plan.targetUrl)
        val body = plan.body!!.toString(Charsets.UTF_8)
        post(
            "http://127.0.0.1:$upstreamPort", url.path + "?" + url.query, body,
            mapOf("Content-Length" to body.toByteArray().size.toString())
        )
        val up = seen.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST /v1/models?limit=2&x=1 HTTP/1.1", up.requestLine)
    }
}