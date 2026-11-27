<?php
// A few thousand compiled calls over typed packed arrays: element reads and writes, compound
// assignment, count() and foreach must all see the primitive storage without re-boxing arrays.

function hotAdd(array $x, array $y, int $rounds): array {
    $n = count($x);
    for ($k = 0; $k < $rounds; $k++) {
        for ($i = $n - 1; $i >= 0; $i--) {
            $y[$i] += $x[$i];
        }
    }
    return $y;
}

function hotSum(array $a): int {
    $sum = 0;
    foreach ($a as $v) {
        $sum += $v;
    }
    return $sum;
}

function hotFloat(array $x, array $y): array {
    $n = count($x);
    for ($i = 0; $i < $n; $i++) {
        $y[$i] = $y[$i] * 1.5 + $x[$i];
    }
    return $y;
}

$x = range(1, 200);
$y = array_fill(0, 200, 0);
$result = $y;
for ($k = 0; $k < 3000; $k++) {
    $result = hotAdd($x, $y, 1);
    $sum = hotSum($result);
}
var_dump($result[0], $result[199], $sum, $y[0]);

$fx = range(0.5, 4.0, 0.5);
$fy = array_fill(0, 8, 1.0);
for ($k = 0; $k < 4000; $k++) {
    $fresult = hotFloat($fx, $fy);
}
var_dump($fresult[0], $fresult[7], $fy[0]);

$copy = $result;
$copy[0] = 'boxed';
for ($k = 0; $k < 2000; $k++) {
    $copy[] = $k;
}
var_dump($result[0], $result[199], $copy[0], $copy[count($copy) - 1], count($copy));
