<?php
// Primitive element reads from packed int and float arrays: arithmetic, comparisons,
// by-value parameters, out-of-range and negative keys, and reads after generalization.

$a = [10, 20, 30, 40];
$sum = 0;
for ($i = 0; $i < 4; $i++) {
    $sum += $a[$i];
}
var_dump($sum);
var_dump($a[0] * 2 + $a[1], $a[2] > $a[1], $a[3] === 40, $a[0] <=> $a[1]);

$f = [1.5, 2.5, 3.5];
$sumf = 0.0;
for ($i = 0; $i < 3; $i++) {
    $sumf += $f[$i];
}
var_dump($sumf);
var_dump($f[0] * 2.0, $f[1] + 1, $f[2] < 4.0, $f[0] - $f[2]);

function readInts(array $x, int $n): int {
    $s = 0;
    for ($i = 0; $i < $n; $i++) {
        $s += $x[$i] * 2;
    }
    return $s;
}

function readFloatAt(array $x, int $i): float {
    return $x[$i] * 2.0;
}

var_dump(readInts($a, 4));
var_dump(readFloatAt($f, 1));

// Out-of-range and negative keys: value semantics, the warning is on stderr.
var_dump($a[10]);
var_dump($a[-1]);
var_dump($f[9]);

// A write of a different type generalizes the array; reads then use the object path.
$g = [1, 2, 3];
$g[1] = 'x';
var_dump($g[0] + $g[2], $g[1]);

// Two-level reads: packed OBJECT of packed LONG/DOUBLE rows.
$m = [[1, 2], [3, 4]];
var_dump($m[0][1] + $m[1][0], $m[1][1] * 2);
$mf = [[1.5, 2.5], [3.5, 4.5]];
var_dump($mf[1][0] * 2.0, $mf[0][0] + $mf[1][1]);
var_dump($mf[5][0]); // missing outer row, warning on stderr

// A few thousand compiled calls over a typed array.
$h = range(1, 100);
$checksum = 0;
for ($k = 0; $k < 2000; $k++) {
    for ($i = 0; $i < 100; $i++) {
        $checksum += $h[$i];
    }
}
var_dump($checksum);

$hf = array_fill(0, 100, 0.5);
$fchecksum = 0.0;
for ($k = 0; $k < 2000; $k++) {
    for ($i = 0; $i < 100; $i++) {
        $fchecksum += $hf[$i] * 2.0;
    }
}
var_dump($fchecksum);
