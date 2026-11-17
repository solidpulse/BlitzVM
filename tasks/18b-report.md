# Task 18b: native bytecode compilation and auxiliary engine cache

Worktree `/home/silva/Documents/java/php-runtime-native`, branch `native-image`.
Baseline native image from task 18: SHA-256
`4405c77477a921f80cd347a6092e0dadf764570baeae92809123a0f3c41ef790`.

Status: **step 0 is solved.** A checked native build now compiles PHP guest code:
`f()` shows `opt done`, the combined PHP workload compiles, all seven native
bytecode WordPress pages match the JVM bytecode bodies byte for byte, and all
246 native bytecode fixtures pass. Cache store/load and the performance schedule
have not started yet.

Working image (build 20): `target/blitzvm`, 117,704,968 bytes (112.25 MiB), SHA-256
`aec4c9644b511fb457a9c745d3a7c86b89d4cd7dda38b6a0afcbb088f410d50b`, built in
6 min 38 s native-image / 6 min 46 s wall, 4.83 GiB peak builder RSS, 3,295
runtime-compilable methods. Build 19 (`5b4b4d75…`, 83.00 MiB) produced the first
`opt done`; build 20 adds all runtime locales and drops the diagnostic `-H`
flags. Build 20 is the measurement/cache binary.

## Scope and initial findings

Only bytecode (`blitz.bytecode=auto`) and PHP-FPM are measured. The previous task
18 benchmark schedule was interrupted and its owned server/load generator
stopped when this task began. No other process or database was stopped or changed.
The same recovered disposable WordPress database described in `18-report.md`
is used; JDBC fixtures are not pointed at it.

Step 0 reproducer on the original image:

```bash
target/blitzvm -Dpolyglot.engine.TraceCompilation=true \
  -Dpolyglot.engine.TraceCompilationDetails=true \
  -Dpolyglot.engine.MultiTier=false \
  -Dpolyglot.engine.SingleTierCompilationThreshold=10 \
  -Dpolyglot.engine.BackgroundCompilation=false \
  -Dpolyglot.engine.AllowExperimentalOptions=true \
  -Dpolyglot.log.file=target/18b/no-graphs.trace \
  -r 'function f($x) { return $x+1; } for($i=0;$i<100;$i=$i+1) f($i); echo "done\n";'
```

This prints `opt queued`, `opt start`, then `opt failed`: the runtime is not a
fallback interpreter. Compilation fails in `OptimizedCallTarget.executeRootNode`
with `FrameWithoutBoxing should not be materialized (must not pass virtual object
into an invoke that cannot be inlined)`. A low-threshold run of the small-functions
kernel similarly fails each function in a few milliseconds. No successful
compilation occurs. The original build's 1,838 runtime-compilable methods do not
by themselves prove that PHP roots or operations are included.

### Step 0 result (2026-11-17, build 19)

The same reproducer on the new binary prints `done`, and its trace contains
`opt queued`, `opt start`, `opt done` for `f()` with **0 failures**
(`target/18b/fix-f.trace`). A PHP workload combining named and spread arguments,
object `foreach` by value and by reference, generator delegation, resumable
`foreach`, array unpacking and string concatenation produces the expected
`610:xab:a-b` and also compiles (`target/18b/compiled-correctness/`).
`PhpBytecodeRootNodeGen.execute`, `CachedBytecodeNode.continueAt`, bytecode
`Enter.enter` and `PhpFunctionRootNode.execute` are all listed as
runtime-compilable in the final build log.

Correctness on this binary (`target/18b/validate-native.log`):

| Check | Result |
|---|---|
| Seven WordPress pages, native bytecode vs JVM bytecode | byte-identical, correct statuses |
| Native bytecode fixtures | 246/246 |
| Trivial `f()` compilation | `opt done`, 0 failed |
| Combined PHP workload compilation | `opt done`, correct output |
| WordPress guest compilation (45 s, 4 conns) | 744 queued, 99 done, **0 failed** |
| WordPress pages during compilation | all seven correct |

So the step-0 diagnosis was right in kind but not in the detail that mattered:
the runtime was never a fallback interpreter, options and module paths were fine,
and the gap was **missing or unusable runtime-compilation graphs**, exposed one
host-library/error-formatting path at a time. The precise chain and every change
are recorded below and in `bin/build-native.sh`.

