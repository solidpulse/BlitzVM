<?php
// array_map/filter/reduce keep php's key rules; usort renumbers, uasort/uksort keep keys.

function show(string $label, mixed $value): void {
    echo $label, '=', json_encode($value), "\n";
}

$double = fn ($v) => $v * 2;
$odd = fn ($v) => $v % 2 === 1;
$add = fn ($acc, $v) => $acc + $v;

show('map-list', array_map($double, [1, 2, 3]));
show('map-string', array_map($double, ['a' => 1, 'b' => 2, 'c' => 3]));
show('map-gaps', array_map($double, [2 => 1, 5 => 2]));
show('map-mixed', array_map($double, ['z' => 1, 7 => 2, 0 => 3]));
show('map-empty', array_map($double, []));
show('map-falsey', array_map(fn ($v) => $v, [0, '', null, false]));

show('filter-list', array_filter([1, 2, 3, 4], $odd));
show('filter-string', array_filter(['a' => 1, 'b' => 2, 'c' => 3], $odd));
show('filter-gaps', array_filter([2 => 1, 5 => 2], $odd));
show('filter-key', array_filter(['a' => 1, 2, 3, 'b' => 4], fn ($k) => is_string($k), ARRAY_FILTER_USE_KEY));
show('filter-both', array_filter(['a' => 1, 'b' => 2, 'c' => 3], fn ($v, $k) => $k === 'b', ARRAY_FILTER_USE_BOTH));
show('filter-all', array_filter([1, 2], fn ($v) => true));
show('filter-none', array_filter([1, 2], fn ($v) => false));
show('filter-empty', array_filter([], $odd));

show('reduce-list', array_reduce([1, 2, 3], $add, 0));
show('reduce-noinit', array_reduce([1, 2, 3], $add));
show('reduce-keys', array_reduce(['a' => 1, 'b' => 2], $add, 0));
show('reduce-empty', array_reduce([], $add, 'x'));
show('reduce-empty-noinit', array_reduce([], $add));

$sorted = [3, 1, 2];
var_dump(usort($sorted, fn ($a, $b) => $a <=> $b));
show('usort', $sorted);
$sorted = ['b' => 3, 'a' => 1, 'c' => 2];
var_dump(uasort($sorted, fn ($a, $b) => $a <=> $b));
show('uasort', $sorted);
$sorted = ['b' => 1, 'a' => 2, 'c' => 3];
var_dump(uksort($sorted, fn ($a, $b) => $a <=> $b));
show('uksort', $sorted);
$sorted = [];
var_dump(usort($sorted, fn ($a, $b) => $a <=> $b));
show('usort-empty', $sorted);

// References inside the array: the walk sees values, filter keeps the cell.
$cell = 10;
$refs = [&$cell, 20, &$cell];
$mapped = array_map(fn ($v) => $v, $refs);
$cell = 99;
show('map-refs', $mapped);
show('refs-after', $refs);

$cell = 5;
$refs = [&$cell, 6];
$filtered = array_filter($refs, fn ($v) => true);
$cell = 7;
show('filter-refs', $filtered);

// A sort comparator sees the original array through its captured cell, not a partial sort.
$source = [3, 1, 2];
$seen = [];
usort($source, function ($a, $b) use (&$source, &$seen) {
    $seen[] = json_encode($source);
    return $a <=> $b;
});
show('sort-source-stable', array_unique($seen));
show('sort-result', $source);
