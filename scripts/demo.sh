#!/usr/bin/env bash
# Interview demo: one CPU slot, a CPU-bound request hogging it, hello-world still fast.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-8080}"
BASE="http://localhost:$PORT"

"$ROOT/bin/php-truffle" serve --port "$PORT" --cpus 1 --quantum-ms 10 --max-exec-ms 5000 "$ROOT/www" &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
until curl -fs "$BASE/__scheduler" >/dev/null 2>&1; do sleep 0.2; done

echo "== 1. hello world"
curl -s "$BASE/?name=Interviewer"

echo "== 2. spin.php holds the only CPU slot for 4s; hello must not wait for it"
curl -s -w '   [spin finished in %{time_total}s]\n' "$BASE/spin.php?s=4" &
SPIN=$!
sleep 0.5
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "   hello #$i while spinning: %{time_total}s\n" "$BASE/?name=$i"
done
echo "   scheduler: $(curl -s "$BASE/__scheduler")"
wait $SPIN

echo "== 3. sleep.php gives its slot away while sleeping"
curl -s -o /dev/null -w '   [sleep finished in %{time_total}s]\n' "$BASE/sleep.php?ms=1500" &
SLEEP=$!
sleep 0.3
curl -s -w '   fib while another request sleeps: %{time_total}s\n' "$BASE/fib.php?n=20"
wait $SLEEP

echo "== 4. while(true){} is preempted every quantum, then killed at max execution time"
curl -s -w '   [HTTP %{http_code} after %{time_total}s]\n' "$BASE/forever.php"
echo "   scheduler: $(curl -s "$BASE/__scheduler")"