WordPress itself now compiles: during 45 s of front-page load at four connections
it queued 744 roots, completed 99 and failed 0, while serving all seven pages
correctly (`target/18b/wp-compile.log`, `target/18b/wp-compile/summary.json`).
The run happened at load average 6.7-8.2 on the shared machine, and compilation
continues in the background, so its 5 req/s / 857 ms p50 is **not** a performance
result. No performance or cache number in this report predates these gates.

The supplied Google-agent diagnosis was checked against the native builder's
error-report module-path listing. Native-image already upgrades the original
Truffle/polyglot jars to named modules; they are unshaded. System properties are
also honored (low-threshold tracing queues `f()`), and the compiler is reached.
Thus neither missing module exports nor ignored options explains this failure.
The working hypothesis was absent interpreter graphs due to default runtime
class initialization. An experimental build initializing node/bytecode classes
progressed into parsing `GeneratorResumeRootNode.execute%%R` (a runtime-compilation
graph), but exposed an SVM graph-encoder null-constant error. Its build failed
after 100 s (109.86 s including Maven), 1.44 GiB peak RSS. That hypothesis then
held: the remaining work was graph-safety, and build 19 finally produced a real
`opt done` (see the result section above).

Initializing the immutable PHP null/array/class sentinels moved that null-constant
failure to an enum constant in compound assignment. Explicitly initializing the
four runtime enum types removed it. Disabling class-initializer simulation was
also tested but failed inside SVM's own constant-origin registration; that flag
is **not** retained.

The resulting build reached real PHP graphs and found 139 blocklist violations:
136 fan out from generic `Object.equals` in dynamic-variable `$this` detection;
the other three come from callable-name `String.indexOf`, generator array-entry
`Map.Entry.getKey`, and string-append `String.concat`. Four small boundary helpers
were added around exactly those host-library operations. No argument evaluation,
PHP conversion, reference handling or value semantics changed. These are build
blockers exposed by enabling runtime compilation, not AST performance tuning.
The `blitz.bytecode=auto` mixed mode still contains unsupported AST roots, so
their graphs must also be safe even though AST-only benchmarks are excluded.

After those four boundaries, a new build found 123 violations, now dominated by
generator entry access (118), plus four other library accesses and one remaining
VM path. A diagnostic build temporarily disabled `TruffleCheckBlockListMethods`,
but failed with 148 violations and five `neverPartOfCompilation` paths. Most
of that fan-out was from generic generator-entry access into unrelated Netty/JDK
implementations. Disabling the check is **not** retained. All diagnostic build
logs are retained under `target/18b/build-graphs-*.log`.

The next checked build (7) removed those reported AST/helper paths and reached
the generated bytecode interpreter: `PhpBytecodeRootNodeGen.execute` →
`CachedBytecodeNode.continueAt` → foreach handlers. Its 131 violations were 129
from bytecode object-foreach entry values, one from the invalid-foreach warning
concatenation, and one from the generic lvalue append fallback. The same narrow
boundary treatment was applied: array traversal and PHP iterator calls remain
outside those boundaries; only object-property snapshot entry access and host
warning formatting/string concatenation move behind them.

### Whole-language initialization experiment

Following the maintainer's more specific hypothesis, build 8 replaces the growing
initialization allowlist with `--initialize-at-build-time=dev.phpruntime`, including
generated nested classes. A source audit identified these runtime carve-outs:

Final runtime carve-outs (build 21; narrowed after the cache experiments):

| Runtime carve-out | Reason |
|---|---|
| `bytecode.BytecodeEmitter` | Startup mode/report/dump properties, counters, report shutdown hook. |
| `runtime.PhpStrings` | Startup `blitz.checkBytes` property. |
| `host`, `sched` packages | Host/server/scheduler lifecycle. |
| `runtime.PhpRequest` | Current-request thread-local. |
| `timelib.ZoneinfoDb` | `TZDIR` and runtime zoneinfo filesystem snapshot/cache. |
| `timelib.ParseDate` | Startup `blitz.date.regexScanner` property. |

`PhpLanguage`, `builtins.Builtins`, `builtins.PregFunctions`, `builtins.HashFunctions`
and `builtins.Bcrypt` were initially runtime-initialized, but that **blocks cache
persistence**: the auxiliary image refuses to persist any object whose class is
runtime-initialized, and the engine root contains the language instance and
registered `Builtins.Def` objects. They are now build-time initialized:
`PhpLanguage` reads `blitz.include.revalidateMs` when the language instance is
constructed, and `HashFunctions`/`Bcrypt` create their `SecureRandom` lazily on
first use instead of freezing a seed at image build. The regex cache and builtin
table are empty/immutable-at-start image-heap objects, copied per process.

