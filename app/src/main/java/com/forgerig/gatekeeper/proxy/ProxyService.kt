@file:Suppress("DEPRECATION")

package com.forgerig.gatekeeper.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Headers.Companion.toHeaders
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProxyService : Service() {

    companion object {
        const val ACTION_STOP = "com.forgerig.gatekeeper.proxy.STOP"
        const val EXTRA_ONLY_IF_STOPPED = "onlyIfStopped"
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024L
        private const val POOL_SIZE = 32
        /** Health self-ping cadence + consecutive failures before self-restart. */
        const val HEALTH_INTERVAL_SEC = 30L
        const val HEALTH_MAX_FAILS = 3

        /** Pure restart decision (unit-tested). */
        fun healthNeedsRestart(failStreak: Int, maxFails: Int = HEALTH_MAX_FAILS): Boolean =
            failStreak >= maxFails
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    private val binder = LocalBinder()
    private val running = AtomicInteger(0)
    @Volatile private var lastError: String? = null
    private var port = 3128
    private var metricsEnabled = true
    private var mitmEnabled = true
    private var client: OkHttpClient? = null
    private var serverThread: Thread? = null
    private var pool = Executors.newFixedThreadPool(POOL_SIZE)
    // Health self-ping: proves the listener accepts connections; restarts
    // the listener (not the process) after consecutive failures.
    private var healthExec: java.util.concurrent.ScheduledExecutorService? = null
    private val healthFails = AtomicInteger(0)
    @Volatile private var lastHealthOkMs: Long = 0L
    private var statsCallback: ((Int, Long, Int, Int) -> Unit)? = null
    private var stateCallback: ((Boolean, String?) -> Unit)? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val requestCount = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    // Session registry (not a counter): add on entry, remove in finally.
    // Removal is idempotent, so the old double-decrement on CONNECT tunnels
    // (handleConnect + handleClient both touched inFlight) can't skew it,
    // and a stuck thread can't inflate the count — one id, one slot.
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun isRunning(): Boolean = running.get() == 1
    fun getLastError(): String? = lastError
    fun getStats(): Triple<Long, Long, Int> =
        Triple(requestCount.get(), bytesOut.get(), activeSessions.size)

    /** Zero the cumulative counters. Active sessions are a live registry, left alone. */
    fun resetStats() {
        requestCount.set(0)
        bytesOut.set(0)
    }

    fun setStateCallback(callback: (Boolean, String?) -> Unit) {
        stateCallback = callback
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            stopSelf()
            return START_NOT_STICKY
        }
        port = intent?.getIntExtra("port", 3128) ?: 3128
        metricsEnabled = intent?.getBooleanExtra("metricsEnabled", true) ?: true
        mitmEnabled = intent?.getBooleanExtra("mitmEnabled", true) ?: true

        if (running.get() == 1) {
            if (intent?.getBooleanExtra(EXTRA_ONLY_IF_STOPPED, false) == true) {
                // Ensure-running ping (app start): already up, don't flap.
                updateNotification("Proxy running on 0.0.0.0:$port", true)
                stateCallback?.invoke(true, null)
                return START_STICKY
            }
            ProxyMetrics.event("Restart requested on :$port — draining old listener")
            stopProxy()
        }
        lastError = null
        if (mitmEnabled) MitmCa.ensureLoaded(this)
        ProxyMetrics.event("Starting proxy on 0.0.0.0:$port")
        startProxy(port)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep-alive on swipe: re-post the foreground notification so the
        // proxy keeps serving. Stop only happens via the Stop button.
        if (running.get() == 1) {
            updateNotification("Proxy running on 0.0.0.0:$port", true)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startProxy(port: Int) {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30_000, TimeUnit.MILLISECONDS)
            .readTimeout(120_000, TimeUnit.MILLISECONDS)
            .writeTimeout(120_000, TimeUnit.MILLISECONDS)
        client = builder.build()
        pool = Executors.newFixedThreadPool(POOL_SIZE)

        acquireLocks()

        // Bind-before-announce: only mark running + notify after the socket
        // is actually bound. Bind failures report an error state instead.
        serverThread = Thread {
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket()
                serverSocket.setReuseAddress(true)
                serverSocket.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket.setSoTimeout(1000)
            } catch (e: Exception) {
                lastError = "Bind failed on 0.0.0.0:$port: ${e.message}"
                ProxyMetrics.eventError(lastError!!, e)
                updateNotification(lastError!!, false)
                stateCallback?.invoke(false, lastError)
                try { serverSocket?.close() } catch (_: Exception) {}
                releaseLocks()
                stopSelf()
                return@Thread
            }
            running.set(1)
            lastHealthOkMs = System.currentTimeMillis()
            updateNotification("Proxy running on 0.0.0.0:$port", true)
            stateCallback?.invoke(true, null)
            scheduleHealth()
            while (running.get() == 1) {
                try {
                    val socket = serverSocket.accept()
                    try {
                        pool.execute { handleClient(socket) }
                    } catch (re: java.util.concurrent.RejectedExecutionException) {
                        try {
                            val out = socket.getOutputStream()
                            out.write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 5\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            out.flush()
                        } catch (_: Exception) {}
                        try { socket.close() } catch (_: Exception) {}
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // ignore, loop
                } catch (e: Exception) {
                    if (running.get() == 1) e.printStackTrace()
                }
            }
            try { serverSocket.close() } catch (_: Exception) {}
        }.apply { start() }
    }

    private fun handleClient(socket: Socket) {
        val sessionId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        activeSessions.add(sessionId)
        try {
            socket.setSoTimeout(30_000)
            // Single buffered source for headers AND body: avoids losing bytes
            // buffered by a discarded reader (the old multi-reader bug).
            val rawIn = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLine(rawIn) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0].uppercase()
            val url = parts[1]

            // HTTPS tunneling: CONNECT host:port -> 200 + raw relay.
            if (method == "CONNECT") {
                if (metricsEnabled) ProxyMetrics.recordRequestStart(
                    sessionId, requestId, url, method,
                    ProviderBroker.store(this).llmHosts()
                )
                handleConnect(socket, rawIn, output, url, sessionId, requestId, startedAt)
                return
            }

            var targetUrl = if (url.startsWith("http")) url else "http://${socket.inetAddress.hostAddress}$url"
            if (metricsEnabled) ProxyMetrics.recordRequestStart(
                sessionId, requestId, targetUrl, method,
                ProviderBroker.store(this).llmHosts()
            )

            var contentLength = 0L
            val headers = mutableMapOf<String, String>()
            val retryAfterHeaders = mutableListOf<String>()
            var line: String? = readLine(rawIn)
            while (line != null && line.isNotBlank()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    headers[key] = value
                    if (key.equals("Content-Length", true)) contentLength = value.toLongOrNull() ?: 0
                    if (key.equals("Retry-After", true)) retryAfterHeaders.add(value)
                }
                line = readLine(rawIn)
            }

            // Local CA fetch: `curl http://127.0.0.1:<port>/ca.pem` serves
            // the MITM CA PEM directly — no manual Export step needed.
            // Matches absolute-form (proxied) and origin-form (direct to
            // the listening port, incl. --noproxy '*'), but ONLY when the
            // request is addressed at us (loopback host): a proxied
            // GET http://example.com/ca.pem must still go upstream.
            // Handled here, before any upstream forwarding, so it never
            // leaks upstream.
            if (method == "GET" && isLocalCaRequest(url)) {
                serveCaPem(output, sessionId, requestId)
                return
            }

            var body: ByteArray? = null
            if (contentLength > 0) {
                if (contentLength > MAX_BODY_BYTES) {
                    output.write("HTTP/1.1 413 Content Too Large\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    output.flush()
                    if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, 413, 0, NetworkScenario.PERMANENT_FAILURE)
                    return
                }
                body = readExact(rawIn, contentLength.toInt())
            }

            // --- LLM-only gate: broker treatment (keys, routes, context,
            // MITM) is reserved for configured provider hosts. Anything
            // else tunnels opaque by default, or 403s in strict mode.
            val routeStore = ProviderBroker.store(this)
            val llmDecision = LlmPolicy.decide(
                LlmPolicy.extractHost(targetUrl),
                routeStore.llmHosts(),
                routeStore.llmOnlyStrict
            )
            if (llmDecision == LlmPolicy.Decision.DENY) {
                val msg = LlmPolicy.denyBody(LlmPolicy.extractHost(targetUrl))
                ProxyMetrics.eventWarning("LLM-only deny: $msg")
                val bb = msg.toByteArray()
                output.write(
                    ("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n" +
                        "Content-Length: ${bb.size}\r\n\r\n").toByteArray()
                )
                output.write(bb)
                output.flush()
                if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                    sessionId, requestId, 403, bb.size.toLong(), NetworkScenario.PERMANENT_FAILURE
                )
                return
            }
            val brokered = llmDecision == LlmPolicy.Decision.BROKERED

            // --- Context layer (smart router, phase 1: gated hook only). ---
            // When enabled and the body parses as a known LLM wire format,
            // the decision carries the correlated conversation; the legacy
            // path below still does the forwarding (null = untouched).
            // Skipped entirely for off-allowlist traffic.
            @Suppress("UNUSED_VARIABLE")
            val ctxDecision = if (brokered) {
                com.forgerig.gatekeeper.proxy.context.ContextLayer
                    .maybeProcess(targetUrl, method, body)
            } else null

            // --- Custom-route rewrite ("we are the provider"): if the
            // request body names one of OUR model ids, swap in the first
            // healthy leg (provider + upstream model) and retarget the URL.
            // Leg failover happens naturally via the key-rollover loop when
            // a leg's keys are exhausted... plus explicit leg advance below.
            // Brokered traffic only: foreign bodies never name our models,
            // and skipping the parse saves the CPU on bulk downloads.
            var routeLeg: ProviderStore.RouteLeg? = null
            if (brokered && routeStore.routeFailoverEnabled && body != null &&
                (headers["Content-Type"]?.contains("json") == true ||
                    headers["content-type"]?.contains("json") == true)
            ) {
                try {
                    val bj = org.json.JSONObject(body.toString(Charsets.UTF_8))
                    val wantModel = bj.optString("model", "")
                    val route = routeStore.routes[wantModel]
                    if (route != null) {
                        for (leg in route.legs) {
                            val lp = routeStore.providers[leg.providerId]
                            if (lp != null && routeStore.activeKey(lp.id) != null) {
                                routeLeg = leg
                                bj.put("model", leg.model)
                                body = bj.toString().toByteArray(Charsets.UTF_8)
                                targetUrl = ProviderStore.retarget(targetUrl, lp.baseUrl)
                                headers.keys.filter {
                                    it.equals("Content-Length", true)
                                }.forEach { headers.remove(it) }
                                headers["Content-Length"] = body!!.size.toString()
                                ProxyMetrics.event("Route '$wantModel' → ${lp.id}/${leg.model}")
                                break
                            }
                        }
                        if (routeLeg == null) {
                            ProxyMetrics.eventWarning("Route '$wantModel': no healthy leg, passing through")
                        }
                    }
                } catch (_: Exception) { /* not JSON — passthrough */ }
            }

            val reqHeaders = headers.toHeaders()
            val mediaType = "application/octet-stream".toMediaType()
            var attempt = 0
            val policy = RetryPolicy(maxRetries = 5, baseBackoffMs = 2_000)
            // --- Key-broker layer: match host to a configured provider and
            // swap the client credential for our active key. On 401/429/5xx
            // roll to the next key and retry the same request (clean
            // rollover, invisible to the client). No keys -> passthrough.
            val host = try { java.net.URL(targetUrl).host.lowercase() } catch (_: Exception) { "" }
            val reqBytes = (body?.size ?: 0).toLong()
            val store = ProviderBroker.store(this)
            val provider = ProviderStore.matchProvider(store, targetUrl)
            var keyCtx: Pair<ProviderStore.Provider, ProviderStore.ApiKey>? =
                if (provider != null) store.activeKey(provider.id) else null
            if (provider != null && keyCtx == null && store.keyRolloverEnabled) {
                ProxyMetrics.eventWarning("No live key for '${provider.id}' — passing through unauthenticated")
            }
            val mutableHeaders = headers.toMutableMap()
            keyCtx?.let { (p, k) ->
                mutableHeaders.keys.filter {
                    it.equals("x-api-key", true) || it.equals("authorization", true)
                }.forEach { mutableHeaders.remove(it) }
                mutableHeaders[p.authHeader] = store.authValue(p, k)
            }
            // Feed the observed-model registry (spillover candidates) with
            // the upstream model actually requested (post route-rewrite).
            if (provider != null && body != null) {
                val seen = modelOf(body)
                if (seen.isNotBlank()) ModelRouter.noteObserved(provider.id, seen)
            }
            var finalCode = -1
            var transferredTotal = 0L
            var keyRounds = 0
            // Cap covers same-provider keys plus route-leg failovers.
            val maxKeyRounds = ((provider?.keys?.size ?: 0) + 4).coerceAtLeast(2)
            keyLoop@ while (true) {
            val reqHeaders = mutableHeaders.toHeaders()
            while (true) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .method(method, body?.toRequestBody(mediaType))
                        .headers(reqHeaders)
                        .build()

                    val response = client?.newCall(request)?.execute()
                    val resp = response ?: return
                    // Key rollover BEFORE anything is forwarded (and outside
                    // any inline lambda — labeled jumps need loop scope).
                    val preCode = resp.code
                    val kc = keyCtx
                    if (kc != null && store.keyRolloverEnabled &&
                        (preCode == 401 || preCode == 403 || preCode == 429 || preCode in 500..599) &&
                        keyRounds + 1 < maxKeyRounds
                    ) {
                        val retryAfterSecs = resp.headers["Retry-After"]?.toLongOrNull()
                        store.report(provider!!.id, kc.second.id, preCode, retryAfterSecs)
                        ModelHealth.recordErr(provider.id, modelOf(body))
                        keyRounds++
                        val next = nextUsableKey(
                            store, provider, kc.second.id, routeLeg,
                            body, targetUrl, headers, mutableHeaders
                        )
                        if (next != null) {
                            keyCtx = next.key
                            routeLeg = next.leg
                            targetUrl = next.url
                            body = next.body
                            mutableHeaders.keys.filter {
                                it.equals("x-api-key", true) || it.equals("authorization", true)
                            }.forEach { mutableHeaders.remove(it) }
                            mutableHeaders[next.key.first.authHeader] =
                                store.authValue(next.key.first, next.key.second)
                            resp.close()
                            attempt = 0
                            continue@keyLoop
                        }
                    }
                    resp.use { r ->
                        val scenario = ScenarioClassifier.classifyResponse(r.code)
                        keyCtx?.let { (p, k) ->
                            val ra = r.headers["Retry-After"]?.toLongOrNull()
                            store.report(p.id, k.id, r.code, ra)
                            val m = modelOf(body)
                            if (r.code in 200..299) ModelHealth.recordOk(p.id, m)
                            else ModelHealth.recordErr(p.id, m)
                        }
                        val action = ScenarioClassifier.toRetryAction(scenario)
                        if (action == RetryAction.RETRY_WITH_BACKOFF && policy.shouldRetry(attempt)) {
                            val retryAfter = r.headers["Retry-After"]?.toLongOrNull()
                            val delayMs = retryAfter?.times(1000) ?: policy.nextDelay(attempt)
                            if (metricsEnabled) {
                                ProxyMetrics.recordRetry(sessionId, requestId, attempt, scenario, delayMs)
                                ProxyMetrics.event("Retry #$attempt ${scenario.name} ${host} backoff=${delayMs}ms")
                            }
                            attempt++
                            Thread.sleep(delayMs)
                            return@use
                        }
                        finalCode = r.code
                        // Streaming payload trim: forward only what the client
                        // needs (see trimHeaders / ssePass / gunzipTap).
                        val respContentType = r.header("Content-Type", "") ?: ""
                        val clientAcceptsGzip = headers["Accept-Encoding"]?.contains("gzip") == true ||
                            headers["accept-encoding"]?.contains("gzip") == true
                        val upstreamGzipped = r.header("Content-Encoding", "")?.contains("gzip") == true
                        val gunzip = upstreamGzipped && !clientAcceptsGzip
                        val sse = respContentType.contains("text/event-stream")
                        val tapCap = 512 * 1024
                        val tap = if (respContentType.contains("json") || sse) {
                            java.io.ByteArrayOutputStream()
                        } else null
                        val statusLine = "HTTP/1.1 ${r.code} ${r.message}\r\n"
                        output.write(statusLine.toByteArray())
                        for ((key, values) in trimHeaders(r.headers.toMultimap(), gunzip)) {
                            for (value in values) {
                                output.write("$key: $value\r\n".toByteArray())
                            }
                        }
                        output.write("\r\n".toByteArray())
                        var transferred = 0L
                        var sseDropped = 0L
                        val lineBuf = java.io.ByteArrayOutputStream()
                        r.body?.byteStream()?.use { bs ->
                            val stream: java.io.InputStream =
                                if (gunzip) java.util.zip.GZIPInputStream(bs) else bs
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = stream.read(buf)
                                if (n < 0) break
                                if (sse) {
                                    // SSE heartbeat strip: forward content
                                    // lines, drop comments + ping frames.
                                    var i = 0
                                    while (i < n) {
                                        val b = buf[i++]
                                        lineBuf.write(b.toInt())
                                        if (b == '\n'.code.toByte()) {
                                            val line = lineBuf.toString("UTF-8")
                                            lineBuf.reset()
                                            if (sseDrop(line)) { sseDropped += line.toByteArray().size; continue }
                                            val lb = line.toByteArray()
                                            output.write(lb)
                                            transferred += lb.size
                                            if (tap != null && tap.size() < tapCap) {
                                                tap.write(lb, 0, lb.size.coerceAtMost(tapCap - tap.size()))
                                            }
                                        }
                                    }
                                } else {
                                    output.write(buf, 0, n)
                                    transferred += n
                                    if (tap != null && tap.size() < tapCap) {
                                        tap.write(buf, 0, n.coerceAtMost(tapCap - tap.size()))
                                    }
                                }
                            }
                        }
                        output.flush()
                        requestCount.incrementAndGet()
                        bytesOut.addAndGet(transferred)
                        ProxyMetrics.addBytes(host, reqBytes, transferred)
                        statsCallback?.invoke(1, transferred, 0, 0)
                        // Token tally from visible usage blocks (no-ops on tunnels).
                        tap?.let {
                            if (it.size() > 0) {
                                val found = ProxyMetrics.scanUsage(it.toString("UTF-8"))
                                if (found[0] + found[1] + found[2] + found[3] > 0) {
                                    // One call credits tallies + rate sampler
                                    // together (see recordUsage).
                                    ProxyMetrics.recordUsage(host, requestId, found)
                                    ProxyMetrics.event(
                                        "Tokens $host in=${found[0]} out=${found[1]} " +
                                            "cacheR=${found[2]} cacheW=${found[3]}"
                                    )
                                }
                            }
                        }
                        transferredTotal = transferred
                        if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, r.code, transferred, scenario)
                        val ms = System.currentTimeMillis() - startedAt
                        ProxyMetrics.event(
                            "$method $host → ${r.code} ${humanBytesShort(transferred)} " +
                                "${ms}ms retries=$attempt" +
                                (if (sseDropped > 0) " sseTrim=${humanBytesShort(sseDropped)}" else "") +
                                (if (gunzip) " gunzipped" else "") +
                                (keyCtx?.let { " key=${it.second.label}" } ?: "")
                        )
                        return
                    } ?: return
                } catch (e: Exception) {
                    val scenario = ScenarioClassifier.classifyError(e)
                    val action = ScenarioClassifier.toRetryAction(scenario)
                    if ((action == RetryAction.RETRY_WITH_BACKOFF || action == RetryAction.RETRY_IMMEDIATE) && policy.shouldRetry(attempt)) {
                        val delayMs = if (action == RetryAction.RETRY_IMMEDIATE) 0 else policy.nextDelay(attempt)
                        if (metricsEnabled) ProxyMetrics.recordRetry(sessionId, requestId, attempt, scenario, delayMs)
                        attempt++
                        if (delayMs > 0) Thread.sleep(delayMs)
                        continue
                    }
                    if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, 502, 0, scenario)
                    try {
                        output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        output.flush()
                    } catch (_: Exception) {}
                    finalCode = 502
                    ProxyMetrics.eventError("$method $host → 502 (${scenario.name}) after $attempt retries")
                    return
                }
            }
            break@keyLoop
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            activeSessions.remove(sessionId)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleConnect(
        clientSocket: Socket,
        clientIn: BufferedInputStream,
        clientOut: java.io.OutputStream,
        authority: String,
        sessionId: String,
        requestId: String,
        startedAt: Long
    ) {
        // Drain remaining CONNECT headers (single buffered source).
        var line: String? = readLine(clientIn)
        while (line != null && line.isNotBlank()) {
            line = readLine(clientIn)
        }
        val hostPort = authority.split(":")
        val host = hostPort[0]
        val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
        // LLM-only gate: MITM split + usage scan are reserved for
        // configured provider hosts. Foreign hosts tunnel opaque
        // (or 403 in strict mode) — no leaf issuance, no scan CPU.
        val connStore = ProviderBroker.store(this)
        when (LlmPolicy.decide(host, connStore.llmHosts(), connStore.llmOnlyStrict)) {
            LlmPolicy.Decision.DENY -> {
                val msg = LlmPolicy.denyBody(host)
                ProxyMetrics.eventWarning("LLM-only deny: $msg")
                val bb = msg.toByteArray()
                clientOut.write(
                    ("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n" +
                        "Content-Length: ${bb.size}\r\n\r\n").toByteArray()
                )
                clientOut.write(bb)
                clientOut.flush()
                if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                    sessionId, requestId, 403, bb.size.toLong(), NetworkScenario.PERMANENT_FAILURE
                )
                try { clientSocket.close() } catch (_: Exception) {}
                return
            }
            LlmPolicy.Decision.TUNNEL -> {
                opaqueTunnel(clientSocket, clientIn, clientOut, host, port, sessionId, requestId, startedAt)
                return
            }
            LlmPolicy.Decision.BROKERED -> { /* fall through to MITM attempt */ }
        }
        // Opt-in HTTPS split (Decrypt-HTTPS toggle): terminate client TLS
        // with our local CA leaf, re-originate verified TLS upstream, scan
        // plaintext usage blocks. Needs the CA installed client-side
        // (setup script curls /ca.pem); otherwise the client aborts the
        // handshake and we fall back to opaque tunneling — so tooling that
        // never installed the CA keeps working byte-for-byte.
        if (mitmEnabled && handleConnectMitm(clientSocket, clientIn, clientOut, host, port, sessionId, requestId, startedAt)) {
            return
        }
        opaqueTunnel(clientSocket, clientIn, clientOut, host, port, sessionId, requestId, startedAt)
    }

    /**
     * Byte-identical relay for one CONNECT session (no MITM, no scan).
     * Used for off-allowlist hosts under LLM-only policy and as the
     * fallback when the MITM split declines.
     */
    private fun opaqueTunnel(
        clientSocket: Socket,
        clientIn: BufferedInputStream,
        clientOut: java.io.OutputStream,
        host: String,
        port: Int,
        sessionId: String,
        requestId: String,
        startedAt: Long
    ) {
        var upstream: Socket? = null
        try {
            upstream = Socket()
            upstream.connect(InetSocketAddress(host, port), 30_000)
            upstream.setSoTimeout(120_000)
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            // No recordRequestEnd here: the tunnel record is finalized at
            // close with lifetime bytes (see recordTunnelEnd below).
            requestCount.incrementAndGet()
            ProxyMetrics.event("CONNECT $host:$port (tunnel established)")
            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()
            val cIn = clientSocket.getInputStream()
            val upBytes = java.util.concurrent.atomic.AtomicLong(0)
            val downBytes = java.util.concurrent.atomic.AtomicLong(0)
            val t1 = Thread { relay(cIn, upOut, upBytes) }
            val t2 = Thread { relay(upIn, clientOut, downBytes) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            // NOTE: bytesOut is fed per-chunk inside relay()/relayTap();
            // adding the lump sum here would double-count tunneled bytes.
            ProxyMetrics.addBytes(host, upBytes.get(), downBytes.get())
            if (metricsEnabled) ProxyMetrics.recordTunnelEnd(
                requestId, upBytes.get() + downBytes.get(), NetworkScenario.SUCCESS
            )
            val ms = System.currentTimeMillis() - startedAt
            ProxyMetrics.event(
                "TUNNEL $host closed up=${humanBytesShort(upBytes.get())} " +
                    "down=${humanBytesShort(downBytes.get())} ${ms}ms"
            )
        } catch (e: Exception) {
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, 502, 0, ScenarioClassifier.classifyError(e))
            try {
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                clientOut.flush()
            } catch (_: Exception) {}
        } finally {
            activeSessions.remove(sessionId)
            try { upstream?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    /**
     * TLS split for one CONNECT session. Returns true when the split
     * handled the session (success or clean error page); false to let the
     * caller fall back to an opaque tunnel.
     */
    private fun handleConnectMitm(
        clientSocket: Socket,
        clientIn: BufferedInputStream,
        clientOut: java.io.OutputStream,
        host: String,
        port: Int,
        sessionId: String,
        requestId: String,
        startedAt: Long
    ): Boolean {
        val serverCtx = try {
            MitmCa.serverContext(this, host.lowercase())
        } catch (_: Exception) { null } ?: return false
        var tlsClient: javax.net.ssl.SSLSocket? = null
        var tlsUp: javax.net.ssl.SSLSocket? = null
        try {
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            tlsClient = serverCtx.socketFactory.createSocket(
                clientSocket, null, clientSocket.port, true
            ) as javax.net.ssl.SSLSocket
            tlsClient.useClientMode = false
            tlsClient.startHandshake()
            val upCtx = MitmCa.upstreamContext()
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 30_000)
            tlsUp = upCtx.socketFactory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
            tlsUp.useClientMode = true
            tlsUp.sslParameters = tlsUp.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            tlsUp.startHandshake()
            requestCount.incrementAndGet()
            ProxyMetrics.event("MITM split $host:$port (plaintext visible)")
            val cIn = tlsClient.inputStream
            val cOut = tlsClient.outputStream
            val uIn = tlsUp.inputStream
            val uOut = tlsUp.outputStream
            val upBytes = java.util.concurrent.atomic.AtomicLong(0)
            val downBytes = java.util.concurrent.atomic.AtomicLong(0)
            val tap = java.io.ByteArrayOutputStream()
            val tapCap = 512 * 1024
            // Live usage scan: tokens count per chunk as they stream (the
            // close-time scan below only covers encoded bodies the live
            // scanner can't read — see scanTapBytesForClose).
            val liveUsage = ProxyMetrics.StreamingUsage()
            val t1 = Thread { relayTap(cIn, uOut, upBytes, null, 0) }
            val t2 = Thread { relayTap(uIn, cOut, downBytes, tap, tapCap, liveUsage, host, requestId) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            // NOTE: bytesOut is fed per-chunk inside relay()/relayTap();
            // adding the lump sum here would double-count tunneled bytes.
            ProxyMetrics.addBytes(host, upBytes.get(), downBytes.get())
            if (metricsEnabled) ProxyMetrics.recordTunnelEnd(
                requestId, upBytes.get() + downBytes.get(), NetworkScenario.SUCCESS
            )
            if (tap.size() > 0) {
                // Plaintext was already counted live per chunk; this only
                // picks up gzip/deflate bodies. Never recount plaintext.
                val found = ProxyMetrics.scanTapBytesForClose(tap.toByteArray())
                if (found[0] + found[1] + found[2] + found[3] > 0) {
                    ProxyMetrics.recordUsage(host, requestId, found)
                    ProxyMetrics.event(
                        "Tokens $host in=${found[0]} out=${found[1]} " +
                            "cacheR=${found[2]} cacheW=${found[3]} (mitm encoded)"
                    )
                }
            }
            // Flush a trailing match deferred at the exact end of the last
            // chunk (digit run of unknown completeness). Disjoint from both
            // live counts and the encoded-body fallback above.
            val tail = liveUsage.flush()
            if (tail[0] + tail[1] + tail[2] + tail[3] > 0) {
                ProxyMetrics.recordUsage(host, requestId, tail)
                ProxyMetrics.event(
                    "Tokens $host in=${tail[0]} out=${tail[1]} " +
                        "cacheR=${tail[2]} cacheW=${tail[3]} (live tail)"
                )
            }
            val ms = System.currentTimeMillis() - startedAt
            ProxyMetrics.event(
                "MITM $host closed up=${humanBytesShort(upBytes.get())} " +
                    "down=${humanBytesShort(downBytes.get())} ${ms}ms"
            )
            return true
        } catch (e: Exception) {
            // Client didn't trust our CA (or pinning) — caller tunnels opaque.
            ProxyMetrics.eventWarning("MITM split declined for $host (${e.message}), tunneling opaque")
            try { tlsClient?.close() } catch (_: Exception) {}
            try { tlsUp?.close() } catch (_: Exception) {}
            return false
        }
    }

    /** Copy with byte counter + optional plaintext tap (capped) + optional
     *  live usage scan (downstream direction only — pass null upstream). */
    private fun relayTap(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        counter: java.util.concurrent.atomic.AtomicLong,
        tap: java.io.ByteArrayOutputStream?,
        tapCap: Int,
        liveUsage: ProxyMetrics.StreamingUsage? = null,
        usageHost: String = "",
        requestId: String? = null
    ) {
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                bytesOut.addAndGet(n.toLong())
                counter.addAndGet(n.toLong())
                if (tap != null && tap.size() < tapCap) {
                    tap.write(buf, 0, n.coerceAtMost(tapCap - tap.size()))
                }
                if (liveUsage != null) {
                    val found = liveUsage.feed(buf, n)
                    if (found[0] + found[1] + found[2] + found[3] > 0) {
                        ProxyMetrics.recordUsage(usageHost, requestId, found)
                        ProxyMetrics.event(
                            "Tokens $usageHost in=${found[0]} out=${found[1]} " +
                                "cacheR=${found[2]} cacheW=${found[3]} (live)"
                        )
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { output.flush() } catch (_: Exception) {}
        }
    }

    private fun relay(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        counter: java.util.concurrent.atomic.AtomicLong? = null
    ) {
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                bytesOut.addAndGet(n.toLong())
                counter?.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            try { output.flush() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = out.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
            }
            out.write(b)
            prev = b
        }
    }

    private fun readExact(input: BufferedInputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n <= 0) break
            read += n
        }
        return if (read == count) buffer else buffer.copyOf(read)
    }

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "proxy:wifi")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
        } catch (_: Exception) {}
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "proxy:cpu")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    /** "ok (12s ago)" / "degraded (2/3)" for the stats card. */
    fun healthStatus(): String {
        val fails = healthFails.get()
        if (running.get() != 1) return "stopped"
        if (fails == 0) {
            val ago = (System.currentTimeMillis() - lastHealthOkMs) / 1000
            return "ok (${ago}s ago)"
        }
        return "degraded ($fails/$HEALTH_MAX_FAILS)"
    }

    /** Start the periodic self-ping (idempotent across restarts). */
    @Synchronized
    private fun scheduleHealth() {
        if (healthExec != null) return
        val exec = Executors.newSingleThreadScheduledExecutor()
        healthExec = exec
        exec.scheduleAtFixedRate(
            { healthPing() },
            HEALTH_INTERVAL_SEC, HEALTH_INTERVAL_SEC, TimeUnit.SECONDS
        )
    }

    /** One self-ping: bare TCP connect proves the listener accepts work. */
    private fun healthPing() {
        if (running.get() != 1) return
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            }
            healthFails.set(0)
            lastHealthOkMs = System.currentTimeMillis()
        } catch (e: Exception) {
            val fails = healthFails.incrementAndGet()
            ProxyMetrics.eventWarning("Health ping failed ($fails/$HEALTH_MAX_FAILS): ${e.message}")
            if (healthNeedsRestart(fails)) {
                ProxyMetrics.eventWarning("Health: listener dead — self-restarting on :$port")
                healthFails.set(0)
                try {
                    stopProxy(cancelHealth = false)
                } catch (_: Exception) {}
                startProxy(port)
            }
        }
    }

    private fun stopProxy(cancelHealth: Boolean = true) {
        running.set(0)
        if (cancelHealth) {
            try {
                healthExec?.shutdownNow()
            } catch (_: Exception) {}
            healthExec = null
            healthFails.set(0)
        }
        serverThread?.interrupt()
        pool.shutdownNow()
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {}
        releaseLocks()
        updateNotification("Proxy stopped", false)
        stateCallback?.invoke(false, null)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("proxy_channel", "Proxy Service", android.app.NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String, isRunning: Boolean) {
        val notification = NotificationCompat.Builder(this, "proxy_channel")
            .setContentTitle("Network Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(isRunning)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
        if (isRunning) startForeground(1, notification) else stopForeground(true)
    }

    fun setStatsCallback(callback: (Int, Long, Int, Int) -> Unit) {
        statsCallback = callback
    }

    // ---- Local CA endpoint (curl-able) ----
    /**
     * True for requests addressed at THIS proxy asking for the CA:
     * origin-form `/ca.pem`, or absolute-form with a loopback host
     * (127.0.0.1/localhost/::1, any port) and path /ca.pem.
     * Anything else — incl. `GET http://example.com/ca.pem` — is a
     * normal proxied request and must go upstream.
     * Logic lives in [CaEndpoint] (unit-tested); kept here as a thin
     * delegate so existing call sites don't churn.
     */
    internal fun isLocalCaRequest(target: String): Boolean = CaEndpoint.isLocalCaRequest(target)

    /** Path component of an origin-form or absolute-form request target. */
    internal fun caPathOf(target: String): String? = CaEndpoint.pathOf(target)

    /** Serve the MITM CA PEM inline; never forwards upstream. */
    private fun serveCaPem(
        output: java.io.OutputStream,
        sessionId: String,
        requestId: String
    ) {
        try {
            val pem = MitmCa.caPem(this)
            if (pem == null) {
                val msg = "CA unavailable"
                val head = "HTTP/1.1 503 Service Unavailable\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${msg.toByteArray().size}\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(head.toByteArray())
                output.write(msg.toByteArray())
                output.flush()
                if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                    sessionId, requestId, 503, 0, NetworkScenario.UNKNOWN
                )
                return
            }
            val body = pem.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-pem-file\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(head.toByteArray())
            output.write(body)
            output.flush()
            requestCount.incrementAndGet()
            bytesOut.addAndGet(body.size.toLong())
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                sessionId, requestId, 200, body.size.toLong(), NetworkScenario.SUCCESS
            )
            ProxyMetrics.event("Served local CA (${humanBytesShort(body.size.toLong())})")
        } catch (e: Exception) {
            try {
                output.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
            } catch (_: Exception) {}
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                sessionId, requestId, 500, 0, NetworkScenario.UNKNOWN
            )
        }
    }

    // ---- Key-broker helpers ----
    private data class NextKey(
        val key: Pair<ProviderStore.Provider, ProviderStore.ApiKey>,
        val leg: ProviderStore.RouteLeg?,
        val url: String,
        val body: ByteArray?
    )

    /**
     * Next usable credential: prefer a sibling key on the same provider,
     * else fail over to the next healthy leg of our custom route (rewriting
     * model + URL + content-length). Null when nothing is usable.
     */
    private fun nextUsableKey(
        store: ProviderStore,
        provider: ProviderStore.Provider,
        failedKeyId: String,
        leg: ProviderStore.RouteLeg?,
        body: ByteArray?,
        url: String,
        headers: MutableMap<String, String>,
        mutableHeaders: MutableMap<String, String>
    ): NextKey? {
        val same = store.activeKey(provider.id)
        if (same != null && same.second.id != failedKeyId) {
            return NextKey(same, leg, url, body)
        }
        // Same-provider pool exhausted — walk route legs after the current one.
        // (Skipped entirely without a leg context; spillover below covers it.)
        if (leg != null && body != null) {
            val wantModel = try {
                org.json.JSONObject(body.toString(Charsets.UTF_8)).optString("model", "")
            } catch (_: Exception) { null }
            val route = if (wantModel != null) {
                store.routes.values.firstOrNull { r ->
                    r.legs.any { it.providerId == leg.providerId && it.model == leg.model }
                }
            } else null
            if (route != null) {
                val idx = route.legs.indexOfFirst { it.providerId == leg.providerId && it.model == leg.model }
                for (next in route.legs.drop(idx + 1)) {
                    val lp = store.providers[next.providerId] ?: continue
                    val lk = store.activeKey(lp.id)?.second ?: continue
                    try {
                        val bj = org.json.JSONObject(body.toString(Charsets.UTF_8))
                        bj.put("model", next.model)
                        val nb = bj.toString().toByteArray(Charsets.UTF_8)
                        val nu = ProviderStore.retarget(url, lp.baseUrl)
                        headers["Content-Length"] = nb.size.toString()
                        mutableHeaders["Content-Length"] = nb.size.toString()
                        ProxyMetrics.event("Leg failover '$wantModel' → ${lp.id}/${next.model}")
                        return NextKey(lp to lk, next, nu, nb)
                    } catch (_: Exception) { continue }
                }
            }
        }
        // Cross-provider spillover (no route config needed): best
        // comparable model on a same-family provider, retargeted URL.
        // Lets Zen-exhausted traffic spill to OpenRouter/etc. mid-keyLoop.
        val failedModel = body?.let { modelOf(it) } ?: ""
        store.spilloverTarget(provider.id, failedModel, failedKeyId)?.let { sel ->
            var nb = body
            if (sel.model.isNotBlank() && body != null) {
                try {
                    val bj = org.json.JSONObject(body.toString(Charsets.UTF_8))
                    bj.put("model", sel.model)
                    nb = bj.toString().toByteArray(Charsets.UTF_8)
                } catch (_: Exception) { /* keep original body */ }
            }
            val nu = ProviderStore.retarget(url, sel.provider.baseUrl)
            val len = (nb?.size ?: 0).toString()
            headers["Content-Length"] = len
            mutableHeaders["Content-Length"] = len
            ProxyMetrics.event(
                "Spillover '${provider.id}' → '${sel.provider.id}/${sel.model}' (tier ${sel.tier})"
            )
            return NextKey(sel.provider to sel.key, null, nu, nb)
        }
        // Full circle: any usable key on the ORIGINAL provider (cooldowns may differ).
        val retry = store.activeKey(provider.id)
        return if (retry != null && retry.second.id != failedKeyId) {
            NextKey(retry, leg, url, body)
        } else null
    }

    /** Top-level "model" field of a JSON API body ("" when absent/opaque). */
    private fun modelOf(body: ByteArray?): String {
        if (body == null) return ""
        return try {
            org.json.JSONObject(body.toString(Charsets.UTF_8)).optString("model", "")
        } catch (_: Exception) {
            ""
        }
    }

    // ---- Streaming payload trims (extreme, but client-safe) ----
    private val keepHeaders = setOf(
        "content-type", "cache-control", "retry-after", "date", "etag",
        "vary", "x-request-id", "x-ratelimit-limit", "x-ratelimit-remaining",
        "x-ratelimit-reset", "ratelimit-limit", "ratelimit-remaining",
        "ratelimit-reset", "retry-after-ms", "anthropic-ratelimit-requests-limit",
        "anthropic-ratelimit-requests-remaining", "anthropic-ratelimit-requests-reset",
        "anthropic-ratelimit-tokens-limit", "anthropic-ratelimit-tokens-remaining",
        "anthropic-ratelimit-tokens-reset", "openai-processing-ms"
    )

    /** Drop telemetry headers the client never needs; drop framing headers
     *  we invalidated when gunzipping (we close-delimit instead). */
    private fun trimHeaders(
        headers: Map<String, List<String>>,
        gunzipped: Boolean
    ): Map<String, List<String>> {
        var dropped = 0
        val out = headers.filterKeys { k ->
            val kl = k.lowercase()
            val keep = kl in keepHeaders || (!kl.startsWith("x-") && !kl.startsWith("cf-") &&
                kl != "server" && kl != "via" && kl != "alt-svc" && kl != "nel" &&
                kl != "report-to" && kl != "reporting-endpoints")
            val framing = gunzipped && (kl == "content-encoding" || kl == "content-length")
            if (!keep || framing) { dropped++; false } else true
        }
        if (dropped > 0) ProxyMetrics.event("Trimmed $dropped response headers")
        return out
    }

    /** SSE frames the client ignores: comments + vendor ping frames. */
    private fun sseDrop(line: String): Boolean {
        val t = line.trim()
        return t.startsWith(":") || t == "event: ping" ||
            t == "event: keep-alive" || t == "event: heartbeat"
    }

    private fun humanBytesShort(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val v = bytes / 1024.0
        return if (v < 1024) String.format(java.util.Locale.US, "%.1f KB", v)
        else String.format(java.util.Locale.US, "%.1f MB", v / 1024)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
}