<?php
require_once __DIR__ . '/boot.php';

echo 'ints=', implode(',', $typedInts), "\n";
echo 'floats=', implode(',', $typedFloats), "\n";
echo 'range=', implode(',', $range), "\n";
echo 'filled=', implode(',', $filled), "\n";
echo 'nested=', implode(',', $nested[0]), '/', implode(',', $nested[1]), "\n";
echo 'mixed=', implode(',', $mixed), "\n";

$typedInts[0] = 100;
$typedInts[] = 'after';
unset($typedInts[1]);
$typedFloats[0] *= 2;
$typedFloats[] = 3;
$nested[0][1] += 5;
$nested[1][] = 6.5;
$copy = $range;
$copy[0] = 'copied';
$r = &$typedInts[2];
$r += 10;
unset($r);

echo 'ints=', implode(',', $typedInts), "\n";
echo 'floats=', implode(',', $typedFloats), "\n";
echo 'nested=', implode(',', $nested[0]), '/', implode(',', $nested[1]), "\n";
echo 'copy=', implode(',', $copy), "\n";
echo 'range=', implode(',', $range), "\n";
echo 'mixed=', implode(',', $mixed), "\n";