This changes initialization policy, not runtime settings or PHP behavior. Both
blocklist and `neverPartOfCompilation` checks stay enabled. Build success alone
does not establish the hypothesis; `f()` and WordPress `opt done` are still required.

Build 8 passed the initialization audit without image-heap/thread/entropy errors,
then failed on 129 compilation-blocklist paths. Of these, 120 share one cause:
`PhpConstants.compileTime()` directly accesses the immutable `Map.ofEntries`
table, whose generic key equality/hash paths fan out into unrelated JDK classes.
That lookup now has a boundary. The nine remaining paths are named-argument
validation when `new` has no constructor, object-foreach snapshot entry access,
ArrayAccess indirect-modification notice formatting, the property magic guard
map lookup, by-reference argument warning formatting, and symbol-table collection
allocation. Boundaries isolate exactly these operations; node/argument evaluation,
loop bodies, reference adaptation and frame access remain outside. No generated
class is edited. Build 9 keeps the whole-language policy and both safety checks.

Build 9 removed the preceding paths. Its 157 reported violations now mostly
fan out from the last generic generator snapshot access,
`ResumableForeachNode.execute()` (153 value and one key access; the two generated
instruction-descriptor diagnostics are downstream of the same value access).
It also reports one `String.valueOf(Object)` path through function default
evaluation with no deeper application source trace. Build 10 isolates the
resumable snapshot entry key/value accesses, leaving frame/iterator/body work
outside. The unresolved `String.valueOf` path will be reassessed after that
large fan-out is removed instead of guessing at an unrelated code change.

Build 10's 138 traces were disconnected JDK cycles rather than useful PHP root
traces. A diagnostic build (11) retained node source positions and temporarily
disabled only blocklist failure so the subsequent `neverPartOfCompilation`
diagnostics could run; it still failed. Those diagnostics identified the actual
source: object-foreach **by-reference** entry access, separate from the by-value
path already isolated (144 downstream blocklist paths plus a key-access path).
They also exposed native bytecode's direct string/string concatenation (one
path). Reuse the entry helpers for by-reference foreach and mark only that
concatenation specialization as a boundary, matching the existing AST string
concatenation policy. Build 12 restores all checks, with source positions retained
for debugging. No disabled-check executable has been produced or measured.

### Follow-up: concrete snapshot entries

The maintainer identified the structural cause of the recurring snapshot errors:
consumers call `Map.Entry.getKey/getValue` on `List<Map.Entry<...>>`, so native
image's conservative type analysis follows every reachable JDK/Netty entry
implementation. A small final, language-owned entry type (for example
`PropEntry<T>(Object key, T value)`) would make those accessors direct and remove
that entire fan-out category without a boundary per access. It would preserve
snapshot order and the exact existing value/reference-cell identities.

For task 18b the boundaries are retained to limit scope. `PhpClass.visibleEntries`
and `visibleRefEntries`, plus array snapshots used by generators, also feed JSON
encoding, SPL storage/helpers, class/output builtins, extract and argument packing.
Changing their return types requires a coordinated API/consumer change and
separate correctness validation. The concrete-entry refactor is a follow-up,
not silently included as a performance optimization here.

Build 12 retained 3,342 runtime-compilable methods (including PHP bytecode roots
and handlers) and reduced the checker failure to five paths: the prototype
`php-bc` language's concat, JS plain-object map allocation, one generic
`String.valueOf` through function defaults, and two PHP warning concatenations.
Build 13 adds boundaries only around the first allocation/concat and the two
warning formatters. The prototype/JS fixes are needed because the same executable
includes these languages and whole-language initialization exposes their graphs;
they are not benchmarked or tuned. The generic value-format path remains under
investigation rather than wrapping PHP function/default evaluation in a boundary.

Inspecting actual application class bytecode (`javap -p -c`) narrowed the remaining
`String.valueOf(Object)` candidates: `DynamicVarNode.execute()` concatenates the
generic `keys[0]` into its undefined-variable warning. The function-default trace
was an inlined call site, not the method containing that concat. Move only the
warning into a boundary; frame/key evaluation and the actual variable read stay
inlineable. This source change was made after build 13 started and therefore
requires the next build before validation.

