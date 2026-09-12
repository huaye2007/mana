"""Sustained Linux TCP tests in disposable, isolated Docker containers.

Requires a Linux Maven build in --work (including Surefire classpath reports).
The workload sources are test-only. No ports except the loopback fault-control
endpoint are published. Only containers/network created by this run are removed.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET


def docker(*args, timeout=45, check=True):
    result = subprocess.run(["docker", *map(str, args)], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"docker {' '.join(map(str, args))}: {result.stderr}")
    return result


def rows(log):
    result = []
    for line in log.splitlines():
        if line.startswith("{"):
            try:
                result.append(json.loads(line))
            except ValueError:
                pass
    return result


def run(args):
    work = args.work.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if args.prepare:
        if work.exists():
            raise RuntimeError("--prepare requires a new --work directory")
        root = Path(__file__).resolve().parents[2]
        work.mkdir(parents=True)
        shutil.copy2(root / "pom.xml", work / "pom.xml")
        for module in ("game-network", "game-rpc"):
            shutil.copytree(root / module, work / module,
                            ignore=shutil.ignore_patterns("target", ".m2", ".git", "__pycache__"))
        cache = (root / "game-network/.m2").as_posix()
        build_name = "rpc-stress-build-" + dt.datetime.now().strftime("%Y%m%d%H%M%S")
        # Only fixed shell text; host paths are passed as individual Docker arguments.
        build = ("cp -a /cache /work/.m2 && mvn -B -o "
                 "-s game-network/.mvn/settings.xml -gs game-network/.mvn/settings.xml "
                 "-Dmaven.repo.local=/work/.m2 -pl game-rpc/game-rpc-netty -am verify "
                 "-Dtest=Rpc*Test -Dsurefire.failIfNoSpecifiedTests=false")
        try:
            result = docker("run", "--name", build_name, "--label", "codex.rpc.stress=" + build_name,
                            "--cpus", "4", "--memory", "2g",
                            "--mount", f"type=bind,source={work.as_posix()},target=/work",
                            "--mount", f"type=bind,source={cache},target=/cache,readonly",
                            "-w", "/work", args.image, "sh", "-c", build, timeout=600, check=False)
            (output / "linux-verify.log").write_text(result.stdout + result.stderr, encoding="utf-8")
            if result.returncode:
                raise RuntimeError("Linux Maven verify failed; see linux-verify.log")
        finally:
            docker("rm", "-f", build_name, check=False)
    classpath = None
    for report in (work / "game-rpc/game-rpc-netty/target/surefire-reports").glob("TEST-*.xml"):
        for prop in ET.parse(report).getroot().findall("properties/property"):
            if prop.get("name") == "java.class.path":
                classpath = prop.get("value")
                break
        if classpath:
            break
    if not classpath:
        raise RuntimeError("Linux Maven verify must complete first")
    prefix = "rpc-stress-" + dt.datetime.now().strftime("%Y%m%d%H%M%S")
    network = prefix + "-net"
    owned = []
    network_created = False
    summary = {
        "started": dt.datetime.now().astimezone().isoformat(),
        "image": args.image,
        "environment": json.loads(docker("info", "--format", "{{json .}}").stdout),
        "image_info": json.loads(docker("image", "inspect", args.image).stdout)[0],
        "measurement": "Closed-loop TCP between separate Linux containers; driver costs included; not production capacity",
        "jvm": ["-Xms256m", "-Xmx512m", "-XX:MaxDirectMemorySize=512m"],
        "runs": [],
    }

    def save():
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def launch(name, role, options, leak):
        owned.append(name)
        cmd = ["run", "-d", "--name", name, "--label", "codex.rpc.stress=" + prefix,
               "--network", network, "--cpus", "2" if role == "server" else "4", "--memory", "1536m",
               "--mount", f"type=bind,source={work.as_posix()},target=/work,readonly", "-w", "/work"]
        if role == "server":
            cmd += ["--network-alias", "rpc-server", "-p", "127.0.0.1::7001"]
        cmd += [args.image, "java", *summary["jvm"], "-Dio.netty.leakDetection.level=" + leak,
                "-cp", classpath, "cn.managame.rpc.netty.RpcDockerStress", role, *map(str, options)]
        docker(*cmd)

    try:
        docker("network", "create", "--label", "codex.rpc.stress=" + prefix, network)
        network_created = True
        cases = [
            ("small-immediate", args.seconds, 64, 128, 1, False, 1, False, "advanced"),
            ("small-consolidated", args.seconds, 64, 128, 1, True, 1, False, "advanced"),
            ("large-consolidated", args.seconds, 16384, 8, 1, True, 1, False, "advanced"),
            ("large-steady", args.business_seconds, 16384, 2, 1, True, 1, False, "advanced"),
            ("business-errors", args.business_seconds, 64, 128, 1, True, 2, False, "advanced"),
            ("faults", args.seconds, 64, 128, 4, True, 1, True, "advanced"),
            ("leak-detection", args.leak_seconds, 64, 32, 1, True, 2, False, "paranoid"),
        ]
        if args.only:
            cases = [c for c in cases if c[0] in args.only.split(",")]
        for name, seconds, size, window, nodes, flush, command, faults, leak in cases:
            server, client = prefix + "-s", prefix + "-c"
            case_dir = output / name
            case_dir.mkdir(exist_ok=True)
            case = {"name": name, "seconds": seconds, "bytes": size, "window": window,
                    "nodes": nodes, "slots_per_node": 4, "workers": 8, "flush": flush,
                    "command": command, "leak_detection": leak, "events": []}
            summary["runs"].append(case)
            save()
            print(json.dumps({"starting": name, "seconds": seconds}), flush=True)
            launch(server, "server", [str(flush).lower()], leak)
            ready_deadline = time.monotonic() + 40
            while True:
                log = docker("logs", server).stdout
                if any(r.get("ready") for r in rows(log)):
                    break
                if time.monotonic() > ready_deadline:
                    raise RuntimeError("Server did not start: " + log)
                time.sleep(.5)
            port = json.loads(docker("inspect", server).stdout)[0]["NetworkSettings"]["Ports"]["7001/tcp"][0]["HostPort"]
            control = f"http://127.0.0.1:{port}/control?"
            launch(client, "client", ["rpc-server", seconds, size, 8, window, nodes, str(flush).lower(), command], leak)
            start = time.monotonic()
            planned = [(seconds * .20, "close"), (seconds * .35, "pauseRead=5000"),
                       (seconds * .50, "pause"), (seconds * .70, "kill-restart")] if faults else []
            next_stats, next_progress = 0, 0
            with (case_dir / "docker-stats.jsonl").open("w", encoding="utf-8") as stats:
                while True:
                    elapsed = time.monotonic() - start
                    if planned and elapsed >= planned[0][0]:
                        _, fault = planned.pop(0)
                        event = {"elapsed_s": elapsed, "fault": fault}
                        if fault == "pause":
                            docker("pause", server)
                            try:
                                time.sleep(5)
                            finally:
                                docker("unpause", server)
                        elif fault == "kill-restart":
                            docker("kill", server)
                            time.sleep(2)
                            docker("start", server)
                        else:
                            with urllib.request.urlopen(control + fault, timeout=10) as response:
                                event["result"] = json.load(response)
                        event["finished_s"] = time.monotonic() - start
                        case["events"].append(event)
                        save()
                        print(json.dumps(event), flush=True)
                    if elapsed >= next_stats:
                        raw = docker("stats", "--no-stream", "--format", "{{json .}}", server, client).stdout
                        stats.write(json.dumps({"elapsed_s": elapsed, "containers": rows(raw)}) + "\n")
                        stats.flush()
                        next_stats = elapsed + 10
                    if elapsed >= next_progress:
                        samples = [r for r in rows(docker("logs", "--tail", "12", client).stdout) if "completed" in r]
                        print(json.dumps({"case": name, "elapsed_s": round(elapsed),
                                          "latest": samples[-1] if samples else None}), flush=True)
                        next_progress = elapsed + 30
                    state = json.loads(docker("inspect", client).stdout)[0]["State"]
                    if not state["Running"]:
                        case["exit_code"] = state["ExitCode"]
                        case["oom_killed"] = state["OOMKilled"]
                        break
                    if elapsed > seconds + 65:
                        raise RuntimeError("Client failed to finish/drain in time")
                    time.sleep(1)
            client_output = docker("logs", client)
            server_output = docker("logs", server)
            for role, logs in (("client", client_output), ("server", server_output)):
                (case_dir / (role + ".log")).write_text(logs.stdout + logs.stderr, encoding="utf-8")
            client_rows = rows(client_output.stdout)
            finals = [r for r in client_rows if r.get("final")]
            case["final"] = finals[-1] if finals else None
            samples = [r for r in client_rows if "completed" in r and not r.get("final")]
            case["samples"] = samples
            case["server_samples"] = [r for r in rows(server_output.stdout) if "completed" in r]
            if len(samples) > 2:
                anchor = next((r for r in samples if r["elapsed_s"] >= min(30, seconds / 4)), samples[0])
                last = samples[-1]
                case["warm_calls_per_second"] = (last["ok"] - anchor["ok"]) / (last["elapsed_s"] - anchor["elapsed_s"])
            text = client_output.stderr + server_output.stderr + client_output.stdout + server_output.stdout
            case["leak_reported"] = "LEAK:" in text
            final = case["final"]
            allowed = {"4", "5", "6"} if faults else ({"1001"} if command == 2 else set())
            case["passed"] = bool(case["exit_code"] == 0 and final and not case["leak_reported"]
                                  and not case["oom_killed"] and not final["pending"]
                                  and not final["corrupt"] and not final["duplicate"]
                                  and final["sent"] == final["completed"] and final["ok"] > 0
                                  and final.get("active_slots") == nodes * 4
                                  and set(final["errors"]).issubset(allowed))
            if faults and case["passed"]:
                # At least two final sampling windows must show progress and no new errors.
                tail = samples[-3:]
                case["recovered"] = len(tail) == 3 and tail[0]["errors"] == tail[-1]["errors"] and tail[-1]["ok"] > tail[0]["ok"]
                case["passed"] = case["recovered"]
            save()
            print(json.dumps({"finished": name, "passed": case["passed"], "final": final}), flush=True)
            docker("stop", "-t", "10", server)
            for container in (client, server):
                docker("rm", container)
                owned.remove(container)
        summary["passed"] = all(r.get("passed") for r in summary["runs"])
    finally:
        for container in reversed(owned):
            try:
                docker("unpause", container, check=False)
                logs = docker("logs", container, check=False)
                (output / (container + "-interrupted.log")).write_text(logs.stdout + logs.stderr, encoding="utf-8")
                docker("rm", "-f", container, check=False)
            except Exception as error:
                print(f"Cleanup {container}: {error}", flush=True)
        if network_created:
            docker("network", "rm", network, check=False)
        summary["finished"] = dt.datetime.now().astimezone().isoformat()
        save()
    if not summary.get("passed"):
        raise SystemExit("One or more stress scenarios failed; see " + str(output / "summary.json"))
    print("PASS: " + str(output / "summary.json"), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--image", default="maven:3.9-eclipse-temurin-25")
    parser.add_argument("--prepare", action="store_true", help="Copy sources/cache and verify in Linux before testing")
    parser.add_argument("--seconds", type=int, default=300)
    parser.add_argument("--business-seconds", type=int, default=120)
    parser.add_argument("--leak-seconds", type=int, default=60)
    parser.add_argument("--only", help="Comma separated scenario names")
    options = parser.parse_args()
    if min(options.seconds, options.business_seconds, options.leak_seconds) < 20:
        parser.error("Every scenario must run for at least 20 seconds")
    run(options)
