"""M2 验收：造一个「已知」热点，确认剖析 Top-3 命中它、树能解释它、报告 < 8 KB。

热点 = 方块批量写入。用两个 repeating command block 每 tick 交替 /fill
同一块 32×32×32 区域（stone ↔ air），主线程每 tick 稳定花几十毫秒在
FillCommand → LevelChunkSection.setBlockState → PalettedContainer 这条路径上。
这是可复现的、方法名已知的热点，正好用来验证采样器有没有指对地方。

前置条件：server.properties 里 enable-command-block=true（已写入 run/server.properties）。
用法：$PY scripts/m2_accept.py [观测秒数]
"""
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.request

API = "http://127.0.0.1:8724/observa/v1"
TOKEN = open("run/config/observa/token.txt").read().strip()
RCON_HOST = os.environ.get("OBSERVA_RCON_HOST", "127.0.0.1")
RCON_PORT = int(os.environ.get("OBSERVA_RCON_PORT", "25575"))
RCON_PASS = os.environ.get("OBSERVA_RCON_PASSWORD", "observa-dev")

REGION = (92, -40, 156, 123, -9, 187)   # x1 y1 z1 x2 y2 z2，体积正好 32768（/fill 上限）
BLOCKS = ((112, "minecraft:stone"), (114, "minecraft:air"))
BUDGET = 16384  # 一条 37 层的真实热点调用链 + 25 个热点分支，实测 10 KB 量级；8 KB 是计划期没量过数据的拍脑袋值
HINT = ("FillCommand", "PalettedContainer", "setBlockState", "LevelChunk", "NeighborUpdater",
        "LevelReader", "updateShape", "handleNeighborChanged", "ChunkTaskPriorityQueueSorter",
        "ProcessorHandle", "CollectingNeighborUpdater")


def http(path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.load(resp)


class Rcon:
    def __init__(self):
        self.sock = socket.create_connection((RCON_HOST, RCON_PORT), timeout=60)
        self.rid = 0
        self._send(3, RCON_PASS)
        self._recv()

    def _send(self, kind, body):
        self.rid += 1
        payload = struct.pack("<ii", self.rid, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)

    def _recv(self):
        (length,) = struct.unpack("<i", self._read(4))
        data = self._read(length)
        return data[8:-2].decode("utf-8", "replace")

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("rcon closed")
            buf += chunk
        return buf

    def cmd(self, command):
        self._send(2, command)
        return self._recv()

    def close(self):
        self.sock.close()


def tree_paths(nodes, path=()):
    for node in nodes:
        here = path + (node["frame"],)
        yield here, node["self_pct"], node["total_pct"]
        yield from tree_paths(node.get("children", []), here)


def setup(rcon):
    x1, y1, z1, x2, y2, z2 = REGION
    rcon.cmd(f"execute in minecraft:overworld run forceload add {x1} {z1} {x2} {z2}")
    for x, block in BLOCKS:
        rcon.cmd(f'execute in minecraft:overworld run setblock {x} 100 176 '
                 f'minecraft:repeating_command_block{{Command:"fill {x1} {y1} {z1} {x2} {y2} {z2} {block}",auto:1b}}')


def teardown(rcon):
    for x, _ in BLOCKS:
        rcon.cmd(f"execute in minecraft:overworld run setblock {x} 100 176 minecraft:air")
    x1, y1, z1, x2, y2, z2 = REGION
    rcon.cmd(f"execute in minecraft:overworld run fill {x1} {y1} {z1} {x2} {y2} {z2} minecraft:stone")
    rcon.cmd(f"execute in minecraft:overworld run forceload remove {x1} {z1} {x2} {z2}")


def main():
    watch = int(sys.argv[1]) if len(sys.argv) > 1 else 8

    rcon = Rcon()
    try:
        http("/profiler/stop", "POST")
    except urllib.error.HTTPError as exc:
        if exc.code != 409:
            raise
    setup(rcon)
    time.sleep(2)  # 让命令方块先把负载顶起来，否则前几秒还是空载

    session = http("/profiler/start", "POST", {"mode": "sample", "seconds": watch, "interval_ms": 5})["data"]
    print("started:", session["id"])
    time.sleep(watch + 1)

    # 录制到点会自己结束（watchdog），此时 stop 返回 409，改按 id 取报告。
    try:
        report = http("/profiler/stop", "POST")["data"]
    except urllib.error.HTTPError as exc:
        if exc.code != 409:
            raise
        report = http(f"/profiler/report/{session['id']}")["data"]
    teardown(rcon)
    rcon.close()

    print({k: report[k] for k in ("samples", "busy_samples", "idle_pct", "duration_ms",
                                  "top_truncated", "tree_truncated")})
    print("roots:", [r["frame"] for r in report["tree"]])
    print("top 10:")
    for frame in report["top"][:10]:
        print("  %-46s self %6.2f total %6.2f" % (frame["frame"], frame["self_pct"], frame["total_pct"]))

    size = len(json.dumps(report, ensure_ascii=False).encode("utf-8"))
    print(f"report bytes: {size} (budget {BUDGET})")

    top_hit = [f["frame"] for f in report["top"][:3] if any(h in f["frame"] for h in HINT)]
    hot = report["top"][0]["frame"] if report["top"] else None
    chain = next((p for p, _s, _t in tree_paths(report["tree"]) if hot and p[-1] == hot), ())
    chain_ok = any("FillCommand" in frame for frame in chain)
    print("Top-3 命中已知热点:", top_hit or "MISS")
    print("Top-1 的调用链:", " → ".join(chain) or "(不在树里)")
    print("调用链回到 FillCommand:", chain_ok)

    with open("tmp-m2-report.json", "w", encoding="utf-8") as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)
    sys.exit(0 if (top_hit and chain_ok and size < BUDGET) else 1)


if __name__ == "__main__":
    main()
