<?php
$x = 10;
$y = 20;
$s = "10";

var_dump($x == $s);
var_dump($x != $y);
var_dump($x === $s);
var_dump($x !== $s);
var_dump($x < $y);
var_dump($x > $y);
var_dump($x <= 10);
var_dump($x >= 10);
var_dump($x <=> $y);
var_dump($y <=> $x);
var_dump($x <=> 10);
