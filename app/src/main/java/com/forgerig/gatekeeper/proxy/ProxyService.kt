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
    private var client: OkHttpClient? = null
    private var serverThread: Thread? = null
    private var pool = Executors.newFixedThreadPool(POOL_SIZE)
    private var statsCallback: ((Int, Long, Int, Int) -> Unit)? = null
    private var stateCallback: ((Boolean, String?) -> Unit)? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val requestCount = AtomicLong(0)
    private val bytesOut = AtomicLong(0)
    private val inFlight = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun isRunning(): Boolean = running.get() == 1
    fun getLastError(): String? = lastError
    fun getStats(): Triple<Long, Long, Int> =
        Triple(requestCount.get(), bytesOut.get(), inFlight.get())

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

        if (running.get() == 1) {
            stopProxy()
        }
        lastError = null
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
        inFlight.incrementAndGet()
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

            val targetUrl = if (url.startsWith("http")) url else "http://${socket.inetAddress.hostAddress}$url"
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

            val reqHeaders = headers.toHeaders()
            val mediaType = "application/octet-stream".toMediaType()
            var attempt = 0
            val policy = RetryPolicy(maxRetries = 5, baseBackoffMs = 2_000)
            while (true) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .method(method, body?.toRequestBody(mediaType))
                        .headers(reqHeaders)
                        .build()

                    val response = client?.newCall(request)?.execute()
                    response?.use { resp ->
                        val scenario = ScenarioClassifier.classifyResponse(resp.code)
                        val action = ScenarioClassifier.toRetryAction(scenario)
                        if (action == RetryAction.RETRY_WITH_BACKOFF && policy.shouldRetry(attempt)) {
                            val retryAfter = resp.headers["Retry-After"]?.toLongOrNull()
                            val delayMs = retryAfter?.times(1000) ?: policy.nextDelay(attempt)
                            if (metricsEnabled) ProxyMetrics.recordRetry(sessionId, requestId, attempt, scenario, delayMs)
                            attempt++
                            Thread.sleep(delayMs)
                            return@use
                        }
                        val statusLine = "HTTP/1.1 ${resp.code} ${resp.message}\r\n"
                        output.write(statusLine.toByteArray())
                        for ((key, values) in resp.headers.toMultimap()) {
                            for (value in values) {
                                output.write("$key: $value\r\n".toByteArray())
                            }
                        }
                        output.write("\r\n".toByteArray())
                        var transferred = 0L
                        resp.body?.byteStream()?.use { bs ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = bs.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                transferred += n
                            }
                        }
                        output.flush()
                        requestCount.incrementAndGet()
                        bytesOut.addAndGet(transferred)
                        statsCallback?.invoke(1, transferred, 0, 0)
                        if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, resp.code, transferred, scenario)
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
                    return
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inFlight.decrementAndGet()
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
        var upstream: Socket? = null
        try {
            upstream = Socket()
            upstream.connect(InetSocketAddress(host, port), 30_000)
            upstream.setSoTimeout(120_000)
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            clientOut.flush()
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, 200, 0, NetworkScenario.SUCCESS)
            requestCount.incrementAndGet()
            val upIn = upstream.getInputStream()
            val upOut = upstream.getOutputStream()
            val cIn = clientSocket.getInputStream()
            val t1 = Thread { relay(cIn, upOut) }
            val t2 = Thread { relay(upIn, clientOut) }
            t1.start(); t2.start()
            t1.join(); t2.join()
        } catch (e: Exception) {
            if (metricsEnabled) ProxyMetrics.recordRequestEnd(sessionId, requestId, 502, 0, ScenarioClassifier.classifyError(e))
            try {
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                clientOut.flush()
            } catch (_: Exception) {}
        } finally {
            inFlight.decrementAndGet()
            try { upstream?.close() } catch (_: Exception) {}
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun relay(input: java.io.InputStream, output: java.io.OutputStream) {
        try {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                bytesOut.addAndGet(n.toLong())
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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
}