<?php
// By-reference parameters passed by name write back through the variable or place.
$s = 'Hello';

$m = null;
var_dump(preg_match(pattern: '/l/', subject: $s, matches: $m));
var_dump($m);

$m = null;
var_dump(preg_match('/l/', $s, matches: $m, flags: PREG_OFFSET_CAPTURE));
var_dump($m);

$c = null;
var_dump(str_replace(search: 'l', replace: 'L', subject: $s, count: $c));
var_dump($c);

$c = null;
var_dump(str_ireplace('hello', 'bye', $s, count: $c));
var_dump($c);

$n = null;
var_dump(preg_replace(pattern: '/l/', replacement: 'L', subject: $s, limit: 2, count: $n));
var_dump($n);

$a = [3, 1, 2];
var_dump(sort(array: $a, flags: SORT_NUMERIC));
var_dump($a);

$a = [3, 1, 2];
var_dump(rsort(array: $a));
var_dump($a);

$a = [1, 2, 3];
var_dump(array_pop(array: $a));
var_dump($a);

$a = [1, 2, 3];
var_dump(array_shift(array: $a));
var_dump($a);

$a = [1, 2, 3, 4];
var_dump(array_splice(array: $a, offset: 1, length: 2, replacement: ['x']));
var_dump($a);

$data = ['a' => 1, 'b' => 2];
$seen = [];
array_walk(array: $data, callback: function ($v, $k) use (&$seen) { $seen[] = "$k=$v"; });
var_dump($seen);

$values = [5, 3, 1];
$m = null;
var_dump(preg_match_all(pattern: '/(\d)/', subject: 'a1b2', matches: $m));
var_dump($m);
