package com.forgerig.gatekeeper.proxy

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface

/** Builds the copy-paste terminal script that routes opencode through this proxy. */
object SetupScript {

    fun lanIp(context: Context): String {
        // Prefer WiFi manager IP (little-endian int on most devices).
        try {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return listOf(ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
                    .joinToString(".")
            }
        } catch (_: Exception) {}
        // Fallback: first non-loopback IPv4 interface.
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.let { return it.hostAddress ?: "" }
        } catch (_: Exception) {}
        return ""
    }

    fun build(context: Context, port: Int): String {
        return """
            |export HTTP_PROXY="http://127.0.0.1:${port}"
            |export HTTPS_PROXY="http://127.0.0.1:${port}"
            |export NO_PROXY="localhost,127.0.0.1"
            |grep -q "network-proxy (managed)" ~/.bashrc 2>/dev/null || cat >> ~/.bashrc <<'EOF'
            |export HTTP_PROXY="http://127.0.0.1:${port}"
            |export HTTPS_PROXY="http://127.0.0.1:${port}"
            |export NO_PROXY="localhost,127.0.0.1"
            |EOF
            |python3 -c "
            |import re,pathlib
            |p=pathlib.Path.home()/'.config/opencode/opencode.jsonc'
            |t=p.read_text()
            |t=re.sub(r'\"maxRetries\"\s*:\s*\d+', '\"maxRetries\": 3', t, count=1)
            |t=re.sub(r'\"retryDelay\"\s*:\s*\d+', '\"retryDelay\": 2000', t, count=1)
            |p.write_text(t)
            |print('opencode.jsonc: maxRetries=3 retryDelay=2000')
            |"
            |python3 -c "import socket; s=socket.create_connection(('127.0.0.1',${port}), timeout=5); s.close(); print('proxy probe: listening on 127.0.0.1:${port}')"
        """.trimMargin()
    }
}
