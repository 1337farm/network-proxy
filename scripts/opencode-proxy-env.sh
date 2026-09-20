#!/usr/bin/env bash
# opencode-proxy-env.sh — print current proxy routing state and test reachability.
#
# Usage:
#   ./opencode-proxy-env.sh [--host IP] [--port PORT]
#
# Reads HTTP_PROXY/HTTPS_PROXY/NO_PROXY from the environment (or --host/--port
# overrides), probes the proxy with curl, and reports opencode.jsonc retry policy.
set -euo pipefail

HOST="${1:-}"
PORT="${2:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    *) shift ;;
  esac
done

# Derive host/port from env when flags are absent.
if [[ -z "$HOST" && -n "${HTTP_PROXY:-}" ]]; then
  HOST="$(echo "$HTTP_PROXY" | sed -E 's|.*://([^:/]+).*|\1|')"
fi
if [[ -z "$PORT" && -n "${HTTP_PROXY:-}" ]]; then
  PORT="$(echo "$HTTP_PROXY" | sed -E 's|.*:([0-9]+).*|\1|')"
fi
HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-3128}"

echo "== proxy env =="
echo "HTTP_PROXY=${HTTP_PROXY:-<unset>}"
echo "HTTPS_PROXY=${HTTPS_PROXY:-<unset>}"
echo "NO_PROXY=${NO_PROXY:-<unset>}"
echo "target=${HOST}:${PORT}"
echo

echo "== reachability =="
if curl -sS -m 10 -x "http://${HOST}:${PORT}" -o /dev/null -w "proxy_probe http=%{http_code} time=%{time_total}s\n" http://httpbin.org/ip; then
  echo "PROXY_OK"
else
  echo "PROXY_FAIL: start the Network Proxy app on the phone (port ${PORT}) and retry"
  exit 1
fi
echo

echo "== opencode retry policy =="
CONFIG="${OPENCODE_CONFIG:-$HOME/.config/opencode/opencode.jsonc}"
if [[ -f "$CONFIG" ]]; then
  grep -E '"maxRetries"|"retryDelay"|"ignoreCooldown"|"transport"' "$CONFIG" || true
else
  echo "config not found: $CONFIG"
  exit 1
fi
