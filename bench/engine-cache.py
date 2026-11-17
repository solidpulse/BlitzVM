#!/usr/bin/env python3
"""Bytecode-only engine-cache validation, storage, warm-up curves and steady state."""
import argparse
import contextlib
import difflib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time
import urllib.error

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("native_benchmark", ROOT / "bench/native-image.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def flags(args, cache=None, store=None, policy="hot", diagnostics=False):
    result = ["-Dblitz.bytecode=auto", "-Dpolyglot.engine.AllowExperimentalOptions=true"]
    if diagnostics:
        result += ["-Dpolyglot.engine.TraceCompilation=true", "-Dpolyglot.engine.TraceCompilationDetails=true",
                   "-Dpolyglot.engine.CompilationFailureAction=Print"]
    if args.gc:
        result.append("-XX:+PrintGC")
    if cache:
        result += [f"-Dpolyglot.engine.CacheLoad={cache}", "-Dpolyglot.engine.TraceCache=true"]
    if store:
        result += [f"-Dpolyglot.engine.CacheStore={store}", "-Dpolyglot.engine.TraceCache=true",
                   f"-Dpolyglot.engine.CacheCompile={policy}"]
    result += args.vm_option
    return result


@contextlib.contextmanager
def server(args, label, cache=None, store=None, policy="hot", diagnostics=False):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", args.port))
    log_path = args.run / f"{label}.server.log"
    with log_path.open("wb") as log:
        command = [str(args.binary), *flags(args, cache, store, policy, diagnostics), "serve", "--port", str(args.port),
                   "--cpus", "4", str(args.docroot)]
        before = bench.load_average()
        uptime = subprocess.check_output(["uptime"], text=True).strip()
        (args.run / f"{label}.command.txt").write_text(uptime + "\n" + " ".join(command) + "\n")
        start = time.monotonic()
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=log)
        try:
            while time.monotonic() - start < 120:
                if process.poll() is not None:
                    raise RuntimeError(f"Server exited: {log_path}")
                try:
                    status, body = bench.get(args.port, "/")
                    if status != 200 or len(body) < 10000:
                        (args.run / f"{label}.error.body").write_bytes(body)
                        raise RuntimeError(f"HTTP {status}: {log_path}")
                    break
                except (ConnectionError, urllib.error.URLError):
                    time.sleep(.02)
            else:
                raise RuntimeError("No first response in 120 seconds")
            startup = (time.monotonic() - start) * 1000
            bench.save(args.run, label + ".startup.json", {"startup_ms": startup, "load": before})
            yield process, startup, before
        finally:
            stopped = time.monotonic()
            process.terminate()
            try:
                process.wait(timeout=args.stop_timeout)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise RuntimeError(f"Cache store/shutdown exceeded {args.stop_timeout}s: {log_path}")
            (args.run / f"{label}.shutdown.json").write_text(json.dumps({
                "elapsed_ms": (time.monotonic() - stopped) * 1000, "exit": process.returncode,
                "load": bench.load_average()}, indent=2) + "\n")


def store(args):
    if args.cache.exists():
        raise RuntimeError(f"Refusing to overwrite existing cache: {args.cache}")
    with server(args, "store-" + args.policy, store=args.cache, policy=args.policy, diagnostics=True):
        for page in bench.PAGES:
            status, body = bench.get(args.port, page)
            if status >= 500:
                raise RuntimeError(f"WordPress failed during training: {page}")
            print(f"train {page}: HTTP {status}, {len(body)} bytes", flush=True)
        bench.loadgen(args.port, "/", 4, args.train_seconds, args.run, "store-" + args.policy + "-train")
    if not args.cache.is_file() or args.cache.stat().st_size == 0:
        raise RuntimeError("No cache file stored")
    print(f"Stored {args.cache}: {args.cache.stat().st_size} bytes", flush=True)


