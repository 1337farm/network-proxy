package com.forgerig.gatekeeper.proxy

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.EOFException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

object ScenarioClassifier {

    fun classifyError(error: Throwable): NetworkScenario {
        return when (error) {
            is SocketTimeoutException -> when {
                error.message?.contains("connect", true) == true -> NetworkScenario.CONNECT_TIMEOUT
                else -> NetworkScenario.READ_TIMEOUT
            }
            is ConnectException -> NetworkScenario.CONNECTION_LOST
            is UnknownHostException -> NetworkScenario.DNS_FAILURE
            is SSLHandshakeException -> NetworkScenario.TLS_FAILURE
            is EOFException -> NetworkScenario.PARTIAL_RESPONSE
            is IOException -> when {
                error.message?.contains("reset", true) == true -> NetworkScenario.CONNECTION_RESET
                error.message?.contains("broken", true) == true -> NetworkScenario.CONNECTION_RESET
                else -> NetworkScenario.UNKNOWN
            }
            else -> NetworkScenario.UNKNOWN
        }
    }

    fun classifyResponse(code: Int): NetworkScenario {
        return when (code) {
            in 200..299 -> NetworkScenario.SUCCESS
            408, 429, 500, 502, 503, 504, 423, 425, 426, 431, 451 -> NetworkScenario.RETRYABLE_HTTP
            400, 401, 403, 404, 405, 410, 418, 422, 428 -> NetworkScenario.PERMANENT_FAILURE
            else -> NetworkScenario.UNKNOWN
        }
    }

    fun toRetryAction(scenario: NetworkScenario): RetryAction {
        return when (scenario) {
            NetworkScenario.PERMANENT_FAILURE -> RetryAction.STOP
            NetworkScenario.RETRYABLE_HTTP -> RetryAction.RETRY_WITH_BACKOFF
            NetworkScenario.CONNECTION_RESET -> RetryAction.RETRY_IMMEDIATE
            NetworkScenario.PARTIAL_RESPONSE -> RetryAction.RESUME_FROM_OFFSET
            NetworkScenario.DNS_FAILURE -> RetryAction.RETRY_WITH_BACKOFF
            NetworkScenario.TLS_FAILURE -> RetryAction.RETRY_WITH_BACKOFF
            NetworkScenario.READ_TIMEOUT -> RetryAction.RESUME_FROM_OFFSET
            NetworkScenario.CONNECT_TIMEOUT -> RetryAction.RETRY_WITH_BACKOFF
            NetworkScenario.CONNECTION_LOST -> RetryAction.WAIT_FOR_CONNECTIVITY
            NetworkScenario.SUCCESS -> RetryAction.STOP
            else -> RetryAction.RETRY_WITH_BACKOFF
        }
    }
}

enum class RetryAction {
    STOP,
    RETRY_IMMEDIATE,
    RETRY_WITH_BACKOFF,
    RESUME_FROM_OFFSET,
    WAIT_FOR_CONNECTIVITY
}