<?php
function inc(&$n, $step = 1) {
    $n += $step;
}

function initIfNull(&$x = null, $val = 42) {
    if ($x === null) {
        $x = $val;
    }
    return $x;
}

function swap(&$a, &$b) {
    $tmp = $a;
    $a = $b;
    $b = $tmp;
}

class Holder {
    public $v = 10;
    public static $sv = 100;
    public function add(&$target, $delta = 5) {
        $target += $delta;
    }
}

$loc = 3;
inc($loc);
inc($loc, 4);
echo "loc=$loc
";

function plainLocalCaller() {
    $u = 7;
    inc($u, 3);
    return $u;
}
echo "plain=" . plainLocalCaller() . "
";

$arr = ['k' => 10];
inc($arr['k'], 5);
inc($arr['nested']['x'], 9);
echo "arr=" . $arr['k'] . "," . $arr['nested']['x'] . "
";

$h = new Holder();
inc($h->v, 7);
inc(Holder::$sv, 23);
$h->add($loc, 10);
echo "obj=" . $h->v . "," . Holder::$sv . ",$loc
";

echo "def1=" . initIfNull() . "
";
$m = null;
echo "def2=" . initIfNull($m, 88) . ",$m
";

$p = 'left';
$q = 'right';
swap($p, $q);
echo "swap=$p,$q
";

try {
    inc(123);
} catch (Error $e) {
    echo "err=" . $e->getMessage() . "
";
}
