#!/usr/bin/env python3
"""Print report-ready tables from raw measurements without rounding intermediate ratios."""
import argparse
import json
from pathlib import Path
import statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", nargs="?", default=str(Path(__file__).with_name("results.json")))
    args = parser.parse_args()
    data = json.loads(Path(args.file).read_text())
    records = data["measurements"]
    for jit in (False, True):
        print("\nGraal enabled" if jit else "\nInterpreter only")
        print("| Benchmark | PHP | AST | BC | AST/BC |")
        print("|---|---:|---:|---:|---:|")
        for name in sorted({r["file"] for r in records}):
            cells = []
            medians = {}
            for mode in ("php", "ast", "bc"):
                values = [r["median_ms"] for r in records if r["jit"] == jit and r["file"] == name and r["mode"] == mode]
                if not values:
                    cells.append("missing")
                    continue
                medians[mode] = statistics.median(values)
                cells.append(f"{medians[mode]:.2f} ({min(values):.2f}–{max(values):.2f}; n={len(values)})")
            ratio = f"{medians['ast'] / medians['bc']:.2f}x" if 'ast' in medians and 'bc' in medians else "missing"
            print("| " + " | ".join([name] + cells + [ratio]) + " |")
    loads = [float(r["load"].split("load average:")[1].split(",")[0]) for r in records]
    print(f"\nPre-run 1-minute load average: {min(loads):.2f}–{max(loads):.2f}")
    print("\nPer-pair AST/BC ratios:")
    for jit in (False, True):
        for name in sorted({r["file"] for r in records}):
            pairs = []
            for repeat in sorted({r["repeat"] for r in records}):
                pair = {r["mode"]: r["median_ms"] for r in records if r["jit"] == jit and r["file"] == name and r["repeat"] == repeat}
                if "ast" in pair and "bc" in pair:
                    pairs.append(pair["ast"] / pair["bc"])
            print(f"{name} jit={jit}: " + ", ".join(f"{p:.3f}x" for p in pairs))


if __name__ == "__main__":
    main()
