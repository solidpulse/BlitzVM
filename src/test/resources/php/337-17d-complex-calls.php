<?php

function addRef(&$x, $y) {
    $x += $y;
    return $x;
}

$x = 1;
echo addRef(x: $x, y: 4), ':', $x, "\n";
$n = 5;
echo addRef($n, ...['y' => 2]), ':', $n, "\n";

function twoRefs(&$a, &$b) {
    $a++;
    $b++;
    return $a + $b;
}
$p = 10;
$q = 20;
echo twoRefs(...[&$p, &$q]), ':', $p, ',', $q, "\n";

class ComplexCalls {
    public int $v = 1;

    public function method(&$a, $b = 0) {
        $a += $b;
        return $this->v + $a;
    }

    public static function stat($a, &$b, $c) {
        $b += $a;
        return $c;
    }

    public function __call($name, $args) {
        return $name . ':' . json_encode($args);
    }

    public static function __callStatic($name, $args) {
        return 'static-' . $name . ':' . json_encode($args);
    }
}

$c = new ComplexCalls();
$r = 3;
echo $c->method(a: $r, b: 2), ':', $r, "\n";
$k = 1;
echo ComplexCalls::stat(2, b: $k, c: 9), ':', $k, "\n";
echo $c->missing(a: 1, b: 2), "\n";
echo ComplexCalls::missing(...['a' => 3, 'b' => 4]), "\n";

class ComplexCtor {
    public int $a;
    public function __construct($a, &$b) {
        $this->a = $a;
        $b++;
    }
}

$t = 5;
$obj = new ComplexCtor(...['a' => 1, 'b' => &$t]);
echo $obj->a, ':', $t, "\n";

$closure = function (&$x, $y) {
    $x += $y;
    return $x;
};
$z = 1;
echo $closure(x: $z, y: 6), ':', $z, "\n";

$fromSpread = [1, 2, 3];
sort(...[&$fromSpread]);
echo implode(',', $fromSpread), "\n";
