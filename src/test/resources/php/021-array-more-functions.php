<?php
$nums = range(1, 5);
echo array_sum($nums), "\n";

$str = implode(", ", $nums);
echo $str, "\n";

$parts = explode(", ", $str);
print_r($parts);

$stack = [1, 2];
array_push($stack, 3, 4);
echo array_pop($stack), "\n";
print_r($stack);

$merged = array_merge([1, 2], [3, 4]);
print_r($merged);

$unsorted = [5, 2, 9, 1, 3];
sort($unsorted);
print_r($unsorted);
