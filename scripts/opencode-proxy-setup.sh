#!/usr/bin/env bash
# opencode-proxy-setup.sh — install/update opencode routing through a proxy.
#
# Backends:
#   proxy.py (default) — pure-Python proxy on this host with the absorbing
#                        retry plugin (429/5xx + Retry-After, metrics JSONL).
#   android            — the phone APK proxy (same retry policy, tunneled HTTPS).
#
# What it does (proxy.py backend):
#   1. Ensures `proxy.py` is installed for python3.14.
#   2. Starts it on --port (default 8080) with proxy_retry_plugin (nohup, pidfile).
#   3. Probes http + https through it (fails fast with a clear message).
#   4. Persists HTTP_PROXY/HTTPS_PROXY/NO_PROXY to your shell rc (opt-out with --no-persist).
#   5. Patches opencode.jsonc retry policy to proxy-owned values
#      (maxRetries=3, retryDelay=2000) via patch-opencode-jsonc.py (JSONC-safe).
#   6. Smoke-tests: curl via proxy, then `opencode run` via proxy (unless --no-smoke).
#
# Usage:
#   ./opencode-proxy-setup.sh [--backend proxy.py|android] [--host IP] [--port PORT]
#                            [--rc FILE] [--max-retries N] [--retry-delay MS]
#                            [--no-persist] [--no-smoke] [--check] [--stop]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="proxy.py"
HOST=""
PORT="8080"
RC=""
MAX_RETRIES="3"
RETRY_DELAY="2000"
PERSIST=1
SMOKE=1
CHECK=0
STOP=0
PY="python3.14"
PIDFILE="${XDG_RUNTIME_DIR:-/tmp}/network-proxy.pid"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend) BACKEND="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --rc) RC="$2"; shift 2 ;;
    --max-retries) MAX_RETRIES="$2"; shift 2 ;;
    --retry-delay) RETRY_DELAY="$2"; shift 2 ;;
    --no-persist) PERSIST=0; shift ;;
    --no-smoke) SMOKE=0; shift ;;
    --check) CHECK=1; shift ;;
    --stop) STOP=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ "$STOP" -eq 1 ]]; then
  if [[ -f "$PIDFILE" ]] && kill "$(cat "$PIDFILE")" 2>/dev/null; then
    echo "stopped proxy (pid $(cat "$PIDFILE"))"
  else
    pkill -f "proxy --port $PORT" 2>/dev/null && echo "stopped proxy" || echo "proxy not running"
  fi
  rm -f "$PIDFILE"
  exit 0
fi

# --- endpoint ---------------------------------------------------------------
if [[ -z "$HOST" ]]; then
  if [[ -n "${HTTP_PROXY:-}" ]]; then
    HOST="$(echo "$HTTP_PROXY" | sed -E 's|.*://([^:/]+).*|\1|')"
  else
    HOST="127.0.0.1"
  fi
fi
if [[ -z "${RC}" ]]; then
  if [[ -f "$HOME/.bashrc" ]]; then RC="$HOME/.bashrc"
  elif [[ -f "$HOME/.zshrc" ]]; then RC="$HOME/.zshrc"
  else RC="$HOME/.bashrc"
  fi
fi

echo "backend        : ${BACKEND}"
echo "proxy endpoint : ${HOST}:${PORT}"
echo "shell rc       : ${RC}"
echo "retry policy   : maxRetries=${MAX_RETRIES} retryDelay=${RETRY_DELAY}"
echo