Build 13 still fails on four paths (3,340 runtime-compilable methods retained):
the aforementioned generic warning value-formatting, named-argument map
allocation, invalid-foreach warning formatting, and generic undefined-lvalue
warning formatting. Build 14 includes the pending dynamic-variable warning fix,
a boundary factory for the five named-argument `LinkedHashMap` allocations,
and boundaries around the two remaining warning formatters. Argument evaluation
and frame/reference work remain outside; both compiler checks stay enabled.

Build 14 removed those four paths and reached two remaining error-formatting
paths: invalid IteratorAggregate return and undefined destructuring source. Build
15 puts only those existing error/warning helpers behind boundaries. No PHP
iterator dispatch, destructuring assignment or reference evaluation is moved.
Build 14 took 3 min 13 s in native-image / 5 min including Maven on this shared
machine (3.38 GiB peak builder RSS), longer than preceding attempts.

Build 15 removed the two preceding paths but exposed the generic lvalue's
unsupported-reference error plus one inlined generator warning. Build 16 isolates
the generic reference-error construction (without sending its frame into the
boundary) and the invalid-subject warning in resumable foreach. These are more
error-path graph blockers; `opt done` is still unproven and no cache benchmark has
started. Build 15 took 3 min 22 s in native-image / 5 min 28 s including Maven,
3.26 GiB peak builder RSS.

Build 16 removed the PHP paths and failed on one remaining JS non-callable
fallback's message concatenation. Build 17 marks only that fallback as a
boundary, so no caller frame or argument evaluation crosses it. Build 16 retained
3,326 runtime-compilable methods and took 3 min 43 s native-image / 5 min 42 s
including Maven, 3.25 GiB peak builder RSS.

Build 17 passed the prior JS fallback path, but found parse-error source-location
queries reaching `Source.getTextMap` and its unsupported-source error formatting
through generic polyglot interop. Build 18 isolates both PHP and JS parse-error
`hasSourceLocation`/`getSourceLocation` exports. These are host diagnostic queries,
not execution paths; exception kind/message/source line remain unchanged. Build
17 retained 3,324 runtime-compilable methods and took 3 min 44 s native-image /
5 min 51 s with Maven, 3.30 GiB peak builder RSS.

**Build 18 passed both compiler safety checks**, retained 3,296 runtime graphs,
and progressed through parsing/inlining into native machine-code compilation.
It then failed on an SVM invariant: `PhpLanguage.globalName%%D(int)` (a deoptimization
target) was scheduled for compilation without a parsed graph. This is no longer
a blocklist violation. The builder's generic `EnableFallbackCompilation` advice
cannot fix a missing graph: exact-source `CompileQueue.defaultCompileFunction`
checks graph presence before choosing optimization suites; the fallback repeats
the same check. No fallback flag is added.

The only guest-inlineable caller of `globalName` is `PhpContext.exposeAutoGlobalById`,
which already calls the boundary `exposeAutoGlobal` to update `_ENV`/`_REQUEST`
iteration order. The other three callers are existing boundaries. Build 19 moves
the by-ID wrapper behind that same boundary so name lookup/order bookkeeping stay
together and no `globalName` deopt target is needed from the guest path. Dense
global read/write operations remain unchanged and inlineable. Build 18 took
8 min 2 s native-image / 10 min 7 s including Maven, 3.54 GiB peak builder RSS.

**Build 19 succeeded.** Both compiler safety checks pass with 3,295
runtime-compilable methods; `f()` and the combined workload show `opt done`, and
pages/fixtures are exact. This is the image used by every result above.

### Locale data (open native-image configuration gap)

WordPress under `-Dpolyglot.engine.TraceCompilation=true` aborts during startup,
and the failure is **not** PHP: `TraceCompilationListener.onCompilationInvalidated`
formats a count through `java.util.Formatter`, which asks for
`sun.text.resources.cldr.ext.FormatData_en_IE`. The image only contains resource
bundles for the builder's default locale, so SVM raises
`Resource bundle lookup must be loaded during native image generation` and the
process aborts. The same path is reached by `CompilationFailureAction=Print`,
which is why the earlier WordPress diagnostic showed `opt fail` followed by an
`UnsupportedFeatureError` instead of a compilation reason.

The host locale is a run-time property of the deployed machine, so the fix is
image configuration, not a hard-coded locale: add `-H:+IncludeAllLocales` to
`bin/build-native.sh`. This affects host diagnostic/locale-dependent formatting
only; PHP byte-string and conversion rules do not change. The next build also
drops the two diagnostic `-H` flags so the measurement binary has the same
optimization/reporting shape as a production build.

