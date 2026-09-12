"""Run fresh JVMs using the classpath from the most recent Maven RPC verification."""
import argparse
import datetime
import json
import os
from pathlib import Path
import platform
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--requests", type=int, default=200000)
parser.add_argument("--warmup", type=int, default=50000)
parser.add_argument("--repeats", type=int, default=3)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
if min(args.requests, args.warmup, args.repeats) <= 0:
    parser.error("requests, warmup and repeats must be positive")
root = Path(__file__).resolve().parents[1]
reports = root / "game-rpc-netty/target/surefire-reports"
classpath = None
for report in reports.glob("TEST-*.xml"):
    for prop in ET.parse(report).getroot().findall("properties/property"):
        if prop.get("name") == "java.class.path":
            classpath = prop.get("value")
            break
    if classpath:
        break
if not classpath or not (root / "game-rpc-netty/target/test-classes/cn/managame/rpc/netty/RpcLoadBenchmark.class").is_file():
    parser.error("Run Maven RPC verify first to compile the benchmark and produce its classpath")
java_version = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip()
output = args.output or root / ("target/benchmark-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
output = output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
result = {"timestamp": datetime.datetime.now().astimezone().isoformat(), "platform": platform.platform(),
          "cpu_count": os.cpu_count(), "java": java_version,
          "jvm_options": ["-Xms256m", "-Xmx256m", "-Dio.netty.leakDetection.level=disabled"],
          "measurement": "closed-loop localhost TCP; client + server + driver in one JVM; allocation includes all three", "runs": []}
if os.name == "nt":
    # Match the Maven socket-test setting; Windows JDK selectors use a Unix-domain wakeup pipe.
    socket_tmp = root / "target"
    socket_tmp.mkdir(parents=True, exist_ok=True)
    result["jvm_options"].append("-Djdk.net.unixdomain.tmpdir=" + str(socket_tmp))
# Different fresh JVMs: small/large payloads, slot count, flush policy, diagnostic delivery.
scenarios = [(64, 1, 128, False, False), (64, 4, 256, False, False),
             (64, 4, 256, True, False), (64, 4, 256, False, True),
             (16384, 4, 8, False, False)]
for repeat in range(1, args.repeats + 1):
    for size, slots, window, flush, diagnostics in scenarios:
        print(f"run={repeat} bytes={size} slots={slots} window={window} flush={flush} diagnostics={diagnostics}", flush=True)
        command = ["java", *result["jvm_options"], "-cp", classpath,
                   "cn.managame.rpc.netty.RpcLoadBenchmark", str(args.requests), str(args.warmup),
                   str(size), str(slots), str(window), str(flush).lower(), str(diagnostics).lower()]
        completed = subprocess.run(command, cwd=root.parent, capture_output=True, text=True, timeout=180)
        if completed.returncode:
            raise RuntimeError(completed.stdout + completed.stderr)
        row = json.loads(next(line for line in completed.stdout.splitlines() if line.startswith("{")))
        row["repeat"] = repeat
        result["runs"].append(row)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(row), flush=True)
print(f"Saved: {output}", flush=True)
