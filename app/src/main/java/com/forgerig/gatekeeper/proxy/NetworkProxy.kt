package com.forgerig.gatekeeper.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null
)

data class HttpResponse(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, List<String>>
)

interface NetworkProxy {
    suspend fun executeRequest(request: HttpRequest): HttpResponse
    suspend fun resumeSession(sessionId: String): HttpResponse
    fun getPendingSessions(): List<SessionState>
    fun clearCompletedSessions()
}