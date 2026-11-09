#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Send raw S16LE/48000/stereo PCM to the daemon's loopback TCP input."""
import argparse
import socket
import sys
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("file", nargs="?", help="raw PCM file; stdin if omitted")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19080)
    parser.add_argument("--rate", type=int, default=48000 * 2 * 2,
                        help="bytes per second for pacing (default 192000)")
    parser.add_argument("--chunk", type=int, default=4096)
    args = parser.parse_args()
    source = open(args.file, "rb") if args.file else sys.stdin.buffer
    start = time.monotonic()
    sent = 0
    with socket.create_connection((args.host, args.port), timeout=5) as sock:
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
    if args.file:
        source.close()


if __name__ == "__main__":
    try:
        main()
    except OSError as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
