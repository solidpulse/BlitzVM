<?php

function bump() {
    static $n = 0;
    $n++;
    return $n;
}

function multiStatic() {
    static $a = 10, $b = 2 * 3, $c;
    $a++;
    $b += 2;
    $c = ($c ?? 0) + 1;
    return $a . ':' . $b . ':' . $c;
}

function recStatic($depth) {
    static $calls = 0;
    $calls++;
    if ($depth <= 0) {
        return $calls;
    }
    return recStatic($depth - 1);
}

class StatParent {
    public function nextVal(): int {
        static $v = 0;
        return ++$v;
    }
}
class StatInherit extends StatParent {}
class StatOverride extends StatParent {
    public function nextVal(): int {
        static $v = 100;
        return ++$v;
    }
}

function makeCounterClosure() {
    return function () {
        static $k = 0;
        return ++$k;
    };
}

echo bump(), ',', bump(), ',', bump(), "\n";
echo multiStatic(), '|', multiStatic(), "\n";
echo recStatic(3), '|', recStatic(1), "\n";

$p = new StatParent();
$inh = new StatInherit();
$ov = new StatOverride();
echo $p->nextVal(), ',', $inh->nextVal(), ',', $p->nextVal(), '|', $ov->nextVal(), ',', $ov->nextVal(), "\n";

$c1 = makeCounterClosure();
$c2 = makeCounterClosure();
echo $c1(), ',', $c1(), '|', $c2(), '|', $c1(), ',', $c2(), "\n";
