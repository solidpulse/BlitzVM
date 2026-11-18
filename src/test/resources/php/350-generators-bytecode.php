<?php
// Generators on bytecode: traces, throw() into try/catch, by-reference parameters, $this and
// static::, closures with use (&), argument errors, yield as an expression, keys, yield from.
// (finally on a destroyed generator is not covered: the runtime has no destructors yet.)
function inner() { yield 1; throw new RuntimeException("boom"); }
function trace() { $e = new Exception("t"); echo str_replace(__DIR__, '', $e->getTraceAsString()), "\n"; yield debug_backtrace()[0]['function'] ?? 'none'; }
foreach (trace() as $v) echo "bt:$v\n";
try { foreach (inner() as $v) echo "v$v\n"; } catch (RuntimeException $e) { echo get_class($e), ':', $e->getMessage(), ':', $e->getLine(), "\n"; }
function catcher() { while (true) { try { $x = yield; echo "got $x\n"; } catch (LogicException $e) { echo "caught ", $e->getMessage(), "\n"; yield 'after'; } } }
$g = catcher(); $g->current(); $g->send('a'); echo $g->throw(new LogicException('L')), "\n"; $g->send('b');
function fin() { try { yield 1; yield 2; } finally { echo "cleanup\n"; } }
function byref(array &$a) { foreach ($a as $k => $v) { $a[$k] = $v * 2; yield $k; } }
$arr = [1, 2, 3]; foreach (byref($arr) as $k) {} echo implode(',', $arr), "\n";
class C { private $items = ['x' => 1, 'y' => 2]; function each() { foreach ($this->items as $k => $v) yield $k => static::class . $v; } }
class D extends C {}
foreach ((new D)->each() as $k => $v) echo "$k=$v\n";
$n = 10; $c = function () use (&$n) { while ($n < 13) yield $n++; };
foreach ($c() as $v) echo "c$v\n"; echo "n=$n\n";
function req($a, $b) { yield $a + $b; }
try { req(1); echo "no error\n"; } catch (ArgumentCountError $e) { echo "ACE\n"; }
function expr() { $x = (yield 1) + (yield 2); echo "sum $x\n"; return $x * 10; }
$g = expr(); $g->current(); $g->send(5); $g->send(7); echo $g->getReturn(), "\n";
function keys() { yield 'a' => 1; yield 2; yield 10 => 3; yield 4; yield from ['k' => 5, 6]; yield 7; }
foreach (keys() as $k => $v) echo "$k:$v ";
echo "\n";
function outer() { $r = yield from inner2(); echo "inner returned $r\n"; yield 99; }
function inner2() { $x = yield 1; echo "inner got $x\n"; return 'R'; }
$g = outer(); echo $g->current(), "\n"; $g->send('S'); echo $g->current(), "\n";
$g = fin(); foreach ($g as $v) {} try { $g->rewind(); echo "rewind ok\n"; } catch (Exception $e) { echo $e->getMessage(), "\n"; }
$g = (function () { yield 1; yield 2; })(); $g->next(); try { foreach ($g as $v) {} } catch (Exception $e) { echo $e->getMessage(), "\n"; }
