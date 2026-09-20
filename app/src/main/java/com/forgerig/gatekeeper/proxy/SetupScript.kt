package com.forgerig.gatekeeper.proxy

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface

/** Builds copy-paste terminal scripts for proot Ubuntu. */
object SetupScript {

    fun lanIp(context: Context): String {
        try {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return listOf(ip and 0xFF, ip shr 8 and 0xFF, ip shr 16 and 0xFF, ip shr 24 and 0xFF)
                    .joinToString(".")
            }
        } catch (_: Exception) {}
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

    fun cleanup(): String {
        return """
            |# >>> network-proxy cleanup (run in proot Ubuntu) >>>
            |if [[ -f ~/.bashrc ]]; then
            |  python3 - ~/.bashrc <<'PYEOF'
            |import sys
            |rc = sys.argv[1]
            |begin, end = "# >>> network-proxy", "# <<< network-proxy"
            |lines = open(rc).read().splitlines(keepends=True)
            |out, skipping = [], False
            |for ln in lines:
            |    if begin in ln:
            |        skipping = True
            |        continue
            |    if end in ln:
            |        skipping = False
            |        continue
            |    if not skipping:
            |        out.append(ln)
            |open(rc, "w").writelines(out)
            |PYEOF
            |  echo "Removed proxy env block from ~/.bashrc"
            |fi
            |if [[ -f /tmp/network-proxy.pid ]] && kill "$(cat /tmp/network-proxy.pid)" 2>/dev/null; then
            |  echo "Killed proxy daemon (pid $(cat /tmp/network-proxy.pid))"
            |else
            |  pkill -f "proxy --port 3128" 2>/dev/null && echo "Killed proxy daemon on port 3128" || true
            |fi
            |rm -f /tmp/network-proxy.pid /tmp/flaky.pid /tmp/proxy18080.pid 2>/dev/null
            |pkill -f "flaky.py" 2>/dev/null && echo "Killed flaky test server" || true
            |METRICS_FILE="${PROXY_METRICS_FILE:-$HOME/.cache/network-proxy/metrics.jsonl}"
            |if [[ -f "$METRICS_FILE" ]]; then
            |  > "$METRICS_FILE"
            |  echo "Cleared metrics: $METRICS_FILE"
            |fi
            |unset HTTP_PROXY HTTPS_PROXY NO_PROXY
            |echo "Unset HTTP_PROXY, HTTPS_PROXY, NO_PROXY"
            |echo "=== Cleanup complete ==="
            |echo "Run 'source ~/.bashrc' or open a new terminal to fully apply."
            |# <<< network-proxy cleanup <<<
        """.trimMargin()
    }
}
