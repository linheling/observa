"""Minimal RCON client for the dev server: python rcon.py "summon zombie" "list".

Used only for building test scenarios (spawn entities, change time) while verifying
Observa's collectors. Response reassembly is single-packet, which is enough for the
short commands we send; large outputs (e.g. a full "help") may come back truncated.
"""

import os
import socket
import struct
import sys

HOST = os.environ.get("OBSERVA_RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("OBSERVA_RCON_PORT", "25575"))
# Dev-server password from run/server.properties; override with OBSERVA_RCON_PASSWORD.
PASSWORD = os.environ.get("OBSERVA_RCON_PASSWORD", "observa-dev")


def pack(request_id: int, packet_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def read_exactly(sock: socket.socket, count: int) -> bytes:
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError("connection closed by server")
        data += chunk
    return data


def read_packet(sock: socket.socket):
    (length,) = struct.unpack("<i", read_exactly(sock, 4))
    data = read_exactly(sock, length)
    request_id, packet_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", "replace")
    return request_id, packet_type, body


def main() -> int:
    commands = sys.argv[1:]
    if not commands:
        print("usage: rcon.py <command> [command ...]", file=sys.stderr)
        return 64
    with socket.create_connection((HOST, PORT), timeout=15) as sock:
        sock.sendall(pack(1, 3, PASSWORD))
        request_id, _, _ = read_packet(sock)
        if request_id == -1:
            print("rcon auth failed", file=sys.stderr)
            return 1
        for command in commands:
            sock.sendall(pack(2, 2, command))
            _, _, body = read_packet(sock)
            print(f"> {command}\n{body}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