### Validation gates (status)

Completed: low-threshold `f()` reproducer, combined PHP workload, all seven
WordPress **bytecode** page bodies/statuses, 246/246 native bytecode fixtures.

Pending: WordPress `opt done` evidence (blocked by the locale gap above), full
Maven suite on the final source, then cache store/load correctness and all
performance runs. Compiler tracing/low thresholds will not be used for
default-setting measurements.

The scratch bytecode-only validation driver is `/tmp/opencode/18b-validate-native.py`:
it records binary identity, checks `f()`/the combined workload with synchronous
low-threshold traces, compares all seven native/JVM **bytecode** bodies/statuses,
then runs all native bytecode fixtures. It does not invoke AST-only benchmarking
or destructive JDBC fixtures. The separate WordPress diagnostic still provides
the mandatory WordPress `opt done` gate.

### Source-change inventory (validated by build 19)

All changes below address native runtime-graph/build blockers. No generated Java
source, fixture output, PHP conversion rule, snapshot ordering, visibility rule or
call/assignment evaluation order is changed. Boundary methods do not accept a
`VirtualFrame`; existing frame reads/writes and argument-node evaluation remain
on the inlineable side.

| File(s) under `src/main/java/dev/phpruntime/` | Change |
|---|---|
| `nodes/call/CallValueNode.java` | Isolate static-callable-name `indexOf`. |
| `nodes/call/CallArgHelper.java` | Isolate named-map emptiness and allocation. |
| `nodes/expr/NewNode.java` | Isolate discarded named-argument validation, after evaluation. |
| `nodes/expr/ArrayLiteralNode.java` | Isolate array snapshot unpack loop; PHP Traversable protocol stays outside. |
| `nodes/generator/YieldFromNode.java`, `ResumableForeachNode.java` | Isolate snapshot entry key/value accesses; invalid-subject warning formatting. |
| `nodes/stmt/ForeachNode.java` | Isolate object snapshot key/value access in both modes and warning formatting. |
| `nodes/stmt/IteratorNode.java` | Isolate invalid IteratorAggregate error construction. |
| `nodes/var/DynamicVarNode.java` | Isolate generic `$this` key equality and undefined-variable formatting. |
| `nodes/var/StringAppendNode.java`, `IndexLValueNode.java`, `LValueNode.java` | Isolate host string concat and generic lvalue undefined/reference error formatting. |
| `nodes/var/DestructureLValueNode.java` | Isolate undefined-source warning. |
| `nodes/var/ArrayAccessNode.java` | Isolate indirect-modification notice formatting. |
| `nodes/var/PropertyLValueNode.java` | Isolate magic guard map lookup and non-object warning formatting. |
| `runtime/PhpCallable.java` | Isolate by-reference argument warning formatting, not argument adaptation. |
| `runtime/PhpConstants.java` | Isolate immutable built-in constant map lookup. |
| `runtime/PhpSymbolTable.java` | Isolate invocation-owned collection allocation in constructor. |
| `PhpContext.java` | Keep auto-global name lookup and iteration-order bookkeeping together behind the existing host boundary. |
| `bytecode/PhpBytecodeRootNode.java` | Isolate object-foreach snapshot key/value, invalid-foreach formatting and string concat specialization. |
| `bc/PhpBytecodeRootNode.java` | Isolate prototype language concat specializations. |
| `js/runtime/JsValues.java`, `js/nodes/JsCalls.java` | Isolate plain-object collection allocation and non-callable error fallback. |
| `parser/PhpParseError.java`, `js/parser/JsParseError.java` | Isolate parse-error source-location exports. |

`bin/build-native.sh` contains the initialization policy/cache switch, the
`-H:+IncludeAllLocales` runtime-locale fix, and no diagnostic `-H` flags by default.
The only
additional `host/Main.java` change is a usage line for the already committed
`--engine-options` diagnostic. Existing shared-engine close/store lifecycle is
unchanged. This inventory will be updated after correctness and guest-compilation
validation, and will distinguish any rejected changes from retained fixes.

Build 18's exact final graph listing confirms `PhpBytecodeRootNodeGen.execute`,
`CachedBytecodeNode.continueAt`, bytecode `Enter.enter`, and
`PhpFunctionRootNode.execute` are retained. The failure happened after the two
safety checks passed, so those checks are no longer the current blocker.

## Cache reference

