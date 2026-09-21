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
        private const val MAX_BODY_BYTES = 32 * 1024 * 1024L
        private const val POOL_SIZE = 32
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    private val binder = LocalBinder()
    private val running = AtomicInteger(0)
    @Volatile private var lastError: String? = null
    private var port = 8080
    private var metricsEnabled = true
    private var mitmEnabled = false
    private var client: OkHttpClient? = null
    private var serverThread: Thread? = null
    private var pool = Executors.newFixedThreadPool(POOL_SIZE)
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
        port = intent?.getIntExtra("port", 8080) ?: 8080
        metricsEnabled = intent?.getBooleanExtra("metricsEnabled", true) ?: true
        mitmEnabled = intent?.getBooleanExtra("mitmEnabled", false) ?: false

        if (running.get() == 1) {
            ProxyMetrics.event("Restart requested on :$port — draining old listener")
            stopProxy()
        }
        lastError = null
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
            updateNotification("Proxy running on 0.0.0.0:$port", true)
            stateCallback?.invoke(true, null)
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
                if (metricsEnabled) ProxyMetrics.recordRequestStart(sessionId, requestId, url, method)
                handleConnect(socket, rawIn, output, url, sessionId, requestId, startedAt)
                return
            }

            var targetUrl = if (url.startsWith("http")) url else "http://${socket.inetAddress.hostAddress}$url"
            if (metricsEnabled) ProxyMetrics.recordRequestStart(sessionId, requestId, targetUrl, method)

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

            // --- Custom-route rewrite ("we are the provider"): if the
            // request body names one of OUR model ids, swap in the first
            // healthy leg (provider + upstream model) and retarget the URL.
            // Leg failover happens naturally via the key-rollover loop when
            // a leg's keys are exhausted... plus explicit leg advance below.
            val routeStore = ProviderBroker.store(this)
            var routeLeg: ProviderStore.RouteLeg? = null
            if (routeStore.routeFailoverEnabled && body != null &&
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
                        store.report(provider!!.id, kc.second.id, preCode)
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
                        keyCtx?.let { (p, k) -> store.report(p.id, k.id, r.code) }
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
                                    ProxyMetrics.addTokens(found[0], found[1], found[2], found[3])
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
        // Opt-in HTTPS split: terminate client TLS with our local CA leaf,
        // re-originate verified TLS upstream, scan plaintext usage blocks.
        // Needs the CA installed client-side (setup script); otherwise the
        // client aborts the handshake and we fall back to opaque tunneling.
        if (mitmEnabled && handleConnectMitm(clientSocket, clientIn, clientOut, host, port, sessionId, requestId, startedAt)) {
            return
        }
        var upstream: Socket? = null
        try {
            upstream = Socket()
            upstream.connect(InetSocketAddress(host, port), 30_000)
            upstream.setSoTimeout(120_000)
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, 200, 0, NetworkScenario.SUCCESS)
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
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, 200, 0, NetworkScenario.SUCCESS)
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
            val t1 = Thread { relayTap(cIn, uOut, upBytes, null, 0) }
            val t2 = Thread { relayTap(uIn, cOut, downBytes, tap, tapCap) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            // NOTE: bytesOut is fed per-chunk inside relay()/relayTap();
            // adding the lump sum here would double-count tunneled bytes.
            ProxyMetrics.addBytes(host, upBytes.get(), downBytes.get())
            if (tap.size() > 0) {
                val found = ProxyMetrics.scanUsage(tap.toString("UTF-8"))
                if (found[0] + found[1] + found[2] + found[3] > 0) {
                    ProxyMetrics.addTokens(found[0], found[1], found[2], found[3])
                    ProxyMetrics.event(
                        "Tokens $host in=${found[0]} out=${found[1]} " +
                            "cacheR=${found[2]} cacheW=${found[3]} (mitm)"
                    )
                }
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

    /** Copy with byte counter + optional plaintext tap (capped). */
    private fun relayTap(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        counter: java.util.concurrent.atomic.AtomicLong,
        tap: java.io.ByteArrayOutputStream?,
        tapCap: Int
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

    private fun stopProxy() {
        running.set(0)
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
        if (leg == null || body == null) return null
        val wantModel = try {
            org.json.JSONObject(body.toString(Charsets.UTF_8)).optString("model", "")
        } catch (_: Exception) { return null }
        val route = store.routes.values.firstOrNull { r ->
            r.legs.any { it.providerId == leg.providerId && it.model == leg.model }
        } ?: return null
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
        // Full circle: any usable key on the ORIGINAL provider (cooldowns may differ).
        val retry = store.activeKey(provider.id)
        return if (retry != null && retry.second.id != failedKeyId) {
            NextKey(retry, leg, url, body)
        } else null
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