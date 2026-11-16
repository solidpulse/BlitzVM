#!/usr/bin/env python3
"""One process at a time, interleaved AST/BC pairs; retain every round and load sample."""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
BENCH = Path(__file__).resolve().parent
INTERPRETER = "-Dpolyglot.engine.AllowExperimentalOptions=true -Dpolyglot.engine.Compilation=false"
FILES = sorted(BENCH.glob("0[1-4]-*.php"))


def run(path, mode, jit, extra=""):
    env = os.environ.copy()
    env["PHP_TRUFFLE_OPTS"] = ("" if jit else INTERPRETER) + " " + extra
    if mode == "php":
        command = ["/usr/bin/php", "-d", "opcache.enable_cli=1", "-d", "opcache.jit=disable", str(path)]
    else:
        launcher = ROOT / ("bin/php-truffle" if mode == "ast" else "bench/bytecode/run-bc.sh")
        command = [str(launcher), str(path)]
    load = subprocess.check_output(["uptime"], cwd=ROOT, text=True).strip()
    started = time.time()
    result = subprocess.run(command, cwd=ROOT, env=env, text=True, capture_output=True, timeout=300)
    if result.returncode:
        raise RuntimeError(f"{command}:\n{result.stdout}\n{result.stderr}")
    rounds = re.findall(r"round=(\d+) ns=(\d+) result=([^\n]+)", result.stdout)
    if len(rounds) != 10 or [int(r[0]) for r in rounds] != list(range(10)):
        raise RuntimeError(f"Unexpected output: {result.stdout}")
    times = [int(r[1]) / 1e6 for r in rounds]
    return dict(file=path.name, mode=mode, jit=jit, started=started, load=load, rounds_ms=times,
                median_ms=statistics.median(times[-3:]),
                results=[r[2] for r in rounds], stdout=result.stdout, stderr=result.stderr)


def normalized(output):
    return re.sub(r"ns=\d+", "ns=<time>", output)


def verify():
    # Keep verification serial too; reference output comes only from /usr/bin/php.
    check = BENCH / "check.php"
    reference = subprocess.check_output(["/usr/bin/php", str(check)], cwd=ROOT)
    for launcher in (ROOT / "bin/php-truffle", BENCH / "run-bc.sh"):
        env = os.environ.copy()
        env["PHP_TRUFFLE_OPTS"] = INTERPRETER
        actual = subprocess.check_output([str(launcher), str(check)], cwd=ROOT, env=env)
        if actual != reference:
            raise RuntimeError(f"Subset checks differ ({launcher}):\n{reference!r}\n{actual!r}")
    for path in FILES:
        expected = normalized(run(path, "php", False)["stdout"])
        for mode in ("ast", "bc"):
            actual = normalized(run(path, mode, False)["stdout"])
            if actual != expected:
                raise RuntimeError(f"Benchmark output differs: {path.name} {mode}")
        print(f"verified {path.name}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--output", default="bench/bytecode/results.json")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--resume", action="store_true", help="Resume the same build's interrupted result file")
    args = parser.parse_args()
    if args.verify:
        verify()
        return
    if args.repeats < 3:
        parser.error("At least three independent measurements are required")
    results = []
    output = ROOT / args.output
    metadata = dict(php=subprocess.check_output(["/usr/bin/php", "-v"], text=True, cwd=ROOT),
                    java=subprocess.run([str(ROOT / ".graalvm/current/bin/java"), "-version"],
                                        capture_output=True, text=True, cwd=ROOT).stderr,
                    repeats=args.repeats,
                    revision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip())
    if args.resume:
        saved = json.loads(output.read_text())
        results = saved["measurements"]
        metadata = saved["metadata"]
        if metadata.get("revision") != subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip():
            parser.error("Do not resume results across different code revisions")
        metadata["repeats"] = args.repeats
    for jit in (False, True):
        for path in FILES:
            expected = None
            for repeat in range(args.repeats):
                # At least A,B,A,B,A,B. Insert the PHP baseline after each pair.
                for mode in ("ast", "bc", "php"):
                    previous = next((r for r in results if r["jit"] == jit and r["file"] == path.name
                                     and r["mode"] == mode and r["repeat"] == repeat), None)
                    if previous is not None:
                        expected = normalized(previous["stdout"])
                        continue
                    entry = run(path, mode, jit)
                    entry["repeat"] = repeat
                    value = normalized(entry["stdout"])
                    if expected is None:
                        expected = value
                    elif value != expected:
                        raise RuntimeError(f"Result mismatch: {path.name} {mode} jit={jit}")
                    results.append(entry)
                    output.write_text(json.dumps(dict(metadata=metadata, measurements=results), indent=2) + "\n")
                    print(f"{path.name} jit={jit} pair={repeat + 1} {mode}: {entry['median_ms']:.3f} ms; {entry['load']}", flush=True)
    for jit in (False, True):
        for path in FILES:
            for mode in ("php", "ast", "bc"):
                times = [r["median_ms"] for r in results if r["jit"] == jit and r["file"] == path.name and r["mode"] == mode]
                print(f"{path.name} jit={jit} {mode}: median={statistics.median(times):.3f} range={min(times):.3f}..{max(times):.3f}")


if __name__ == "__main__":
    main()
