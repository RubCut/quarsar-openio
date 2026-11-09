#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Send authenticated raw S16LE/48000/stereo PCM to the loopback TCP input."""
import argparse
from pathlib import Path
import re
import socket
import sys
import time

DEFAULT_TOKEN = "/data/local/tmp/quasar-openio/audio.token"


def read_token(path: str) -> str:
    token = Path(path).read_text(encoding="ascii").strip()
    if not re.fullmatch(r"[0-9a-f]{64}", token):
        raise ValueError("audio token file must contain exactly 64 lowercase hex characters")
    return token


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("file", nargs="?", help="raw PCM file; stdin if omitted")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19080)
    parser.add_argument("--token-file", default=DEFAULT_TOKEN,
                        help="0600 bearer token created by quasar_openio")
    parser.add_argument("--rate", type=int, default=48000 * 2 * 2,
                        help="bytes per second for pacing (default 192000)")
    parser.add_argument("--chunk", type=int, default=4096)
    args = parser.parse_args()
    if args.chunk <= 0 or args.rate < 0 or not (1 <= args.port <= 65535):
        raise SystemExit("invalid chunk/rate/port")
    token = read_token(args.token_file)
    source = open(args.file, "rb") if args.file else sys.stdin.buffer
    start = time.monotonic()
    sent = 0
    try:
        with socket.create_connection((args.host, args.port), timeout=5) as sock:
            # Mandatory handshake: no PCM is accepted before this line.
            sock.sendall(f"AUTH {token}\n".encode("ascii"))
            while True:
                data = source.read(args.chunk)
                if not data:
                    break
                sock.sendall(data)
                sent += len(data)
                if args.rate > 0:
                    target = start + sent / args.rate
                    delay = target - time.monotonic()
                    if delay > 0:
                        time.sleep(delay)
    finally:
        if args.file:
            source.close()


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
