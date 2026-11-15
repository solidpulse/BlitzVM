<?php

function accumulate($items) {
    $sum = 0;
    $log = [];
    $add = function ($v) use (&$sum, &$log) {
        $sum += $v;
        $log[] = $v;
    };
    $byValBefore = fn() => $sum;
    foreach ($items as $x) {
        $add($x);
    }
    return $sum . ':' . implode(',', $log) . '|before=' . $byValBefore();
}

function createUndefined() {
    $init = function () use (&$fresh) {
        var_dump($fresh);
        $fresh = 42;
    };
    $init();
    return $fresh;
}

function captureParam($x) {
    $mod = function ($d) use (&$x) {
        $x += $d;
    };
    $mod(5);
    $mod(10);
    return $x;
}

function nestedRef() {
    $x = 1;
    $outer = function () use (&$x) {
        $x += 10;
        $inner = function () use (&$x) {
            $x += 100;
        };
        $inner();
    };
    $outer();
    return $x;
}

function closureWithStaticAndRef() {
    return function () {
        static $n = 0;
        $inc = function () use (&$n) {
            $n++;
        };
        $inc();
        return $n;
    };
}

echo accumulate([3, 4, 5]), "\n";
echo 'fresh=', createUndefined(), "\n";
echo 'param=', captureParam(7), "\n";
echo 'nested=', nestedRef(), "\n";

$g = 10;
$gInc = function () use (&$g) {
    $g *= 3;
};
$gInc();
echo 'global=', $g, "\n";

$cs1 = closureWithStaticAndRef();
$cs2 = closureWithStaticAndRef();
echo $cs1(), ',', $cs1(), '|', $cs2(), "\n";

$v = 5;
$orig = function () use (&$v) { return ++$v; };
$cloned = clone $orig;
echo $orig(), ',', $cloned(), ',', $v, "\n";
