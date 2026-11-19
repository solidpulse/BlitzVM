<?php
// Key order, string keys mixed with integer keys.
$a = ['b' => 1, 'a' => 2, 5 => 'five', 2 => 'two'];
var_dump($a);

// nextIndex after a string key and an explicit integer key.
$b = ['x' => 1, 5 => 2];
$b[] = 3;
$b['y'] = 4;
$b[] = 5;
var_dump($b);

// Numeric-string keys: canonical integers convert, leading zero and float strings stay strings.
$c = ['10' => 'ten', '01' => 'zero one', '-3' => 'minus three', '1.5' => 'one point five'];
var_dump($c);

// Duplicate keys in a literal: the last value wins and the first position is kept.
$d = ['k' => 1, 'j' => 2, 'k' => 3];
var_dump($d);

// A literal with sequential integer keys stays an appendable list.
$e = [0 => 'a', 1 => 'b', 2 => 'c'];
$e[] = 'd';
var_dump($e);

// Undefined variables auto-create the array in the layout the first key implies.
unset($f, $g);
$f['x'] = 1;
$f[] = 2;
var_dump($f);
$g[5] = 'five';
$g[] = 'six';
var_dump($g);

// array_merge knows the total size before it builds the result.
$h = array_merge(['a' => 1, 2 => 'x'], ['b' => 2, 2 => 'y'], [10, 20]);
var_dump($h);

// JSON object members are string keys from the start.
$i = json_decode('{"z":1,"a":{"y":2,"x":3},"5":"five","0":"zero"}', true);
var_dump($i);
