# Task 21 report: delete the AST execution path

Baseline: `d37d331` (main). Branch `delete-ast`. No benchmarks, load tests, server or
WordPress runs were made, per the task rules. Only compilation and the test suite were run.

## Result

The AST is now only the parser's syntax tree. Every root is compiled and run by the Truffle
Bytecode DSL; an unsupported node is an error instead of an AST fallback. All tests pass.

`git diff --stat main..HEAD`:

```
Code only (`git diff --stat main..HEAD -- src`): 118 files changed, 228 insertions(+), 6551 deletions(-). Whole branch including this report: 119 files changed, 295 insertions(+), 6551 deletions(-).
```

Net: about 6,300 lines removed.

## Commits

| Commit | Message | Change |
|---|---|---|
| `4b78331` | Remove the bytecode mode switch and AST fallback | `-Dblitz.bytecode=off/auto/all`, `ALL`, `astExecuted`, all fallback returns; `Unsupported` now names the root and throws. Report prints the compiled count; dump still works. |
| `a277776` | Remove the AST generator lowering and resume path | `GeneratorLowering`, `GeneratorResumeRootNode`, `Resumable*`, `Memo*`, `asGenerator`'s resume target, `PhpGenerator.frame`/`resumeTarget`; `Parser` passes the generator body directly as `generatorSource`. |
| `8dd0aaf` | Remove the AST expression, function and main roots | `PhpExprRootNode`; `PhpFunctionRootNode.execute` and argument binding; `PhpMainRootNode.execute`, `runBody`, `initializeIncludedLocals`. The roots remain as descriptions for the emitter. |
| `7c1c444` | Remove the AST execute paths from expression nodes | `execute`/typed variants, DSL specializations and their profiles in `nodes/expr`; arithmetic nodes became plain syntax nodes (`new AddNode(...)` instead of `AddNodeGen.create(...)`). |
| `3d3909b` | Remove the AST execute paths from variable nodes | Assignment, reference, compound, inc/dec, append, isset/unset, index read and destructuring execution; typed local reads/writes used only by them. |
| `f14bc76` | Remove the AST execute paths from statement nodes | Blocks, loops (`LoopNode`/`RepeatingNode` machinery), if/switch/foreach, jumps, goto dispatch state machine, try/catch execution, declarations, echo/print/constants. |
| `a5ab5e4` | Remove the AST execute paths from call nodes | Call/method/static/value call execution, AST argument binding helpers, magic-call evaluation; `DispatchCallNode.evalMagicArgs`. |
| `39e2dc6` | Remove the remaining AST execution helpers and the spike package | `PhpNode` execute methods, `evalKeys`, `ClassRefNode.resolve`, `CondNode.executeCondition`, unused `ControlFlow` exceptions, `dev.phpruntime.bc`, and the goto frame-slot/scope leftovers. |

Every commit was compiled with `mvn -q -B -DskipTests compile` and followed by
`mvn -q -B test`; all passed.

## Test results

- Before step 1, the baseline also passed `mvn -q -B test -Dblitz.bytecode=all`.
- After step 1 the property no longer exists; the default is the only mode.
- Final full run: `Tests run: 374, Failures: 0, Errors: 0, Skipped: 2`
  (`MysqliConformanceTest` is skipped without a database); `BUILD SUCCESS`.
- No tests, fixtures or `.out` files were edited.

## Kept AST-looking code and why

- **Runtime helper nodes**: `IteratorNode`, `DispatchCallNode`, `ObjectMethodNode`,
  `SplBuiltinNode`, `ArrayAccessNode` and the `Spl*RootNode`/`Closure*RootNode`/
  `GeneratorMethodRootNode` roots are executed by operations or exposed as methods. They
  keep their `execute` methods.
- **Cached engines**: `ReadConstantNode.execute`, `IncludeNode.executePath`,
  `CompactNode.build`, `ExtractNode.prepare/run/extractPrepared` are called by bytecode
  operations on headless copies.
- **Place API**: `LValueNode` and the headless copies keep `read`, `write`,
  `obtainCell`, `bindCell`, `readIfSet`, `isSet`, `appendString` and `readValue`; bytecode
  place operations evaluate keys and call these.
- **Shared static semantics**: `CastNode.cast`, `UnaryNode.apply`, `ThrowNode.throwValue`,
  `MatchNode.unhandled`, `IncDecNode.incrementString`, `TraitClassNameNode.nameOf`,
  `FirstClassCallableNode.create*`, `ClassRefNode.resolveValue`/`resolveValueOrNull`/
  `resolveForInstanceof`, `CallArgHelper`'s raw-argument/binding helpers,
  `PhpFunctionRootNode.newSymbolTable`/`packVariadic`, and `TryCatchNode`'s catch
  matching/finally helpers are all still called from `PhpBytecodeRootNode`.
- **Root defaults**: `PhpRootNode.execute` throws; `PhpFunctionRootNode` and
  `PhpMainRootNode` are descriptions of functions/files and no longer executable roots.
- **`ControlFlow`**: only `Exit` remains (thrown by `exit`/`die` and unwound by the
  bytecode). `GeneratorCloseSignal` remains for closing bytecode generators, and
  `TryCatchNode.shouldRunFinallyFor` still consults it.
- **Profiles/`@ExplodeLoop`**: kept only where bytecode runtime code still uses the
  specialized loops (`TryCatchNode` catch matching and `IndexLValueNode` read paths).
