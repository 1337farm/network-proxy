"""proxy.py plugin: absorb transient failures so clients never see them.

Policy (mirrors nanogatekeeper-proxy ScenarioClassifier):
  - Retryable: 408, 425, 426, 429, 431, 451, 500, 502, 503, 504.
  - Honors `Retry-After` (seconds) when present, else exponential backoff
    with jitter.
  - Only response *headers* are buffered before the forward/retry decision,
    so large/streaming bodies are never held in memory.
  - HTTPS tunnels (CONNECT) pass through untouched with access logging only.
  - Every completed request appends one JSON line to the metrics file.

Config via environment:
  PROXY_RETRY_MAX      max redial attempts (default 5)
  PROXY_RETRY_BASE_MS  base backoff ms (default 2000)
  PROXY_METRICS_FILE   JSONL metrics path (default ~/.cache/network-proxy/metrics.jsonl)

Load with:
  python3 -m proxy --port 8080 --plugins proxy_retry_plugin.RetryAbsorbPlugin
  (run from this scripts/ dir, or add it to PYTHONPATH)
"""
import json
import os
import random
import socket
import time
from pathlib import Path
from typing import Optional

from proxy.http.parser import HttpParser
from proxy.http.proxy import HttpProxyBasePlugin

RETRYABLE = {408, 425, 426, 429, 431, 451, 500, 502, 503, 504}
HDR_END = b"\r\n\r\n"


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


def _metrics_path() -> Path:
    default = Path.home() / ".cache" / "network-proxy" / "metrics.jsonl"
    return Path(os.environ.get("PROXY_METRICS_FILE", str(default)))


def _parse_head(raw: bytes):
    """Split raw response bytes into (status_code, headers dict, head_len).

    Raises ValueError if headers are not complete yet.
    """
    idx = raw.find(HDR_END)
    if idx < 0:
        raise ValueError("headers incomplete")
    head = raw[:idx].decode("iso-8859-1")
    lines = head.split("\r\n")
    try:
        code = int(lines[0].split(" ", 2)[1])
    except (IndexError, ValueError):
        code = 0
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, _, v = line.partition(":")
            headers[k.strip().lower()] = v.strip()
    return code, headers, idx + len(HDR_END)


def _retry_after(headers: dict) -> Optional[float]:
    raw = headers.get("retry-after")
    if not raw:
        return None
    try:
        # Delta-seconds form; HTTP-date form is ignored (falls back to backoff).
        return max(0.0, float(raw.strip()))
    except ValueError:
        return None


