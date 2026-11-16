# Bytecode DSL spike

This is a separate, incomplete PHP execution path, **not** a replacement for `php-truffle`.
All new Java code lives in `src/main/java/dev/phpruntime/bc/`; the production parser and
runtime are unchanged. It uses the existing lexer, PHP byte strings, `PhpArray` COW/cursors,
`PhpOps`, `PhpArith`, and `PhpNull`.

From this worktree's root:

```bash
mvn -q -B -DskipTests package
bench/bytecode/run-bc.sh bench/bytecode/01-wp-array-get.php
python3 bench/bytecode/measure.py --verify
python3 bench/bytecode/measure.py
python3 bench/bytecode/summarize.py
python3 bench/bytecode/profile.py
```

The launcher selects the installed `.graalvm/current` and accepts the same
`PHP_TRUFFLE_OPTS` environment variable as `bin/php-truffle`. For interpreter-only runs:

```bash
PHP_TRUFFLE_OPTS='-Dpolyglot.engine.AllowExperimentalOptions=true -Dpolyglot.engine.Compilation=false' \
  bench/bytecode/run-bc.sh bench/bytecode/02-small-functions.php
```

## Workloads and timing

Each file measures ten calls to `benchmark()` in one process, prints the monotonic-clock
nanoseconds and a checksum, and does no output inside the timed workload. Parsing, JVM
startup and function declaration are outside the timer. JVM/guest warm-up, compilation
interference and GC **inside** the timed call are included.

1. WordPress `_wp_array_get`: original function body, 600,000 calls, a fresh two/three-element
   path array for every call. Untimed checks also exercise null/missing/default paths.
2. Fifty different small function roots, direct static call sites executed round-robin,
   12,000 cycles = 600,000 calls. Three array reads and a branch per function.
3. 600,000 short-string building iterations with `strlen`; strings reset every 100
   iterations to avoid turning the test into an enormous quadratic-copy benchmark.
4. 1,000-element packed array, 1,000 by-value traversals summing values; also assigns keys.

`measure.py` runs AST, BC, PHP, AST, BC, PHP, AST, BC, PHP for each file/configuration, always
one benchmark process at a time. A process measurement is the median of rounds 7, 8, 9;
the report uses the median of three process measurements and their min/max spread. Every
round, stdout/stderr and pre-run `uptime` sample is retained in `results.json`. PHP always
uses `/usr/bin/php -d opcache.enable_cli=1 -d opcache.jit=disable`; the PHP baseline is the
same configuration in both Java-mode rows. `--verify` compares outputs after normalizing
only `ns=...`, and also checks the independent `check.php` subset exercise.

`profile.py` collects three interleaved default-configuration `TraceCompilation` runs per
path and a separate interpreter-only JFR for benchmark 2 (same workload, 150 outer rounds,
three-second delay to exclude startup). Traces, recording and full sample data stay under
`/tmp/opencode/bytecode-spike`; small summaries are checked in here. Profiling timings do
not enter the performance table.

## Supported subset and limitations

Constants (`long`, `double`, PHP byte string, bool, null), frame locals/assignment,
`+ - * < <= == === .`, short-circuit `&& || !`, `if/else`, `while`, `for`, `return`,
direct named user calls/default arguments/recursion, array literals/index reads/nested
index writes/appends, `isset`, by-value `foreach`, `break/continue` (one level), `echo`,
and the small requested builtin set. `is_int` and `hrtime(true)` are added for the unmodified
WordPress function and measurement. Numeric overflow falls back to double without changing
subsequent non-overflow integer results. Bytecode quickening and long/double/bool operand
stack/local boxing elimination are enabled; the initial cached interpreter is measured
(no uncached tier, superinstructions or benchmark-specific operations).

Not supported: references, closures, dynamic calls/variables, globals/includes, objects,
general PHP error/warning behaviour, interpolated strings, or most of PHP's grammar and
builtins (including `++/--`; use explicit addition/subtraction). Missing array/local reads
return `PhpNull` without production warnings. Only
array/null offset writes are valid. This deliberately avoids the migration's hard parts;
its numbers are a best-case subset experiment, not a predicted WordPress request speedup.

See `tasks/15-report.md` for the results and decision.
