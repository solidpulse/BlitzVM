#!/usr/bin/env bash
# Machine code size per PHP construct: compiles bench/pe-size/cost.php with Graal and prints
# bytes per occurrence for each f_<construct> (tier 1, empty function subtracted). Use it to
# check that a node change does not inflate compiled code: every byte here is multiplied by
# every use of the construct in an application.
#   bench/pe-size/measure.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TRACE="$(mktemp)"
trap 'rm -f "$TRACE"' EXIT
PHP_TRUFFLE_OPTS="-Dpolyglot.engine.TraceCompilation=true" "$ROOT/bin/php-truffle" "$ROOT/bench/pe-size/cost.php" 2>&1 \
  | grep "opt done" > "$TRACE" || true
python3 - "$TRACE" <<'PY'
import re, sys
rows = {}
for line in open(sys.argv[1]):
    m = re.search(r'f_(\w+)\(\)\s*\|Tier 1.*?CodeSize\s+(\d+)', line)
    if m:
        rows[m.group(1)] = int(m.group(2))
empty = rows.pop('empty', 0)
for name, size in sorted(rows.items(), key=lambda kv: -kv[1]):
    print(f"{name:18} {(size - empty) / 10:8.0f} bytes")
PY
