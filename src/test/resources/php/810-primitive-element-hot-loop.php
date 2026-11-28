<?php
// A few thousand compiled calls exercising primitive element reads, writes and compounds,
// including by-reference cells and two-level arrays.

function hotRead(array $x): int {
    $s = 0;
    for ($i = 0, $n = count($x); $i < $n; $i++) {
        $s += $x[$i];
    }
    return $s;
}

function hotDouble(array $x): array {
    for ($i = 0, $n = count($x); $i < $n; $i++) {
        $x[$i] = $x[$i] * 2;
    }
    return $x;
}

function hotAdd(array $x): array {
    for ($i = 0, $n = count($x); $i < $n; $i++) {
        $x[$i] += 3;
    }
    return $x;
}

function hotFloat(array $x): array {
    for ($i = 0, $n = count($x); $i < $n; $i++) {
        $x[$i] = $x[$i] * 1.5 + 0.25;
    }
    return $x;
}

function hotRef(array &$x): void {
    for ($i = 0, $n = count($x); $i < $n; $i++) {
        $x[$i] -= 1;
    }
}

function hotTwoLevel(array $m): int {
    $s = 0;
    for ($i = 0; $i < 10; $i++) {
        for ($j = 0; $j < 10; $j++) {
            $m[$i][$j] = $m[$i][$j] + 1;
            $s += $m[$i][$j];
        }
    }
    return $s;
}

$x = range(1, 100);
$y = array_fill(0, 100, 0);
$f = array_fill(0, 100, 0.5);
$m = [];
for ($i = 0; $i < 10; $i++) {
    for ($j = 0; $j < 10; $j++) {
        $m[$i][$j] = $i + $j;
    }
}

$checksum = 0;
for ($k = 0; $k < 2000; $k++) {
    $checksum += hotRead($x);
    $y = hotAdd($y);
    $f = hotFloat($f);
    hotRef($y);
    $checksum += hotTwoLevel($m);
}
var_dump($y[0], $y[99], $f[0], $f[99], $checksum);

$copy = hotDouble($x);
var_dump($copy[0], $copy[99], $x[0], $x[99]);
