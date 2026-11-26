<?php
// ArrayObject and ArrayIterator keep their native storage through the offset methods.
$ao = new ArrayObject(['a' => 1, 'b' => 2]);
var_dump(isset($ao['a']));
var_dump(isset($ao['z']));
var_dump($ao['a'] ?? 'default');
var_dump($ao['z'] ?? 'default');
var_dump($ao['a']);
$ao['c'] = 3;
$ao[] = 4;
var_dump(count($ao));
unset($ao['a']);
var_dump(isset($ao['a']));
foreach ($ao as $k => $v) { echo $k, '=', $v, "\n"; }

$it = new ArrayIterator(['x' => 1, 'y' => 2]);
var_dump(isset($it['x']));
var_dump($it['y']);
$it['z'] = 3;
var_dump(count($it));
foreach ($it as $k => $v) { echo $k, '=', $v, "\n"; }
