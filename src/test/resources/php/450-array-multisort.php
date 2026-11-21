<?php
$starts = [10, 5, 10, 5];
$ends = [20, 30, 10, 30];
$indices = [0, 1, 2, 3];
$tokens = ['a', 'b', 'c', 'd'];

var_dump(array_multisort($starts, SORT_ASC, SORT_NUMERIC, $ends, SORT_DESC, SORT_NUMERIC, $indices, SORT_ASC, SORT_NUMERIC, $tokens));
var_dump($starts, $ends, $indices, $tokens);

$a = [3, 1, 2];
$b = [1, 3, 2];
var_dump(array_multisort($a, SORT_DESC, SORT_NUMERIC, $b));
var_dump($a, $b);

$names = ['b', 'A', 'a'];
var_dump(array_multisort($names, SORT_ASC, SORT_STRING | SORT_FLAG_CASE));
var_dump($names);

var_dump(SORT_ASC, SORT_DESC, SORT_REGULAR, SORT_NUMERIC, SORT_STRING, SORT_NATURAL, SORT_FLAG_CASE);

$short = [3, 1];
$other = [1];
try {
    array_multisort($short, $other);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}

try {
    array_multisort($short, 99);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}

var_dump(array_multisort([3, 1], SORT_ASC));
