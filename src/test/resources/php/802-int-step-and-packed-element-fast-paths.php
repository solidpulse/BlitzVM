<?php
// ++/-- on int locals (exact, overflow to float, a site that overflows on every call) and
// $array[int] reads, writes and compound assignments on packed arrays: copy-on-write, reference
// elements, missing keys, numeric strings and nested arrays must behave as in php.

function stepAtMax(int $n) {
    $x = PHP_INT_MAX;
    $x++;
    $y = PHP_INT_MIN;
    $y--;
    $sum = 0;
    for ($i = 0; $i < $n; $i++) {
        $sum++;
    }
    return [$x, $y, $sum];
}
for ($k = 0; $k < 3; $k++) {
    var_dump(stepAtMax(5));
}
$m = PHP_INT_MAX;
var_dump($m++, $m, ++$m);

function loopSum(int $n): int {
    $a = 0;
    for ($i = $n; $i > 0; $i--) {
        $a++;
    }
    return $a;
}
$total = 0;
for ($k = 0; $k < 3000; $k++) {
    $total += loopSum(100);
}
var_dump($total);

$a = [1, 2, PHP_INT_MAX, 4.5, "5", PHP_INT_MIN];
$a[0] += 10;
$a[1] -= 3;
$a[2] += 1;
$a[3] *= 2;
$a[4] += 1;
$a[5] -= 1;
var_dump($a);
$p = [3, 4];
$p[0] *= PHP_INT_MAX;
$p[1] += 0.5;
var_dump($p);

$orig = [1, 2, 3];
$copy = $orig;
$copy[0] += 100;
$copy[1] = 50;
var_dump($orig, $copy);

$withRef = [10, 20, 30];
$r = &$withRef[1];
$withRef[1] += 5;
var_dump($r);
$withRef[1] = 99;
var_dump($r);
unset($r);

$short = [1, 2];
$short[5] += 1;
var_dump($short);
$short[1] .= "x";
$short[0] %= 2;
var_dump($short);
var_dump($short[9] ?? 'missing');

$inner = [1, 2];
$outer = [0, 0];
$outer[0] = $inner;
$inner[0] = 9;
var_dump($outer[0][0]);

function hot(array $x, array $y): array {
    $n = count($x);
    for ($k = 0; $k < 50; $k++) {
        for ($i = $n - 1; $i >= 0; $i--) {
            $y[$i] += $x[$i];
        }
    }
    return $y;
}
$x = range(1, 40);
$y = array_fill(0, 40, 0);
for ($k = 0; $k < 400; $k++) {
    $res = hot($x, $y);
}
var_dump($res[0], $res[39], $y[0]);
