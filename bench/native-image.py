#!/usr/bin/env python3
"""Native-image/JVM/FPM WordPress A/B, correctness checks and interpreter kernels.

Examples (from the repository root):
  bench/native-image.py check /path/to/wordpress
  bench/native-image.py measure /path/to/wordpress
  bench/native-image.py micro /path/to/wordpress
All servers are owned by this process and stopped in finally blocks. Existing
listeners and containers are never reused or stopped. Raw results go in target.
"""
import argparse
import contextlib
import datetime
import difflib
import json
import os
from pathlib import Path
import re
import socket
import statistics
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
HOST = "localhost:18088"
PAGES = ["/", "/?p=1", "/?cat=1", "/?s=hello", "/wp-login.php", "/?feed=rss2", "/?p=999"]


def load_average():
    return list(os.getloadavg())


def command(config):
    kind, mode = config.split("-", 1)
    flags = [f"-Dblitz.bytecode={'off' if mode == 'ast' else 'auto'}"]
    if kind == "native":
        return [str(ROOT / "target/blitzvm"), *flags]
    return [str(ROOT / ".graalvm/current/bin/java"), "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow", "-XX:-DontCompileHugeMethods",
            "-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1", *flags,
            "-cp", f"{ROOT}/target/classes:{ROOT}/target/lib/*", "dev.phpruntime.host.Main"]


def descendants(pid):
    found = {pid}
    rows = subprocess.check_output(["ps", "-eo", "pid=,ppid="], text=True).splitlines()
    while True:
        extra = {int(p) for row in rows for p, parent in [row.split()] if int(parent) in found}
        if extra <= found:
            return found
        found |= extra


def rss_mib(pids):
    total = 0
    for pid in pids:
        try:
            for line in Path(f"/proc/{pid}/status").read_text().splitlines():
                if line.startswith("VmRSS:"):
                    total += int(line.split()[1])
        except FileNotFoundError:
            pass
    return total / 1024


def get(port, path):
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"Host": HOST})
    try:
        response = urllib.request.urlopen(req, timeout=120)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read()


@contextlib.contextmanager
def server(config, docroot, run, label, port):
    with socket.socket() as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", port))
    container = None
    process = None
    log = (run / f"{label}.server.log").open("wb")
    start = time.monotonic()
    try:
        if config == "fpm":
            fpm_port = port + 1
            with socket.socket() as sock:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(("127.0.0.1", fpm_port))
            replacements = {"__RUN__": str(run), "__FPM_PORT__": str(fpm_port),
                            "__WORKERS__": "4", "__JIT__": "disable",
                            "__HTTP_PORT__": str(port), "__WWW__": str(docroot)}
            for name in ("php-fpm", "nginx"):
                text = (ROOT / f"bench/fpm/{name}.conf.tmpl").read_text()
                for key, value in replacements.items():
                    text = text.replace(key, value)
                (run / f"{name}.conf").write_text(text)
            process = subprocess.Popen(["php-fpm8.5", "-d", "opcache.enable=1", "-d",
                                        "opcache.jit=disable", "-d", "opcache.jit_buffer_size=0",
                                        "-F", "-y", str(run / "php-fpm.conf"), "-p", str(run)],
                                       cwd=ROOT, stdout=log, stderr=log)
            name = f"blitz-native-nginx-{os.getpid()}"
            container = subprocess.check_output([
                "docker", "run", "-d", "--rm", "--name", name, "--pull=never", "--network", "host",
                "-v", f"{run}/nginx.conf:/etc/nginx/nginx.conf:ro", "-v", f"{docroot}:/www:ro",
                "nginx:alpine"], text=True).strip()
        else:
            process = subprocess.Popen([*command(config), "serve", "--port", str(port), "--cpus", "4",
                                        str(docroot)], cwd=ROOT, stdout=log, stderr=log)
        while time.monotonic() - start < 120:
            if process.poll() is not None:
                raise RuntimeError(f"Server exited: {log.name}")
            try:
                status, body = get(port, "/")
                if status != 200 or len(body) < 10000:
                    raise RuntimeError(f"Invalid first front page: HTTP {status}, {len(body)} bytes")
                break
            except (ConnectionError, urllib.error.URLError):
                time.sleep(.02)
        else:
            raise RuntimeError(f"No first response after 120 s: {log.name}")
        startup = (time.monotonic() - start) * 1000
        yield process, startup, container
    finally:
        if container:
            subprocess.run(["docker", "stop", "-t", "5", container], stdout=subprocess.DEVNULL, check=False)
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        log.close()


