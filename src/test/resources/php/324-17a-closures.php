<?php
// Bytecode 17a: closures capture by value and by reference, arrow functions capture by value,
// and a closure carries $this and the called class.

function make_counter() {
    $count = 0;
    return function () use (&$count) {
        return ++$count;
    };
}

$counter = make_counter();
echo $counter(), $counter(), $counter(), "\n";

$x = 10;
$byValue = function () use ($x) { return $x; };
$byRef = function () use (&$x) { $x++; return $x; };
$x = 20;
echo $byValue(), ':', $byRef(), ':', $x, "\n";

$g = 1;
$globalRef = function () use (&$g) { $g += 2; };
$globalRef();
echo $g, "\n";

$mul = 3;
$arrow = fn($n) => $n * $mul;
$mul = 5;
echo $arrow(4), "\n";

class Counter {
    private $n = 0;
    public function make() {
        return function ($step) {
            $this->n += $step;
            return static::class . ':' . $this->n;
        };
    }
    public static function makeStatic() {
        return static function () {
            return isset($this) ? 'this' : 'no-this';
        };
    }
}

$co = (new Counter())->make();
echo $co(2), '|', $co(3), "\n";
$st = Counter::makeStatic();
echo $st(), "\n";

function returns_closure() {
    $v = 'snap';
    return function () use ($v) { return $v; };
}

$f = returns_closure();
$v = 'changed';
echo $f(), "\n";
