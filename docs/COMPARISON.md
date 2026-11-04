# php-truffle vs php-fpm vs Swoole

Measured on one machine (8 cores, Linux 6.8) with the same load generator
(`bench/LoadGen.java`) against all servers. Reproduce with `bench/run.sh`.

| Server | Version and setup |
|---|---|
| php-fpm | PHP 8.5.12, opcache on, static pool, nginx (alpine, Docker, host network) in front |
| php-fpm + JIT | as above with `opcache.jit=tracing` |
| Swoole | 6.2.3 on PHP 8.4.27 (`phpswoole/swoole` image, host network), `SWOOLE_HOOK_ALL` |
| Swoole + preemptive | as above with `enable_preemptive_scheduler => true` |
| php-truffle | this repo on GraalVM CE 23.0.2 (Graal JIT), JDK `HttpServer` front end |

"Workers" means FPM `pm.max_children`, Swoole `worker_num`, php-truffle `--cpus`.

## Results

| | php-fpm | php-fpm + JIT | Swoole | Swoole + preempt | php-truffle |
|---|---|---|---|---|---|
| hello.php req/s, 4 workers, 32 connections | 6,134 | 4,639 | **19,384** | 12,886 | 2,340 |
| hello.php p50 / p99 latency | 4.3 / 15.8 ms | 6.5 / 16.2 ms | **1.0 / 8.0 ms** | 1.9 / 9.5 ms | 9.6 / 53.3 ms |
| Memory after load (RSS) | 129 MB (5 processes) | 129 MB | **13 MB** | 13 MB | 382 MB (one JVM) |
| fib(27), warm | 8.3 ms | 8.7 ms | 10.8 ms | 9.9 ms | 4 to 10 ms |
| hello while a 4 s CPU loop runs, 1 worker | 3,392 ms | 3,434 ms | 3,531 ms | **~5 ms** | **~50 ms** |
| hello while a request sleeps 1.5 s, 1 worker | 1,262 ms | 1,285 ms | **~0 ms** | **~0 ms** | **~10 ms** |
| Loop iterations in 4 s (`microtime()` per iteration) | 147 M | 149 M | 151 M | 149 M | 42 M |

The last two latency rows are the first hello request issued after the slow request
started, minus about 80 ms of client start-up that every first probe includes (a fresh
JVM `HttpClient`). Raw output: `docs/bench-results.txt`.

## What the numbers say

**Starvation.** With one worker, a CPU-bound request blocks php-fpm and default Swoole
for its whole duration: the hello request waited 3.4 to 3.5 s. php-fpm relies on the OS
to time-slice between processes, but one process serves one request, so a busy request
owns its worker until it finishes. Default Swoole is cooperative: coroutines switch only
at I/O hooks, and a tight loop never reaches one. php-truffle and Swoole with
`enable_preemptive_scheduler` both time-slice the busy request and serve hello almost
immediately.

**Blocking calls.** php-fpm blocks the worker during `usleep`, so the hello request waited
1.26 s. Swoole's hooks turn `usleep` into a coroutine yield. php-truffle releases its CPU
slot during sleep. Both serve hello right away.

**Throughput and memory.** Swoole wins clearly: no per-request bootstrap, an event loop
written in C, and 13 MB for the whole server. php-fpm pays for FastCGI and nginx. php-truffle
is last, and the bottleneck is its front end: the JDK's built-in `HttpServer` serves a static
file at only about 5,100 req/s on this machine. The runtime itself costs 31 µs per request
(create context, run hello.php, close context, single thread, warm), about 32,000 req/s
per core. Replacing the front end with Netty or Undertow is the obvious next step. The JVM's
382 MB is mostly heap and code cache, shared by all requests.

**Raw compute.** Once the Graal JIT has compiled `fib`, php-truffle matches or beats opcache
with and without its JIT. Its first request is slow (about 200 ms for fib(27)) because the
code starts interpreted. The `microtime()` loop is 3.5 times slower because every builtin
call crosses a `@TruffleBoundary` into host Java. Intrinsifying hot builtins would close
most of that gap.

## Architecture comparison

| | php-fpm | Swoole | php-truffle |
|---|---|---|---|
| Unit of concurrency | OS process per in-flight request | coroutine inside a long-running worker process | thread per in-flight request, N CPU slots |
| Request isolation | complete (shared-nothing, script re-run per request) | none by default: globals and statics survive between requests | complete at the PHP level (one Truffle context per request) |
| Code reuse across requests | opcache shared memory | app stays loaded in the worker | one shared Truffle engine: parsed ASTs and JIT code |
| CPU-bound request | owns a worker until done | blocks its worker unless preemption is enabled | preempted every quantum (10 ms) at Truffle safepoints |
| Blocking I/O | blocks the worker | hooked calls yield the coroutine | sleep releases the slot; still costs a thread |
| Runaway script | `max_execution_time`, `request_terminate_timeout` | no per-request CPU kill by default | per-request CPU budget, context cancelled |
| PHP compatibility | full | full, with rules for long-running state | subset (no classes, closures, include) |

**How the two preemptive designs compare.** Swoole's preemptive scheduler and php-truffle
work the same way. A watchdog thread notices that the running code has had its quantum,
then sets a flag that the VM checks at safe points. Swoole uses the Zend VM's interrupt
flag (`EG(vm_interrupt)`), checked at loop back-edges and calls. php-truffle uses Truffle's
`ThreadLocalAction`, which runs at guest safepoints. Graal compiles those safepoints into
the machine code, so preemption still works after the loop is JIT-compiled.

There are two differences. First, Swoole yields one coroutine to another inside the same
worker, where all coroutines share the app's state. php-truffle hands an execution slot
between fully isolated requests. Second, php-truffle charges CPU time per request and can
cancel one request without killing a process.

## Caveats

- One machine, closed-loop client on the same host, short runs. Treat differences under
  about 20% as noise (opcache JIT measuring slower than plain opcache on hello.php is an example).
- FPM goes through nginx in Docker; Swoole and php-truffle serve HTTP directly.
- Swoole runs PHP 8.4 and the FPM pool runs PHP 8.5.
- Swoole's handlers are closures in a long-running server (`bench/swoole/server.php`), which
  is how Swoole apps are written; FPM and php-truffle execute the files in `www/` per request.
- php-truffle implements a PHP subset, so this compares runtimes on scripts all three can run,
  not general PHP applications.
