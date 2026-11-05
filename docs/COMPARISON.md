# php-truffle vs php-fpm vs Swoole

Measured on one machine (8 cores, Linux 6.8). Reproduce with `bench/run.sh`; raw output
is in `docs/bench-results.txt`.

| Server | Version and setup |
|---|---|
| php-fpm | PHP 8.5.12, opcache on, static pool, nginx (alpine, Docker, host network) in front |
| php-fpm + JIT | as above with `opcache.jit=tracing` |
| Swoole | 6.2.3 on PHP 8.4.27 (`phpswoole/swoole` image, host network), `SWOOLE_HOOK_ALL` |
| Swoole + preemptive | as above with `enable_preemptive_scheduler => true` |
| php-truffle (Netty) | this repo, GraalVM CE 23.0.2 with Truffle 24.1 (Graal JIT), Netty 4.2 native epoll front end (default). The repo now uses GraalVM 25.5; these numbers predate the upgrade |
| php-truffle (JDK) | same runtime behind the JDK's built-in `HttpServer` (`--http jdk`) |

Load: `bench/LoadGen.java`, closed loop, 32 keep-alive connections over raw sockets,
15 s warm-up then 10 s measured. "Workers" means FPM `pm.max_children`, Swoole
`worker_num`, php-truffle `--cpus`.

## Results

| | php-fpm | fpm + JIT | Swoole | Swoole + preempt | php-truffle Netty | php-truffle JDK |
|---|---|---|---|---|---|---|
| hello.php req/s, 4 workers | 17,510 | 18,855 | 131,536 | **132,633** | 20,644 | 16,633 |
| hello.php p50 / p99 | 1.76 / 3.34 ms | 1.64 / 3.12 ms | 0.18 / 1.44 ms | **0.18 / 1.36 ms** | 1.28 / 7.59 ms | 1.76 / 5.08 ms |
| index.php req/s (template, superglobals, date) | 14,156 | 14,642 | 104,052 | **107,085** | 19,567 | 15,643 |
| static file req/s (no PHP) | 90,313 (nginx) | **92,143** | 33,475 | 33,577 | 80,236 | 47,957 |
| Memory after load (RSS) | 138 MB | 138 MB | **26 MB** | 26 MB | 508 MB | 513 MB |
| fib(27), warm | 7.9 ms | 7.9 ms | 9.2 ms | 10.4 ms | **3.0 to 4.3 ms** | 2.2 to 3.9 ms |
| hello while a 4 s CPU loop runs, 1 worker | ~3,400 ms | ~3,330 ms | ~3,410 ms | **~10 ms** | ~30 ms | ~30 ms |
| hello while a request sleeps 1.5 s, 1 worker | ~1,190 ms | ~1,260 ms | <60 ms | **<10 ms** | **<10 ms** | <10 ms |
| `microtime()` loop iterations in 4 s | 148 M | 150 M | **150 M** | 150 M | 43 M | 43 M |

**Correction.** The "fpm + JIT" column above ran with the JIT inactive: the bench set
`opcache.jit` per pool, which PHP ignores because the JIT buffer is allocated when the
master starts. Those numbers are plain opcache. `bench/run.sh` now passes the JIT
settings on the php-fpm command line; see "Compute kernels" below for runs with the JIT on.

The two "hello while ..." rows are the first request sent after the slow request started,
minus the roughly 80 ms of client start-up that every first probe includes.

## What the numbers say

**Throughput.** Swoole is in a different class: 130k req/s for hello and 105k for the
template, six to seven times php-fpm and php-truffle, with a 26 MB footprint. Its workers
keep the application loaded and answer from an event loop written in C, so there is no
per-request bootstrap at all. php-truffle on Netty is ahead of php-fpm: 18% on hello.php
and 38% on index.php, where the template work favours compiled code. php-fpm pays for two
hops (nginx to FastCGI to a worker) and re-runs the script per request.

**What Netty changed.** Moving php-truffle from the JDK `HttpServer` to Netty with native
epoll raised the static-file ceiling from 48k to 80k req/s (+67%) and PHP throughput by
24%. The front end is no longer the limit for PHP requests. The remaining per-request cost
is the runtime: creating and closing an isolated Truffle context (about 31 µs warm) and
handing the CPU slot between threads through a fair semaphore. nginx is still the fastest
static server here; Swoole's static handler is its weak spot.

**Starvation.** With one worker, a CPU-bound request blocks php-fpm and default Swoole for
its whole duration (about 3.4 s). php-fpm time-slices processes through the OS, but one
process serves one request, so a busy request owns its worker. Default Swoole is
cooperative: coroutines switch only at I/O hooks, and a tight loop never reaches one.
Swoole with `enable_preemptive_scheduler` and php-truffle both time-slice the busy request
and answer hello within tens of milliseconds.

**Blocking calls.** php-fpm blocks the worker during `usleep`. Swoole's hooks turn it into
a coroutine yield, and php-truffle releases its CPU slot while sleeping.