def loadgen(port, path, connections, seconds, run, label):
    load = load_average()
    uptime = subprocess.check_output(["uptime"], text=True).strip()
    cmd = ["java", "-Dwarmup=0", f"-Dhost={HOST}", str(ROOT / "bench/LoadGen.java"),
           "load", f"http://127.0.0.1:{port}{path}", str(connections), str(seconds)]
    output = subprocess.check_output(cmd, cwd=ROOT, text=True, timeout=seconds + 180)
    (run / f"{label}.load.log").write_text(uptime + "\n" + " ".join(cmd) + "\n" + output)
    print(f"{label}: {output.strip()} load={load}", flush=True)
    match = re.search(r"requests=(\d+) errors=(\d+) rps=(\d+) p50=([\d.]+)ms", output)
    if not match:
        raise RuntimeError(output)
    requests, errors, rps, p50 = match.groups()
    if int(errors) or not int(requests):
        raise RuntimeError(f"Invalid load measurement: {output}")
    return {"requests": int(requests), "errors": int(errors), "rps": int(rps), "p50_ms": float(p50),
            "load": load, "load_after": load_average()}


def save(run, name, value):
    (run / name).write_text(json.dumps(value, indent=2) + "\n")


def check(args):
    pages = {}
    for config in ("jvm-ast", "jvm-bc", "native-ast", "native-bc"):
        with server(config, args.docroot, args.run, f"check-{config}", args.port):
            pages[config] = []
            for index, path in enumerate(PAGES):
                status, body = get(args.port, path)
                (args.run / f"page-{config}-{index}.body").write_bytes(body)
                baseline = pages["jvm-ast"][index] if config != "jvm-ast" else (status, body)
                same = (status, body) == baseline
                print(f"{config} {path}: HTTP {status}, {len(body)} bytes, equal={same}", flush=True)
                if not same:
                    diff = difflib.unified_diff(baseline[1].decode(errors="replace").splitlines(),
                                                body.decode(errors="replace").splitlines(),
                                                fromfile="jvm-ast", tofile=config)
                    (args.run / f"page-{config}-{index}.diff").write_text("\n".join(diff) + "\n")
                pages[config].append((status, body))
    summary = {config: [{"path": path, "status": result[0], "bytes": len(result[1]),
                        "equal": result == pages["jvm-ast"][index]}
                       for index, (path, result) in enumerate(zip(PAGES, values))]
               for config, values in pages.items()}
    save(args.run, "pages.json", summary)
    fixtures = {}
    for config in ("native-ast", "native-bc"):
        failures = []
        scripts = sorted((ROOT / "src/test/resources/php").glob("*.php"))
        for script in scripts:
            result = subprocess.run([*command(config), str(script.relative_to(ROOT))], cwd=ROOT,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
            if result.stdout != script.with_suffix(".out").read_bytes() or result.returncode:
                failures.append({"script": script.name, "exit": result.returncode,
                                 "stderr": result.stderr.decode(errors="replace")})
                (args.run / f"fixture-{config}-{script.stem}.actual").write_bytes(result.stdout)
                print(f"FAIL {config} {script.name}: {failures[-1]}", flush=True)
        fixtures[config] = {"total": len(scripts), "failures": failures}
        print(f"{config}: {len(scripts) - len(failures)}/{len(scripts)} fixtures passed", flush=True)
    save(args.run, "fixtures.json", fixtures)


def measure(args):
    results = []
    # Three pairs (standing measurement rules), never concurrent servers.
    schedules = [(["native-ast", "native-bc", "jvm-ast", "jvm-bc", "fpm"], 90)]
    schedules += [(["native-ast", "native-bc"], 300)]
    for configs, front_warmup in schedules:
        for repeat in range(1, args.repeats + 1):
            for config in configs:
                label = f"{front_warmup}s-{repeat}-{config}"
                before = load_average()
                print(f"Starting {label} at {datetime.datetime.now().isoformat()} load={before}", flush=True)
                with server(config, args.docroot, args.run, label, args.port) as (process, startup, container):
                    row = {"configuration": config, "repeat": repeat, "front_warmup_s": front_warmup,
                           "startup_ms": startup, "startup_load": before}
                    row["warm_front"] = loadgen(args.port, "/", 4, front_warmup, args.run, label + "-warm-front")
                    row["warm_boot"] = loadgen(args.port, "/wp-load.php", 4, 30, args.run, label + "-warm-boot")
                    row["boot"] = loadgen(args.port, "/wp-load.php", 1, 15, args.run, label + "-boot")
                    row["front"] = loadgen(args.port, "/", 1, 15, args.run, label + "-front")
                    row["throughput"] = loadgen(args.port, "/", 4, 15, args.run, label + "-throughput")
                    row["rss_mib"] = rss_mib(descendants(process.pid))
                    if container:
                        nginx_pid = int(subprocess.check_output([
                            "docker", "inspect", container, "--format", "{{.State.Pid}}"], text=True))
                        row["nginx_rss_mib"] = rss_mib(descendants(nginx_pid))
                    results.append(row)
                    save(args.run, "measurements.json", results)


def micro(args):
    rows = []
    for repeat in range(1, args.repeats + 1):
        for config in ("native-ast", "native-bc"):
            for name in ("01-wp-array-get.php", "02-small-functions.php"):
                label = f"micro-{repeat}-{config}-{name}"
                before = load_average()
                uptime = subprocess.check_output(["uptime"], text=True).strip()
                result = subprocess.run([*command(config), "-Dpolyglot.engine.Compilation=false",
                                         str(ROOT / "bench/bytecode" / name)], cwd=ROOT,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
                (args.run / f"{label}.log").write_text(uptime + "\n" + result.stdout.decode() +
                                                     "\nstderr:\n" + result.stderr.decode())
                if result.returncode:
                    raise RuntimeError(f"Kernel failed: {label}")
                times = [int(value) / 1e6 for value in re.findall(rb"ns=(\d+)", result.stdout)]
                checksums = re.findall(rb"result=(\d+)", result.stdout)
                expected = b"5400000" if name.startswith("01") else b"3620400000"
                if len(times) != 10 or any(value != expected for value in checksums):
                    raise RuntimeError(f"Kernel output invalid: {result.stdout!r}")
                row = {"configuration": config, "repeat": repeat, "kernel": name,
                       "last_three_median_ms": statistics.median(times[-3:]), "rounds_ms": times,
                       "load": before, "load_after": load_average()}
                print(f"{label}: {row['last_three_median_ms']:.2f} ms load={before}", flush=True)
                rows.append(row)
                save(args.run, "micro.json", rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "measure", "micro"))
    parser.add_argument("docroot", type=Path)
    parser.add_argument("--run", type=Path, default=ROOT / "target/native-bench")
    parser.add_argument("--port", type=int, default=18088)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    args.docroot = args.docroot.resolve()
    args.run = args.run.resolve()
    args.run.mkdir(parents=True, exist_ok=True)
    globals()[args.action](args)


if __name__ == "__main__":
    main()
