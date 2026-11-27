<?php
// Two-level writes and reads: packed OBJECT outer arrays with packed typed rows.

$m = [];
for ($i = 0; $i < 5; $i++) {
    for ($j = 0; $j < 4; $j++) {
        $m[$i][$j] = $i * 10 + $j;
    }
}
var_dump($m);

$m[2][1] = 99;
$m[3][2] += 5;
$m[4][0] -= 1;
var_dump($m[2][1], $m[3][2], $m[4][0]);

$mf = [];
for ($i = 0; $i < 4; $i++) {
    for ($j = 0; $j < 3; $j++) {
        $mf[$i][$j] = $i + 0.25 * $j;
    }
}
var_dump($mf);
$mf[1][1] *= 2.0;
$mf[2][0] += 0.5;
var_dump($mf[1][1], $mf[2][0]);

$sum = 0;
for ($i = 0; $i < 5; $i++) {
    for ($j = 0; $j < 4; $j++) {
        $sum += $m[$i][$j];
    }
}
var_dump($sum);

// Copy-on-write of the outer array must not change the source rows.
$copy = $m;
$copy[0][0] = -1;
var_dump($m[0][0], $copy[0][0]);

// A reference to a row keeps the general path correct.
$row =& $m[1];
$m[1][0] = 7;
var_dump($m[1], $row);

// Missing rows and columns read as null.
var_dump($m[8][0]);
var_dump($m[0][8]);

// Appending into rows.
$n = [];
$n[0][] = 1;
$n[0][] = 2;
$n[1][] = 3;
var_dump($n);

// A few thousand compiled two-level writes and reads.
$matrix = [];
for ($i = 0; $i < 10; $i++) {
    for ($j = 0; $j < 10; $j++) {
        $matrix[$i][$j] = $i + $j;
    }
}
$checksum = 0;
for ($k = 0; $k < 2000; $k++) {
    for ($i = 0; $i < 10; $i++) {
        for ($j = 0; $j < 10; $j++) {
            $matrix[$i][$j] = $matrix[$i][$j] + 1;
            $checksum += $matrix[$i][$j];
        }
    }
}
var_dump($matrix[0][0], $matrix[9][9], $checksum);
