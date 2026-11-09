#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Example NDJSON client. Python is not required by the daemon itself."""
import argparse
import base64
import json
import socket
import sys


def send(sock, action, **fields):
    sock.sendall((json.dumps({"action": action, **fields}) + "\n").encode())


def response(sock):
    with sock.makefile("rb") as stream:
        for line in stream:
            message = json.loads(line)
            if "event" not in message:
                return message
    raise SystemExit("daemon closed the connection")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--socket", default="/data/local/tmp/quasar.sock")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    sub.add_parser("pause")
    sub.add_parser("resume")
    sub.add_parser("listen")
    music = sub.add_parser("music-state")
    music.add_argument("state", choices=["playing", "paused", "stopped", "idle", "auto"])
    anim = sub.add_parser("animation")
    anim.add_argument("name", help="plugin animation mode, e.g. music-play or volume-ring")
    for name, size in (("draw-screen", 1200), ("draw-ring", 72)):
        p = sub.add_parser(name, help="legacy direct draw; only with --no-plugin")
        p.add_argument("file", help=f"raw RGB888 file, exactly {size} bytes")
        p.set_defaults(size=size)
    args = parser.parse_args()
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(5)
        sock.connect(args.socket)
        if args.command == "listen":
            with sock.makefile("rb") as stream:
                for line in stream:
                    print(line.decode().strip(), flush=True)
            return
        if args.command == "status":
            send(sock, "status")
        elif args.command == "pause":
            send(sock, "pause_sdk")
        elif args.command == "resume":
            send(sock, "resume_sdk")
        elif args.command == "music-state":
            send(sock, "music_state", data=args.state)
        elif args.command == "animation":
            send(sock, "plugin_animation", data=args.name)
        else:
            data = open(args.file, "rb").read()
            if len(data) != args.size:
                raise SystemExit(f"expected {args.size} bytes, got {len(data)}")
            send(sock, "draw_screen" if args.command == "draw-screen" else "draw_ring",
                 data=base64.b64encode(data).decode())
        print(json.dumps(response(sock), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
