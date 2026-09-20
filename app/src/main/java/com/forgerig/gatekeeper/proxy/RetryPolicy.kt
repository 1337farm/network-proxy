package com.forgerig.gatekeeper.proxy

import java.util.concurrent.ThreadLocalRandom

data class RetryPolicy(
    val maxRetries: Int = 3,
    val baseBackoffMs: Long = 2_000,
    val maxBackoffMs: Long = 30_000,
    val jitter: Boolean = true
) {
    fun nextDelay(attempt: Int): Long {
        val exponential = baseBackoffMs * (1L shl attempt.coerceAtMost(5))
        val capped = exponential.coerceAtMost(maxBackoffMs)
        return if (jitter) capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1)
        else capped
    }

    fun shouldRetry(attempt: Int): Boolean = attempt < maxRetries
}