# --- 1. backend up -----------------------------------------------------------
if [[ "$BACKEND" == "proxy.py" ]]; then
  echo "--- ensure proxy.py ---"
  if ! "$PY" -c "import proxy" 2>/dev/null; then
    "$PY" -m pip install "proxy.py" || {
      echo "FAIL: could not install proxy.py (needs pip for ${PY})" >&2; exit 1; }
  fi
  if [[ "$CHECK" -eq 0 ]]; then
    echo "--- start proxy.py :${PORT} ---"
    if [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
      echo "already running (pid $(cat "$PIDFILE"))"
    else
      export PROXY_METRICS_FILE="${PROXY_METRICS_FILE:-$HOME/.cache/network-proxy/metrics.jsonl}"
      cd "$SCRIPT_DIR"
      PYTHONPATH="$SCRIPT_DIR" nohup "$PY" -m proxy --port "$PORT" \
        --plugins proxy_retry_plugin.RetryAbsorbPlugin \
        &>"${XDG_RUNTIME_DIR:-/tmp}/network-proxy.log" &
      echo $! > "$PIDFILE"
      sleep 3
    fi
  fi
else
  echo "--- android backend: start the Network Proxy app on the phone (port ${PORT}) ---"
fi

# --- 2. probe -----------------------------------------------------------------
echo "--- probe proxy (http + https) ---"
if ! curl -sS -m 15 -x "http://${HOST}:${PORT}" -o /dev/null -w "http: %{http_code}\n" http://httpbin.org/ip; then
  echo "FAIL: proxy not reachable at ${HOST}:${PORT}" >&2; exit 1
fi
if ! curl -sS -m 20 -x "http://${HOST}:${PORT}" -o /dev/null -w "https: %{http_code}\n" https://httpbin.org/ip; then
  echo "FAIL: https via proxy not working at ${HOST}:${PORT}" >&2; exit 1
fi
echo

# --- 3. persist env ------------------------------------------------------------
if [[ "$PERSIST" -eq 1 && "$CHECK" -eq 0 ]]; then
  echo "--- persist env to ${RC} ---"
  touch "$RC"
  python3 - "$RC" <<'PYEOF'
import sys
rc = sys.argv[1]
begin, end = "# >>> network-proxy", "# <<< network-proxy"
lines = open(rc).read().splitlines(keepends=True)
out, skipping = [], False
for ln in lines:
    if begin in ln:
        skipping = True
        continue
    if end in ln:
        skipping = False
        continue
    if not skipping:
        out.append(ln)
open(rc, "w").writelines(out)
PYEOF
  cat >> "$RC" <<EOF
# >>> network-proxy (managed by opencode-proxy-setup.sh) >>>
export HTTP_PROXY="http://${HOST}:${PORT}"
export HTTPS_PROXY="http://${HOST}:${PORT}"
export NO_PROXY="localhost,127.0.0.1"
# <<< network-proxy <<<
EOF
  echo "wrote proxy env block (re-source or reopen shell to apply)"
  echo
fi

export HTTP_PROXY="http://${HOST}:${PORT}"
export HTTPS_PROXY="http://${HOST}:${PORT}"
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"

# --- 4. patch opencode.jsonc -----------------------------------------------------
echo "--- opencode.jsonc retry policy ---"
PATCH_ARGS=(--max-retries "$MAX_RETRIES" --retry-delay "$RETRY_DELAY")
if [[ "$CHECK" -eq 1 ]]; then
  python3 "$SCRIPT_DIR/patch-opencode-jsonc.py" --check "${PATCH_ARGS[@]}"
else
  python3 "$SCRIPT_DIR/patch-opencode-jsonc.py" "${PATCH_ARGS[@]}"
fi
echo

# --- 5. smoke test ----------------------------------------------------------------
if [[ "$SMOKE" -eq 1 && "$CHECK" -eq 0 ]]; then
  echo "--- smoke: opencode via proxy ---"
  if command -v opencode >/dev/null 2>&1; then
    opencode run "say ok" --model nvidia/z-ai/glm-5.3 2>&1 | tail -4
  else
    echo "opencode not in PATH; skipping opencode smoke test"
  fi
fi

echo
echo "DONE. Routing: opencode -> ${HOST}:${PORT} (${BACKEND}, absorbs 429s with Retry-After)."
