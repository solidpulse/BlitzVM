# php-truffle

php-truffle is a PHP 8 subset interpreter written in Java on the Truffle framework, with an HTTP front end and a preemptive scheduler. Inspired by abertschi/graalphp (https://github.com/abertschi/graalphp), which used the Eclipse PDT parser and Truffle 20.2. This project is a fresh implementation: hand-written lexer and Pratt parser, Truffle 24.1 APIs (FrameDescriptor.Builder, ContextPolicy.SHARED, TruffleSafepoint, ThreadLocalAction).

## Quick Start

```bash
mvn package
scripts/get-graalvm.sh
bin/php-truffle script.php
bin/php-truffle serve --port 8080 --cpus 1 --quantum-ms 10 --max-exec-ms 5000 www
curl 'localhost:8080/?name=World'
curl localhost:8080/__scheduler
scripts/demo.sh        # the preemption demo end to end
```

Build and execution steps:

- `mvn package`: compiles and packages the runtime into a JAR with runtime dependencies in `target/lib`. Requires JDK 21+.
- `scripts/get-graalvm.sh` (optional): downloads GraalVM CE 23.0.2 into `./.graalvm`, which pairs with Truffle 24.1 and enables the Graal JIT; `bin/php-truffle` prefers it over `JAVA_HOME` (set `PHP_TRUFFLE_JAVA_HOME` to override).
- `bin/php-truffle script.php`: runs a PHP script in CLI mode and streams output to standard output and standard error.
- `bin/php-truffle serve --port 8080 --cpus 1 --quantum-ms 10 --max-exec-ms 5000 www`: starts the HTTP server on port 8080, serving directory `www` with 1 CPU execution slot, a 10 ms time-slice quantum, and a 5000 ms per-request execution budget.
- `curl 'localhost:8080/?name=World'`: executes `www/index.php` and returns the generated HTTP response.
- `curl localhost:8080/__scheduler`: prints scheduler metrics (cpus, running, waiting, completed, preemptions, timeouts).
- `scripts/demo.sh`: starts a 1-slot server and runs the hello world, CPU hog, sleep and infinite-loop scenarios with timings.

## Architecture

Packages reside under `dev.phpruntime`:

### parser
`Lexer` alternates between HTML mode and code mode for `<?php`, `<?=`, and `?>`. It supports string interpolation for `"$a"`, `"$a[k]"`, and `"{$expr}"`. `Parser` uses recursive descent for statements and Pratt precedence climbing for expressions, emitting Truffle nodes directly like SimpleLanguage. `Scope` performs compile-time variable allocation: function locals get frame slots; top-level variables, `global` imports and superglobals use the context's global table.

### nodes
AST nodes execute guest code. Arithmetic and comparison operations use Truffle DSL specializations with a `long` fast path with `rewriteOn = ArithmeticException.class` to `double`, mirroring PHP int overflow to float, with generic fallbacks for numeric strings and mixed operands. Loops use Truffle `LoopNode`, providing on-stack replacement (OSR) and safepoint polls on back edges. `CallNode` resolves on first execution and caches a `DirectCallNode` (inlinable by Graal) or a builtin. Control flow (`break N`, `continue N`, `return`, `exit`) uses `ControlFlowException`.

### runtime
Core values and execution helpers. `PhpArray` provides an ordered hash map, PHP key normalisation, and copy-on-write value semantics via `share()` and `forWrite()`. `PhpOps` implements PHP 8 type juggling, loose and strict comparison, and float formatting matching `zend_gcvt` with precision 14 for `echo` and 17 for `var_dump`. `PhpArith` implements fallback arithmetic routines.

### builtins
Contains about 175 functions across strings, arrays, math, output (`var_dump`, `print_r`, `printf`), type checking, date and time, `sleep`/`usleep`, `header()`, and `http_response_code()`.

### sched
Contains `Scheduler`, which coordinates fair multi-tenant request execution and time slicing across guest threads. Its implementation is detailed in the Preemptive Scheduling section.

### host
Process hosting and HTTP integration. `PhpEngine` maintains one shared polyglot `Engine` so parsed ASTs and compiled code are reused across requests, and allocates one `Context` per request, which gives PHP's shared-nothing request model. `PhpHttpServer` uses JDK `HttpServer` to map URLs to files under the document root and populates `$_GET`, `$_POST`, `$_SERVER`, and `$_REQUEST`. `Main` provides the command-line interface.

## Preemptive Scheduling

The scheduler allocates CPU time across concurrent HTTP requests:

- N CPU slots = a fair `java.util.concurrent.Semaphore`. A request must hold a slot to run guest code.
- A ticker thread runs every `quantum / 2`. If a running request has used more than its quantum and other requests are waiting for a slot, the ticker calls `TruffleLanguage.Env.submitThreadLocal` with a `ThreadLocalAction` targeting that request's thread.
- Truffle executes the action at the guest's next safepoint: loop back-edges (`LoopNode`, `foreach`) and function entry (`PhpFunctionRootNode` polls `TruffleSafepoint`). In compiled code the poll is part of Graal's safepoint mechanism, so preemption points cost close to nothing.
- Inside the action the request releases its slot and re-acquires it, which puts it at the tail of the fair queue: round-robin.
- Waiting uses `TruffleSafepoint.setBlockedThreadInterruptible` so other safepoint actions (such as cancellation) can still run.
- `sleep`/`usleep` release the slot while blocked, like a goroutine parking on a syscall.
- `max_execution_time`: per-request CPU time (time while holding a slot) is tracked; when it exceeds `--max-exec-ms` the context is cancelled with `Context.close(true)` and the response is a 500 with `"PHP Fatal error:  Maximum execution time of N seconds exceeded"`.
- This is similar to Go's asynchronous preemption.
- This is similar to BEAM reduction counting.
- php-fpm instead uses one OS process per request and has no time slicing.

## Measured Results

Setup: 1 CPU slot, 10 ms quantum. The scheduling results below were taken on Temurin 21 (interpreter only); preemption behaves the same with the Graal JIT, see `docs/COMPARISON.md`.

- While `spin.php` held the only slot for 4 s, three hello-world requests completed in 19 ms, 8 ms and 8 ms; scheduler reported preemptions=3; `spin.php` still completed its 4 s of work.
- `forever.php` (`while (true) {}`) was cancelled at the 5 s budget with HTTP 500.
- `fib(27)` recursive:
  - `php-cli` 8.5: about 9 ms.
  - `php-truffle` on GraalVM: about 11 ms after warm-up (first run 295 ms).
  - `php-truffle` on stock Temurin 21 without the Graal JIT: about 116 ms after warm-up.

See `docs/COMPARISON.md` for a side-by-side benchmark against php-fpm and Swoole (`bench/run.sh`).

## Testing

`mvn test` runs two test suites:

- `ConformanceTest`: 32 PHP scripts in `src/test/resources/php` whose expected `.out` files were produced by real php 8.5; all pass byte for byte.
- `SchedulerTest`: a busy loop and a sleeping request must not starve a hello-world request on a single slot; the busy loop must hit max execution time.

## Supported Subset

- `echo`/`print`
- Inline HTML and `<?= ?>`
- Alternative syntax (`if`/`endif`, `foreach`/`endforeach`, `while`/`endwhile`, `for`/`endfor`, `switch`/`endswitch`)
- `int`, `float`, `string`, `bool`, `null`, `array`
- All arithmetic, string, comparison, logical, bitwise and assignment operators including `??=`, `++`/`--`
- `if`/`elseif`/`else`, `while`, `do-while`, `for`, `foreach` with keys, `switch`, `break N`/`continue N`
- Functions with defaults and recursion
- `global`
- `isset`/`unset`/`empty`
- Casts
- Superglobals
- `header()`, `http_response_code()`

## Not Supported

- Classes and objects
- Closures
- References (`&`) are accepted syntactically, treated as by-value
- Static variables
- `include`/`require`
- Heredoc/nowdoc
- Exceptions/`try`-`catch`
- Generators
- Regex (`preg_*`)
- Sessions and cookies output
- Strings are Java Strings (UTF-16) rather than byte strings, so `strlen` is computed on UTF-8 bytes, while `substr` works on characters

## Project Layout

```
php-runtime/
├── bin/
│   └── php-truffle
├── pom.xml
├── scripts/
│   ├── demo.sh
│   └── get-graalvm.sh
├── src/
│   ├── main/
│   │   └── java/dev/phpruntime/
│   │       ├── PhpContext.java
│   │       ├── PhpFileDetector.java
│   │       ├── PhpLanguage.java
│   │       ├── builtins/
│   │       │   ├── ArrayFunctions.java
│   │       │   ├── Builtins.java
│   │       │   ├── CoreFunctions.java
│   │       │   ├── MathFunctions.java
│   │       │   ├── OutputFunctions.java
│   │       │   └── StringFunctions.java
│   │       ├── host/
│   │       │   ├── Main.java
│   │       │   ├── PhpEngine.java
│   │       │   └── PhpHttpServer.java
│   │       ├── nodes/
│   │       │   ├── ControlFlow.java
│   │       │   ├── PhpFunctionRootNode.java
│   │       │   ├── PhpMainRootNode.java
│   │       │   ├── PhpNode.java
│   │       │   ├── PhpRootNode.java
│   │       │   ├── call/
│   │       │   ├── expr/
│   │       │   ├── stmt/
│   │       │   └── var/
│   │       ├── parser/
│   │       │   ├── Lexer.java
│   │       │   ├── Parser.java
│   │       │   ├── PhpParseError.java
│   │       │   ├── Scope.java
│   │       │   └── Token.java
│   │       ├── runtime/
│   │       │   ├── GuestScheduler.java
│   │       │   ├── PhpArith.java
│   │       │   ├── PhpArray.java
│   │       │   ├── PhpError.java
│   │       │   ├── PhpFunction.java
│   │       │   ├── PhpNull.java
│   │       │   ├── PhpOps.java
│   │       │   └── PhpRequest.java
│   │       └── sched/
│   │           └── Scheduler.java
│   └── test/
│       ├── java/dev/phpruntime/
│       │   ├── ConformanceTest.java
│       │   └── SchedulerTest.java
│       └── resources/php/
└── www/
    ├── fib.php
    ├── forever.php
    ├── index.php
    ├── sleep.php
    └── spin.php
```
