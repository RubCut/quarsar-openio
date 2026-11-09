#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Consume the authenticated loopback audio HTTP endpoint without putting the
bearer token in argv/process listings. Writes binary audio to stdout by default."""
import argparse
import http.client
from pathlib import Path
import re
import sys

DEFAULT_TOKEN = "/data/local/tmp/quasar-openio/audio.token"


def read_token(path: str) -> str:
    token = Path(path).read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", token):
        raise ValueError("audio token file must contain exactly 64 lowercase hex characters")
    return token


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19081)
    parser.add_argument("--path", choices=["/audio.wav", "/audio.pcm", "/health"],
                        default="/audio.wav")
    parser.add_argument("--token-file", default=DEFAULT_TOKEN)
    parser.add_argument("--output", help="output file; stdout if omitted")
    args = parser.parse_args()
    token = read_token(args.token_file)
    out = open(args.output, "wb") if args.output else sys.stdout.buffer
    conn = http.client.HTTPConnection(args.host, args.port, timeout=15)
    try:
        conn.putrequest("GET", args.path)
        conn.putheader("Host", f"{args.host}:{args.port}")
        conn.putheader("Authorization", f"Bearer {token}")
        conn.endheaders()
        response = conn.getresponse()
        if response.status != 200:
            body = response.read(4096).decode("utf-8", "replace")
            raise SystemExit(f"HTTP {response.status}: {body}")
        while True:
            chunk = response.read(65536)
            if not chunk:
                break
            out.write(chunk)
            out.flush()
    finally:
        conn.close()
        if args.output:
            out.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, http.client.HTTPException) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
