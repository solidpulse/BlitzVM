# Bytecode engine-cache experiments

These experiments only use `-Dblitz.bytecode=auto`, including its existing mixed
mode fallbacks. No AST-only performance results are produced.

```bash
# Build without/with auxiliary-engine-cache capability (same GraalVM version).
bin/build-native.sh
BLITZ_NATIVE_ENGINE_CACHE=1 bin/build-native.sh
target/blitzvm --engine-options

# Verify guest compilation before making performance claims.
bench/engine-cache.py diagnose /path/to/wordpress --run target/cache-diagnostic

# Train and store the shared engine on a clean shutdown.
bench/engine-cache.py store /path/to/wordpress \
  --cache target/wordpress.image --run target/cache-store --policy hot

# Compare uncached/loaded responses and all PHP CLI fixture outputs.
# Omit --cache for uncached bytecode correctness before the cache-enabled build.
bench/engine-cache.py check /path/to/wordpress \
  --cache target/wordpress.image --run target/cache-check

# Three alternating uncached/loaded/FPM runs, default heap sizing.
bench/engine-cache.py curve /path/to/wordpress \
  --cache target/wordpress.image --run target/cache-perf --gc
bench/engine-cache.py measure /path/to/wordpress \
  --cache target/wordpress.image --run target/cache-perf --gc
bench/engine-cache.py gc_summary /path/to/wordpress --run target/cache-perf
```

Do not reuse a cache with a rebuilt native executable: caches are bound to that
exact binary. Use separate output directories for changed binaries or runtime
options. The harness records and checks the binary SHA-256. It does not install
tools, modify WordPress, run destructive JDBC fixtures, or stop existing servers.
It only stops child PIDs and nginx containers it creates. An existing cache file
is never overwritten by `store`.

`curve` uses one Java load-generator process for 18 consecutive ten-second
windows, four connections. Each sample reports front-page req/s, p50 at those
four connections, and the 1/5/15-minute load averages. Sample epoch timestamps
also retain the offsets from server launch (including Java source-launch overhead
and the initial response); nominal windows are relative to the load phase.
Startup is the time from
server launch to the first complete successful WordPress `/` response. FPM
startup includes its nginx container launch. `measure` uses task 18's warm-up
and steady-state phases: 90 s `/`, 30 s boot, 15 s boot/front p50 at one
connection, 15 s front throughput at four connections.

`--binary PATH`, `--port N`, `--repeats N`, and `--vm-option=-Dname=value`
configure experiments. Use a fresh directory for each `CacheCompile` variant.
`--gc` enables native Serial GC logs without changing heap sizes or collector.
FPM always uses four static children, opcache enabled and JIT disabled.
The harness checks runtime load/patch confirmation, records separate cache
prepare/persist/write/load times, and tests fixtures both loaded and uncached.

Cache compilation policies are `none`, `compiled`, `hot`, `aot`, and `executed`.
All seven pages are requested during training before at least 120 seconds of
front-page load. The shared `PhpEngine` closes on shutdown, which is the runtime's
automatic `engine.CacheStore` persistence point. Shutdown has a separate timeout
and is recorded; missing/empty cache files are failures, not successes.
