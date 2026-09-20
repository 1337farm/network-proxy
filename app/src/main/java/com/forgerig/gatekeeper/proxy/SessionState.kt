package com.forgerig.gatekeeper.proxy

import kotlinx.serialization.Serializable

enum class NetworkScenario {
    RETRYABLE_HTTP,
    PERMANENT_FAILURE,
    CONNECTION_LOST,
    PARTIAL_RESPONSE,
    DNS_FAILURE,
    TLS_FAILURE,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    CONNECTION_RESET,
    SUCCESS,
    UNKNOWN
}

enum class RetryType {
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    HTTP_RETRY,
    CONNECTION_RESET,
    DNS_FAILURE,
    TLS_FAILURE
}

@Serializable
data class SessionState(
    val sessionId: String,
    val url: String,
    val destPath: String,
    var bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val partFilePath: String,
    var lastHeartbeat: Long = System.currentTimeMillis(),
    var status: SessionStatus = SessionStatus.PENDING,
    val headers: MutableMap<String, String> = mutableMapOf(),
    val scenarios: MutableList<NetworkScenario> = mutableListOf()
) {
    fun markHeartbeat() { lastHeartbeat = System.currentTimeMillis() }
}

enum class SessionStatus { PENDING, DOWNLOADING, COMPLETED, FAILED }