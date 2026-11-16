#!/usr/bin/env bash
# Differential test of the date/time port: runs runner.php on /usr/bin/php (twice, to find
# cases that depend on the wall clock) and on BlitzVM, then reports per-kind pass counts and
# the first mismatches.
#   scripts/datetime-diff/run.sh [kind] [max-mismatches-shown]
# BLITZ_ROOT selects the BlitzVM checkout (default: the one this script lives in).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${BLITZ_ROOT:-$(cd "$HERE/../.." && pwd)}"
KIND="${1:-}"
SHOW="${2:-30}"
OUT="${TMPDIR:-/tmp}/datetime-diff.$$"
mkdir -p "$OUT"
export CORPUS="$HERE/corpus.tsv" KIND
php "$HERE/runner.php" > "$OUT/php1" 2>/dev/null
sleep 1.2
php "$HERE/runner.php" > "$OUT/php2" 2>/dev/null
"$ROOT/bin/php-truffle" "$HERE/runner.php" > "$OUT/blitz" 2> "$OUT/blitz.err"
awk -F'\t' -v show="$SHOW" -v corpus="$HERE/corpus.tsv" '
  BEGIN { while ((getline l < corpus) > 0) { c[++nc] = l } }
  FILENAME == ARGV[1] { p1[$1] = $0; next }
  FILENAME == ARGV[2] { p2[$1] = $0; order[++n] = $1; kind[$1] = $2; next }
  { b[$1] = $0 }
  END {
    for (i = 1; i <= n; i++) {
      k = order[i]; t = kind[k]; total[t]++
      if (p1[k] != p2[k]) { vol[t]++; continue }
      if (b[k] == p2[k]) { pass[t]++; continue }
      fail[t]++
      if (shown < show) {
        shown++
        split(c[k], f, "\t")
        printf "case %d [%s] tz=%s a=\"%s\" b=\"%s\"\n  php:   %s\n  blitz: %s\n", k, t, f[2], f[3], f[4], substr(p2[k], length(k) + length(t) + 3), (k in b ? substr(b[k], length(k) + length(t) + 3) : "(no output)")
      }
    }
    printf "\n%-20s %7s %7s %7s %9s\n", "kind", "total", "pass", "fail", "volatile"
    for (t in total) { printf "%-20s %7d %7d %7d %9d\n", t, total[t], pass[t], fail[t], vol[t]; T += total[t]; P += pass[t]; F += fail[t]; V += vol[t] }
    printf "%-20s %7d %7d %7d %9d\n", "ALL", T, P, F, V
  }' "$OUT/php1" "$OUT/php2" "$OUT/blitz"
if [[ -s "$OUT/blitz.err" ]]; then echo; echo "BlitzVM stderr (first 10 lines):"; grep -v WARNING "$OUT/blitz.err" | head -10; fi
rm -rf "$OUT"