class RetryAbsorbPlugin(HttpProxyBasePlugin):
    """Buffer headers, absorb retryable responses, stream everything else."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._max_attempts = _env_int("PROXY_RETRY_MAX", 5)
        self._base_ms = _env_int("PROXY_RETRY_BASE_MS", 2000)
        self._reset_request_state()

    def _reset_request_state(self):
        self._req_bytes = b""
        self._host = ""
        self._port = 80
        self._method = ""
        self._buf = bytearray()
        self._attempt = 0
        self._t0 = time.time()
        self._passthrough = False
        self._decided = False
        self._draining = False

    # -- request path -------------------------------------------------
    def handle_client_request(self, request: HttpParser) -> Optional[HttpParser]:
        self._reset_request_state()
        try:
            method = (request.method or b"").decode("ascii", "replace").upper()
        except Exception:
            method = ""
        self._method = method
        if method == "CONNECT" or bool(getattr(request, "is_https_tunnel", False)):
            self._passthrough = True
            return request
        try:
            self._req_bytes = bytes(request.build())
        except Exception:
            self._req_bytes = b""
        # NB: parser.host strips the port -- the authoritative authority
        # (host:port) lives in the Host header.
        host_s = ""
        try:
            raw_host = request.header(b"host")
            if raw_host:
                host_s = raw_host.decode("ascii")
        except Exception:
            pass
        if not host_s:
            host = getattr(request, "host", b"") or b""
            try:
                host_s = host.decode("ascii") if isinstance(host, bytes) else str(host)
            except Exception:
                host_s = ""
        if ":" in host_s:
            h, _, p = host_s.partition(":")
            self._host = h
            try:
                self._port = int(p)
            except ValueError:
                self._port = 80
        else:
            self._host = host_s
            self._port = 80
        return request

    # -- response path ------------------------------------------------
    def handle_upstream_chunk(self, chunk: memoryview) -> Optional[memoryview]:
        if self._passthrough or self._decided:
            return chunk
        if self._draining:
            return None  # swallow leftover bytes of an absorbed error response
        self._buf += bytes(chunk)
        try:
            code, headers, _ = _parse_head(bytes(self._buf))
        except ValueError:
            return None  # headers not complete yet: keep buffering
        if code in RETRYABLE and self._attempt < self._max_attempts and self._req_bytes:
            self._absorb_and_redial(headers, code)
            return None
        self._log(scenario="SUCCESS" if 200 <= code < 300 else (
            "RETRYABLE_HTTP" if code in RETRYABLE else "PASSTHROUGH"),
            status=code, delay_ms=0, final=True)
        return self._forward_buffered()

    def _backoff(self, headers: dict) -> float:
        retry_after = _retry_after(headers)
        if retry_after is not None:
            return min(120.0, retry_after)
        delay = min(30.0, (self._base_ms / 1000.0) * (2 ** self._attempt))
        return delay / 2 + random.uniform(0, delay / 2)

    def _absorb_and_redial(self, headers: dict, code: int):
        """Sleep, then re-send the buffered request on a fresh socket.

        On success the good response is relayed inline and the original
        upstream's leftovers are swallowed. On redial failure (or exhausted
        attempts) the original error response is forwarded untouched.
        """
        delay = self._backoff(headers)
        self._attempt += 1
        self._log(scenario="RETRYABLE_HTTP", status=code,
                  delay_ms=int(delay * 1000), final=False)
        time.sleep(delay)
        sock = None
        try:
            sock = socket.create_connection((self._host, self._port), timeout=30)
            sock.settimeout(120)
            sock.sendall(self._req_bytes)
            raw = bytearray()
            while HDR_END not in raw:
                piece = sock.recv(65536)
                if not piece:
                    break
                raw += piece
            try:
                code2, _, _ = _parse_head(bytes(raw))
            except ValueError:
                code2 = 0
            if code2 in RETRYABLE and self._attempt < self._max_attempts:
                # Absorb this one too and try again (bounded by max attempts).
                try:
                    sock.close()
                except Exception:
                    pass
                self._buf = bytearray(raw)
                try:
                    _, headers2, _ = _parse_head(bytes(raw))
                except ValueError:
                    headers2 = {}
                self._absorb_and_redial(headers2, code2)
                return
            # Forward the redialed response, then relay the rest inline.
            self.client.queue(memoryview(bytes(raw)))
            transferred = len(raw)
            try:
                while True:
                    piece = sock.recv(65536)
                    if not piece:
                        break
                    self.client.queue(memoryview(piece))
                    transferred += len(piece)
            except Exception:
                pass
            self._log(scenario="SUCCESS" if 200 <= code2 < 300 else "RETRYABLE_HTTP",
                      status=code2, delay_ms=0, final=True, bytes_out=transferred)
            self._draining = True  # swallow original upstream's leftovers
        except Exception as e:
            self._log(scenario="REDIAL_FAILED", status=0,
                      delay_ms=0, final=False, error="%r" % e)
            self._forward_buffered()
        finally:
            try:
                if sock is not None:
                    sock.close()
            except Exception:
                pass

    def _flush_buffered(self):
        if self._buf:
            self.client.queue(memoryview(bytes(self._buf)))
            self._buf = bytearray()

    def _forward_buffered(self):
        """Forward buffered bytes; later chunks stream straight through."""
        self._flush_buffered()
        self._decided = True
        return None

    def _log(self, scenario: str, status: int, delay_ms: int, final: bool, bytes_out: int = 0, error: str = ""):
        try:
            path = _metrics_path()
            path.parent.mkdir(parents=True, exist_ok=True)
            rec = {
                "ts": time.time(),
                "method": self._method,
                "host": self._host,
                "status": status,
                "scenario": scenario,
                "attempt": self._attempt,
                "delay_ms": delay_ms,
                "final": final,
                "bytes_out": bytes_out,
                "duration_ms": int((time.time() - self._t0) * 1000),
                "error": error,
            }
            with open(path, "a") as f:
                f.write(json.dumps(rec) + "\n")
        except Exception:
            pass
