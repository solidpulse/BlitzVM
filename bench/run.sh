#!/usr/bin/env bash
# Compares php-truffle with php-fpm (behind nginx) and Swoole on the same scenarios.
#   bench/run.sh [target...]   targets: truffle fpm fpm-jit swoole swoole-preempt (default: all)
# Needs: php-fpm8.5 on the host, docker (nginx:alpine, phpswoole/swoole:latest).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${RUN_DIR:-$ROOT/target/bench}"
mkdir -p "$RUN"
LOAD="java $ROOT/bench/LoadGen.java"
HTTP_PORT=18402
FPM_PORT=9101
THROUGHPUT_WORKERS="${THROUGHPUT_WORKERS:-4}"
LOAD_SECONDS="${LOAD_SECONDS:-10}"

wait_up() { for _ in $(seq 1 100); do curl -fs "$1" >/dev/null 2>&1 && return 0; sleep 0.2; done; echo "server did not come up: $1" >&2; return 1; }

start_truffle() { # $1 workers
  "$ROOT/bin/php-truffle" serve --port 18401 --cpus "$1" --quantum-ms 10 --max-exec-ms 30000 "$ROOT/www" > "$RUN/truffle.log" 2>&1 &
  PIDS=$!; BASE=http://127.0.0.1:18401; wait_up "$BASE/hello.php"
}
start_fpm() { # $1 workers, $2 jit mode
  sed -e "s|__RUN__|$RUN|g" -e "s|__FPM_PORT__|$FPM_PORT|g" -e "s|__WORKERS__|$1|g" -e "s|__JIT__|$2|g" \
    "$ROOT/bench/fpm/php-fpm.conf.tmpl" > "$RUN/php-fpm.conf"
  sed -e "s|__HTTP_PORT__|$HTTP_PORT|g" -e "s|__FPM_PORT__|$FPM_PORT|g" -e "s|__WWW__|$ROOT/www|g" \
    "$ROOT/bench/fpm/nginx.conf.tmpl" > "$RUN/nginx.conf"
  php-fpm8.5 -F -y "$RUN/php-fpm.conf" -p "$RUN" > "$RUN/fpm.out" 2>&1 &
  PIDS=$!
  docker run -d --rm --name bench-nginx --network host -v "$RUN/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:alpine >/dev/null
  BASE=http://127.0.0.1:$HTTP_PORT; wait_up "$BASE/hello.php"
}
start_swoole() { # $1 workers, $2 preempt 0|1
  docker run -d --rm --name bench-swoole --network host -e PORT=18403 -e WORKERS="$1" -e PREEMPT="$2" \
    -v "$ROOT/bench/swoole/server.php:/app/server.php:ro" phpswoole/swoole:latest php /app/server.php >/dev/null
  BASE=http://127.0.0.1:18403; wait_up "$BASE/hello.php"
}
stop_all() {
  [[ -n "${PIDS:-}" ]] && kill $PIDS 2>/dev/null && wait $PIDS 2>/dev/null
  docker rm -f bench-nginx bench-swoole >/dev/null 2>&1
  PIDS=""; sleep 0.5
}
start() { # $1 target, $2 workers
  case "$1" in
    truffle) start_truffle "$2" ;;
    fpm) start_fpm "$2" disable ;;
    fpm-jit) start_fpm "$2" tracing ;;
    swoole) start_swoole "$2" 0 ;;
    swoole-preempt) start_swoole "$2" 1 ;;
  esac
}
rss_mb() { # resident memory of the serving processes
  case "$1" in
    truffle) ps -o rss= -p "$(pgrep -f 'host.Main serve --port 18401' | head -1)" | awk '{printf "%.0f", $1/1024}' ;;
    fpm|fpm-jit) ps -C php-fpm8.5 -o rss= | awk '{s+=$1} END {printf "%.0f", s/1024}' ;;
    swoole*) docker stats --no-stream --format '{{.MemUsage}}' bench-swoole | cut -d/ -f1 | sed 's/MiB//; s/ //g' ;;
  esac
}

scenario() { # $1 target
  local t="$1"
  echo "################ $t"

  start "$t" "$THROUGHPUT_WORKERS"
  echo "== throughput: hello.php, $THROUGHPUT_WORKERS workers, 32 connections, ${LOAD_SECONDS}s after 5s warm-up"
  $LOAD load "$BASE/hello.php" 32 "$LOAD_SECONDS"
  echo "== memory after load: $(rss_mb "$t") MB"
  echo "== fib(27), 6 runs (last is warm)"
  for _ in 1 2 3 4 5 6; do $LOAD once "$BASE/fib.php?n=27"; done | tail -2
  stop_all

  start "$t" 1
  echo "== starvation: 1 worker, spin.php?s=4 running, then hello every 300ms"
  $LOAD once "$BASE/spin.php?s=4" > "$RUN/spin.out" 2>&1 &
  local spin=$!
  sleep 0.5
  $LOAD probe "$BASE/hello.php" 4 300
  wait $spin; echo "   spin: $(cat "$RUN/spin.out")"

  echo "== blocking: 1 worker, sleep.php?ms=1500 running, then hello"
  $LOAD once "$BASE/sleep.php?ms=1500" > "$RUN/sleep.out" 2>&1 &
  local sl=$!
  sleep 0.3
  $LOAD probe "$BASE/hello.php" 2 100
  wait $sl; echo "   sleep: $(cat "$RUN/sleep.out")"
  stop_all
}

trap stop_all EXIT
TARGETS=("$@")
[[ ${#TARGETS[@]} -eq 0 ]] && TARGETS=(truffle fpm fpm-jit swoole swoole-preempt)
(cd "$ROOT" && mvn -q -B -DskipTests package)
for t in "${TARGETS[@]}"; do scenario "$t"; done
