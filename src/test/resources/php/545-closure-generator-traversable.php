<?php
function gen(): Generator { yield 1; yield 2; }
function takeGen(Generator $g): Generator { return $g; }
function takeIt(Iterator $i): Iterator { return $i; }
function takeTr(Traversable $t): Traversable { return $t; }
function takeClosure(Closure $c): Closure { return $c; }
function takeCallable(callable $c): callable { return $c; }

$g = takeGen(gen());
var_dump($g instanceof Generator, $g instanceof Iterator, $g instanceof Traversable);
var_dump(takeTr($g) === $g);

$it = takeIt(new ArrayIterator([1, 2]));
var_dump($it instanceof Iterator, $it instanceof Traversable);
var_dump($it instanceof Countable, $it instanceof ArrayAccess);
var_dump(takeTr($it) === $it);

$closure = takeClosure(function () { return 1; });
var_dump($closure instanceof Closure, is_callable($closure));
var_dump(takeCallable($closure) === $closure);
var_dump(takeCallable('strlen') === 'strlen');
var_dump(takeCallable([new ArrayObject([]), 'count']) instanceof Closure);

$plain = new stdClass();
var_dump($plain instanceof Traversable, $plain instanceof Iterator);
var_dump($plain instanceof Closure, $plain instanceof Generator);
var_dump($plain instanceof Countable);

try { takeGen($it); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeClosure($g); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeIt($plain); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
