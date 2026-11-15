<?php

$orig = ['b' => 3, 'a' => 1, 'c' => 2];
$copy = $orig;
usort($copy, fn($x, $y) => $x <=> $y);
var_export($orig); echo "\n";
var_export($copy); echo "\n";

$items = [
    ['k' => 2, 'tag' => 'first2'],
    ['k' => 1, 'tag' => 'one'],
    ['k' => 2, 'tag' => 'second2'],
    ['k' => 2, 'tag' => 'third2'],
];
usort($items, fn($x, $y) => $x['k'] <=> $y['k']);
echo implode(',', array_map(fn($i) => $i['tag'], $items)), "\n";

$scores = ['bob' => 20, 'alice' => 30, 'carol' => 20];
uasort($scores, fn($x, $y) => $x <=> $y);
var_export($scores); echo "\n";

uksort($scores, fn($k1, $k2) => strcmp($k2, $k1));
var_export($scores); echo "\n";
