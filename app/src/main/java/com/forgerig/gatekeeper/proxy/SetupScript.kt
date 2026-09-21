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

    /** Setup script. Safe to paste multiple times: bashrc append is
     *  marker-guarded, the jsonc edit converges, the probe is read-only. */
    fun build(context: Context, port: Int): String {
        return """
            |# >>> network-proxy setup (run in proot Ubuntu) >>>
            |# NOTE: the proxy itself runs inside the Android app on this
            |# phone — this only routes this terminal through it. Start the
            |# app with the Start button first, then paste this block.
            |# Safe to run repeatedly: every step below converges.
            |export HTTP_PROXY="http://127.0.0.1:${port}" HTTPS_PROXY="http://127.0.0.1:${port}" NO_PROXY="localhost,127.0.0.1,::1"
            |export http_proxy="http://127.0.0.1:${port}" https_proxy="http://127.0.0.1:${port}" no_proxy="localhost,127.0.0.1,::1"
            |# Both cases: some runtimes (node/bun) only honor lowercase.
            |# ::1 in NO_PROXY keeps TUI<->server loopback direct (no loops).
            |# Persist the same env into ~/.bashrc inside fenced markers, so
            |# the cleanup script can find and remove exactly this block.
            |# Inserted ABOVE the PS1 early-exit guard ('[ -z ... ] && return')
            |# (present in default bashrc files) so the vars also apply to
            |# non-interactive shells that explicitly 'source ~/.bashrc'
            |# (e.g. tool/CI invocations, which never see lines below it).
            |# The marker check keeps repeat runs from appending duplicates.
            |python3 - "${port}" ~/.bashrc <<'PYEOF'
            |import sys
            |port, rc = sys.argv[1], sys.argv[2]
            |begin = "# >>> network-proxy (managed) >>>"
            |try:
            |    text = open(rc).read()
            |except FileNotFoundError:
            |    text = ""
            |if begin in text:
            |    print("bashrc: managed block already present (idempotent skip)")
            |else:
            |    block = "\n".join([
            |        begin,
            |        'export HTTP_PROXY="http://127.0.0.1:' + port + '"',
            |        'export HTTPS_PROXY="http://127.0.0.1:' + port + '"',
            |        'export NO_PROXY="localhost,127.0.0.1,::1"',
            |        'export http_proxy="http://127.0.0.1:' + port + '"',
            |        'export https_proxy="http://127.0.0.1:' + port + '"',
            |        'export no_proxy="localhost,127.0.0.1,::1"',
            |        "# <<< network-proxy (managed) <<<",
            |    ]) + "\n"
            |    guard = '[ -z "${"$"}PS1" ] && return'
            |    if guard in text:
            |        text = text.replace(guard, block + guard, 1)
            |        where = "above PS1 guard"
            |    else:
            |        text = text.rstrip("\n") + "\n" + block
            |        where = "appended"
            |    open(rc, "w").write(text)
            |    print("bashrc: managed block " + where)
            |PYEOF
            |# Tell opencode to absorb 429s itself (proxy also retries).
            |# Converges: re-running rewrites the same values, no duplication.
            |python3 -c "
            |import re,pathlib,sys
            |p=pathlib.Path.home()/'.config/opencode/opencode.jsonc'
            |t=p.read_text() if p.exists() else '{}'
            |t2=re.sub(r'\"maxRetries\"\s*:\s*\d+', '\"maxRetries\": 3', t, count=1)
            |t2=re.sub(r'\"retryDelay\"\s*:\s*\d+', '\"retryDelay\": 2000', t2, count=1)
            |changed = t2 != t or not p.exists()
            |p.parent.mkdir(parents=True, exist_ok=True)
            |p.write_text(t2)
            |print('opencode.jsonc: maxRetries=3 retryDelay=2000' + ('' if changed else ' (already set)'))
            |"
            |# If 'opencode serve' is already running, RESTART it from this shell:
            |# exports only affect servers started after them (check with:
            |# tr '\\0' '\\n' </proc/$(pgrep -f '^opencode serve' | head -1)/environ | grep -i proxy).
|# Sanity probe: fail fast here if the app proxy isn't listening.
             |# Read-only: safe to run any number of times.
             |python3 -c "import socket,sys; s=socket.create_connection(('127.0.0.1',${port}), timeout=5); s.close(); print('proxy probe: listening on 127.0.0.1:${port}')"
             |# --- MITM CA trust (only matters when Decrypt-HTTPS is ON) ---
             |# Fetches the CA straight from the running proxy — no manual
             |# Export step: `curl http://127.0.0.1:${port}/ca.pem` (works
             |# with or without proxy env, direct-to-port included). Falls
             |# back to a previously exported file when the proxy isn't up.
             |# Trusted for this terminal: Ubuntu store (best effort),
             |# Termux/Python/Node bundles, plus persistent exports.
             |# Skips cleanly when absent (Decrypt OFF = opaque tunnels,
             |# nothing breaks).
             |CA_PEM=""
             |CA_TMP="${"$"}HOME/.config/network-proxy/ca.pem"
             |mkdir -p "${"$"}HOME/.config/network-proxy"
             |if command -v curl >/dev/null 2>&1; then
             |  curl -sS -m 10 --noproxy '*' "http://127.0.0.1:${port}/ca.pem" -o "${"$"}CA_TMP.tmp" 2>/dev/null && \\
             |    grep -q "BEGIN CERTIFICATE" "${"$"}CA_TMP.tmp" 2>/dev/null && mv "${"$"}CA_TMP.tmp" "${"$"}CA_TMP" && echo "MITM CA fetched from proxy (:${port}/ca.pem)"
             |  rm -f "${"$"}CA_TMP.tmp" 2>/dev/null || true
             |fi
             |if [[ ! -s "${"$"}CA_TMP" ]]; then
             |  for c in /sdcard/Download/network-proxy-ca.pem "${"$"}HOME/Download/network-proxy-ca.pem"; do
             |    if [[ -f "${"$"}c" ]]; then cp "${"$"}c" "${"$"}CA_TMP"; echo "MITM CA taken from ${"$"}c (proxy fetch skipped)"; break; fi
             |  done
             |fi
             |if [[ -s "${"$"}CA_TMP" ]]; then
             |  CA_PEM="${"$"}CA_TMP"
             |  SYS_BUNDLE=""; for b in /etc/ssl/certs/ca-certificates.crt "${"$"}PREFIX/etc/tls/cert.pem"; do
             |    if [[ -f "${"$"}b" ]]; then SYS_BUNDLE="${"$"}b"; break; fi
             |  done
             |  if [[ -n "${"$"}SYS_BUNDLE" ]]; then
             |    cat "${"$"}SYS_BUNDLE" "${"$"}HOME/.config/network-proxy/ca.pem" > "${"$"}HOME/.config/network-proxy/bundle.pem"
             |    export SSL_CERT_FILE="${"$"}HOME/.config/network-proxy/bundle.pem"
             |    export REQUESTS_CA_BUNDLE="${"$"}HOME/.config/network-proxy/bundle.pem"
             |    export NODE_EXTRA_CA_CERTS="${"$"}HOME/.config/network-proxy/ca.pem"
             |  fi
             |  if [[ -d /usr/local/share/ca-certificates ]]; then
             |    cp "${"$"}HOME/.config/network-proxy/ca.pem" /usr/local/share/ca-certificates/network-proxy-ca.crt 2>/dev/null || true
             |    update-ca-certificates 2>/dev/null || true
             |  fi
             |  if ! grep -q "network-proxy-ca" ~/.bashrc 2>/dev/null; then
             |    cat >> ~/.bashrc <<'CAEOF'
             |# >>> network-proxy-ca (managed) >>>
             |export SSL_CERT_FILE="${"$"}HOME/.config/network-proxy/bundle.pem"
             |export REQUESTS_CA_BUNDLE="${"$"}HOME/.config/network-proxy/bundle.pem"
             |export NODE_EXTRA_CA_CERTS="${"$"}HOME/.config/network-proxy/ca.pem"
             |# <<< network-proxy-ca (managed) <<<
             |CAEOF
             |  fi
             |  echo "MITM CA trusted for this terminal (bundle rebuilt)"
             |else
             |  echo "MITM CA not found — HTTPS will be tunneled opaque (no MITM)"
             |fi
             |# <<< network-proxy setup <<<
        """.trimMargin()
    }

    /** Cleanup script. Safe to run multiple times: every removal is
     *  guarded (missing file / already-removed lines are no-ops), unsets
     *  are idempotent, and the probes never mutate state. Also purges
     *  legacy unmarked exports left by older setup builds. */
    fun cleanup(port: Int = 3128): String {
        return """
            |# >>> network-proxy cleanup (run in proot Ubuntu) >>>
            |# NOTE: the proxy runs inside the Android app, not here — there
            |# is no daemon pid lent to kill on this side. This only removes
            |# the env/config this setup script added. Stop the app via its
            |# Stop button to actually shut the proxy down.
            |# Safe to run repeatedly: second run finds nothing and no-ops.
            |if [[ -f ~/.bashrc ]]; then
            |  python3 - ~/.bashrc <<'PYEOF'
            |import sys
            |rc = sys.argv[1]
            |begin, end = "# >>> network-proxy", "# <<< network-proxy"
            |lines = open(rc).read().splitlines(keepends=True)
            |# Pass 1: drop fenced marker blocks plus legacy unmarked lines
            |# from older setup builds (bare 127.0.0.1 proxy exports and the
            |# old grep-guard line). Anything else is left untouched.
            |def managed(ln):
            |    s = ln.strip()
            |    if "network-proxy" in ln:
            |        return True
            |    if s.startswith("export ") and "127.0.0.1" in s and "PROXY" in s.upper():
            |        return True
            |    return False
            |kept, skipping = [], False
            |removed_idx, removed = set(), 0
            |for i, ln in enumerate(lines):
            |    if begin in ln:
            |        skipping = True
            |        removed_idx.add(i)
            |        removed += 1
            |        continue
            |    if end in ln:
            |        skipping = False
            |        removed_idx.add(i)
            |        removed += 1
            |        continue
            |    if skipping or managed(ln):
            |        removed_idx.add(i)
            |        removed += 1
            |        continue
            |    kept.append((i, ln))
            |# Pass 2: drop orphan heredoc closers — a bare EOF immediately
            |# following a removed line is debris from our own old block.
            |final = [(i, ln) for (i, ln) in kept
            |        if not (ln.strip() == "EOF" and (i - 1) in removed_idx)]
            |removed += len(kept) - len(final)
            |open(rc, "w").writelines(ln for (_, ln) in final)
            |print(f"Cleanup pass: removed {removed} managed line(s) from " + rc
            |      + (" (nothing to do)" if removed == 0 else ""))
            |PYEOF
            |fi
            |if [ -n "${"$"}{PROXY_METRICS_FILE:-}" ]; then METRICS_FILE="${"$"}PROXY_METRICS_FILE"; else METRICS_FILE="${"$"}HOME/.cache/network-proxy/metrics.jsonl"; fi
            |if [[ -f "${"$"}METRICS_FILE" ]]; then
            |  > "${"$"}METRICS_FILE"
            |  echo "Cleared local metrics copy: ${"$"}METRICS_FILE"
            |fi
            |unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy NO_PROXY no_proxy
            |echo "Unset proxy env vars (both cases; re-running is a no-op)"
            |echo "Checking proxy is no longer used..."
            |if command -v curl >/dev/null 2>&1; then
            |  curl -s -m 5 -o /dev/null -w "direct probe http_code=%{http_code}\n" https://api.github.com/zen || echo "(probe failed — check network)"
            |else
            |  python3 -c "import socket; s=socket.create_connection(('8.8.8.8',53),timeout=5); s.close(); print('direct connectivity OK (proxy bypassed)')" 2>/dev/null || echo "(probe failed — check network)"
            |fi
            |echo "=== Cleanup complete ==="
            |echo "To stop the actual proxy, tap Stop in the Android app."
            |echo "Then run 'source ~/.bashrc' or open a new terminal to fully apply."
            |# <<< network-proxy cleanup <<<
        """.trimMargin()
    }
}