Official documentation read:
[Auxiliary Engine Caching](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/AuxiliaryEngineCaching/)
and [Truffle AOT Overview](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/AOTOverview/).
The installed GraalVM class files are the authority for this version; its
`AuxiliaryEngineCacheSupport` options and store-on-close implementation were
inspected without changing any installed file. Cache images are tied to the exact
native binary, not portable across rebuilds. They contain code and profiles, not
intended request globals, but profiled literals may contain runtime strings.

Build switch added: `BLITZ_NATIVE_ENGINE_CACHE=0|1 bin/build-native.sh` (default
0). Enabled mode passes `-H:+AuxiliaryEngineCache` and reserves 1 GiB virtual
address space, not committed physical RAM. `Main --engine-options` lists the
actual engine option descriptors, defaults, experimental status, implementation
and compilation capability. No PHP semantics or dependencies change.

### Options in the installed 25.5 runtime

All ten cache options are stable descriptors (the build's auxiliary-cache flag
is separate). The native `--engine-options` output is retained with final results.

| Option | Default | Meaning |
|---|---|---|
| `engine.Cache` | empty | Load and store the same path. |
| `engine.CacheLoad` | empty | Load the stored shared engine at creation. |
| `engine.CacheStore` | empty | Automatically store the shared engine on close. |
| `engine.CacheStoreEnabled` | false | Prepare for explicit `Engine.storeCache(Path)`. |
| `engine.CacheStoreMaxImageSize` | -1 | Maximum file size; negative means unlimited, 0 skips storage. |
| `engine.CacheCompile` | hot | Compilation policy at storage; see below. |
| `engine.CacheCompileUseLastTier` | true | Force last-tier rather than first-tier compilations. |
| `engine.CachePreinitializeContext` | true | Preinitialize a fresh context for languages that support it. |
| `engine.CacheUpdate` | always | Decide whether to rewrite a loaded cache. |
| `engine.TraceCache` | false | Trace preparation, persistence, writing and loading. |

Compile policies: `none` invalidates existing compiled code and stores none;
`compiled` keeps completed code without forcing work; `hot` finishes queued
compilations; `aot` also compiles roots supporting `prepareForAOT`; `executed`
forces all executed and AOT-capable roots. Update policies: `always`, `newcode`,
`newsource`, `newroot`, `never`. These control storage, not compilation thresholds.

The existing `PhpEngine.close()` calls `Engine.close()`; the installed cache
support's `onEngineClosing` performs the store. No explicit persistence API is
needed unless testing proves shutdown ordering prevents that path. A cache
failure during shutdown is a failure, not a successful empty cache.

State to verify when cache loading becomes reachable (not preemptively changed):
`PhpLanguage` keeps engine-local source/include/ID maps and templates;
`ResolvedInclude.checkedAt` uses the training process's `nanoTime` origin;
`PhpClassTemplate.linked` retains the last linked class and parent/interface/trait
identity tuple. Request globals/cells/output/error handlers belong to new
`PhpContext` instances. Loaded-cache correctness must test these actual paths
before deciding whether lifecycle cleanup/patching is needed. Their presence
alone does not establish a cache bug.

## Measurement protocol (as run)

Binary: the cache-capable build (build 22), 118,425,864 bytes, SHA-256
`1e04d791…`, 3,295 runtime-compilable methods, `-H:+AddAllCharsets`,
`-H:+IncludeAllLocales`, and `-H:+AuxiliaryEngineCache`
(`target/18b/build-cache4.log`). Cache: the 430.86 MiB `hot` image above. All
servers use four PHP/CPU slots and the recovered WordPress database; FPM uses
four static children with opcache on and JIT off. Native runs are bytecode-only
(`-Dblitz.bytecode=auto`). `--gc` adds `-XX:+PrintGC`; heap sizing is the native
default (no `-Xms/-Xmx`). Warm-up curves are 18 consecutive 10 s windows at four
connections; steady state is task 18's protocol (90 s `/` + 30 s boot warm-up,
then 15 s phases at one connection for boot/front p50 and four for throughput).
Readings alternate uncached → loaded → FPM within each repeat. Machine load is
recorded at every window/phase.

## Harness

