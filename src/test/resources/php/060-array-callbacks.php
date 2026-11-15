<?php

$assoc = ['a' => 1, 'b' => 2, 'c' => 3];
var_export(array_map(fn($v) => $v * 10, $assoc)); echo "\n";
var_export(array_map(fn($x, $y) => ($x ?? 0) + ($y ?? 0), [1, 2, 3], [10, 20])); echo "\n";
var_export(array_map(null, [1, 2], ['x', 'y', 'z'])); echo "\n";

$mix = ['zero' => 0, 'one' => 1, 'empty' => '', 'two' => 2, 'ok' => 'yes'];
var_export(array_filter($mix)); echo "\n";
var_export(array_filter($mix, fn($v) => is_int($v) && $v > 0)); echo "\n";
var_export(array_filter($mix, fn($k) => strlen($k) === 3, ARRAY_FILTER_USE_KEY)); echo "\n";
var_export(array_filter($mix, fn($v, $k) => $k === 'two' || $v === 'yes', ARRAY_FILTER_USE_BOTH)); echo "\n";

echo array_reduce([1, 2, 3, 4], fn($acc, $v) => $acc + $v, 10), '|',
     array_reduce(['a', 'b', 'c'], fn($acc, $v) => ($acc === null ? '' : $acc . '-') . $v), "\n";

$log = new stdClass();
$log->items = [];
array_walk($assoc, function ($val, $key, $prefix) use ($log) {
    $log->items[] = $prefix . ':' . $key . '=' . $val;
}, 'P');
echo implode(',', $log->items), "\n";

var_dump(
    array_find($assoc, fn($v, $k) => $v > 1),
    array_find_key($assoc, fn($v, $k) => $k === 'c'),
    array_find($assoc, fn($v) => $v > 99),
    array_any($assoc, fn($v) => $v === 2),
    array_all($assoc, fn($v) => $v > 0),
    array_all($assoc, fn($v) => $v > 1)
);
