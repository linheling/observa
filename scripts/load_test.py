"""负载测试驱动：起剖析 → 灌实体负载 → 收剖析 → 打印报告摘要。

用法（在工程根目录）：$PY scripts/load_test.py [实体数]
"""
import json
import subprocess
import sys
import time
import urllib.request

API = "http://127.0.0.1:8724/observa/v1"
TOKEN = open("run/config/observa/token.txt").read().strip()
RCON = subprocess.run  # noqa: F841  实际走 scripts/rcon.py
PY = sys.executable


def call(path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.load(resp)


def rcon(*cmds):
    subprocess.run([PY, "scripts/rcon.py", *cmds], check=False,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    count = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    seconds = int(sys.argv[2]) if len(sys.argv) > 2 else 25

    print("start:", call("/profiler/start", "POST",
                         {"mode": "sample", "seconds": seconds, "interval_ms": 5})["data"]["id"])

    # 用 tick 命令保证实体都生成在世界出生点的已加载区块里（之前踩过 pending 的坑）。
    rcon("execute in minecraft:overworld run fill 88 -60 152 136 64 200 minecraft:stone")
    batch = [f"execute in minecraft:overworld run summon minecraft:zombie 112 67 176" for _ in range(count)]
    for i in range(0, len(batch), 50):
        rcon(*batch[i:i + 50])
    rcon(*[f"execute in minecraft:overworld run summon minecraft:item 112 68 176 "
           f'{{Item:{{id:"minecraft:stone",count:1}}}}' for _ in range(100)])

    time.sleep(max(1, seconds - 3))
    rep = call("/profiler/stop", "POST")["data"]
    print({k: rep[k] for k in ("id", "samples", "idle_samples", "busy_samples",
                               "idle_pct", "duration_ms", "truncated")})
    print("roots:", len(rep["tree"]))
    for r in rep["tree"][:5]:
        print("  ROOT %-42s total %5.2f  kids %d" % (r["frame"], r["total_pct"], len(r.get("children", []))))
    print("top:")
    for f in rep["top"][:12]:
        print("  %-44s self %6.2f total %6.2f" % (f["frame"], f["self_pct"], f["total_pct"]))
    with open("tmp-report.json", "w", encoding="utf-8") as fh:
        json.dump(rep, fh, ensure_ascii=False, indent=1)
    print("report bytes:", len(open("tmp-report.json", "rb").read()))

    rcon("execute in minecraft:overworld run kill @e[type=minecraft:zombie]",
         "execute in minecraft:overworld run kill @e[type=minecraft:item]")


if __name__ == "__main__":
    main()