`bench/engine-cache.py` adds bytecode-only diagnostics, store, loaded/uncached
correctness, steady-state A/B against FPM, and 18 consecutive 10-second warm-up
windows. `bench/LoadGen.java` has a `curve` mode reusing its existing closed-loop
measurement method within one Java process, avoiding launcher time between
samples. Its existing `load`, `probe` and `once` modes are unchanged. Each
sample includes `/proc/loadavg`; every server/load phase also records `uptime`.
Cached and uncached modes are interleaved three times, using the same binary.
Load-generator sockets are now explicitly closed at each window boundary and
after failed requests: `curve` reuses one process, so leaving completed workers'
connections to GC would retain idle connections across windows. This changes
resource cleanup, not request/status/latency accounting. Sample epoch timestamps
locate the windows relative to server launch including Java source-launch time.
Cache loading must produce the runtime's successful patch message, including
for fixture subprocesses; a missing cache/fallback does not count as correctness.

## Reproducing step 0

```bash
# Checked build (no diagnostic -H flags; includes all runtime locales).
bin/build-native.sh

# Decisive guest-compilation check: expect opt queued/start/done, zero failed.
target/blitzvm -Dblitz.bytecode=auto \
  -Dpolyglot.engine.AllowExperimentalOptions=true \
  -Dpolyglot.engine.TraceCompilation=true \
  -Dpolyglot.engine.TraceCompilationDetails=true \
  -Dpolyglot.engine.MultiTier=false \
  -Dpolyglot.engine.SingleTierCompilationThreshold=10 \
  -Dpolyglot.engine.BackgroundCompilation=false \
  -Dpolyglot.log.file=target/18b/fix-f.trace \
  -r 'function f($x) { return $x+1; } for($i=0;$i<100;$i=$i+1) f($i); echo "done\n";'
```

`/tmp/opencode/18b-validate-native.py` (scratch, not committed) additionally checks
the combined workload, the seven native-vs-JVM **bytecode** pages and 246 native
bytecode fixtures; `target/18b/validate-native.log` is its output for build 19.
Build logs and traces from every failed attempt are kept under `target/18b/`;
`build-graphs-19.log` is the successful build and `fix-f.trace` its compilation
trace.

## Host test suite

`mvn -q -B test` passes on the final source: **344 tests, 0 failures, 0 errors,
2 skipped** (the two database-gated tests; JDBC fixtures are not pointed at the
WordPress database). `ConformanceTest` alone is 246/246. Output:
`target/18b/maven-test.log`.

### Cache persistence findings

First store attempt (cache-enabled build 20, `hot` policy): training and 120 s of
front-page load succeeded, 558 roots were force-compiled in 92 s, and then
persistence failed:

```
[engine] [cache] Failed to persist engine to buffer.
java.lang.UnsupportedOperationException: Objects of classes which are initialized
at runtime currently cannot be persisted: class dev.phpruntime.PhpLanguage
```

This is a build-configuration issue, not a PHP/runtime-cache bug. The engine root
contains the language instance and registered builtin definitions, so their
classes must be image-build-time initialized. Build 21 narrows the runtime carve-out
list accordingly (see the table above). Remaining runtime-initialized classes are
static utilities/host lifecycle types with no persisted instances.

Status: **store/load works** on the cache-capable build. A CLI smoke test wrote a
323,588-byte image, a fresh process loaded it, the runtime reported
`Engine from image successfully patched with new options.`, and both processes
printed the same result (`target/18b/cache-smoke/`). The full WordPress training
store is next; nothing is claimed beyond the smoke test until its pages and all
246 fixtures pass loaded.

### Store and loaded correctness (hot policy)

Training: all seven pages, then 120 s of `/` at four connections. Store metrics
(`target/18b/cache-exp2/store/`):

| Metric | Value |
|---|---:|
| Sources / function roots in image | 523 / 7,727 |
| Roots force-compiled (`hot`) | 1,971 |
| Engine preparation (forced compiles) | 316,834 ms |
| Persistence (serialize) | 12,904 ms |
| Write to disk | 407 ms |
| Cache file size | 451,785,032 bytes (430.86 MiB) |
| SHA-256 | `84543faf…` |

Loaded correctness (`target/18b/cache-exp2/check/`): a fresh server loaded the
image in **61 ms**, reported a successful option patch, and produced all seven
pages byte-identically to the uncached server. Fixtures: **246/246 uncached and
246/246 loaded**. Loaded startup to first response was 327 ms versus 1,566 ms
uncached on the same binary (single cold comparison, not a benchmark result).

This answers the large correctness question: cache loading does not corrupt
request-visible results in WordPress or the fixture suite.