**Raw compute.** Once Graal has compiled `fib`, php-truffle runs it about twice as fast as
opcache with the JIT off (PHP's default). Against the tracing JIT it depends on the code;
see "Compute kernels" below. Its first request is slow (about
200 ms for fib(27)) because code starts interpreted. Builtin-heavy code goes the other way:
the `microtime()` loop is 3.5 times slower, because every builtin call crosses a
`@TruffleBoundary` into host Java instead of being inlined.

**Memory.** One JVM at about 510 MB serves all requests: heap, Graal's compiler and the
code cache. php-fpm's 138 MB is five processes; Swoole's 26 MB is the whole server.

## Compute kernels (GraalVM 25, JIT verified on)

`SCENARIO=compute bench/run.sh fpm-jit truffle swoole`, kernels in `www/compute.php`,
1 worker, 12 runs each, median of the last three, all warm. Swoole cannot use PHP's JIT:
PHP disables it when an extension installs user opcode handlers, which Swoole does.

| Kernel | php-truffle | php-fpm, tracing JIT | Swoole | php-truffle vs JIT |
|---|---|---|---|---|
| tak(24, 16, 8) | **8.2 ms** | 14.6 ms | 36.9 ms | 1.8x faster |
| collatz to 300,000 | **144 ms** | 238 ms | 505 ms | 1.65x faster |
| pixel loop, small helper functions | **20.5 ms** | 27.3 ms | 69.5 ms | 1.3x faster |
| fib(27) | 2.4 to 3.8 ms | 4.3 to 4.6 ms | 8.8 ms | 1.1x to 1.9x, noisy |
| gcd sum, n=600 | 16.0 ms | **11.7 ms** | 29.7 ms | 0.73x |
| primes to 2,000,000 | 306 ms | **216 ms** | 739 ms | 0.71x |
| mandelbrot 600 x 600 | 56 ms | **44 ms** | 234 ms | 0.79x |
| spectral-norm n=300 | 64 ms | **48 ms** | 108 ms | 0.75x |
| sieve to 1,000,000 | 260 to 960 ms | 57 ms (CLI, no JIT) | not run | much slower |

php-truffle beats Swoole on every kernel except sieve. Against PHP's tracing JIT it wins on
deep recursion, integer loops with branches and code made of small user functions, where
Graal inlines across calls; the JIT wins on float loops and on loops dominated by `%`.
Sieve is slow because large array writes take the generic array path.

## Architecture comparison

| | php-fpm | Swoole | php-truffle |
|---|---|---|---|
| Unit of concurrency | OS process per in-flight request | coroutine inside a long-running worker process | thread per in-flight request, N CPU slots |
| Request isolation | complete (shared-nothing, script re-run per request) | none by default: globals and statics survive between requests | complete at the PHP level (one Truffle context per request) |
| Code reuse across requests | opcache shared memory | app stays loaded in the worker | one shared Truffle engine: parsed ASTs and JIT code |
| CPU-bound request | owns a worker until done | blocks its worker unless preemption is enabled | preempted every quantum (10 ms) at Truffle safepoints |
| Blocking I/O | blocks the worker | hooked calls yield the coroutine | sleep releases the slot; still costs a thread |
| Runaway script | `max_execution_time`, `request_terminate_timeout` | no per-request CPU kill by default | per-request CPU budget, context cancelled |
| HTTP front end | nginx + FastCGI | built in (C event loop) | Netty, native epoll; event loop never runs PHP |
| PHP compatibility | full | full, with rules for long-running state | subset (no classes, closures, include) |

**How the two preemptive designs compare.** Swoole's preemptive scheduler and php-truffle
work the same way. A watchdog thread notices that running code has had its quantum, then
sets a flag that the VM checks at safe points. Swoole uses the Zend VM's interrupt flag
(`EG(vm_interrupt)`), checked at loop back-edges and calls. php-truffle uses Truffle's
`ThreadLocalAction`, which runs at guest safepoints. Graal compiles those safepoints into
the machine code, so preemption still works after the loop is JIT-compiled.

There are two differences. First, Swoole yields one coroutine to another inside the same
worker, where all coroutines share the app's state. php-truffle hands an execution slot
between fully isolated requests. Second, php-truffle charges CPU time per request and can
cancel one request without killing a process.

## Where php-truffle's per-request time goes, and next steps

- Context per request (about 31 µs): the price of isolation. A pool of pre-created
  contexts would remove it from the request path.
- Fair-semaphore handoff between 32 connections and 4 slots: every release wakes a parked
  thread. A non-fair fast path when no slot is contended would help hello-world loads.
- Builtins behind `@TruffleBoundary`: intrinsify the hot ones (`microtime`, `strlen`,
  `count`) as Truffle nodes so Graal can inline them.

## Caveats

- One machine, client on the same host, single runs. Treat differences under about 15% as
  noise (fpm with and without JIT on hello.php, for example).
- FPM's nginx runs in Docker with host networking; Swoole runs in Docker; php-truffle runs
  on the host.
- Swoole runs PHP 8.4 and the FPM pool runs PHP 8.5.
- Swoole's handlers are closures in a long-running server (`bench/swoole/server.php`),
  which is how Swoole apps are written; FPM and php-truffle execute the files in `www/`
  per request.
- php-truffle implements a PHP subset, so this compares runtimes on scripts all three can
  run, not general PHP applications.
- An earlier version of this document reported far lower throughput for every server.
  Those runs were limited by the load generator (the JDK `HttpClient` funnels all I/O
  through one selector thread); `bench/LoadGen.java` now uses raw sockets.
