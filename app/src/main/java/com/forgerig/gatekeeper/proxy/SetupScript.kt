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
     *  marker-guarded, the jsonc edit converges, the probe is read-only.
     *  NOTE: the emitted script must stay pure ASCII (no em-dashes, no
     *  smart quotes, no arrows) - proot/Termux locales mangle multibyte
     *  chars on paste and corrupt the paste. [scriptFor] is the pure,
     *  unit-tested core; [build] keeps the Context signature for callers. */
    /**
     * No port parameter: there is only one ([PROXY_PORT]), so the script
     * and the listener cannot drift apart.
     */
    fun build(context: Context): String = scriptFor(PROXY_PORT)

    /**
     * Non-LLM hosts that bypass the router entirely (direct connection).
     * The router is an LLM broker; bulk downloads, registries and VCS
     * hosting gain nothing from it and only burn phone CPU/battery plus
     * a single egress IP. Suffix-matched by most HTTP clients.
     * NOTE: never add LLM provider hosts here (openrouter, nvidia, zen, …) — those MUST stay proxied.
     */
    const val BYPASS_HOSTS = "github.com,githubusercontent.com," +
        "objects.githubusercontent.com,release-assets.githubusercontent.com," +
        "registry.npmjs.org,nodejs.org,pypi.org,files.pythonhosted.org," +
        "maven.apache.org,repo.maven.apache.org,plugins.gradle.org,services.gradle.org," +
        "dl.google.com,storage.googleapis.com,blob.core.windows.net," +
        "crates.io,static.crates.io"

    /** Pure core: no Context needed, safe to call from JVM unit tests. */
    fun scriptFor(port: Int): String {
        return """
            |# >>> forge-router setup (run in Termux or proot Ubuntu; auto-detects) >>>
            |# NOTE: the router itself runs inside the Android app on this
            |# phone - this only routes this terminal through it. Start the
            |# app with the Start button first, then paste this block.
            |# Safe to run repeatedly: every step below converges.
            |# No CA, no trust stores: plain-HTTP harness traffic needs no
            |# TLS at all, and TLS front-door clients pin the router's own
            |# endpoint certificate (fetched below) directly.
            |export HTTP_PROXY="http://127.0.0.1:${port}" HTTPS_PROXY="http://127.0.0.1:${port}" NO_PROXY="localhost,127.0.0.1,::1,${BYPASS_HOSTS}"
            |export http_proxy="http://127.0.0.1:${port}" https_proxy="http://127.0.0.1:${port}" no_proxy="localhost,127.0.0.1,::1,${BYPASS_HOSTS}"
            |# Both cases: some runtimes (node/bun) only honor lowercase.
            |# ::1 in NO_PROXY keeps TUI<->server loopback direct (no loops).
            |# BYPASS_HOSTS keeps non-LLM traffic (git hosts, registries,
            |# maven/gradle, big downloads) off the proxy entirely - it is
            |# an LLM broker, not a general egress.
            |# Persist the same env into ~/.bashrc inside fenced markers, so
            |# the cleanup script can find and remove exactly this block.
            |# Inserted ABOVE the PS1 early-exit guard ('[ -z ... ] && return')
            |# (present in default bashrc files) so the vars also apply to
            |# non-interactive shells that explicitly 'source ~/.bashrc'
            |# (e.g. tool/CI invocations, which never see lines below it).
            |# The cert-path export MUST live in this same above-guard block:
            |# opencode serve is started non-interactively (opencode-start),
            |# so an export below the guard never applies to it and pinned
            |# TLS clients fail to verify the router.
            |# The marker check keeps repeat runs from appending duplicates.
            |python3 - "${port}" ~/.bashrc <<'PYEOF'
            |import sys
            |port, rc = sys.argv[1], sys.argv[2]
            |begin = "# >>> forge-router (managed) >>>"
            |cert_begin = "# >>> forge-router-cert (managed) >>>"
            |cert_end = "# <<< forge-router-cert (managed) <<<"
            |legacy_begin = "# >>> network-proxy (managed) >>>"
            |legacy_ca_begin = "# >>> network-proxy-ca (managed) >>>"
            |legacy_ca_end = "# <<< network-proxy-ca (managed) <<<"
            |try:
            |    text = open(rc).read()
            |except FileNotFoundError:
            |    text = ""
            |# Pass 1: strip legacy standalone CA blocks (a CA block whose begin
            |# line is NOT inside a managed proxy block - i.e. the legacy
            |# below-guard layout). The unified block nests the cert markers
            |# INSIDE the router markers, so only strip when the CA begin
            |# appears before any proxy begin, ours or the stable app's.
            |lines = text.splitlines(keepends=True)
            |kept, skipping, stripped, in_proxy = [], False, 0, False
            |for ln in lines:
            |    if begin in ln or legacy_begin in ln:
            |        in_proxy = True
            |        kept.append(ln)
            |        continue
            |    if "# <<< forge-router (managed) <<<" in ln or "# <<< network-proxy (managed) <<<" in ln:
            |        in_proxy = False
            |        kept.append(ln)
            |        continue
            |    if legacy_ca_begin in ln and not in_proxy:
            |        skipping = True
            |        stripped += 1
            |        continue
            |    if legacy_ca_end in ln and skipping:
            |        skipping = False
            |        stripped += 1
            |        continue
            |    if skipping:
            |        stripped += 1
            |        continue
            |    kept.append(ln)
            |text = "".join(kept)
            |# Pass 2: drop the short-lived forge-variant proxy block
            |# (network-proxy markers carrying our own port). The stable
            |# :3128 block, if present, belongs to the other app and is left
            |# untouched.
            |lines = text.splitlines(keepends=True)
            |kept, buf, in_legacy = [], [], False
            |for ln in lines:
            |    if legacy_begin in ln:
            |        in_legacy = True
            |        buf = [ln]
            |        continue
            |    if in_legacy:
            |        buf.append(ln)
            |        if "# <<< network-proxy (managed) <<<" in ln:
            |            in_legacy = False
            |            if any("3129" in b for b in buf):
            |                stripped += len(buf)
            |            else:
            |                kept.extend(buf)
            |            buf = []
            |        continue
            |    kept.append(ln)
            |text = "".join(kept)
            |# Unified block present (router markers + nested cert markers):
            |# converge, do not rewrite. Persist the stale-strip when it
            |# removed something, then stop.
            |if begin in text and cert_begin in text:
            |    if stripped:
            |        open(rc, "w").write(text)
            |    print("bashrc: managed block already present (idempotent skip)")
            |    raise SystemExit(0)
            |if begin in text:
            |    # Legacy router-only block (no nested cert exports): drop it so
            |    # the rewrite below installs the unified block with the cert
            |    # path above the guard (opencode serve needs it there).
            |    lines2 = text.splitlines(keepends=True)
            |    kept2, skipping2 = [], False
            |    for ln in lines2:
            |        if begin in ln:
            |            skipping2 = True
            |            stripped += 1
            |            continue
            |        if "# <<< forge-router (managed) <<<" in ln:
            |            skipping2 = False
            |            stripped += 1
            |            continue
            |        if skipping2:
            |            stripped += 1
            |            continue
            |        kept2.append(ln)
            |    text = "".join(kept2)
            |    lines2 = text.splitlines(keepends=True)
            |    kept2, skipping2 = [], False
            |    for ln in lines2:
            |        if begin in ln:
            |            skipping2 = True
            |            stripped += 1
            |            continue
            |        if "# <<< forge-router (managed) <<<" in ln:
            |            skipping2 = False
            |            stripped += 1
            |            continue
            |        if skipping2:
            |            stripped += 1
            |            continue
            |        kept2.append(ln)
            |    text = "".join(kept2)
            |if begin in text:
            |    print("bashrc: managed block already present (idempotent skip)")
            |else:
            |    block = "\n".join([
            |        begin,
            |        'export HTTP_PROXY="http://127.0.0.1:' + port + '"',
            |        'export HTTPS_PROXY="http://127.0.0.1:' + port + '"',
            |        'export NO_PROXY="localhost,127.0.0.1,::1,${BYPASS_HOSTS}"',
            |        'export http_proxy="http://127.0.0.1:' + port + '"',
            |        'export https_proxy="http://127.0.0.1:' + port + '"',
            |        'export no_proxy="localhost,127.0.0.1,::1,${BYPASS_HOSTS}"',
            |        cert_begin,
            |        'export FORGE_ROUTER_CERT="${"$"}HOME/.config/forge-router/endpoint-cert.pem"',
            |        cert_end,
            |        "# <<< forge-router (managed) <<<",
            |    ]) + "\n"
            |    guard = '[ -z "${"$"}PS1" ] && return'
            |    if guard in text:
            |        text = text.replace(guard, block + guard, 1)
            |        where = "above PS1 guard"
            |    else:
            |        text = text.rstrip("\n") + "\n" + block
            |        where = "appended"
            |    open(rc, "w").write(text)
            |    print("bashrc: managed block " + where + (" (stale block removed)" if stripped else ""))
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
             |# Sanity probe: warn (don't abort) if the router isn't up yet.
             |# The router port may still be starting; the cert fetch below
             |# tolerates that and falls back to a previously fetched file.
             |python3 -c "import socket; s=socket.create_connection(('127.0.0.1',${port}), timeout=5); s.close(); print('router probe: listening on 127.0.0.1:${port}')" || \
             |  echo "router probe: 127.0.0.1:${port} refused - start the Forge Router app, then re-paste this script"
             |# --- Router endpoint certificate (TLS front-door pinning) ---
             |# Fetches the pinned cert straight from the running router - no
             |# CA, no trust store changes: TLS clients pin this file
             |# directly, and plain-HTTP harness traffic needs no TLS at all.
             |# \`curl http://127.0.0.1:${port}/endpoint-cert.pem\` works with
             |# or without proxy env, direct-to-port included. Falls back to
             |# a previously fetched file when the router isn't up.
             |CERT_PEM=""
             |CERT_TMP="${"$"}HOME/.config/forge-router/endpoint-cert.pem"
             |mkdir -p "${"$"}HOME/.config/forge-router"
             |if command -v curl >/dev/null 2>&1; then
             |  curl -sS -m 10 --noproxy '*' "http://127.0.0.1:${port}/endpoint-cert.pem" -o "${"$"}CERT_TMP.tmp" 2>/dev/null && \
             |    grep -q "BEGIN CERTIFICATE" "${"$"}CERT_TMP.tmp" 2>/dev/null && mv "${"$"}CERT_TMP.tmp" "${"$"}CERT_TMP" && echo "Router cert fetched from :${port}/endpoint-cert.pem"
             |  rm -f "${"$"}CERT_TMP.tmp" 2>/dev/null || true
             |fi
             |if [[ -s "${"$"}CERT_TMP" ]]; then
             |  CERT_PEM="${"$"}CERT_TMP"
             |  export FORGE_ROUTER_CERT="${"$"}CERT_PEM"
             |  echo "Router cert ready for this terminal (${"$"}CERT_PEM}); pin TLS clients to it"
             |else
             |  echo "Router cert not found - start the app and re-paste, or fetch manually: curl --noproxy '*' http://127.0.0.1:${port}/endpoint-cert.pem"
             |fi
             |# <<< forge-router setup <<<
        """.trimMargin()
    }

    /** Cleanup script. Safe to run multiple times: every removal is
     *  guarded (missing file / already-removed lines are no-ops), unsets
     *  are idempotent, and the probes never mutate state. Also purges
     *  legacy unmarked exports left by older setup builds. */
    fun cleanup(port: Int = PROXY_PORT): String {
        return """
            |# >>> forge-router cleanup (run in proot Ubuntu) >>>
            |# NOTE: the router runs inside the Android app, not here - there
            |# is no daemon pid lent to kill on this side. This only removes
            |# the env/config this setup script added. Stop the app via its
            |# Stop button to actually shut the router down.
            |# Safe to run repeatedly: second run finds nothing and no-ops.
            |# Only forge-router blocks and our own port's exports are
            |# removed: the stable proxy's :3128 block belongs to the other
            |# app and is left untouched.
            |if [[ -f ~/.bashrc ]]; then
            |  python3 - ~/.bashrc $port <<'PYEOF'
            |import sys
            |rc, port = sys.argv[1], sys.argv[2]
            |begin, end = "# >>> forge-router", "# <<< forge-router"
            |lines = open(rc).read().splitlines(keepends=True)
            |# Pass 1: drop fenced marker blocks plus legacy unmarked lines
            |# from older setup builds (bare 127.0.0.1 exports for OUR port
            |# and the old grep-guard line). Anything else is left untouched.
            |def managed(ln):
            |    s = ln.strip()
            |    if "forge-router" in ln:
            |        return True
            |    if s.startswith("export ") and ("127.0.0.1:" + port) in s and "PROXY" in s.upper():
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
            |# Pass 2: drop orphan heredoc closers - a bare EOF immediately
            |# following a removed line is debris from our own old block.
            |final = [(i, ln) for (i, ln) in kept
            |        if not (ln.strip() == "EOF" and (i - 1) in removed_idx)]
            |removed += len(kept) - len(final)
            |open(rc, "w").writelines(ln for (_, ln) in final)
            |print(f"Cleanup pass: removed {removed} managed line(s) from " + rc
            |      + (" (nothing to do)" if removed == 0 else ""))
            |PYEOF
            |fi
            |if [[ -f "${"$"}HOME/.cache/forge-router/metrics.jsonl" ]]; then
            |  > "${"$"}HOME/.cache/forge-router/metrics.jsonl"
            |  echo "Cleared local metrics copy: ${"$"}HOME/.cache/forge-router/metrics.jsonl"
            |fi
            |unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy NO_PROXY no_proxy
            |echo "Unset proxy env vars (both cases; re-running is a no-op)"
            |echo "Checking proxy is no longer used..."
            |if command -v curl >/dev/null 2>&1; then
            |  curl -s -m 5 -o /dev/null -w "direct probe http_code=%{http_code}\n" https://api.github.com/zen || echo "(probe failed - check network)"
            |else
            |  python3 -c "import socket; s=socket.create_connection(('8.8.8.8',53),timeout=5); s.close(); print('direct connectivity OK (proxy bypassed)')" 2>/dev/null || echo "(probe failed - check network)"
            |fi
            |echo "=== Cleanup complete ==="
            |echo "To stop the actual router, tap Stop in the Android app."
            |echo "Then run 'source ~/.bashrc' or open a new terminal to fully apply."
            |# <<< forge-router cleanup <<<
        """.trimMargin()
    }
}