Operational note: a build only has cache support if `BLITZ_NATIVE_ENGINE_CACHE=1`
reaches `bin/build-native.sh`. The script now echoes
`native build: engine-cache=1 flags=-H:+AuxiliaryEngineCache ...` as its first log
line, because one earlier build was produced with the default (disabled) value and
correctly reported that it could not store a cache.

## Warm-up and steady state

Scope note (maintainer decision, end of session): the steady-state schedule and
`CacheCompile` policy variants were stopped after the three warm-up repeats. The
report therefore contains the warm-up curves, cache metrics and loaded correctness,
not a steady-state table, a tuned-flag set, or extra php-fpm runs.

### Warm-up curve (median of repeats)

Front-page throughput and p50 at four connections, one 10 s window per row,
starting at the offset shown from server launch. `uncached` and `loaded` are
medians of three repeats, php-fpm of two (the third was stopped during wrap-up).

| Window start | uncached req/s / p50 ms | loaded req/s / p50 ms | php-fpm req/s / p50 ms |
|---:|---:|---:|---:|
| 3 s | 6 / 592 | 10 / 394 | 60 / 62 |
| 13 s | 6 / 600 | 10 / 408 | 64 / 62 |
| 24 s | 7 / 557 | 10 / 400 | 64 / 62 |
| 34 s | 7 / 548 | 10 / 396 | 63 / 63 |
| 44 s | 6 / 599 | 10 / 397 | 64 / 62 |
| 55 s | 7 / 532 | 10 / 406 | 64 / 62 |
| 65 s | 7 / 565 | 9 / 427 | 64 / 62 |
| 75 s | 7 / 556 | 10 / 410 | 64 / 62 |
| 86 s | 7 / 539 | 10 / 413 | 63 / 63 |
| 96 s | 8 / 507 | 9 / 430 | 63 / 63 |
| 106 s | 8 / 482 | 9 / 450 | 64 / 63 |
| 117 s | 8 / 507 | 9 / 429 | 64 / 63 |
| 127 s | 7 / 542 | 9 / 432 | 63 / 63 |
| 137 s | 7 / 497 | 9 / 431 | 64 / 62 |
| 148 s | 7 / 483 | 9 / 441 | 60 / 64 |
| 158 s | 7 / 498 | 9 / 431 | 62 / 63 |
| 168 s | 8 / 464 | 9 / 451 | 64 / 62 |
| 179 s | 8 / 465 | 9 / 448 | 64 / 62 |

| Configuration | Runs | Startup to first response, median | RSS after curve, median |
|---|---:|---:|---:|
| uncached | 3 | 1.56 s | 593 MiB |
| loaded | 3 | 0.35 s | 951 MiB |
| fpm | 2 | 0.76 s | 227 MiB |

Machine load (1-min, median across windows): uncached 5.70, loaded 5.54,
php-fpm 5.32; ranges 2.43-8.47, 4.37-7.32 and 4.62-6.19 respectively.


### Result

The loaded cache removes the warm-up slope. From the first 10 s window it runs
flat at **9-10 req/s with p50 ~395-450 ms**, whereas the uncached server climbs
from 6 to 8 req/s and its p50 falls from ~600 to ~465 ms over three minutes.
Startup to the first successful response is **0.35 s loaded versus 1.56 s
uncached**. The cache image is 430.86 MiB, takes 317 s to prepare (force-compiling
1,971 roots), 12.9 s to serialize, 0.4 s to write, and **61 ms to load**.

The plateau, however, is unchanged in absolute terms: native bytecode reaches
about **9-10 req/s**, roughly half the ~18 req/s JVM-bytecode figure from task 18,
and about one-sixth of **PHP-FPM at ~63 req/s / 62-64 ms p50** in the same windows.
So the cache solves the warm-up problem it was meant to solve, but not the
steady-state gap.

Serial GC is a first-order limiter. All runs used default heap sizing; parsing
`PrintGC` from these curve runs, pauses consume a median **39.4% of server wall
time uncached and 38.2% loaded** (the earlier task-18 observation was about 29%).
No `-Xms`/`-Xmx`, collector change, tuned flags, steady-state schedule, or
`CacheCompile` variants were run under the wrap-up decision.

Correctness gate passed: the loaded server printed
`Engine from image successfully patched with new options.`, produced all seven
pages byte-identically to the uncached server, and passed 246/246 fixtures both
uncached and loaded.

**Answer: yes, a loaded cache removes the warm-up, safely; it does not make native
bytecode competitive with php-fpm, and with the default heap the Serial GC alone
consumes well over a third of the time.**
