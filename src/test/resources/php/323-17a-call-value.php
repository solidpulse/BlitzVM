<?php
// Bytecode 17a: $f(...) on a function-name string, a "Class::method" string, [$obj, 'method'],
// ['Class', 'method'], an invokable object, and builtins, including by-reference arguments.

function twice($n) { return $n * 2; }
function bump(&$n) { $n++; return $n; }

class KV {
    public $v;
    public function __construct($v) { $this->v = $v; }
    public function get() { return $this->v; }
    public static function make($v) { return new KV($v); }
}

class Inv {
    public function __invoke($x) { return 'inv:' . $x; }
}

$f = 'twice';
echo $f(21), "\n";

$obj = new KV(7);
$m = [$obj, 'get'];
echo $m(), "\n";

$sm = ['KV', 'make'];
$made = $sm(9);
echo $made->get(), "\n";

$str = 'KV::make';
$made2 = $str(11);
echo $made2->get(), "\n";

$inv = new Inv();
echo $inv('x'), "\n";

$builtin = 'strtoupper';
echo $builtin('abc'), "\n";

$sort = 'sort';
$list = [3, 1, 2];
$sort($list);
echo implode(',', $list), "\n";

$n = 4;
$b = 'bump';
echo $b($n), ':', $n, "\n";
