# network-proxy

Network proxy for opencode routing. 429s (and other transient failures) are
absorbed with `Retry-After`-aware retries inside the proxy, so the client
stays dumb.

## Backends

- **proxy.py (default)** — pure-Python proxy on the local host with the
  absorbing-retry plugin (`scripts/proxy_retry_plugin.py`: retryable
  408/425/426/429/431/451/500/502/503/504, `Retry-After` honored, otherwise
  exponential backoff with jitter, JSONL metrics).
- **android** — foreground proxy app (`app/`, package
  `com.forgerig.gatekeeper.proxy`) implementing the same retry policy with
  tunneled HTTPS, metrics, and session state.

## Quickstart

```sh
./scripts/opencode-proxy-setup.sh --backend proxy.py --port 8080
./scripts/opencode-proxy-setup.sh --backend android --host <phone-ip> --port 8080
```

See `scripts/opencode-proxy-setup.sh --help` for all options
(`--check`, `--stop`, `--no-persist`, `--no-smoke`).
