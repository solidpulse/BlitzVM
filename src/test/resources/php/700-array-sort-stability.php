<?php
// usort is stable for equal elements; uasort and uksort keep keys, usort renumbers them.

$items = [
    ['rank' => 2, 'id' => 'a'],
    ['rank' => 1, 'id' => 'b'],
    ['rank' => 2, 'id' => 'c'],
    ['rank' => 1, 'id' => 'd'],
    ['rank' => 3, 'id' => 'e'],
    ['rank' => 2, 'id' => 'f'],
];
usort($items, fn ($x, $y) => $x['rank'] <=> $y['rank']);
echo implode(',', array_map(fn ($i) => $i['id'] . $i['rank'], $items)), "\n";

$equal = [5, 5, 5, 5];
var_dump(usort($equal, fn ($a, $b) => 0));
var_dump($equal);

$assoc = ['x' => 3, 'y' => 1, 'z' => 2, 'w' => 1];
var_dump(uasort($assoc, fn ($a, $b) => $a <=> $b));
var_dump($assoc);

$keys = ['b' => 1, 'a' => 2, 'd' => 3, 'c' => 4];
var_dump(uksort($keys, fn ($a, $b) => $a <=> $b));
var_dump($keys);

// Integer keys of a list are renumbered by usort and preserved by uasort.
$list = ['a', 'b', 'c'];
var_dump(usort($list, fn ($a, $b) => strcmp($b, $a)));
var_dump($list);
$list = ['a', 'b', 'c'];
var_dump(uasort($list, fn ($a, $b) => strcmp($b, $a)));
var_dump($list);

// Comparator return values follow php's comparison rules (int, string, float, null).
$mixed = ['10', 2, '2', 1];
usort($mixed, fn ($a, $b) => $a <=> $b);
var_dump($mixed);
