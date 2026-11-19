# Task 22 report: deoptimization loops on the WordPress front page

Branch `deopt-loops`, fix commit `a384edd` ("Keep bytecode locals boxed across type changes").
No benchmarks, load generators or concurrent request loops were run. WordPress warm-up was a
sequential `curl` loop against `http://localhost:18088/`, as the task allows.

## Causes

Two related ways a bytecode local could be read or written through the wrong typed accessor,
both making compiled code hit `FrameWithoutBoxing.expectLong/expectDouble` and deoptimize on
every execution:

1. **Local slot tags were changed after warm-up.** `PhpBytecodeRootNode.store` and the typed
   `WriteVar`/`StoreVar`/`StepVar`/`IncDecVar` paths wrote `Long`/`Double` with
   `acc.setLong`/`setDouble` even when `VarKind` already knew the variable had held a
   non-number. A local such as `$files = false; ... $files = glob(...)` or
   `$count = null; ++$count` first got an object slot and then a long/double slot, and the
   next call changed it back. Every change calls
   `setCachedLocalTagInternal` -> `reportReplace(this, this, "local tags updated")`, which
   invalidates the whole compiled function, so it was recompiled in a loop. Int/float mixed
   locals were handled by `VarKind`, but object/number mixes were not.

2. **Typed local accesses were attempted on locals that cannot hold that type.**
   `ReadVar.readLong/readDouble`, `StepVar.step` and `IncDecVar.incLong` called
   `acc.getLong`/`getDouble` based only on `!isCell()`. For a double local (for example
   `$below_midpoint_count`, from `round()`), a boxed int/float local, an object local or a
   cell (`global`/`static`) this asks the frame for the wrong tag. In compiled code
   `FrameWithoutBoxing.expectLong/expectDouble` calls
   `transferToInterpreterAndInvalidate()` *before* it throws the `UnexpectedResultException`
   that the operation catches, so the operation's interpreter fallback never gets a chance:
   the same instruction deoptimizes on every loop iteration. This is what kept
   `compute_spacing_sizes()` in a deopt/recompile cycle even after the slot-tag fix.

## Fix (`a384edd`)

- `VarKind` now tracks `noObjects`. Once a variable has held any non-number, its numbers are
  stored boxed, so its bytecode slot tag stays `OBJECT`. `longBoxed()`/`doubleBoxed()` also
  return true in that case, and `LocalVarNode`/`AppendVar` mark object writes (buffer append,
  plain object assignment).
- `VarKind.longSlot()`/`doubleSlot()` report whether a local has only ever held one numeric
  type. `ReadVar`, `StepVar` and `IncDecVar` use typed frame access only under those guards;
  all other cases use the generic path (`getObject`/arithmetic helpers).
- Object writes in `store`, `WriteVar`, `StoreVar`, `StepVar` and `IncDecVar` now use the
  boxed decision, including cell writes.
- Fixture `364-local-type-changes` covers `null -> int`, `int -> float`, `int/string`
  alternation and `++`/`.=` on such locals.

## Evidence: WordPress front page (950 sequential requests)

Before the fix (`wp-tag.log`, same 950 requests):

```
     16 parse_next_tag()
     16 compute_spacing_sizes()
      1 _wp_filter_build_unique_id()
      1 remove_keys_not_in_schema()
      1 _get_custom_object_labels()
      1 add_filter()
  total: 36 deopts
```

After the fix (`wp-final.log`):

```
      1 _wp_filter_build_unique_id()
      1 remove_keys_not_in_schema()
      1 _get_custom_object_labels()
      1 block_has_support()
      1 add_filter()
  total: 5 deopts
```

The remaining five are one-time bootstrap invalidations, not loop cycles.

Traces:

- `WP_HTML_Tag_Processor::parse_next_tag`: before, 16 `opt deopt ... Reason uncommon trap`
  with the transition at `c.StoreVar` bci 1700, source
  `class-wp-html-tag-processor.php:1796` (the loop whose `$at = strpos(...)` can be an int or
  `false`; `++$at` at line 1798). After: 0.
- `WP_Theme_JSON::compute_spacing_sizes`: before, 16 deopts at
  `c.ToBool$FromBoolean$unboxed` bci 2332, `class-wp-theme-json.php:5521`; after only the
  slot-tag part of the fix it still showed `c.ReadVar` bci 2400 at line 5525. The operative
  lines are `--$below_midpoint_count` (a double from `round()`, line 5501) and
  `++$x_small_count` (starts `null`, line 5526); `StepVar` was asking for a long slot on both.
  After the full fix: 0.
- The task's `register_core_block_style_handles()` (112) and `convert_smilies()` (36) cycles
  did not reproduce in our 950-request trace: both compiled once and were not invalidated.
  Their hot locals are the same kinds covered by the fix (`static $core_blocks_meta` is a
  cell, `$files` is `false`/array, `global $wp_smiliessearch` is a cell, `$output` becomes a
  `PhpStringBuffer`), and standalone runs of both functions with forced loop paths show 0
  deopts after the fix (`smilies-long.php`, `register2.php`). Their exact before counts could
  not be measured, see "Left unexplained".

## Evidence: standalone loop reproductions

Run under `bin/php-truffle` with `TraceCompilation=true` and
`TraceBytecodeTransition=transferToInterpreter`:

| Script | Before | After | Trace before |
|---|---|---|---|
| `oscB.php` (`$count = null;` then `++$count` inside a for loop, 300k calls) | 9 | 0 | `c.ReadVar$long`, loop line |
| `oscI.php` (same, `++$count` on a never-numeric-then-int local) | 15 | 0 | 14 invalidations at the loop instruction |
| `spacing2.php` (copy of `compute_spacing_sizes`, default spacing scale, 300k calls) | 4 (`+1` in `{main}` OSR) | 0 (`+1` in `{main}` OSR) | `c.ToBool$FromBoolean$unboxed`, line 53/57, the `++$x_small_count`/double-decrement loop |
| `smilies-long.php` (`convert_smilies` on a 30k-tag string, OSR) | 0 relevant | 0 | none |
| `register2.php` (`register_core_block_style_handles`, separate-assets filter forced) | 0 relevant | 0 | none |

The standalone scripts are only loop-shape checks, not benchmarks.

## Tests and WordPress

- `mvn -q -B test`: **375 tests, 0 failures, 0 errors, 2 skipped** (`MysqliConformanceTest`
  without a database). Run once after the completed fix; the earlier interim run also passed.
- WordPress after the fix: `/` returned `200` and a full page (69,230 bytes) with the server
  started as `bin/php-truffle serve --port 18088 --cpus 4 .`; the server was stopped afterwards.
- No call operations (`DoCall`, `CallCallee`, `ResolveFunction`, `ResolveMethod`,
  `DoMethodCall`, `SendValue`, `SendVar`, builtin call helpers) were edited.

## Left unexplained

- The exact 112/36 deopt counts for `register_core_block_style_handles()` and
  `convert_smilies()` from the task trace were not reproduced here. In our WordPress state both
  roots compiled once and stayed valid both before and after the fix, so their reported counts
  likely came from a run with different transient/cache content or a different theme state.
  The mechanisms fixed here (unstable local slot tags, typed access to object/cell/double
  locals) are present in both functions, but a trace of their failing state would be needed to
  attribute a specific line.
- `compute_spacing_sizes()` and `parse_next_tag()` were the two reproducible loop roots; both
  are clean in the final trace.