def check(args):
    pages = {}
    for label, cache in (("uncached", None), ("loaded", args.cache)):
        with server(args, "check-" + label, cache=cache):
            pages[label] = []
            for index, page in enumerate(bench.PAGES):
                status, body = bench.get(args.port, page)
                pages[label].append((status, body))
                (args.run / f"check-{label}-{index}.body").write_bytes(body)
                equal = label == "uncached" or pages[label][-1] == pages["uncached"][index]
                print(f"{label} {page}: HTTP {status}, {len(body)} bytes, equal={equal}", flush=True)
                if not equal:
                    diff = difflib.unified_diff(pages["uncached"][index][1].decode(errors="replace").splitlines(),
                                                body.decode(errors="replace").splitlines())
                    (args.run / f"check-{index}.diff").write_text("\n".join(diff) + "\n")
    failures = []
    for script in sorted((ROOT / "src/test/resources/php").glob("*.php")):
        result = subprocess.run([str(args.binary), *flags(args, cache=args.cache), str(script.relative_to(ROOT))],
                                cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        if result.returncode or result.stdout != script.with_suffix(".out").read_bytes():
            failure = {"script": script.name, "exit": result.returncode,
                       "stderr": result.stderr.decode(errors="replace")}
            failures.append(failure)
            (args.run / f"fixture-{script.stem}.actual").write_bytes(result.stdout)
            print("FAIL", failure, flush=True)
    bench.save(args.run, "check.json", {"pages_equal": pages["loaded"] == pages["uncached"],
                                       "fixtures_total": len(list((ROOT / "src/test/resources/php").glob("*.php"))),
                                       "failures": failures})
    if failures or pages["loaded"] != pages["uncached"]:
        raise RuntimeError("Loaded-cache correctness failed")


def steady(port, run, label, process, startup, load):
    row = {"label": label, "startup_ms": startup, "startup_load": load}
    row["warm_front"] = bench.loadgen(port, "/", 4, 90, run, label + "-warm-front")
    row["warm_boot"] = bench.loadgen(port, "/wp-load.php", 4, 30, run, label + "-warm-boot")
    for key, page, connections in (("boot", "/wp-load.php", 1), ("front", "/", 1), ("throughput", "/", 4)):
        row[key] = bench.loadgen(port, page, connections, 15, run, label + "-" + key)
    row["rss_mib"] = bench.rss_mib(bench.descendants(process.pid))
    row["rss_load"] = bench.load_average()
    return row


def measure(args):
    path = args.run / "measurements.json"
    rows = json.loads(path.read_text()) if path.exists() else []
    configs = ["uncached", "loaded", "fpm"] if args.cache else ["uncached", "fpm"]
    for repeat in range(1, args.repeats + 1):
        for config in configs:
            label = f"steady-{repeat}-{config}"
            if any(row["label"] == label for row in rows):
                continue
            if config == "fpm":
                before = bench.load_average()
                with bench.server("fpm", args.docroot, args.run, label, args.port) as (process, startup, container):
                    row = steady(args.port, args.run, label, process, startup, before)
                    pid = int(subprocess.check_output(["docker", "inspect", container,
                                                       "--format", "{{.State.Pid}}"], text=True))
                    row["nginx_rss_mib"] = bench.rss_mib(bench.descendants(pid))
            else:
                with server(args, label, cache=args.cache if config == "loaded" else None) as (process, startup, before):
                    row = steady(args.port, args.run, label, process, startup, before)
            rows.append(row)
            bench.save(args.run, "measurements.json", rows)


def curve(args):
    path = args.run / "curves.json"
    rows = json.loads(path.read_text()) if path.exists() else []
    configs = ["uncached", "loaded", "fpm"] if args.cache else ["uncached", "fpm"]
    for repeat in range(1, args.repeats + 1):
        for config in configs:
            label = f"curve-{repeat}-{config}"
            if any(row["label"] == label for row in rows):
                continue
            manager = bench.server("fpm", args.docroot, args.run, label, args.port) if config == "fpm" else server(
                args, label, cache=args.cache if config == "loaded" else None)
            before = bench.load_average()
            with manager as (process, startup, unused):
                command = ["java", "-Dwarmup=0", f"-Dhost={bench.HOST}", str(ROOT / "bench/LoadGen.java"),
                           "curve", f"http://127.0.0.1:{args.port}/", "4", "10", "18"]
                print(f"{label}: startup={startup:.0f}ms load={before}", flush=True)
                output = subprocess.check_output(command, cwd=ROOT, text=True, timeout=360)
                (args.run / f"{label}.curve.log").write_text(output)
                samples = []
                for line in output.splitlines():
                    match = re.search(r"sample=(\d+) start=([\d.]+)s load=([\d.]+) ([\d.]+) ([\d.]+).*requests=(\d+) errors=(\d+) rps=(\d+) p50=([\d.]+)ms", line)
                    if match:
                        sample, start, l1, l5, l15, requests, errors, rps, p50 = match.groups()
                        if int(errors):
                            raise RuntimeError(line)
                        samples.append({"sample": int(sample), "start_s": float(start), "load": [float(l1), float(l5), float(l15)],
                                        "requests": int(requests), "rps": int(rps), "p50_ms": float(p50)})
                if len(samples) != 18:
                    raise RuntimeError(output)
                row = {"label": label, "startup_ms": startup, "startup_load": before, "samples": samples,
                       "rss_mib": bench.rss_mib(bench.descendants(process.pid))}
            rows.append(row)
            bench.save(args.run, "curves.json", rows)


def diagnose(args):
    with server(args, "diagnostic", diagnostics=True) as (process, startup, before):
        bench.loadgen(args.port, "/", 4, 30, args.run, "diagnostic-front")
    text = (args.run / "diagnostic.server.log").read_text(errors="replace")
    counts = {key: len(re.findall(r"opt " + key + r"\b", text)) for key in ("queued", "done", "failed")}
    print("Compilation counts:", counts)
    bench.save(args.run, "diagnostic.json", counts)
    if not counts["done"]:
        raise RuntimeError("No successful guest compilation; do not benchmark this image as JIT-enabled")


def gc_summary(args):
    summaries = []
    for path in sorted(args.run.glob("*.server.log")):
        text = path.read_text(errors="replace")
        # Serial GC's short PrintGC format uses seconds at the end of each event.
        times = [float(value) for value in re.findall(r"GC[^\n]*?([\d.]+) secs\]", text)]
        if not times:
            times = [float(value) / 1000 for value in re.findall(r"GC\([^\n]*?([\d.]+)ms", text)]
        summaries.append({"log": path.name, "events": len(times), "pause_s": sum(times)})
    bench.save(args.run, "gc-summary.json", summaries)
    print(json.dumps(summaries, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("diagnose", "store", "check", "measure", "curve", "gc_summary"))
    parser.add_argument("docroot", type=Path)
    parser.add_argument("--binary", type=Path, default=ROOT / "target/blitzvm")
    parser.add_argument("--run", type=Path, default=ROOT / "target/engine-cache")
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--policy", choices=("none", "compiled", "hot", "aot", "executed"), default="hot")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--train-seconds", type=int, default=120)
    parser.add_argument("--stop-timeout", type=int, default=600)
    parser.add_argument("--port", type=int, default=18088)
    parser.add_argument("--gc", action="store_true")
    parser.add_argument("--vm-option", action="append", default=[])
    args = parser.parse_args()
    args.docroot = args.docroot.resolve()
    args.binary = args.binary.resolve()
    args.run = args.run.resolve()
    args.cache = args.cache.resolve() if args.cache else None
    args.run.mkdir(parents=True, exist_ok=True)
    if args.repeats < 1 or args.train_seconds < 120 or args.stop_timeout < 1:
        parser.error("positive repeats/stop-timeout and at least 120 training seconds are required")
    if args.action in ("store", "check") and not args.cache:
        parser.error("--cache required")
    identity = {"path": str(args.binary), "bytes": args.binary.stat().st_size,
                "sha256": hashlib.sha256(args.binary.read_bytes()).hexdigest(), "vm_options": args.vm_option}
    previous = args.run / "binary.json"
    if previous.exists() and json.loads(previous.read_text()) != identity:
        parser.error("result directory belongs to another binary/options; use a fresh --run directory")
    bench.save(args.run, "binary.json", identity)
    globals()[args.action](args)


if __name__ == "__main__":
    main()
