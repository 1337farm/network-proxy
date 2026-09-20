package com.forgerig.gatekeeper.proxy

import org.json.JSONObject
import java.io.File
import java.util.Base64

data class ProxyConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 0,
    val connectTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 120_000,
    val maxRetries: Int = 3,
    val retryBackoffMs: Long = 2_000,
    val metricsEnabled: Boolean = true,
    val metricsExportPath: String = "",
    val metricsRemoteEndpoint: String = ""
) {
    val isProxyEnabled: Boolean get() = enabled && host.isNotBlank()

    fun toJson(): String {
        val json = JSONObject()
        json.put("enabled", enabled)
        json.put("host", host)
        json.put("port", port)
        json.put("connectTimeoutMs", connectTimeoutMs)
        json.put("readTimeoutMs", readTimeoutMs)
        json.put("maxRetries", maxRetries)
        json.put("retryBackoffMs", retryBackoffMs)
        json.put("metricsEnabled", metricsEnabled)
        json.put("metricsExportPath", metricsExportPath)
        json.put("metricsRemoteEndpoint", metricsRemoteEndpoint)
        return json.toString()
    }

    companion object {
        fun fromJson(json: String): ProxyConfig {
            val obj = org.json.JSONObject(json)
            return ProxyConfig(
                enabled = obj.optBoolean("enabled", false),
                host = obj.optString("host", ""),
                port = obj.optInt("port", 0),
                connectTimeoutMs = obj.optInt("connectTimeoutMs", 30_000),
                readTimeoutMs = obj.optInt("readTimeoutMs", 120_000),
                maxRetries = obj.optInt("maxRetries", 3),
                retryBackoffMs = obj.optLong("retryBackoffMs", 2_000),
                metricsEnabled = obj.optBoolean("metricsEnabled", true),
                metricsExportPath = obj.optString("metricsExportPath", ""),
                metricsRemoteEndpoint = obj.optString("metricsRemoteEndpoint", "")
            )
        }
    }
}