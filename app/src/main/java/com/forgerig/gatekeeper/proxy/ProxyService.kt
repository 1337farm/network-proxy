@file:Suppress("DEPRECATION")

package com.forgerig.gatekeeper.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
        /**
         * Notification refresh cadence. 5s is plenty for a glanceable
         * tok/s: the post is skipped entirely unless the rendered line
         * changes, so this only bounds how fast a real change can appear.
         */
        private const val NOTIFY_TICK_SEC = 5L
        /** How long a cached interface list stays fresh. */
        /** Notification channel: DEFAULT importance so "proxy is up" is visible. */
        private const val CHANNEL_ID = "proxy_status"

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
    // Written on the main thread (onStartCommand/onBind), read by all 32 pool
    // threads and the health/notification executor. @Volatile only — no
    // locking, so the hot relay read paths stay lock-free.
    @Volatile private var port = PROXY_PORT
    @Volatile private var metricsEnabled = true
    private var client: OkHttpClient? = null
    private var serverThread: Thread? = null
    private var pool = Executors.newFixedThreadPool(POOL_SIZE)
    // Health self-ping: proves the listener accepts connections; restarts
    // the listener (not the process) after consecutive failures.
    private var healthExec: java.util.concurrent.ScheduledExecutorService? = null
    /** Cached interface list + when it was refreshed. */

    /**
     * The single authoritative uptime stamp for the current run: written once,
     * at the top of [startProxy] (i.e. before the socket binds), and read by
     * both the notification ([uptimeText]) and the in-app counter
     * ([uptimeMs]). Stamping early means the shade shows a plausible uptime
     * through the bind window instead of nothing; both readers additionally
     * gate on `running`, so a stamp from a run that never bound (cleared below)
     * is never rendered.
     */
    @Volatile private var startedAtMs: Long = 0L
    /** Last posted notification signature; suppresses no-op re-posts. */
    @Volatile private var lastNotifSignature: String = ""
    private val healthFails = AtomicInteger(0)
    @Volatile private var lastHealthOkMs: Long = 0L
    @Volatile private var stateCallback: ((Boolean, String?) -> Unit)? = null

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

    /**
     * Uptime of the current run, 0 when not actually serving. Reads the one
     * authoritative stamp ([startedAtMs]), so this agrees with the value in
     * the notification instead of lagging it by the bind time. The `running`
     * test is what hides the pre-bind window: 0 renders as plain
     * "Router running" until the socket is up.
     */
    fun uptimeMs(): Long {
        val s = startedAtMs
        return if (running.get() == 1 && s > 0) System.currentTimeMillis() - s else 0L
    }

    /** Key-backed sessions for the Sessions-by-key UI (oldest first). */
    fun sessionDetails(): List<SessionTracker.SessionInfo> = SessionTracker.snapshot()

    /** Zero the cumulative counters. Active sessions are a live registry, left alone. */
    fun resetStats() {
        requestCount.set(0)
        bytesOut.set(0)
    }

    /**
     * Install the state sink. The lambda captures the caller's ViewModel, so
     * the service holds a strong reference to it for as long as the field is
     * set — [clearStateCallback] (called from [onUnbind] and [onDestroy]) is
     * what breaks that cycle.
     */
    fun setStateCallback(callback: (Boolean, String?) -> Unit) {
        stateCallback = callback
    }

    /**
     * Drop the state sink so a later [stopProxy] can't post into a ViewModel
     * nobody observes any more. Safe to call when nothing is installed; a
     * reconnect re-installs via [setStateCallback].
     */
    fun clearStateCallback() {
        stateCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxyAndRelease()
            stopSelf()
            return START_NOT_STICKY
        }
        port = PROXY_PORT
        metricsEnabled = intent?.getBooleanExtra("metricsEnabled", true) ?: true

        if (running.get() == 1) {
            if (intent?.getBooleanExtra(EXTRA_ONLY_IF_STOPPED, false) == true) {
                // Ensure-running ping (app start): already up, don't flap.
                // Re-assert the foreground state (the guard below would
                // otherwise skip it) but don't re-post an identical shade row.
                updateNotification("Router running on $BIND_ADDRESS:$port", true, forceForeground = true)
                stateCallback?.invoke(true, null)
                return START_STICKY
            }
            ProxyMetrics.event("Restart requested on :$port — draining old listener")
            stopProxyAndRelease()
        }
        lastError = null
        ProxyMetrics.event("Starting router on $BIND_ADDRESS:$port")
        startProxy(port)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep-alive on swipe: re-assert the foreground notification so the
        // proxy keeps serving. Stop only happens via the Stop button.
        // forceForeground: the signature is normally unchanged, so the
        // no-op guard in updateNotification would otherwise swallow this
        // and the service would never re-enter the foreground.
        if (running.get() == 1) {
            updateNotification("Router running on $BIND_ADDRESS:$port", true, forceForeground = true)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startProxy(port: Int) {
        // Sole stamp of the run's uptime. Also invalidates the previous run's
        // notification signature, so the new port/IP/uptime is re-posted
        // instead of being suppressed as a no-op.
        startedAtMs = System.currentTimeMillis()
        lastNotifSignature = ""
        val builder = OkHttpClient.Builder()
            .connectTimeout(30_000, TimeUnit.MILLISECONDS)
            .readTimeout(120_000, TimeUnit.MILLISECONDS)
            .writeTimeout(120_000, TimeUnit.MILLISECONDS)
            // Provider calls must never touch a proxy: the device may carry
            // a system/APN proxy (or a debugging one), and a MITM or
            // filtering hop in front of provider traffic breaks TLS trust
            // and leaks credentials outside this channel.
            .proxy(java.net.Proxy.NO_PROXY)
        // Brokered TLS goes through BouncyCastle (see TlsTransport): the
        // platform Conscrypt fingerprint is blocked at several provider
        // edges. Null (any failure) keeps the platform default.
        TlsTransport.upstreamFactory()?.let { (factory, tm) ->
            try {
                builder.sslSocketFactory(factory, tm)
                ProxyMetrics.event("Upstream TLS via BouncyCastle")
            } catch (e: Exception) {
                ProxyMetrics.eventWarning("BC TLS factory rejected: ${e.message}")
            }
        } ?: ProxyMetrics.eventWarning("BC TLS unavailable; upstream on platform TLS")
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
                serverSocket.bind(InetSocketAddress(BIND_ADDRESS, port))
                serverSocket.setSoTimeout(1000)
            } catch (e: Exception) {
                lastError = "Bind failed on $BIND_ADDRESS:$port: ${e.message}"
                // No listener: clear the run stamp so nothing can render an
                // uptime for a run that never served.
                startedAtMs = 0L
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
            updateNotification("Router running on $BIND_ADDRESS:$port", true)
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
        var sessionId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        activeSessions.add(sessionId)
        var tlsSession: EndpointTls? = null
        try {
            socket.setSoTimeout(30_000)
            // Single buffered source for headers AND body: avoids losing bytes
            // buffered by a discarded reader (the old multi-reader bug).
            // One listener serves two clients with different requirements: the
            // front door arrives as TLS (the client pinned our endpoint cert),
            // while the host retry proxy dials us in plaintext and must keep
            // working. Sniff the first byte -- a TLS record starts 0x16, and
            // every HTTP method starts with an ASCII letter -- then hand the
            // socket to the right side.
            //
            // The peek must not let a buffered reader run ahead: a TLS
            // ClientHello is hundreds of bytes, and if those land in a
            // BufferedInputStream that the layered SSLSocket never sees, the
            // handshake dies with "unexpected message". Read exactly one byte
            // and put it back with a PushbackInputStream, which both the
            // plaintext path and the TLS wrap read through.
            val pushed = java.io.PushbackInputStream(socket.getInputStream(), 1)
            val firstByte = try { pushed.read() } catch (_: Exception) { -1 }
            if (firstByte >= 0) pushed.unread(firstByte)
            val tls = firstByte == TLS_HANDSHAKE_FIRST_BYTE

            // One stream pair for the whole request. Plaintext clients use the
            // socket streams directly; TLS clients get a pin-verified session
            // driven over the very same streams via EndpointTls (see its doc
            // for why an SSLSocket layered over the socket cannot work here).
            val readSource: java.io.InputStream
            val writeTarget: java.io.OutputStream
            if (tls) {
                val engine = EndpointCert.engine(this)
                if (engine == null) {
                    try { socket.close() } catch (_: Exception) {}
                    ProxyMetrics.eventWarning("TLS request but endpoint cert unavailable; dropped")
                    return
                }
                try {
                    tlsSession = EndpointTls.handshake(engine, pushed, socket.getOutputStream())
                    socket.setSoTimeout(30_000)
                    ProxyMetrics.event("TLS handshake ok from ${socket.inetAddress?.hostAddress}")
                } catch (e: Exception) {
                    try { tlsSession?.close() } catch (_: Exception) {}
                    ProxyMetrics.eventWarning("TLS handshake rejected: ${e.javaClass.simpleName}")
                    return
                }
                readSource = tlsSession.input()
                writeTarget = tlsSession.output()
            } else {
                readSource = pushed
                writeTarget = socket.getOutputStream()
            }
            val inputStream = readSource
            val output = writeTarget
            val rawIn = BufferedInputStream(inputStream)

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

            // For a forward-proxy request the target names a remote host.
            // For a front-door request there is no target yet: the upstream
            // comes from the route table, so keep a placeholder that the
            // rewrite below replaces (or that the default-provider fallback
            // fills in). Never reconstruct it from the peer address here --
            // that would address the request back at this same socket.
            var targetUrl = if (url.startsWith("http")) url else "http://gateway.invalid$url"
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

            // "We are the provider": an origin-form target whose Host is this
            // proxy is a request addressed to our own front door, not one
            // being forwarded somewhere. It skips the forward-proxy
            // reconstruction below (which would otherwise point the request
            // back at the peer address), and it is brokered unconditionally:
            // no interception is involved, so there is no foreign traffic to
            // keep away from the rewrite path.
            val gatewayMode = GatewayRequest.isAddressedToUs(url, headers["Host"], port)
            if (gatewayMode) {
                ProxyMetrics.event("Gateway request on :$port ${GatewayRequest.pathOf(url)}")
            } else if (!url.startsWith("http")) {
                // Origin-form that is not addressed to us: keep the legacy
                // forward-proxy reconstruction so existing traffic is
                // completely unaffected by this change.
                targetUrl = "http://${socket.inetAddress.hostAddress}$url"
            }

            // Session correlation: `x-session-id` names the conversation
            // this request belongs to. When the client sends it, the
            // per-request UUID is swapped for it here — after header
            // parsing, before anything downstream — so every request in
            // one conversation lands on a single session row. The CONNECT
            // branch above already returned and keeps its own UUID. The
            // UUID's slot in activeSessions is swapped with the new id so
            // the finally block releases the id actually in use.
            val clientSessionId = SessionTracker.sessionIdOf(headers)
            if (clientSessionId.isNotEmpty()) {
                activeSessions.remove(sessionId)
                sessionId = clientSessionId
                activeSessions.add(sessionId)
            }

            // Router-local endpoint: `curl http://127.0.0.1:<port>/endpoint-cert.pem`
            // serves the pinned TLS certificate — no CA, no manual export.
            // Matches absolute-form (proxied) and origin-form (direct to
            // the listening port, incl. --noproxy '*'), but ONLY when the
            // request is addressed at us (loopback host). Handled here,
            // before any upstream forwarding, so it never leaks upstream.
            val peerIsLoopback = RouterEndpoint.isLoopbackPeer(socket.inetAddress?.hostAddress)
            if (method == "GET" && RouterEndpoint.isLocalEndpointCertRequest(url, peerIsLoopback)) {
                serveEndpointCertPem(output, sessionId, requestId)
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

            // --- LLM-only gate: broker treatment (keys, routes, context)
            // is reserved for configured provider hosts. Anything
            // else tunnels opaque by default, or 403s in strict mode.
            val routeStore = ProviderBroker.store(this)
            val llmDecision = if (gatewayMode) {
                // The client addressed us directly, so there is no host to
                // match against an allowlist: this is the provider call.
                LlmPolicy.Decision.BROKERED
            } else {
                LlmPolicy.decide(
                    LlmPolicy.extractHost(targetUrl),
                    routeStore.llmHosts(),
                    routeStore.llmOnlyStrict
                )
            }
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
                                targetUrl = ProviderStore.retarget(targetUrl, lp.baseUrl, gatewayMode)
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

            // Front door: the client named no route we know, so targetUrl is
            // still the gateway.invalid placeholder. Decide where it goes in
            // GatewayPlanner (pure, unit-tested) rather than here, where it
            // needs a Context and a socket to reach.
            if (gatewayMode && routeLeg == null) {
                when (val plan = GatewayPlanner.plan(routeStore, body, targetUrl)) {
                    is GatewayPlanner.Plan.Reject -> {
                        val bb = plan.message.toByteArray()
                        ProxyMetrics.eventWarning("Gateway request with no usable provider")
                        output.write(
                            ("HTTP/1.1 ${plan.status} Service Unavailable\r\nContent-Type: text/plain\r\n" +
                                "Content-Length: ${bb.size}\r\nConnection: close\r\n\r\n").toByteArray()
                        )
                        output.write(bb)
                        output.flush()
                        if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                            sessionId, requestId, plan.status, bb.size.toLong(),
                            NetworkScenario.PERMANENT_FAILURE
                        )
                        return
                    }
                    is GatewayPlanner.Plan.Forward -> {
                        targetUrl = plan.targetUrl
                        if (plan.body != null && !plan.body.contentEquals(body ?: ByteArray(0))) {
                            body = plan.body
                            headers.keys.filter {
                                it.equals("Content-Length", true)
                            }.forEach { headers.remove(it) }
                            headers["Content-Length"] = body!!.size.toString()
                        }
                        if (plan.routeLabel != null) {
                            ProxyMetrics.event("Route matched → ${plan.routeLabel}")
                        } else {
                            ProxyMetrics.event("Gateway fallback → ${java.net.URL(plan.targetUrl).host}")
                        }
                        // Key injection is deliberately left to the existing
                        // ProviderStore.matchProvider path below, which
                        // resolves the provider from the rewritten host and
                        // strips whatever credential the client sent.
                    }
                }
            }

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
            // Session correlation headers are proxy-internal metadata:
            // stripped before forwarding, unconditionally (not just on
            // key-swap) so the upstream never sees them.
            mutableHeaders.keys.filter {
                it.equals(SessionTracker.ID_HEADER_KEY, true) ||
                    it.equals("x-session-name", true) ||
                    it.equals("x-session-title", true)
            }.forEach { mutableHeaders.remove(it) }
            // Feed the observed-model registry (spillover candidates) with
            // the upstream model actually requested (post route-rewrite).
            if (provider != null && body != null) {
                val seen = modelOf(body)
                if (seen.isNotBlank()) ModelRouter.noteObserved(provider.id, seen)
            }
            // Attribute this session for the Sessions-by-key UI: actual
            // upstream key label + model, plus a conversation title from
            // the first user turn (blank for non-chat bodies) and the
            // client's own session name (`x-session-name`, or body
            // metadata) so the list shows "Oc proxy" and not just the key.
            if (provider != null) {
                val (pid, klabel) = keyCtx?.let { it.first.id to it.second.label }
                    ?: (provider.id to "—")
                SessionTracker.note(
                    sessionId, pid, klabel, modelOf(body), host,
                    title = SessionTracker.titleOf(body),
                    name = SessionTracker.nameOf(headers, body)
                )
            }
            // A request may carry the conversation's display name on a
            // later turn; note() only sets it at registration, so refresh
            // it here (no-op for an unknown session).
            SessionTracker.renameSession(sessionId, SessionTracker.nameOf(headers, body))
            // Re-key from UUID to hash of first user message (if available)
            if (provider != null && body != null) {
                val hashId = SessionTracker.hashOf(SessionTracker.titleOf(body))
                if (hashId.isNotEmpty() && hashId != sessionId) {
                    activeSessions.remove(sessionId)
                    SessionTracker.rekey(sessionId, hashId)
                    sessionId = hashId
                    activeSessions.add(sessionId)
                }
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
                    // Upstream-edge diagnostics: header NAMES only (values can
                    // carry sessions) plus the negotiated TLS parameters, so
                    // a hostile edge names itself in the log instead of
                    // presenting as a faceless 403.
                    if (preCode == 403) {
                        val hs = try { resp.handshake } catch (_: Exception) { null }
                        ProxyMetrics.eventWarning(
                            "upstream 403 diag: tls=${hs?.tlsVersion} cipher=${hs?.cipherSuite} " +
                                "respHeaders=${resp.headers.names()} " +
                                "cf-mitigated=${resp.header("cf-mitigated")}"
                        )
                    }
                    if (kc != null && store.keyRolloverEnabled &&
                        (preCode == 401 || preCode == 403 || preCode == 429 || preCode in 500..599) &&
                        keyRounds + 1 < maxKeyRounds
                    ) {
                        val retryAfterSecs = resp.headers["Retry-After"]?.toLongOrNull()
                        store.report(provider!!.id, kc.second.id, preCode, retryAfterSecs)
                        ModelHealth.recordErr(provider.id, modelOf(body))
                        SessionTracker.noteEvent(
                            sessionId,
                            "HTTP $preCode on '${kc.second.label}' → rolling"
                        )
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
                            SessionTracker.note(
                                sessionId, next.key.first.id, next.key.second.label,
                                modelOf(body), LlmPolicy.extractHost(targetUrl)
                            )
                            val spilled = next.key.first.id != provider.id
                            SessionTracker.noteEvent(
                                sessionId,
                                if (spilled) "spill → ${next.key.first.id}/${modelOf(body)}"
                                else "roll → '${next.key.second.label}'"
                            )
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
                        // Verbose log: the full tapped body, stored only
                        // after the stream loop has drained (the tap is
                        // complete). JSON/SSE responses only — the tap is
                        // null for other content types.
                        if (verboseLogging()) {
                            tap?.let { ResponseLog.add(host, respContentType, it.toByteArray()) }
                        }
                        requestCount.incrementAndGet()
                        bytesOut.addAndGet(transferred)
                        ProxyMetrics.addBytes(host, reqBytes, transferred)
                        // Token tally from visible usage blocks (no-ops on tunnels).
                        tap?.let {
                            if (it.size() > 0) {
                                val found = ProxyMetrics.scanUsage(it.toString("UTF-8"))
                                if (found[0] + found[1] + found[2] + found[3] > 0) {
                                    // One call credits tallies + rate sampler
                                    // together (see recordUsage). Model is the
                                    // upstream id actually requested.
ProxyMetrics.recordUsage(host, modelOf(body), requestId, found)
SessionTracker.noteUsage(sessionId, found)
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
            SessionTracker.clear(sessionId)
            ProxyMetrics.recordRequestEndIfOpen(sessionId, requestId)
            try { tlsSession?.close() } catch (_: Exception) {}
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
        // LLM-only gate: usage scan is reserved for configured provider
        // hosts. Foreign hosts tunnel opaque (or 403 in strict mode).
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
            LlmPolicy.Decision.BROKERED -> { /* fall through to opaque tunnel */ }
        }
        // Attribute tunneled sessions at provider level (client holds its
        // own key inside the tunnel — label shows that; model fills in
        // once the head sniff lands, else stays blank). matchHost (not
        // matchProvider): CONNECT only has a bare host, no API path.
        run {
            val mp = ProviderStore.matchHost(connStore, host)
            if (mp != null) {
                SessionTracker.note(sessionId, mp.id, "(client key)", "", host.lowercase())
            }
        }
        // No TLS interception: CONNECT sessions always tunnel byte-identical.
        // Request/response reading happens on the brokered plain-HTTP path,
        // where the harness addresses the router directly.
        opaqueTunnel(clientSocket, clientIn, clientOut, host, port, sessionId, requestId, startedAt)
    }

    /**
     * Byte-identical relay for one CONNECT session.
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
            // NOTE: bytesOut is fed per-chunk inside relay();
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
            SessionTracker.clear(sessionId)
            try { upstream?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
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

    /**
     * Start the periodic self-ping and the notification refresh tick
     * (idempotent across restarts). Both run on the same executor.
     */
    @Synchronized
    private fun scheduleHealth() {
        if (healthExec != null) return
        val exec = Executors.newSingleThreadScheduledExecutor()
        healthExec = exec
        // scheduleAtFixedRate silently stops running a task that throws, so
        // both are wrapped: an uncaught exception here would permanently
        // disable health self-restarting for the life of the process.
        exec.scheduleAtFixedRate(
            {
                try { healthPing() } catch (t: Throwable) {
                    android.util.Log.w("NetworkProxy", "health tick failed", t)
                }
            },
            HEALTH_INTERVAL_SEC, HEALTH_INTERVAL_SEC, TimeUnit.SECONDS
        )
        exec.scheduleAtFixedRate(
            {
                try { refreshNotification() } catch (t: Throwable) {
                    android.util.Log.w("NetworkProxy", "notification tick failed", t)
                }
            },
            NOTIFY_TICK_SEC, NOTIFY_TICK_SEC, TimeUnit.SECONDS
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
                    stopProxyAndRelease(cancelHealth = false)
                } catch (_: Exception) {}
                startProxy(port)
            }
        }
    }

    /**
     * Stop serving, and hand back the retired OkHttp client for teardown
     * outside the monitor.
     *
     * @return the client that must be passed to [releaseUpstream], or null.
     * Callers should use [stopProxyAndRelease] rather than calling this
     * directly: this method holds the service monitor, and the teardown is
     * the only part of a stop that can block.
     */
    @Synchronized
    private fun stopProxy(cancelHealth: Boolean = true): OkHttpClient? {
        running.set(0)
        if (cancelHealth) {
            try {
                healthExec?.shutdownNow()
            } catch (_: Exception) {}
            healthExec = null
            healthFails.set(0)
        }
        // Stop the accept loop, then drop the handle: the listener exits on
        // its own (soTimeout 1s, plus the interrupt) and a later stop must
        // not interrupt a thread object that is already dead. Deliberately no
        // join() here — this runs on the main thread for the Stop button and
        // for ACTION_STOP, and the listener's remaining work is one accept
        // timeout plus a close().
        val listener = serverThread
        serverThread = null
        try { listener?.interrupt() } catch (_: Exception) {}
        pool.shutdownNow()
        val retiring = client
        // Nulled under the monitor so a concurrent startProxy can never hand
        // this instance to a new run; the actual closing happens in
        // releaseUpstream, off the monitor.
        client = null
        releaseLocks()
        updateNotification("Proxy stopped", false)
        stateCallback?.invoke(false, null)
        return retiring
    }

    /**
     * [stopProxy] plus the OkHttp teardown. The split matters: evictAll()
     * closes pooled connections and SSLSocket.close() writes a TLS
     * close_notify alert, so both can block on a wedged upstream — and
     * updateNotification (the 5s tick) shares this same monitor, so blocking
     * while holding it would stall the notification and any concurrent
     * stop/start.
     */
    private fun stopProxyAndRelease(cancelHealth: Boolean = true) {
        releaseUpstream(stopProxy(cancelHealth))
    }

    /**
     * Release everything a retired OkHttpClient owns. Shutting the dispatcher's
     * executor down is not enough:
     *  - idle pooled connections stay open and are handed to the next run
     *    (a keep-alive from the previous session, on the old config);
     *  - an in-flight call from the previous run keeps running, holding the
     *    old client (and its dispatcher threads) alive past the restart.
     * Order: cancel in-flight, evict the pool, then stop the executor.
     */
    private fun releaseUpstream(retiring: OkHttpClient?) {
        if (retiring == null) return
        try { retiring.dispatcher.cancelAll() } catch (_: Exception) {}
        try { retiring.connectionPool.evictAll() } catch (_: Exception) {}
        try { retiring.dispatcher.executorService.shutdown() } catch (_: Exception) {}
    }

    /**
     * Re-evaluate the running notification so the body tracks the live tok/s
     * and uptime. Cheap: [updateNotification] no-ops (no notify, no
     * startForeground) unless the rendered text moved.
     */
    private fun refreshNotification() {
        if (running.get() != 1) return
        updateNotification("Router running on $BIND_ADDRESS:$port", true)
    }

    /**
     * Local addresses for the notification, cached briefly.
     *
     * NetworkInterface.getNetworkInterfaces() is a JNI call that allocates a
     * fresh set of interface objects per call, and it was running on every
     * notification tick - including the ticks where the post was then
     * skipped because nothing had changed. Interfaces change on the order of
     * minutes, not seconds, so a short TTL takes it off the hot path without
     * the notification showing a stale address for long.
     */
    /**
     * Elapsed proxy uptime as a compact "1h 04m" / "12m 30s" string.
     * Same authoritative stamp as [uptimeMs], so the shade and the in-app
     * counter can never disagree. Null (no uptime rendered) when the stamp is
     * unset — i.e. a run that failed to bind.
     */
    private fun uptimeText(): String? {
        val started = startedAtMs
        if (started <= 0) return null
        val s = (System.currentTimeMillis() - started) / 1000
        if (s < 0) return null
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%dh %02dm", h, m)
        else if (m > 0) String.format(java.util.Locale.US, "%dm %02ds", m, sec)
        else String.format(java.util.Locale.US, "%ds", sec)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // New channel id: importance is immutable once created, so the
            // old LOW channel can never be promoted in place. Users have to
            // SEE that the proxy is up, hence DEFAULT (still silent, no badge).
            val channel = NotificationChannel(CHANNEL_ID, "Proxy Service", NotificationManager.IMPORTANCE_DEFAULT)
            channel.setShowBadge(false)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setDescription("Shows the proxy port and this device's addresses")
            manager.createNotificationChannel(channel)
            if (CHANNEL_ID != "proxy_channel") manager.deleteNotificationChannel("proxy_channel")
        }
    }

    /**
     * @param forceForeground re-assert `startForeground` even when the
     *   rendered notification is byte-identical to the last post. Callers
     *   that only want to keep the service in the foreground (keep-alive
     *   re-posts) pass true and still skip the `notify` when nothing
     *   changed; the normal 5s tick does not, so an idle proxy never
     *   re-enters the foreground every tick.
     */
    @Synchronized
    private fun updateNotification(text: String, isRunning: Boolean, forceForeground: Boolean = false) {
        // Re-check under the lock: a tick can pass the running test in
        // refreshNotification and then be overtaken by stopProxy(), which
        // would otherwise re-post a non-dismissible "running" notification
        // and re-enter the foreground for a proxy that is down.
        if (isRunning && running.get() != 1) return
        val port = this.port
        val tps = if (isRunning) ProxyMetrics.outputTokensPerSecond() else 0.0
        val title = if (isRunning) "Forge Router running" else "Forge Router stopped"
        val body = if (isRunning) {
            NotificationText.running(BIND_ADDRESS, port, tps, uptimeText())
        } else {
            NotificationText.plain(text)
        }
        // Skip the re-post entirely when nothing moved, so an idle proxy does
        // not churn the shade (onlyAlertOnce also keeps it silent). A forced
        // foreground re-assert still has to run the builder (it needs a
        // Notification to hand to startForeground), it just skips notify().
        val signature = "$title|$port|${body.collapsed}|${body.expanded}|$tps"
        val changed = signature != lastNotifSignature
        if (!changed && !(forceForeground && isRunning)) return
        lastNotifSignature = signature

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.collapsed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.expanded))
            // Non-dismissible while the proxy runs: ongoing keeps it out of
            // the swipe-away gesture, and a null delete intent means there is
            // no dismiss action behind it either. It disappears only when the
            // service actually stops.
            .setOngoing(isRunning)
            .setAutoCancel(false)
            .setDeleteIntent(null)
            // Tapping the notification opens the app.
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        // Single-layer vector, not the adaptive launcher mipmap: the
        // status-bar small-icon slot does no adaptive masking, so an
        // adaptive icon renders oversized / as a solid block. A bitmap
        // small icon is also ignored by skins that force the app icon
        // into that slot, so the rate lives in the notification body.
        builder.setSmallIcon(R.drawable.ic_stat_proxy)
        val notification = builder.build()

        val manager = getSystemService(NotificationManager::class.java)
        android.util.Log.i("NetworkProxy", "notification[$title]: ${body.collapsed}")
        if (changed) manager.notify(1, notification)
        if (isRunning) startForeground(1, notification) else stopForeground(true)
    }


    /** Serve our endpoint certificate PEM inline; never forwards upstream. */
    private fun serveEndpointCertPem(
        output: java.io.OutputStream,
        sessionId: String,
        requestId: String
    ) {
        try {
            val pem = EndpointCert.pem(this)
            if (pem == null) {
                val msg = "Endpoint certificate unavailable"
                output.write(
                    ("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\n" +
                        "Content-Length: ${msg.length}\r\nConnection: close\r\n\r\n$msg").toByteArray()
                )
                output.flush()
                if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                    sessionId, requestId, 503, 0, NetworkScenario.UNKNOWN
                )
                return
            }
            val body = pem.toByteArray(Charsets.UTF_8)
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/x-pem-file\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(body)
            output.flush()
            requestCount.incrementAndGet()
            bytesOut.addAndGet(body.size.toLong())
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(
                sessionId, requestId, 200, body.size.toLong(), NetworkScenario.SUCCESS
            )
            ProxyMetrics.event("Served endpoint certificate (${humanBytesShort(body.size.toLong())})")
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

    /**
     * Verbose response payload logging toggle, shared with the dashboard
     * checkbox (MainActivity.VERBOSE_LOGGING_PREF). Off by default: the
     * response bodies this retains are model output, so capturing them
     * is opt-in.
     */
    private fun verboseLogging(): Boolean =
        getSharedPreferences("gatekeeper", Context.MODE_PRIVATE).getBoolean("verbose_logging", false)

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

    override fun onUnbind(intent: Intent?): Boolean {
        // The client is gone (ViewModel cleared / Activity destroyed). This is
        // a started service, so it keeps proxying — but the ViewModel that
        // owns stateCallback is unreachable, and the field is a strong ref to
        // it plus its LiveData. Release it here; a rebind re-installs.
        // Returning super keeps the default (false => onBind again on rebind).
        clearStateCallback()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopProxyAndRelease()
        // After, not before: stopProxy's final `false` state is what flips the
        // UI to "stopped" while observers still exist.
        clearStateCallback()
        super.onDestroy()
    }
}

/**
 * First byte of a TLS record: handshake (content type 22).
 *
 * Used to tell a front-door TLS connection from the plaintext the host retry
 * proxy speaks, on the one listener that serves both. HTTP method names all
 * begin with an ASCII letter, so this byte cannot collide with a plaintext
 * request line.
 */
private const val TLS_HANDSHAKE_FIRST_BYTE = 0x16
