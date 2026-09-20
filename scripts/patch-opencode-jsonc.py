#!/usr/bin/env python3
"""Idempotent JSONC patcher for opencode provider options.

Preserves comments and formatting: only rewrites the specific
key/value pairs via regex, never re-serializes the file.

Usage:
    patch-opencode-jsonc.py [--config PATH] [--max-retries N] [--retry-delay MS]
                            [--check]

Exit codes:
    0  all requested values already present (or successfully written)
    1  usage / file error
    2  --check found a mismatch (nothing written)
"""
import argparse
import re
import sys
from pathlib import Path

DEFAULT_CONFIG = Path.home() / ".config" / "opencode" / "opencode.jsonc"

# key -> (regex, replacement-template). Matches `"key": <number>` with any spacing.
PATCHES = {
    "maxRetries": (
        re.compile(r'"maxRetries"\s*:\s*\d+'),
        lambda v: f'"maxRetries": {v}',
    ),
    "retryDelay": (
        re.compile(r'"retryDelay"\s*:\s*\d+'),
        lambda v: f'"retryDelay": {v}',
    ),
}


def patch(text: str, key: str, value: int) -> tuple[str, bool]:
    pattern, render = PATCHES[key]
    new_text, n = pattern.subn(render(value), text, count=1)
    return new_text, n == 1


def check(text: str, key: str, value: int) -> bool:
    pattern, render = PATCHES[key]
    m = pattern.search(text)
    return m is not None and m.group(0) == render(value)


def main() -> int:
    ap = argparse.ArgumentParser(description="Patch opencode.jsonc provider retry policy (JSONC-safe).")
    ap.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    ap.add_argument("--max-retries", type=int, default=3)
    ap.add_argument("--retry-delay", type=int, default=2000)
    ap.add_argument("--check", action="store_true", help="verify only, do not write")
    args = ap.parse_args()

    if not args.config.exists():
        print(f"ERROR: config not found: {args.config}", file=sys.stderr)
        return 1

    text = args.config.read_text()
    wanted = {"maxRetries": args.max_retries, "retryDelay": args.retry_delay}

    if args.check:
        bad = [k for k, v in wanted.items() if not check(text, k, v)]
        if bad:
            print(f"MISMATCH: {', '.join(bad)} not at requested values in {args.config}")
            return 2
        print(f"OK: {args.config} already has maxRetries={wanted['maxRetries']} retryDelay={wanted['retryDelay']}")
        return 0

    missing = [k for k in wanted if PATCHES[k][0].search(text) is None]
    if missing:
        print(
            f"ERROR: keys not found (refusing to insert to preserve formatting): {', '.join(missing)}\n"
            "Add them under provider.<name>.options first, then re-run.",
            file=sys.stderr,
        )
        return 1

    for key, value in wanted.items():
        text, _ = patch(text, key, value)
    args.config.write_text(text)
    print(f"OK: wrote maxRetries={wanted['maxRetries']} retryDelay={wanted['retryDelay']} to {args.config}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
